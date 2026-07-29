#!/usr/bin/env python3
"""789 naturalistic-replication subset freeze (tempdoc 789 Phase 2 naturalistic prep).

Deterministically selects 20 qids from the materialized `mixed/enron-qa` BEIR-shape
corpus (`queries.jsonl` / `qrels/test.tsv` / `corpus.jsonl`, produced by
`scripts/search/convert-enronqa-to-beir.py`) for the F1/F2 naturalistic replication
probe. Pure function of the materialized files + a fixed seed -- no network, no model
call, no dev stack.

Selection rule (stated up front, not tuned post-hoc):
  1. Load all queries + qrels + the corpus doc-id -> text map.
  2. Sanity filter: (a) the query's qrel-gold doc id exists in corpus.jsonl (defensive --
     always true by the converter's own construction, since it derives both from the
     same flattened rows, but asserted rather than assumed); (b) the question is
     non-trivial, defined as >= 6 whitespace-separated tokens (drops degenerate/short
     questions that would not exercise a retrieval-and-answer loop meaningfully);
     (c) the gold email is <= `--max-email-chars` (default 3000, matching
     `synthesize_answers.py`'s `EMAIL_TRUNCATE_CHARS`) -- a first pass without this
     filter (2026-07-29) found the local answer-synthesis step degraded to
     "the provided email does not contain information..." on 4/20 draws, all four the
     longest gold emails in the draw (7.2KB-56.8KB, truncated to the first 3000 chars
     before the fact the question asks about ever appeared); capping gold-email length
     at selection time keeps the synthesized reference grounded in the FULL email the
     question is about, rather than shipping a known-degenerate reference and
     documenting it after the fact.
  3. Deterministically shuffle the sanity-passing pool with `random.Random(SEED)` and
     take the first N=20 in draw order.
  4. Assign position labels q0001..q0020 in that draw order (782-hero convention --
     labels are positional, not semantically tied to the source `_id`; the source id
     is preserved separately as `source_qid` for traceability).

Output: `selected.json` -- [{qid, source_qid, question, evidence_ids: [doc_id]}, ...].
Deliberately NO "answer" field here -- answer synthesis is a separate, LLM-dependent
step (`synthesize_answers.py`) so the deterministic, hash-frozen identity of *which
questions were picked* never depends on model output.

Usage:
  python select_subset.py --corpus-dir <path to mixed/enron-qa> --seed 789 --n 20 \
    --out selected.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_beir(corpus_dir: Path) -> tuple[dict[str, str], dict[str, str], dict[str, int]]:
    """Returns (qid -> question text, qid -> gold doc id, doc id -> text length)."""
    queries: dict[str, str] = {}
    with open(corpus_dir / "queries.jsonl", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            queries[row["_id"]] = row["text"]

    qrels: dict[str, str] = {}
    with open(corpus_dir / "qrels" / "test.tsv", encoding="utf-8") as f:
        header = f.readline()
        assert header.strip() == "query-id\tcorpus-id\tscore", f"unexpected qrels header: {header!r}"
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            qid, doc_id, _score = line.split("\t")
            qrels[qid] = doc_id  # one gold doc per query by construction

    doc_lengths: dict[str, int] = {}
    with open(corpus_dir / "corpus.jsonl", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            doc_lengths[row["_id"]] = len(row["text"])

    return queries, qrels, doc_lengths


def select(
    queries: dict[str, str], qrels: dict[str, str], doc_lengths: dict[str, int], *, seed: int,
    n: int, min_words: int = 6, max_email_chars: int = 3000,
) -> list[dict]:
    sane_qids = []
    for qid, question in queries.items():
        gold = qrels.get(qid)
        if gold is None or gold not in doc_lengths:
            continue  # sanity (a): gold doc must exist in corpus
        if len(question.split()) < min_words:
            continue  # sanity (b): non-trivial question
        if doc_lengths[gold] > max_email_chars:
            continue  # sanity (c): gold email must fit untruncated in the synthesis prompt
        sane_qids.append(qid)

    sane_qids.sort()  # fix input order before shuffling -- dict iteration order must not matter
    rng = random.Random(seed)
    drawn = sane_qids[:]
    rng.shuffle(drawn)
    picked = drawn[:n]

    return [
        {
            "qid": f"q{i + 1:04d}",
            "source_qid": qid,
            "question": queries[qid],
            "evidence_ids": [qrels[qid]],
        }
        for i, qid in enumerate(picked)
    ]


def compute_digests(selected: list[dict]) -> dict[str, str]:
    qids = [row["qid"] for row in selected]
    qid_list_sha256 = _sha256_text("\n".join(qids) + "\n")
    canonical = [
        {"qid": r["qid"], "question": r["question"], "evidence_ids": r["evidence_ids"]}
        for r in selected
    ]
    selected_question_sha256 = _sha256_text(json.dumps(canonical, separators=(",", ":")))
    return {"qid_list_sha256": qid_list_sha256, "selected_question_sha256": selected_question_sha256}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corpus-dir", required=True, type=Path)
    ap.add_argument("--seed", type=int, default=789)
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--max-email-chars", type=int, default=3000)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    queries, qrels, doc_lengths = load_beir(args.corpus_dir)
    selected = select(queries, qrels, doc_lengths, seed=args.seed, n=args.n,
                       max_email_chars=args.max_email_chars)
    digests = compute_digests(selected)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(selected, indent=2, ensure_ascii=False), encoding="utf-8")

    print(f"Selected {len(selected)} qids from {len(queries)} total queries "
          f"(seed={args.seed}, max_email_chars={args.max_email_chars}).")
    print(json.dumps(digests, indent=2))


if __name__ == "__main__":
    main()
