"""Deterministic real-text host + fabricated-gold corpus assembly (tempdoc 707)."""

from __future__ import annotations

import hashlib
import json
import random
import re
from pathlib import Path

METHOD = "real-text-injection-v1"
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _canonical_digest(value) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _interleave(host: str, injection: str) -> str:
    host_sentences = [part.strip() for part in _SENTENCE_RE.split(host.strip()) if part.strip()]
    gold_sentences = [part.strip() for part in _SENTENCE_RE.split(injection.strip()) if part.strip()]
    if not host_sentences:
        return " ".join(gold_sentences)
    if not gold_sentences:
        return " ".join(host_sentences)
    positions = [
        max(1, min(len(host_sentences), round(index * len(host_sentences) / (len(gold_sentences) + 1))))
        for index in range(1, len(gold_sentences) + 1)
    ]
    result = []
    gold_index = 0
    for host_index, sentence in enumerate(host_sentences, 1):
        result.append(sentence)
        while gold_index < len(gold_sentences) and positions[gold_index] == host_index:
            result.append(gold_sentences[gold_index])
            gold_index += 1
    result.extend(gold_sentences[gold_index:])
    return " ".join(result)


def assemble(
    real_docs: list[dict],
    fabricated_docs: list[dict],
    queries: list[dict],
    *,
    seed: int,
    n_distractors: int,
    style: str = "interleave",
    host_min_words: int = 60,
) -> tuple[list[dict], dict]:
    """Pure deterministic assembly; queries and fabricated facts remain unchanged."""
    if style not in {"append", "interleave"}:
        raise ValueError("style must be append or interleave")
    gold_ids = {evidence for query in queries for evidence in query.get("evidence_ids", [])}
    gold_docs = sorted(
        (doc for doc in fabricated_docs if doc.get("_id") in gold_ids),
        key=lambda doc: str(doc.get("_id")),
    )
    missing = sorted(gold_ids - {doc.get("_id") for doc in gold_docs})
    if missing:
        raise ValueError(f"fabricated queries reference missing gold docs: {missing}")

    pool = [
        doc for doc in real_docs
        if doc.get("_id") and len(str(doc.get("text", "")).split()) >= host_min_words
        and doc.get("_id") not in gold_ids
    ]
    pool.sort(key=lambda doc: str(doc["_id"]))
    random.Random(seed).shuffle(pool)
    needed = len(gold_docs) + n_distractors
    if len(pool) < needed:
        raise ValueError(f"need {needed} eligible real docs, found {len(pool)}")
    hosts = pool[:len(gold_docs)]
    distractors = pool[len(gold_docs):needed]

    injected = []
    host_mapping = []
    for gold, host in zip(gold_docs, hosts):
        host_text = str(host.get("text", ""))
        gold_text = str(gold.get("text", ""))
        text = host_text + "\n\n" + gold_text if style == "append" else _interleave(host_text, gold_text)
        injected.append({"_id": gold["_id"], "title": gold.get("title", ""), "text": text})
        host_mapping.append({"gold_id": gold["_id"], "host_id": host["_id"]})

    docs = injected + [
        {"_id": doc["_id"], "title": doc.get("title", ""), "text": doc.get("text", "")}
        for doc in distractors
    ]
    report = {
        "method": METHOD,
        "seed": seed,
        "style": style,
        "n_gold_docs": len(injected),
        "n_distractors": len(distractors),
        "host_min_words": host_min_words,
        "host_mapping": host_mapping,
        "assembled_digest": _canonical_digest({"docs": docs, "queries": queries}),
    }
    return docs, report


def build_source(
    real_corpus_dir: str | Path,
    gold_source_dir: str | Path,
    output_source_dir: str | Path,
    *,
    seed: int,
    n_distractors: int,
    style: str,
    real_source_id: str,
    license_id: str,
    host_min_words: int = 60,
) -> dict:
    """Build transient source shape and prove same-input assembly determinism twice."""
    real_root = Path(real_corpus_dir)
    real_path = real_root / "corpus.jsonl"
    if not real_path.is_file():
        real_path = real_root / "docs.jsonl"
    gold_root = Path(gold_source_dir)
    real_docs = _read_jsonl(real_path)
    fabricated_docs = _read_jsonl(gold_root / "docs.jsonl")
    queries = json.loads((gold_root / "queries.json").read_text(encoding="utf-8"))
    gold_meta = json.loads((gold_root / "meta.json").read_text(encoding="utf-8"))

    docs, report = assemble(
        real_docs, fabricated_docs, queries, seed=seed, n_distractors=n_distractors,
        style=style, host_min_words=host_min_words,
    )
    second_docs, second_report = assemble(
        real_docs, fabricated_docs, queries, seed=seed, n_distractors=n_distractors,
        style=style, host_min_words=host_min_words,
    )
    deterministic = report["assembled_digest"] == second_report["assembled_digest"] and docs == second_docs
    provenance = {
        **report,
        "real_source_id": real_source_id,
        "real_source_sha256": hashlib.sha256(real_path.read_bytes()).hexdigest(),
        "license": license_id,
        "fabrication_provenance": gold_meta.get("generation_provenance"),
        "assembly_determinism": {
            "passed": deterministic,
            "method": "same-input-double-assembly",
            "digest": report["assembled_digest"],
        },
    }
    if not deterministic:
        raise RuntimeError("real-text injection assembly is nondeterministic")

    output = Path(output_source_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "docs.jsonl").write_text(
        "".join(json.dumps(doc, ensure_ascii=False) + "\n" for doc in docs), encoding="utf-8"
    )
    (output / "queries.json").write_text(
        json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    meta = {
        "version": "1.0",
        "type_axis": gold_meta.get("type_axis", "prose"),
        "suite": "707-real-text-injection",
        "contamination_class": "private-synthetic",
        "generation_provenance": provenance,
    }
    (output / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return meta


def write_commitment(
    commitment_dir: str | Path,
    gold_source_dir: str | Path,
    provenance: dict,
) -> Path:
    """Commit only recipe, fabricated inputs, and host IDs; never real host text."""
    root = Path(commitment_dir)
    root.mkdir(parents=True, exist_ok=True)
    gold_root = Path(gold_source_dir)
    recipe = dict(provenance)
    recipe.pop("fabrication_provenance", None)
    (root / "recipe.json").write_text(json.dumps(recipe, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name in ("docs.jsonl", "queries.json", "meta.json"):
        (root / f"fabricated-{name}").write_bytes((gold_root / name).read_bytes())
    return root
