#!/usr/bin/env python3
"""
Download OHR-Bench dataset and produce paired BEIR corpora for measuring
the ingestion quality tax.

Dataset: opendatalab/OHR-Bench (ICCV 2025, "OCR Hinders RAG")

Schema (per row = one PDF page):
  domain: str          Document domain (academic, law, finance, newspaper, manual, administration)
  doc_name: str        Document ID (e.g. "academic/2305.02437v3")
  page_idx: int        Page number within document
  gt_text: str         Ground-truth clean text
  semantic_noise_{GOT,MinerU,Qwen2.5-VL}_{mild,moderate,severe}: str
  formatting_noise_{mild,moderate,severe}: str

This script produces TWO corpus variants sharing the same doc IDs:
  1. ohr-bench-clean:  gt_text as document content (baseline)
  2. ohr-bench-noisy:  OCR-extracted text at configurable noise level

Since OHR-Bench has no queries, this script generates simple keyword queries
from the ground-truth text using extractive heuristics (first sentence, named
entities, key phrases). For proper retrieval evaluation, use GPL-style synthetic
query generation after creating the corpus.

Usage:
  python scripts/search/convert-ohrbench-to-beir.py --dry-run
  python scripts/search/convert-ohrbench-to-beir.py
  python scripts/search/convert-ohrbench-to-beir.py --domain law --noise-source MinerU --noise-level moderate
"""

import argparse
import json
import random
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

VALID_DOMAINS = ["academic", "law", "finance", "newspaper", "manual", "administration", "textbook"]
VALID_NOISE_SOURCES = ["GOT", "MinerU", "Qwen2.5-VL"]
VALID_NOISE_LEVELS = ["mild", "moderate", "severe"]


def extract_query(text: str, max_len: int = 100) -> str:
    """Extract a simple query from document text (first meaningful sentence)."""
    sentences = re.split(r'[.!?]\s+', text.strip())
    for sent in sentences:
        sent = sent.strip()
        # Skip very short sentences, headers, formulas
        if len(sent) < 20:
            continue
        if sent.startswith('#') or sent.startswith('$'):
            continue
        # Truncate to max_len
        if len(sent) > max_len:
            sent = sent[:max_len].rsplit(' ', 1)[0]
        return sent
    # Fallback: first max_len chars
    return text[:max_len].strip()


