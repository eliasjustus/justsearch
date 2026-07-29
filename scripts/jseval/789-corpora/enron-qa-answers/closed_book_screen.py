#!/usr/bin/env python3
"""789 naturalistic-replication closed-book contamination screen ($0, LOCAL model proxy).

Per tempdoc 789's Phase 2 probe design, the real campaign calibrates contamination with
a SONNET closed-book pass (`jseval corpus-certify` / `utility_calibrate.closed_book_filter`,
which shells out to the paid `claude` CLI). This script is explicitly NOT that: it is a
$0 LOCAL-MODEL proxy run before any founder spend, to give the real calibration step a
prior on which of the 20 selected questions are contamination-suspect (Enron email content
is public and has been in wide circulation/training corpora for two decades, so some
memorization is expected on this corpus specifically -- 707/789 already treat this dataset
as needing the closed-book gate for exactly this reason).

Method: (1) ask the LOCAL model each question with NO corpus context ("closed-book").
(2) ask the SAME local model to judge whether its own closed-book answer conveys the same
core fact(s) as our synthesized reference answer (a substring scorer would not fit --
these are free-text sentences, not short slot answers). Both steps use the identical
llama-server instance already running for answer synthesis.

Output: closed-book-screen.v1.json -- per-qid {closed_book_answer, contamination_suspect,
judge_rationale}, plus an aggregate suspect count. Labeled everywhere as a local-model
proxy, never conflated with the real sonnet closed-book gate.

Usage:
  python closed_book_screen.py --queries queries.json --base-url http://127.0.0.1:33231 \
    --out closed-book-screen.v1.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from local_llm_client import chat, health  # noqa: E402

CLOSED_BOOK_PROMPT = (
    "Answer this question concisely from your own knowledge, in one or two sentences. "
    "If you do not know, say \"I don't know.\" Question: {question}"
)

JUDGE_PROMPT = (
    "REFERENCE ANSWER: {reference}\n\n"
    "CANDIDATE ANSWER: {candidate}\n\n"
    "Does the CANDIDATE ANSWER convey the same core fact(s) as the REFERENCE ANSWER "
    "(even if worded differently)? A candidate that says it doesn't know, or that states "
    "different/unrelated facts, does NOT match. Respond with exactly one word: YES or NO."
)


def screen(rows: list[dict], *, base_url: str, seed: int) -> list[dict]:
    out = []
    for i, row in enumerate(rows):
        qid = row.get("qid") or f"q{i + 1:04d}"
        closed_book_answer = chat(
            base_url, CLOSED_BOOK_PROMPT.format(question=row["query"]),
            max_tokens=200, seed=seed)
        judge_raw = chat(
            base_url,
            JUDGE_PROMPT.format(reference=row["answer"], candidate=closed_book_answer),
            max_tokens=10, seed=seed)
        suspect = judge_raw.strip().upper().startswith("YES")
        out.append({
            "qid": qid,
            "question": row["query"],
            "reference_answer": row["answer"],
            "closed_book_answer": closed_book_answer,
            "judge_verdict_raw": judge_raw.strip(),
            "contamination_suspect": suspect,
        })
        print(f"  {qid}: suspect={suspect} closed_book={closed_book_answer[:80]!r}", file=sys.stderr)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--queries", required=True, type=Path,
                     help="utility-run-shaped queries.json (from synthesize_answers.py)")
    ap.add_argument("--base-url", default="http://127.0.0.1:33231")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    if not health(args.base_url):
        print(f"ERROR: llama-server not healthy at {args.base_url}", file=sys.stderr)
        sys.exit(1)

    rows = json.loads(args.queries.read_text(encoding="utf-8"))
    results = screen(rows, base_url=args.base_url, seed=args.seed)
    n_suspect = sum(1 for r in results if r["contamination_suspect"])

    output = {
        "schema": "789-naturalistic-closed-book-screen.v1",
        "method": "local-model-proxy",
        "model": "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
        "_note": "NOT the sonnet closed-book calibration the real campaign runs "
                 "(jseval corpus-certify / utility_calibrate.closed_book_filter). This is a "
                 "$0 local-model prior for the founder-gated launch's own paid calibration step.",
        "n_questions": len(results),
        "n_contamination_suspect": n_suspect,
        "results": results,
    }
    args.out.write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n{n_suspect}/{len(results)} questions flagged contamination-suspect "
          f"(local-model proxy). Wrote {args.out}")


if __name__ == "__main__":
    main()
