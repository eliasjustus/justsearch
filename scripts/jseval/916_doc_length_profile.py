#!/usr/bin/env python3
"""Tempdoc 916 Part 1, step 1 — offline document-length profiling.

Answers the open question 916 sec.C.3 leaves standing: which corpora actually chunk at all, and
therefore which are arms and which are controls. A corpus whose documents never reach
``CHUNK_THRESHOLD_CHARS`` produces zero chunk documents, so running twelve chunk-size arms
against it would be machine-days of measuring nothing.

Reads ``datasets/<family>/<slug>/corpus.jsonl`` only. No backend, no network, no LLM.

Token estimation mirrors ``ChunkSplitter`` exactly rather than picking a convenient constant:
``LATIN_CHARS_PER_TOKEN = 5.0/1.3`` (3.846) unless a document is CJK-dominant (>50% of its
non-whitespace characters in a CJK block), in which case the splitter uses 1.0 char/token. That
rule is language-agnostic by construction -- it keys on the character set of the document, not on
a declared language -- so it does not introduce a per-language lever (invariant 6, ADR-0043).

Chunk-count estimation uses the splitter's own advance recurrence

    targetChars  = tokens_to_chars(target_tokens)
    overlapChars = tokens_to_chars(overlap_tokens)
    minChars     = min(tokens_to_chars(min_tokens), max(1, targetChars))
    advance      = max(targetChars - overlapChars, minChars)

and NOT its boundary search: ``findBoundaryAbsolute`` shifts each cut by up to +/-200 chars to
land on a sentence or paragraph, which perturbs individual spans but not the count materially.
The estimate is therefore labelled an estimate everywhere it is reported.

Usage:
    python 916_doc_length_profile.py [--out scripts/jseval/tmp/916-part1/doc-length-profile.json]
"""

from __future__ import annotations

import argparse
import json
import os
import math
import statistics
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# The corpora tempdoc 916 sec.C.3 nominates, in the order the campaign would run them.
CORPORA: list[tuple[str, str]] = [
    ("beir", "scifact"),
    ("mixed", "enron-qa"),
    ("mixed", "legal-clerc-200"),
    ("mixed", "miracl-de-2k"),
    ("mixed", "miracl-fr-2k"),
    ("mixed", "ohr-bench-clean"),
]

LATIN_CHARS_PER_TOKEN = 5.0 / 1.3
CJK_CHARS_PER_TOKEN = 1.0
CJK_THRESHOLD = 0.5
SHIPPED_THRESHOLD_CHARS = 2000

TARGETS = (128, 256, 384, 500)
OVERLAPS = (0, 25, 50)


def _is_cjk(ch: str) -> bool:
    """The blocks ChunkSplitter.isCjkCharacter names, expressed over Unicode code points."""
    cp = ord(ch)
    return (
        0x4E00 <= cp <= 0x9FFF  # CJK Unified Ideographs
        or 0x3400 <= cp <= 0x4DBF  # Extension A
        or 0x20000 <= cp <= 0x2A6DF  # Extension B
        or 0xF900 <= cp <= 0xFAFF  # Compatibility Ideographs
        or 0x3040 <= cp <= 0x309F  # Hiragana
        or 0x30A0 <= cp <= 0x30FF  # Katakana
        or 0xAC00 <= cp <= 0xD7AF  # Hangul Syllables
        or 0x1100 <= cp <= 0x11FF  # Hangul Jamo
    )


def chars_per_token(text: str) -> float:
    total = 0
    cjk = 0
    for ch in text:
        if ch.isspace():
            continue
        total += 1
        if _is_cjk(ch):
            cjk += 1
    if total == 0:
        return LATIN_CHARS_PER_TOKEN
    return CJK_CHARS_PER_TOKEN if (cjk / total) >= CJK_THRESHOLD else LATIN_CHARS_PER_TOKEN


def tokens_to_chars(tokens: int, ratio: float) -> int:
    if tokens <= 0:
        return 1
    return int(tokens * ratio)


def estimate_chunks(doc_chars: int, ratio: float, target: int, overlap: int, min_tokens: int) -> int:
    """Chunks a document of ``doc_chars`` would yield, by the splitter's advance recurrence."""
    target_chars = tokens_to_chars(target, ratio)
    overlap_chars = tokens_to_chars(overlap, ratio) if overlap > 0 else 0
    min_chars = min(tokens_to_chars(min_tokens, ratio), max(1, target_chars))
    advance = max(target_chars - overlap_chars, min_chars)
    if doc_chars <= target_chars:
        return 1
    return 1 + math.ceil((doc_chars - target_chars) / advance)