def main():
    parser = argparse.ArgumentParser(description="Convert OHR-Bench to paired BEIR corpora")
    parser.add_argument("--domain", default="all",
                        help=f"Domain filter ({', '.join(VALID_DOMAINS)}, or 'all')")
    parser.add_argument("--noise-source", default="MinerU",
                        help=f"OCR source for noisy variant ({', '.join(VALID_NOISE_SOURCES)})")
    parser.add_argument("--noise-level", default="moderate",
                        help=f"Noise severity ({', '.join(VALID_NOISE_LEVELS)})")
    parser.add_argument("--max-pages", type=int, default=1000,
                        help="Max pages to include (default: 1000)")
    parser.add_argument("--out-base", type=str, default="datasets/mixed",
                        help="Base output directory")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument("--dry-run", action="store_true", help="Print stats only")
    args = parser.parse_args()

    noise_col = f"semantic_noise_{args.noise_source}_{args.noise_level}"

    try:
        from datasets import load_dataset
    except ImportError:
        print("ERROR: pip install datasets", file=sys.stderr)
        sys.exit(1)

    print("Loading OHR-Bench from HuggingFace...")
    ds = load_dataset("opendatalab/OHR-Bench")
    data = ds["train"]
    print(f"Loaded {len(data)} pages, columns: {data.column_names}")

    if noise_col not in data.column_names:
        print(f"ERROR: noise column '{noise_col}' not found. Available:")
        noise_cols = [c for c in data.column_names if 'noise' in c]
        for c in noise_cols:
            print(f"  {c}")
        sys.exit(1)

    if args.dry_run:
        domain_counts = Counter(data["domain"])
        print(f"\nDomain distribution:")
        for domain, count in domain_counts.most_common():
            print(f"  {domain}: {count} pages")
        print(f"\nTotal: {len(data)} pages")

        # Sample text lengths
        gt_lengths = [len(row["gt_text"]) for row in data]
        gt_lengths.sort()
        print(f"\ngt_text length: median={gt_lengths[len(gt_lengths)//2]}, "
              f"p90={gt_lengths[int(len(gt_lengths)*0.9)]}, "
              f"max={gt_lengths[-1]}")
        return

    random.seed(args.seed)
    rows = list(data)

    # Filter by domain
    if args.domain != "all":
        rows = [r for r in rows if r["domain"] == args.domain]
        print(f"Filtered to domain '{args.domain}': {len(rows)} pages")

    # Sample if needed
    if len(rows) > args.max_pages:
        rows = random.sample(rows, args.max_pages)
        print(f"Sampled {args.max_pages} pages")

    # Group pages by document for multi-page docs
    docs_clean = {}
    docs_noisy = {}

    for row in rows:
        doc_id = f"{row['doc_name']}_p{row['page_idx']}"
        domain = row["domain"]

        gt_text = row["gt_text"]
        noisy_text = row[noise_col]

        docs_clean[doc_id] = {
            "title": f"{domain} — {row['doc_name']} page {row['page_idx']}",
            "text": gt_text,
        }
        docs_noisy[doc_id] = {
            "title": f"{domain} — {row['doc_name']} page {row['page_idx']}",
            "text": noisy_text,
        }

    print(f"Documents: {len(docs_clean)}")

    # Generate simple queries from ground-truth text
    queries = {}
    qrels = {}
    for i, (doc_id, doc) in enumerate(docs_clean.items()):
        q_text = extract_query(doc["text"])
        if len(q_text) < 10:
            continue
        q_id = f"q_{i}"
        queries[q_id] = q_text
        qrels[q_id] = {doc_id: 1}

    print(f"Generated {len(queries)} extractive queries")

    # Write CLEAN corpus
    clean_dir = Path(args.out_base) / "ohr-bench-clean"
    _write_beir(clean_dir, docs_clean, queries, qrels, {
        "version": "1.0",
        "created_date": datetime.now().isoformat(),
        "source": "OHR-Bench (opendatalab/OHR-Bench) — ground truth text",
        "paper": "OCR Hinders RAG, arXiv 2412.02592",
        "domain_filter": args.domain,
        "corpus_size": len(docs_clean),
        "query_count": len(queries),
        "variant": "clean (gt_text)",
    })

    # Write NOISY corpus (same queries and qrels, different text)
    noisy_dir = Path(args.out_base) / f"ohr-bench-{args.noise_source.lower()}-{args.noise_level}"
    _write_beir(noisy_dir, docs_noisy, queries, qrels, {
        "version": "1.0",
        "created_date": datetime.now().isoformat(),
        "source": f"OHR-Bench — {args.noise_source} {args.noise_level} noise",
        "paper": "OCR Hinders RAG, arXiv 2412.02592",
        "domain_filter": args.domain,
        "noise_source": args.noise_source,
        "noise_level": args.noise_level,
        "corpus_size": len(docs_noisy),
        "query_count": len(queries),
        "variant": f"noisy ({noise_col})",
    })

    print(f"\nDone! Two paired corpora created:")
    print(f"  Clean: {clean_dir}")
    print(f"  Noisy: {noisy_dir}")
    print(f"\nMeasure ingestion tax:")
    print(f"  python -m jseval run --dataset mixed/ohr-bench-clean --modes lexical,splade,bm25_splade,full")
    print(f"  python -m jseval run --dataset mixed/ohr-bench-{args.noise_source.lower()}-{args.noise_level} --modes lexical,splade,bm25_splade,full")
    print(f"  python -m jseval compare_runs ohr-bench-clean ohr-bench-{args.noise_source.lower()}-{args.noise_level}")


def _write_beir(out_dir: Path, corpus: dict, queries: dict, qrels: dict, meta: dict):
    """Write a BEIR-format dataset."""
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "qrels").mkdir(exist_ok=True)

    with open(out_dir / "corpus.jsonl", "w", encoding="utf-8") as f:
        for doc_id, doc in corpus.items():
            json.dump({"_id": doc_id, "title": doc["title"], "text": doc["text"]},
                      f, ensure_ascii=False)
            f.write("\n")

    with open(out_dir / "queries.jsonl", "w", encoding="utf-8") as f:
        for q_id, q_text in queries.items():
            json.dump({"_id": q_id, "text": q_text}, f, ensure_ascii=False)
            f.write("\n")

    with open(out_dir / "qrels" / "test.tsv", "w", encoding="utf-8") as f:
        f.write("query-id\tcorpus-id\tscore\n")
        for q_id, rels in qrels.items():
            for doc_id, rel in rels.items():
                f.write(f"{q_id}\t{doc_id}\t{rel}\n")

    with open(out_dir / "metadata.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    print(f"  Wrote {len(corpus)} docs, {len(queries)} queries to {out_dir}")


if __name__ == "__main__":
    main()
