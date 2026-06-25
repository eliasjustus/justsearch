#!/usr/bin/env python3
"""
Convert EnronQA to BEIR format for jseval.

Dataset: MichaelR207/enron_qa_0922 (arXiv 2505.00263)
Output: datasets/mixed/enron-qa/

Schema: each row has an email (str), questions (list[str]), include_email
(list[int]) where 1 = question requires the email to answer (grounded).
Train split has grounded questions; test split does not.

IMPORTANT: corpus-id values are lowercased for Windows filesystem compatibility.

Usage:
  python scripts/search/convert-enronqa-to-beir.py --dry-run
  python scripts/search/convert-enronqa-to-beir.py
  python scripts/search/convert-enronqa-to-beir.py --user dasovich-j --max-queries 300
"""

import argparse
import json
import random
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Convert EnronQA to BEIR format")
    parser.add_argument("--user", default=None,
                        help="User inbox filter (e.g. 'dasovich-j'). "
                             "'all' = cross-user. Default: auto-select largest.")
    parser.add_argument("--split", default="train",
                        help="Dataset split (default: train — test has 0 grounded questions)")
    parser.add_argument("--max-queries", type=int, default=300)
    parser.add_argument("--out-dir", type=str, default="datasets/mixed/enron-qa")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    try:
        from datasets import load_dataset
    except ImportError:
        print("ERROR: pip install datasets", file=sys.stderr)
        sys.exit(1)

    print(f"Loading EnronQA split '{args.split}' from HuggingFace...")
    data = load_dataset("MichaelR207/enron_qa_0922", split=args.split)
    print(f"Split '{args.split}': {len(data)} emails")

    if args.dry_run:
        user_counts = Counter(data["user"])
        print(f"\nTop 20 users:")
        for user, count in user_counts.most_common(20):
            print(f"  {user}: {count} emails")
        grounded = sum(
            sum(1 for f in row["include_email"] if f == 1)
            for row in data
        )
        total = sum(data["questions_count"])
        print(f"\nTotal questions: {total}")
        print(f"Grounded (include_email=1): {grounded} ({grounded * 100 // max(total, 1)}%)")
        return

    random.seed(args.seed)

    # Filter by user using dataset.filter() (avoids loading all rows into memory)
    selected_user = args.user
    if selected_user and selected_user != "all":
        data = data.filter(lambda r: r["user"] == selected_user)
        print(f"Filtered to user '{selected_user}': {len(data)} emails")
    elif selected_user is None:
        user_counts = Counter(data["user"])
        best_user = user_counts.most_common(1)[0][0]
        data = data.filter(lambda r: r["user"] == best_user)
        selected_user = best_user
        print(f"Auto-selected user '{best_user}': {len(data)} emails")

    # Flatten: one entry per (email, grounded question) pair
    flat = []
    for row in data:
        # Lowercase the doc ID for Windows filesystem compatibility
        doc_id = f"{row['user']}/{row['path']}".lower()
        for qi, q_text in enumerate(row["questions"]):
            if qi >= len(row["include_email"]) or row["include_email"][qi] != 1:
                continue
            flat.append({
                "doc_id": doc_id,
                "email": row["email"],
                "question": q_text,
            })

    print(f"Grounded question-email pairs: {len(flat)}")

    if len(flat) == 0:
        print("ERROR: No grounded questions found. Use --split train.", file=sys.stderr)
        sys.exit(1)

    # Build corpus (deduplicate by doc_id)
    corpus = {}
    for item in flat:
        did = item["doc_id"]
        if did not in corpus:
            email = item["email"]
            title = ""
            for line in email.split("\n"):
                if line.lower().startswith("subject:"):
                    title = line[len("subject:"):].strip()
                    break
            corpus[did] = {"title": title, "text": email}

    print(f"Unique emails: {len(corpus)}")

    # Sample queries
    if len(flat) > args.max_queries:
        flat = random.sample(flat, args.max_queries)
        print(f"Sampled {args.max_queries} queries")

    # Build queries and qrels
    queries = {}
    qrels = {}
    for i, item in enumerate(flat):
        q_id = f"q_{i}"
        queries[q_id] = item["question"]
        qrels[q_id] = {item["doc_id"]: 1}

    # Write output
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "qrels").mkdir(exist_ok=True)

    with open(out_dir / "corpus.jsonl", "w", encoding="utf-8") as f:
        for doc_id, doc in corpus.items():
            json.dump({"_id": doc_id, "title": doc["title"], "text": doc["text"]},
                      f, ensure_ascii=False)
            f.write("\n")
    print(f"Wrote {len(corpus)} docs to corpus.jsonl")

    with open(out_dir / "queries.jsonl", "w", encoding="utf-8") as f:
        for q_id, q_text in queries.items():
            json.dump({"_id": q_id, "text": q_text}, f, ensure_ascii=False)
            f.write("\n")
    print(f"Wrote {len(queries)} queries to queries.jsonl")

    with open(out_dir / "qrels" / "test.tsv", "w", encoding="utf-8") as f:
        f.write("query-id\tcorpus-id\tscore\n")
        for q_id, rels in qrels.items():
            for doc_id, rel in rels.items():
                f.write(f"{q_id}\t{doc_id}\t{rel}\n")
    print(f"Wrote {len(qrels)} qrels")

    with open(out_dir / "metadata.json", "w", encoding="utf-8") as f:
        json.dump({
            "version": "1.0",
            "created_date": datetime.now().isoformat(),
            "source": "EnronQA (MichaelR207/enron_qa_0922)",
            "paper": "arXiv 2505.00263",
            "split": args.split,
            "user_filter": selected_user,
            "grounded_only": True,
            "corpus_size": len(corpus),
            "query_count": len(queries),
        }, f, indent=2, ensure_ascii=False)

    print(f"\nDataset at: {out_dir}")
    print(f"\nNext steps:")
    print(f"  1. Materialize: python -c \"...\"  (see guide)")
    print(f"  2. Start backend with GPU + BGE-M3")
    print(f"  3. Add watched root: curl POST /api/indexing/roots")
    print(f"  4. Wait for backfill")
    print(f"  5. python -m jseval requery --dataset mixed/enron-qa --modes lexical,splade,bm25_splade,full --splade --embedding")


if __name__ == "__main__":
    main()
