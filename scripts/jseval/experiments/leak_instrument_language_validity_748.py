#!/usr/bin/env python3
"""748 Phase 0 — measurement-validity probe for the corpus-leak instruments on German.

`scripts/jseval/jseval/corpus_leak.py` tokenizes with `[a-z0-9']+` (ASCII-only,
`corpus_leak.py:81-86`) and drops an ENGLISH-only stopword list
(`corpus_leak.py:61-77`). Both choices are correct for the two English members
and silently wrong for a German one:

  * `Straße` tokenizes to `stra` + `e`; `über` to `ber` — umlauts and `ß` act as
    token separators, so German content words fragment.
  * German function words (`der`, `die`, `das`, `und`, `mit`, `von`, `ist`) are
    not stopwords here, so they count as *content* overlap on both sides.

Either effect biases `query_overlap_report` and `ngram_selectivity_report` on a
German member, and those two are structural gates the 748 rebuild leans on. This
script QUANTIFIES the bias instead of assuming it is small: it recomputes the
same statistic with a language-agnostic tokenizer (Unicode `\\w+` over NFC text)
and a corpus-derived stopword set (top-N by document frequency — no authored
per-language list, so it stays inside the ADR-0043 spirit), and reports the delta.

This probe does not change any gate. Its output is evidence for the honest
caveat attached to the German member's certification record.

Usage:
  python leak_instrument_language_validity_748.py \
      --docs <cell>/fabricated-docs.jsonl --queries <cell>/fabricated-queries.json \
      --out tmp/748/leak-instrument-validity.json
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import unicodedata
from collections import Counter
from pathlib import Path

ASCII_RE = re.compile(r"[a-z0-9']+")
UNICODE_RE = re.compile(r"[\w']+", re.UNICODE)


def ascii_tokens(text: str) -> list[str]:
    return ASCII_RE.findall((text or "").lower())


def unicode_tokens(text: str) -> list[str]:
    return UNICODE_RE.findall(unicodedata.normalize("NFC", text or "").lower())


def load_docs(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text("utf-8").splitlines() if line.strip()]


def load_queries(path: Path) -> list[dict]:
    raw = json.loads(path.read_text("utf-8"))
    if isinstance(raw, dict):
        raw = raw.get("queries", raw)
    if isinstance(raw, dict):
        return [{"_id": k, **(v if isinstance(v, dict) else {"text": v})} for k, v in raw.items()]
    return raw


def doc_text(doc: dict) -> str:
    return " ".join(p for p in (doc.get("title") or "", doc.get("text") or "") if p)


def derived_stopwords(docs: list[dict], tok, top_n: int = 60) -> set[str]:
    """Corpus-derived stopwords: the top-N tokens by document frequency.

    Derived, not authored — no per-language resource is added to the repo.
    """
    df: Counter[str] = Counter()
    for d in docs:
        df.update(set(tok(doc_text(d))))
    return {t for t, _ in df.most_common(top_n)}


def overlap(queries: list[dict], docs: list[dict], tok, stop: set[str]) -> dict:
    by_id = {d.get("_id"): d for d in docs}
    ratios, zero = [], 0
    for q in queries:
        q_toks = {t for t in tok(q.get("text") or q.get("query") or "") if t not in stop}
        if not q_toks:
            continue
        best = 0.0
        for gid in q.get("evidence_ids") or []:
            d = by_id.get(gid)
            if not d:
                continue
            d_toks = {t for t in tok(doc_text(d)) if t not in stop}
            best = max(best, len(q_toks & d_toks) / len(q_toks))
        ratios.append(best)
        zero += 1 if best == 0.0 else 0
    return {
        "n_queries": len(ratios),
        "mean_overlap": round(statistics.fmean(ratios), 4) if ratios else 0.0,
        "max_overlap": round(max(ratios), 4) if ratios else 0.0,
        "zero_overlap_queries": zero,
        "zero_overlap_share": round(zero / len(ratios), 4) if ratios else 0.0,
    }


def fragmentation(docs: list[dict]) -> dict:
    """How much the ASCII tokenizer shatters the corpus relative to Unicode."""
    a = u = 0
    lost = Counter()
    for d in docs:
        text = doc_text(d)
        at, ut = ascii_tokens(text), unicode_tokens(text)
        a += len(at)
        u += len(ut)
        for t in ut:
            if not ASCII_RE.fullmatch(t):
                lost[t] += 1
    return {
        "ascii_token_count": a,
        "unicode_token_count": u,
        "ascii_over_unicode_ratio": round(a / u, 4) if u else 0.0,
        "n_non_ascii_token_types": len(lost),
        "top_fragmented_tokens": lost.most_common(15),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--docs", required=True, type=Path)
    ap.add_argument("--queries", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    docs = load_docs(args.docs)
    queries = load_queries(args.queries)

    from jseval.corpus_leak import _STOPWORDS  # the shipped English list

    uni_stop = derived_stopwords(docs, unicode_tokens)
    report = {
        "schema": "748-leak-instrument-validity.v1",
        "label": args.label or args.docs.parent.name,
        "docs": str(args.docs),
        "n_docs": len(docs),
        "n_queries": len(queries),
        "fragmentation": fragmentation(docs),
        "query_overlap": {
            "shipped_ascii_english_stopwords": overlap(
                queries, docs, ascii_tokens, set(_STOPWORDS)
            ),
            "unicode_corpus_derived_stopwords": overlap(
                queries, docs, unicode_tokens, uni_stop
            ),
        },
        "derived_stopword_sample": sorted(uni_stop)[:40],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, ensure_ascii=False), "utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False)[:3000])
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
