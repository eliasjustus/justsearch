#!/usr/bin/env python3
"""789 naturalistic-replication reference-answer synthesis ($0, local model only).

WHY this exists instead of projecting the upstream `gold_answers` field: verified live
(HuggingFace datasets-server `first-rows` API, 2026-07-29) that `MichaelR207/enron_qa_0922`
DOES carry a `gold_answers` column (plus `rephrased_questions` / `alternate_answers` /
`incorrect_answers` / `gold_rationales` / `alternate_rationales`) aligned index-for-index
with `questions`. BUT this exact annotation layer was already investigated and ruled out by
a founder-ratified project decision: tempdoc 707's "EnronQA email-member source decision"
memo (707 EN-email, ratified 2026-07-16) found the HF dataset card states no license and
the paper (arXiv 2505.00263) states no dataset redistribution terms for the *derived QA
annotation layer* (questions/answers/rationales the paper authors added on top of the
public-domain FERC/CMU email text), and ratified Option 2: use the real email text (public
domain, FERC-investigation precedent) but supply OUR OWN gold rather than the paper's
QA-annotation layer. This script applies that same resolution here: it keeps the real,
already-established `questions` field (the pre-existing, years-long `mixed/enron-qa` usage
this repo already had before 707 ever ran -- tempdoc 666 documents this as one of the
"working, register-validated mixed/ corpora"), but synthesizes an independent reference
answer from (question, gold email text) via a LOCAL model instead of reading `gold_answers`
off the HF row. Scoring against these references is judge-authoritative by design (the
launch commands mandate a judge overlay) -- the reference text does not need to match any
particular external ground truth, only to state, in our own words, what the gold email
actually supports.

Requires an out-of-band llama-server already running (782 Section I recipe) -- this script
does not start or stop it.

Usage:
  python synthesize_answers.py --corpus-dir <mixed/enron-qa dir> --selected selected.json \
    --base-url http://127.0.0.1:33231 --out answers.json --queries-out queries.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from local_llm_client import chat, health  # noqa: E402

PROMPT_TEMPLATE_PATH = Path(__file__).parent / "answer-synthesis-prompt.v1.txt"
EMAIL_TRUNCATE_CHARS = 3000  # documented in the tempdoc: 5/20 selected emails exceed this


def load_corpus(corpus_dir: Path) -> dict[str, str]:
    docs: dict[str, str] = {}
    with open(corpus_dir / "corpus.jsonl", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            docs[row["_id"]] = row["text"]
    return docs


def synthesize(selected: list[dict], docs: dict[str, str], *, base_url: str,
               seed: int, checkpoint_path: Path) -> list[dict]:
    """Writes one JSON line per completed item to `checkpoint_path` as it goes, so a
    mid-run crash (observed live: a competing worktree's GPU lane starved this one and
    the server process was killed mid-loop) loses only the in-flight item, not every
    answer synthesized so far. Resumable: rows already present in the checkpoint are
    skipped on a re-run."""
    template = PROMPT_TEMPLATE_PATH.read_text(encoding="utf-8")
    done: dict[str, dict] = {}
    if checkpoint_path.is_file():
        with open(checkpoint_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    row = json.loads(line)
                    done[row["qid"]] = row
        print(f"  resuming: {len(done)} already-synthesized rows in checkpoint", file=sys.stderr)

    out = []
    with open(checkpoint_path, "a", encoding="utf-8") as ckpt:
        for row in selected:
            if row["qid"] in done:
                out.append(done[row["qid"]])
                continue
            doc_id = row["evidence_ids"][0]
            email = docs[doc_id]
            truncated = email[:EMAIL_TRUNCATE_CHARS]
            prompt = template.format(question=row["question"], email=truncated)
            answer = chat(base_url, prompt, max_tokens=400, seed=seed)
            record = {
                "qid": row["qid"],
                "source_qid": row["source_qid"],
                "question": row["question"],
                "answer": answer,
                "evidence_ids": row["evidence_ids"],
                "question_type": "natural",
                "email_truncated": len(email) > EMAIL_TRUNCATE_CHARS,
                "email_chars": len(email),
            }
            out.append(record)
            ckpt.write(json.dumps(record, ensure_ascii=False) + "\n")
            ckpt.flush()
            print(f"  {row['qid']} ({doc_id}): {answer[:100]!r}", file=sys.stderr)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corpus-dir", required=True, type=Path)
    ap.add_argument("--selected", required=True, type=Path)
    ap.add_argument("--base-url", default="http://127.0.0.1:33231")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True, type=Path, help="full synthesis record (with metadata)")
    ap.add_argument("--queries-out", required=True, type=Path,
                     help="utility-run-shaped queries.json: [{query, answer, question_type, evidence_ids}]")
    args = ap.parse_args()

    if not health(args.base_url):
        print(f"ERROR: llama-server not healthy at {args.base_url}", file=sys.stderr)
        sys.exit(1)

    selected = json.loads(args.selected.read_text(encoding="utf-8"))
    docs = load_corpus(args.corpus_dir)
    checkpoint_path = args.out.with_suffix(".checkpoint.jsonl")
    synthesized = synthesize(selected, docs, base_url=args.base_url, seed=args.seed,
                              checkpoint_path=checkpoint_path)

    args.out.write_text(json.dumps(synthesized, indent=2, ensure_ascii=False), encoding="utf-8")

    # utility-run shape (agent_utility_inspect.py:1649 expects r["query"] / r["answer"])
    queries_shaped = [
        {
            "query": r["question"],
            "answer": r["answer"],
            "question_type": r["question_type"],
            "evidence_ids": r["evidence_ids"],
        }
        for r in synthesized
    ]
    args.queries_out.write_text(json.dumps(queries_shaped, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {len(synthesized)} synthesized answers to {args.out}")
    print(f"Wrote utility-run-shaped queries to {args.queries_out}")


if __name__ == "__main__":
    main()
