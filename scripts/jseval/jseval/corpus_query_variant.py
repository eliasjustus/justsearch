"""Query-variant dataset generation (tempdoc 678 §Pillar-5 E5-C).

E5-C's query-shape sweep needs the SAME corpus + qrels as an existing local
(``golden/``/``mixed/``) dataset, with the queries transformed — to discriminate
whether dense/SPLADE near-dead recall on CLERC-shaped legal text is a query-shape
artifact (verbose citing-sentence queries dilute the query embedding) or a doc-side
one. This module builds one variant: ``keyword`` — a deterministic, LLM-free
keyword-extraction transform (the licensing-clean control the sweep also needs
alongside an LLM-reduced variant). The transform registry (:data:`_TRANSFORMS`) is
the extension point for future variants (e.g. ``llm-reduced``).

Single source -> one projection: ``corpus.jsonl`` and ``qrels/`` are copied
verbatim (unchanged — only the queries move), ``queries.jsonl``/``queries.json``
are rewritten with transformed query text, and a ``metadata.json`` records the
variant's identity/provenance. Deliberately does NOT copy ``corpus-dir/``: the
retrieval-eval ingestion path (``jseval.ingest._materialize_into``) always
re-derives materialized doc files from ``corpus.jsonl`` (never reads
``corpus-dir/`` back in), and the corpus itself is unchanged here, so
``corpus-dir/`` carries no information a query-variant consumer needs — see that
module's docstring for the "single source -> projections" contract this mirrors.
"""

from __future__ import annotations

import json
import re
import shutil
from collections import Counter
from pathlib import Path
from typing import Callable

TOOL_VERSION = "jseval.corpus_query_variant/1"

# Output-name shorthand per variant (``--suffix`` defaults to this).
VARIANT_SUFFIXES: dict[str, str] = {
    "keyword": "kw",
}

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_MIN_TOKEN_LEN = 3

TransformFn = Callable[[str, "Counter[str]", int], tuple[str, bool]]


def _tokenize(text: str) -> list[str]:
    """Lowercase + split on non-alphanumeric — the one tokenization rule this module uses,
    shared by both DF computation and query transforms so they agree on what a "token" is."""
    return _TOKEN_RE.findall(text.lower())


def document_frequencies(corpus_path: Path) -> "Counter[str]":
    """Document frequency of every token across a ``corpus.jsonl`` (title + text), counting
    each token once per document regardless of how many times it appears in that document."""
    df: Counter[str] = Counter()
    for line in corpus_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        text = " ".join(part for part in (obj.get("title", ""), obj.get("text", "")) if part)
        df.update(set(_tokenize(text)))
    return df


def keyword_variant(text: str, df: "Counter[str]", top_k: int) -> tuple[str, bool]:
    """Deterministic keyword-extraction transform (no randomness, no seed, no LLM).

    Tokenize ``text`` the same way :func:`document_frequencies` tokenized the corpus; drop
    tokens shorter than :data:`_MIN_TOKEN_LEN` or absent from the corpus vocabulary (they
    carry no retrieval signal); rank the remaining DISTINCT tokens by ascending corpus DF
    (rarest = most discriminative), ties broken by first-occurrence order in the query; keep
    the top ``top_k`` by that ranking; re-emit the kept tokens in their ORIGINAL QUERY ORDER
    (order matters for phrase-ish behavior — this is not a bag-of-words shuffle).

    Returns ``(new_text, used_fallback)``. When zero tokens are eligible, falls back to the
    original ``text`` unchanged and reports ``used_fallback=True`` so the caller can count it.
    """
    tokens = _tokenize(text)
    first_occurrence: dict[str, int] = {}
    for i, tok in enumerate(tokens):
        if len(tok) < _MIN_TOKEN_LEN or tok not in df:
            continue
        if tok not in first_occurrence:
            first_occurrence[tok] = i

    if not first_occurrence:
        return text, True

    ranked = sorted(first_occurrence.items(), key=lambda kv: (df[kv[0]], kv[1]))
    kept_terms = {tok for tok, _ in ranked[:top_k]}

    seen: set[str] = set()
    ordered_unique: list[str] = []
    for tok in tokens:
        if tok in kept_terms and tok not in seen:
            seen.add(tok)
            ordered_unique.append(tok)
    return " ".join(ordered_unique), False


