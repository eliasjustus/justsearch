"""Deterministic real-text host + fabricated-gold corpus assembly (tempdoc 707)."""

from __future__ import annotations

import hashlib
import json
import random
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from jseval.corpus_build import read_jsonl

METHOD = "real-text-injection-v1"
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


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


def _cross_process_assembly(
    real_path: Path,
    fabricated_path: Path,
    queries_path: Path,
    *,
    seed: int,
    n_distractors: int,
    style: str,
    host_min_words: int,
    expected_docs: list[dict],
    expected_digest: str,
) -> dict:
    """Run the real assembly twice in independent interpreters and compare bytes."""
    request = {
        "real_path": str(real_path.resolve()),
        "fabricated_path": str(fabricated_path.resolve()),
        "queries_path": str(queries_path.resolve()),
        "seed": seed,
        "n_distractors": n_distractors,
        "style": style,
        "host_min_words": host_min_words,
    }
    outputs = []
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        request_path = root / "request.json"
        request_path.write_text(json.dumps(request), encoding="utf-8")
        for index in range(2):
            output_path = root / f"result-{index}.json"
            completed = subprocess.run(
                [sys.executable, str(Path(__file__).resolve()), str(request_path), str(output_path)],
                check=False,
                capture_output=True,
                text=True,
            )
            if completed.returncode != 0:
                raise RuntimeError(
                    "cross-process real-text regeneration failed: "
                    f"{completed.stderr or completed.stdout}"
                )
            outputs.append(output_path.read_bytes())
    regenerated = [json.loads(body.decode("utf-8")) for body in outputs]
    passed = (
        outputs[0] == outputs[1]
        and all(item.get("docs") == expected_docs for item in regenerated)
        and all(
            (item.get("report") or {}).get("assembled_digest") == expected_digest
            for item in regenerated
        )
    )
    return {
        "passed": passed,
        "method": "cross-process-regeneration-diff",
        "digest": expected_digest if passed else None,
    }


def _assembly_worker(request_path: Path, output_path: Path) -> None:
    request = json.loads(request_path.read_text(encoding="utf-8"))
    queries = json.loads(Path(request["queries_path"]).read_text(encoding="utf-8"))
    for index, query in enumerate(queries, 1):
        query.setdefault("query_variant", "verbose")
        query.setdefault("query_family_id", f"q{index:04d}")
    docs, report = assemble(
        read_jsonl(Path(request["real_path"])),
        read_jsonl(Path(request["fabricated_path"])),
        queries,
        seed=int(request["seed"]),
        n_distractors=int(request["n_distractors"]),
        style=request["style"],
        host_min_words=int(request["host_min_words"]),
    )
    output_path.write_text(
        json.dumps({"docs": docs, "report": report}, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )


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
    real_docs = read_jsonl(real_path)
    fabricated_docs = read_jsonl(gold_root / "docs.jsonl")
    queries = json.loads((gold_root / "queries.json").read_text(encoding="utf-8"))
    for index, query in enumerate(queries, 1):
        query.setdefault("query_variant", "verbose")
        query.setdefault("query_family_id", f"q{index:04d}")
    gold_meta = json.loads((gold_root / "meta.json").read_text(encoding="utf-8"))

    docs, report = assemble(
        real_docs, fabricated_docs, queries, seed=seed, n_distractors=n_distractors,
        style=style, host_min_words=host_min_words,
    )
    determinism = _cross_process_assembly(
        real_path,
        gold_root / "docs.jsonl",
        gold_root / "queries.json",
        seed=seed,
        n_distractors=n_distractors,
        style=style,
        host_min_words=host_min_words,
        expected_docs=docs,
        expected_digest=report["assembled_digest"],
    )
    deterministic = determinism["passed"]
    provenance = {
        **report,
        "real_source_id": real_source_id,
        "real_source_sha256": hashlib.sha256(real_path.read_bytes()).hexdigest(),
        "license": license_id,
        "fabrication_provenance": gold_meta.get("generation_provenance"),
        "assembly_determinism": determinism,
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
    committed = [
        "recipe.json", "fabricated-docs.jsonl", "fabricated-queries.json",
        "fabricated-meta.json",
    ]
    manifest = {
        "schema": "707-corpus-commitment.v1",
        "files": {
            name: hashlib.sha256((root / name).read_bytes()).hexdigest()
            for name in committed
        },
    }
    (root / "commitment.v1.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return root


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("internal usage: python -m jseval.corpus_inject REQUEST RESULT")
    _assembly_worker(Path(sys.argv[1]), Path(sys.argv[2]))