def scaled_min_tokens(target: int) -> int:
    """The campaign's min_tokens rule: one fifth of the target, so the advance floor never binds.

    Measured in ChunkingPolicyTest: at target 128 the shipped floor of 100 tokens delivers ~70% of
    a requested 50-token overlap; at 256 and above it is already inert. target//5 reproduces the
    shipped 100 at target 500, so the incumbent arm is unchanged by the rule.
    """
    return max(1, target // 5)


def _beir_docs(slug: str) -> list[dict] | None:
    """Load a BEIR corpus through ir_datasets, the way ``corpora._load_beir`` does.

    BEIR corpora are not materialized under ``datasets/``; they live in the shared ir_datasets
    cache (tempdoc 709). ``apply_ir_datasets_home`` points at this worktree's cache root, which on
    a fresh worktree is empty, so the main checkout's shared root is tried as a fallback -- read
    only, and only for a cache that is shared across worktrees by design.
    """
    try:
        import ir_datasets  # noqa: PLC0415
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from jseval import dataset_cache  # noqa: PLC0415
    except Exception:
        return None

    dataset_cache.apply_ir_datasets_home()
    candidates = [os.environ.get("IR_DATASETS_HOME")]
    main_checkout = Path("F:/justsearch-public/scripts/jseval/tmp/dataset-fetch-cache/ir_datasets")
    if main_checkout.exists():
        candidates.append(str(main_checkout))

    for home in candidates:
        if not home:
            continue
        os.environ["IR_DATASETS_HOME"] = home
        try:
            ds = ir_datasets.load(f"beir/{slug}/test")
            return [
                {"title": d.title or "", "text": d.text or ""} for d in ds.docs_iter()
            ]
        except Exception:
            continue
    return None


def read_corpus(path: Path) -> list[dict]:
    docs = []
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            docs.append(json.loads(line))
    return docs


def doc_text(doc: dict) -> str:
    title = doc.get("title") or ""
    body = doc.get("text") or doc.get("content") or ""
    # The indexer extracts a document's whole text; title and body are one document downstream.
    return (title + "\n" + body) if title else body


def profile(family: str, slug: str) -> dict | None:
    path = REPO_ROOT / "datasets" / family / slug / "corpus.jsonl"
    if path.exists():
        docs = read_corpus(path)
    elif family == "beir":
        docs = _beir_docs(slug)
        if docs is None:
            return {"family": family, "slug": slug, "present": False, "path": "ir_datasets cache"}
    else:
        return {"family": family, "slug": slug, "present": False, "path": str(path)}

    lengths: list[int] = []
    ratios: list[float] = []
    cjk_docs = 0
    for doc in docs:
        text = doc_text(doc)
        lengths.append(len(text))
        ratio = chars_per_token(text)
        ratios.append(ratio)
        if ratio == CJK_CHARS_PER_TOKEN:
            cjk_docs += 1

    lengths_sorted = sorted(lengths)

    def pct(p: float) -> int:
        if not lengths_sorted:
            return 0
        idx = min(len(lengths_sorted) - 1, int(round((len(lengths_sorted) - 1) * p)))
        return lengths_sorted[idx]

    above = [n for n in lengths if n >= SHIPPED_THRESHOLD_CHARS]
    est_tokens = [n / r for n, r in zip(lengths, ratios)]

    arms = {}
    for target in TARGETS:
        for overlap in OVERLAPS:
            total = 0
            for n, r in zip(lengths, ratios):
                if n < SHIPPED_THRESHOLD_CHARS:
                    continue
                c = estimate_chunks(n, r, target, overlap, scaled_min_tokens(target))
                # The writer drops a split that yields a single chunk (ChunkDocumentWriter:152).
                total += c if c > 1 else 0
            arms[f"{target}/{overlap}"] = total

    return {
        "family": family,
        "slug": slug,
        "present": True,
        "docs": len(docs),
        "chars": {
            "min": min(lengths) if lengths else 0,
            "p50": pct(0.50),
            "p90": pct(0.90),
            "p99": pct(0.99),
            "max": max(lengths) if lengths else 0,
            "mean": round(statistics.fmean(lengths), 1) if lengths else 0.0,
        },
        "est_tokens": {
            "p50": round(statistics.median(est_tokens), 1) if est_tokens else 0.0,
            "mean": round(statistics.fmean(est_tokens), 1) if est_tokens else 0.0,
            "max": round(max(est_tokens), 1) if est_tokens else 0.0,
        },
        "cjk_dominant_docs": cjk_docs,
        "chars_per_token_used": sorted(set(round(r, 3) for r in ratios)),
        "above_threshold_2000": {
            "docs": len(above),
            "share": round(len(above) / len(lengths), 4) if lengths else 0.0,
        },
        "estimated_chunks_per_arm": arms,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--out",
        default=str(REPO_ROOT / "scripts" / "jseval" / "tmp" / "916-part1" / "doc-length-profile.json"),
    )
    args = ap.parse_args()

    results = [profile(family, slug) for family, slug in CORPORA]

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps({"schema": "916-doc-length-profile.v1", "corpora": results}, indent=2),
        encoding="utf-8",
    )

    header = (
        f"{'corpus':<22}{'docs':>7}{'p50':>8}{'p90':>8}{'p99':>9}{'max':>9}"
        f"{'>=2000':>9}{'share':>8}{'chunks@500/50':>15}{'chunks@128/50':>15}"
    )
    print(header)
    print("-" * len(header))
    for r in results:
        if not r.get("present"):
            print(f"{r['family']+'/'+r['slug']:<22}{'ABSENT':>7}   {r['path']}")
            continue
        print(
            f"{r['family']+'/'+r['slug']:<22}{r['docs']:>7}{r['chars']['p50']:>8}"
            f"{r['chars']['p90']:>8}{r['chars']['p99']:>9}{r['chars']['max']:>9}"
            f"{r['above_threshold_2000']['docs']:>9}"
            f"{r['above_threshold_2000']['share']*100:>7.1f}%"
            f"{r['estimated_chunks_per_arm']['500/50']:>15}"
            f"{r['estimated_chunks_per_arm']['128/50']:>15}"
        )
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