_TRANSFORMS: dict[str, TransformFn] = {
    "keyword": keyword_variant,
}


def available_variants() -> list[str]:
    return sorted(_TRANSFORMS)


def build_query_variant(
    source_dir: Path, dest_dir: Path, *, variant: str, top_k: int
) -> dict:
    """Build a query-variant dataset at ``dest_dir`` from the local dataset at ``source_dir``.

    Copies ``corpus.jsonl`` and ``qrels/`` verbatim; rewrites ``queries.jsonl`` (and
    ``queries.json`` if present) with the transform applied to the query-text field; writes
    ``metadata.json`` with identity/provenance. Returns the written metadata dict.
    """
    if variant not in _TRANSFORMS:
        raise ValueError(
            f"Unknown variant: {variant!r}. Available: {available_variants()}"
        )
    transform = _TRANSFORMS[variant]

    corpus_path = source_dir / "corpus.jsonl"
    if not corpus_path.is_file():
        raise FileNotFoundError(f"Source corpus.jsonl not found: {corpus_path}")
    qrels_src = source_dir / "qrels"
    if not qrels_src.is_dir():
        raise FileNotFoundError(f"Source qrels/ not found: {qrels_src}")
    queries_jsonl_src = source_dir / "queries.jsonl"
    if not queries_jsonl_src.is_file():
        raise FileNotFoundError(f"Source queries.jsonl not found: {queries_jsonl_src}")

    df = document_frequencies(corpus_path)

    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(corpus_path, dest_dir / "corpus.jsonl")

    qrels_dest = dest_dir / "qrels"
    if qrels_dest.exists():
        shutil.rmtree(qrels_dest)
    shutil.copytree(qrels_src, qrels_dest)

    total_queries = 0
    fallback_count = 0
    out_lines: list[str] = []
    for line in queries_jsonl_src.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        total_queries += 1
        new_text, used_fallback = transform(obj["text"], df, top_k)
        if used_fallback:
            fallback_count += 1
        new_obj = dict(obj)
        new_obj["text"] = new_text
        out_lines.append(json.dumps(new_obj, ensure_ascii=False))
    (dest_dir / "queries.jsonl").write_text(
        "".join(line + "\n" for line in out_lines), encoding="utf-8"
    )

    queries_json_src = source_dir / "queries.json"
    if queries_json_src.is_file():
        data = json.loads(queries_json_src.read_text(encoding="utf-8"))
        for entry in data:
            new_text, _used_fallback = transform(entry["query"], df, top_k)
            entry["query"] = new_text
        (dest_dir / "queries.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
        )

    try:
        from .corpus_identity import corpus_signature

        source_signature = corpus_signature(source_dir)
    except Exception:
        source_signature = "unavailable"

    metadata = {
        "source_dataset": _dataset_display_name(source_dir),
        "source_corpus_signature": source_signature,
        "variant": variant,
        "top_k": top_k,
        "tool_version": TOOL_VERSION,
        "transform_description": (
            "Deterministic, LLM-free keyword extraction: tokenize on "
            r"[a-z0-9]+ over the lowercased query; drop tokens shorter than "
            f"{_MIN_TOKEN_LEN} chars or absent from the source corpus vocabulary; "
            "rank remaining distinct tokens by ascending corpus document frequency "
            "(rarest first, ties broken by first-occurrence order); keep the top "
            "top_k by that ranking; re-emit the kept tokens in their original "
            "query order. Falls back to the unmodified original query text when "
            "zero tokens are eligible."
        ),
        "total_queries": total_queries,
        "fallback_count": fallback_count,
    }
    (dest_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return metadata


def _dataset_display_name(source_dir: Path) -> str:
    """Best-effort ``golden/<name>`` / ``mixed/<name>`` display form of a resolved source dir."""
    parts = source_dir.parts
    for prefix in ("golden", "mixed"):
        if prefix in parts:
            idx = parts.index(prefix)
            return "/".join(parts[idx:])
    return source_dir.name
