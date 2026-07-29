#!/usr/bin/env python3
"""748 Phase 1 — lexical-overlap stratification of REAL German retrieval.

Purpose
-------
Tempdoc 748 asks whether the German 10k semantic collapse is (a) the encoders'
German representation quality, (b) a scale/candidate-depth interaction, (c) the
fabricated DE gold design (pure zero-lexical-overlap synonym descriptors), or
(d) German text mechanics.

The charter's cheapest experiment (staged-recall decomposition over the existing
`mixed/de-miracl-*` 10k run artifacts) is **not runnable**: those run directories
no longer exist on disk (see `--prove-absence`). This script supplies the
offline substitute that the surviving artifacts *do* support, and it attacks the
same (a)-vs-(c) fork:

    On a REAL German corpus with REAL MIRACL queries and REAL qrels — no
    fabricated gold, no `_FILLER`, no injection, hence no leak — does German
    retrieval quality degrade as the query/gold **lexical** overlap falls
    towards zero?

If retrieval stays healthy in the lowest-overlap stratum, the encoders bridge
German semantically and hypothesis (a) is weakened at this scale. If it
collapses with overlap, (a) gains support independent of any corpus artifact.

The overlap statistic is deliberately **corpus-derived and language-agnostic**
(no stopword list, no stemmer, no per-language resource — mirroring the engine's
locale-invariant analysis invariant, ADR-0043):

  * `coverage`      — fraction of distinct query tokens present in the gold doc.
  * `idf_coverage`  — the same, weighted by corpus IDF computed from this very
                      corpus, so high-frequency function words contribute ~0
                      without anyone authoring a German stopword list.

Inputs (all read-only, all already on disk):
  --run-dir      a jseval eval-results run directory (needs `hybrid_per_query.json`
                 and `qrels.json`).
  --corpus-dir   the materialized corpus directory (`<name>.txt` per doc; the
                 filename is the URL-quoted BEIR doc id).

Output: one JSON report. Every number in tempdoc 748 that cites this experiment
comes from this script.

Usage:
  python de_bridge_lexical_stratification_748.py \
      --run-dir  <eval-results>/20260624T184651_mixed_miracl-de-2k \
      --corpus-dir <eval-corpora>/mixed/miracl-de-2k \
      --out tmp/748/lexical-stratification-de.json
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import unicodedata
from collections import Counter
from pathlib import Path
from urllib.parse import unquote

# Language-agnostic tokenizer: Unicode word characters, NFC-normalized, lowercased.
# Mirrors the engine's ICU + NFC + lowercase analysis chain closely enough for an
# overlap statistic; deliberately carries no per-language rule.
_TOKEN_RE = re.compile(r"\w+", re.UNICODE)


def tokenize(text: str) -> list[str]:
    return _TOKEN_RE.findall(unicodedata.normalize("NFC", text).lower())


def load_corpus(corpus_dir: Path) -> dict[str, str]:
    """Map BEIR doc id -> raw text. Filenames are URL-quoted doc ids."""
    docs: dict[str, str] = {}
    for path in corpus_dir.glob("*.txt"):
        doc_id = unquote(path.stem)
        docs[doc_id] = path.read_text(encoding="utf-8", errors="replace")
    return docs


def corpus_idf(docs: dict[str, str]) -> tuple[dict[str, float], float]:
    """Document-frequency IDF over the corpus itself; returns (idf, default_idf)."""
    df: Counter[str] = Counter()
    for text in docs.values():
        df.update(set(tokenize(text)))
    n = max(len(docs), 1)
    idf = {tok: math.log((n + 1) / (freq + 1)) + 1.0 for tok, freq in df.items()}
    default = math.log(n + 1) + 1.0  # an unseen token is maximally informative
    return idf, default


def overlap_stats(
    query: str,
    gold_texts: list[str],
    idf: dict[str, float],
    default_idf: float,
) -> dict[str, float]:
    """Best-over-gold query-token coverage, plain and IDF-weighted."""
    q_tokens = set(tokenize(query))
    if not q_tokens:
        return {"coverage": 0.0, "idf_coverage": 0.0, "n_query_tokens": 0}

    best_cov = 0.0
    best_idf_cov = 0.0
    total_idf = sum(idf.get(t, default_idf) for t in q_tokens)
    for text in gold_texts:
        d_tokens = set(tokenize(text))
        hit = q_tokens & d_tokens
        cov = len(hit) / len(q_tokens)
        idf_cov = (
            sum(idf.get(t, default_idf) for t in hit) / total_idf if total_idf else 0.0
        )
        best_cov = max(best_cov, cov)
        best_idf_cov = max(best_idf_cov, idf_cov)
    return {
        "coverage": best_cov,
        "idf_coverage": best_idf_cov,
        "n_query_tokens": len(q_tokens),
    }


def _mean(values: list[float]) -> float:
    return statistics.fmean(values) if values else 0.0


def stratify(rows: list[dict], key: str, edges: list[float]) -> list[dict]:
    """Bucket rows by `key` into [edges[i], edges[i+1]) strata and aggregate."""
    out = []
    for lo, hi in zip(edges, edges[1:]):
        # The final stratum is closed on the right so a value of exactly 1.0 lands somewhere.
        last = hi == edges[-1]
        members = [
            r
            for r in rows
            if lo <= r[key] and (r[key] <= hi if last else r[key] < hi)
        ]
        out.append(
            {
                "stratum": f"[{lo:.2f},{hi:.2f}{']' if last else ')'}",
                "n": len(members),
                "mean_ndcg@10": round(_mean([r["ndcg"] for r in members]), 4),
                "mean_recall@10": round(_mean([r["recall"] for r in members]), 4),
                "mean_p@1": round(_mean([r["p1"] for r in members]), 4),
                "mean_" + key: round(_mean([r[key] for r in members]), 4),
            }
        )
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-dir", required=True, type=Path)
    ap.add_argument("--corpus-dir", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--mode", default="hybrid")
    args = ap.parse_args(argv)

    per_query_path = args.run_dir / f"{args.mode}_per_query.json"
    qrels_path = args.run_dir / "qrels.json"
    for p in (per_query_path, qrels_path, args.corpus_dir):
        if not p.exists():
            print(f"MISSING: {p}", file=sys.stderr)
            return 2

    per_query = json.loads(per_query_path.read_text(encoding="utf-8"))
    qrels = json.loads(qrels_path.read_text(encoding="utf-8"))
    docs = load_corpus(args.corpus_dir)
    idf, default_idf = corpus_idf(docs)

    rows: list[dict] = []
    missing_gold_text = 0
    for rec in per_query:
        qid = rec["qid"]
        gold_ids = [d for d, rel in qrels.get(qid, {}).items() if float(rel) > 0]
        gold_texts = [docs[g] for g in gold_ids if g in docs]
        if not gold_texts:
            missing_gold_text += 1
            continue
        stats = overlap_stats(rec.get("query", ""), gold_texts, idf, default_idf)
        rows.append(
            {
                "qid": qid,
                "coverage": stats["coverage"],
                "idf_coverage": stats["idf_coverage"],
                "n_query_tokens": stats["n_query_tokens"],
                "ndcg": float(rec.get("ndcgAtK") or 0.0),
                "recall": float(rec.get("recallAtK") or 0.0),
                "p1": float(rec.get("p1AtK") or 0.0),
            }
        )

    edges = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0]
    report = {
        "schema": "748-lexical-stratification.v1",
        "run_dir": str(args.run_dir),
        "corpus_dir": str(args.corpus_dir),
        "mode": args.mode,
        "n_docs": len(docs),
        "n_queries_scored": len(rows),
        "n_queries_dropped_no_gold_text": missing_gold_text,
        "overall": {
            "mean_ndcg@10": round(_mean([r["ndcg"] for r in rows]), 4),
            "mean_recall@10": round(_mean([r["recall"] for r in rows]), 4),
            "mean_p@1": round(_mean([r["p1"] for r in rows]), 4),
            "mean_coverage": round(_mean([r["coverage"] for r in rows]), 4),
            "mean_idf_coverage": round(_mean([r["idf_coverage"] for r in rows]), 4),
        },
        "by_coverage": stratify(rows, "coverage", edges),
        "by_idf_coverage": stratify(rows, "idf_coverage", edges),
        "lowest_idf_coverage_decile": _lowest_decile(rows),
        "per_query": sorted(rows, key=lambda r: r["idf_coverage"]),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps({k: report[k] for k in ("overall", "by_idf_coverage")}, indent=2))
    print(f"\nwrote {args.out}")
    return 0


def _lowest_decile(rows: list[dict]) -> dict:
    """The 10% of queries with the least IDF-weighted lexical anchor to their gold."""
    if not rows:
        return {}
    ordered = sorted(rows, key=lambda r: r["idf_coverage"])
    k = max(1, len(ordered) // 10)
    sub = ordered[:k]
    return {
        "n": len(sub),
        "max_idf_coverage_in_stratum": round(max(r["idf_coverage"] for r in sub), 4),
        "mean_ndcg@10": round(_mean([r["ndcg"] for r in sub]), 4),
        "mean_recall@10": round(_mean([r["recall"] for r in sub]), 4),
        "mean_p@1": round(_mean([r["p1"] for r in sub]), 4),
    }


if __name__ == "__main__":
    raise SystemExit(main())
