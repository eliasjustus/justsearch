#!/usr/bin/env python3
"""check_golden_parity.py — FINALIZE-time search-quality parity check for a
JustSearch Sandbox release-candidate validation round.

Design (settled with the owner): the Sandbox cannot measure absolute search
quality (no jseval there). Instead it checks PARITY WITH DEV: at
candidate-qualification time the operator runs gen_golden_parity.py against
the dev stack on the SAME build + SAME corpus (scifact) the Sandbox round will
use, producing a per-candidate golden-parity.json baseline. During the round,
the Sandbox agent runs the same fixed query set (golden-queries.json) against
the installed candidate's API and saves each raw response as evidence
(evidence/golden/<queryId>.json — collect-evidence.ps1 does this). This script
runs on the HOST at finalize and compares the two, with TOLERANCE, not exact
rank equality:

  - per query, at least 7 of the golden top-10 doc identities must appear
    (in any order) in the captured top-10, AND
  - the golden #1 doc must appear somewhere in the captured top-3.

Goldens are regenerated per candidate, so this is self-maintaining across
intentional ranking changes — it only fails when the *installed candidate*
retrieves meaningfully differently than the dev build that qualified it.

Fails CLOSED, matching check_coverage.py's philosophy: any query with a
missing capture file is a failure, not a skip.

Pure Python 3 standard library only. No network access.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Any

MIN_OVERLAP = 7
TOP_N = 10
FIRST_WITHIN_TOP = 3


# ---------------------------------------------------------------------------
# Doc-identity normalization (shared with gen_golden_parity.py)
# ---------------------------------------------------------------------------


def normalize_identity(raw: str) -> str:
    """Strip directory prefixes so host/sandbox path roots don't break parity.

    The corpus files' basenames are stable across environments even when the
    full path (host staging dir vs. sandbox mapped folder) differs. Handles
    both '/' and '\\' separators since a raw id may come from either a
    POSIX-staged corpus or a Windows-mapped one.
    """
    normalized = raw.replace("\\", "/")
    return normalized.rsplit("/", 1)[-1]


def extract_doc_identity(hit: dict[str, Any]) -> str | None:
    """Resolve a search-result hit to a normalized doc identity.

    Priority: fields.parent_doc_id (chunk hit, links to the parent document)
    -> fields.doc_id (whole-doc hit; every indexed doc carries this) -> hit.id
    (last-resort fallback if fields are absent). Returns None if nothing
    resolvable is present.
    """
    fields = hit.get("fields") or {}
    raw = fields.get("parent_doc_id") or fields.get("doc_id")
    if not raw:
        raw = hit.get("id")
    if not raw:
        return None
    return normalize_identity(str(raw))


def extract_top_identities(response: dict[str, Any], limit: int = TOP_N) -> list[str]:
    """Return the ordered, normalized doc identities for the top `limit` hits.

    Hits with no resolvable identity are skipped (not counted, not padded) —
    a malformed hit should not silently manufacture a phantom parity match.
    """
    identities: list[str] = []
    for hit in (response.get("results") or [])[:limit]:
        identity = extract_doc_identity(hit)
        if identity is not None:
            identities.append(identity)
    return identities


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass
class GoldenQuery:
    id: str
    query: str
    kind: str
    expected_top10: list[str] = field(default_factory=list)

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "GoldenQuery":
        return GoldenQuery(
            id=raw.get("id", ""),
            query=raw.get("query", ""),
            kind=raw.get("kind", ""),
            expected_top10=list(raw.get("expectedTop10", []) or []),
        )


@dataclass
class QueryVerdict:
    query: GoldenQuery
    passed: bool
    reason: str
    overlap: int | None = None
    captured_top10: list[str] | None = None


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------


def load_golden(path: str) -> dict[str, Any]:
    """Load and return the golden-parity baseline. Caller handles I/O/JSON errors."""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def load_capture(evidence_dir: str, query_id: str) -> dict[str, Any] | None:
    """Load the sandbox-captured raw search response for `query_id`.

    Returns None if the capture file is missing or unreadable — the caller
    treats that as a failure (fail closed), not a skip.
    """
    capture_path = os.path.join(evidence_dir, "golden", f"{query_id}.json")
    if not os.path.isfile(capture_path):
        return None
    try:
        with open(capture_path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return None


# ---------------------------------------------------------------------------
# Tolerance check
# ---------------------------------------------------------------------------


def evaluate_query(golden_query: GoldenQuery, captured_top10: list[str]) -> QueryVerdict:
    """Apply the tolerance rule: >=MIN_OVERLAP/TOP_N overlap AND golden #1
    within the captured top FIRST_WITHIN_TOP. NOT exact rank equality."""
    expected = golden_query.expected_top10
    if not expected:
        return QueryVerdict(
            query=golden_query,
            passed=False,
            reason="golden baseline has an empty expectedTop10 for this query — nothing to compare",
            overlap=0,
            captured_top10=captured_top10,
        )

    overlap = len(set(expected) & set(captured_top10))
    overlap_ok = overlap >= MIN_OVERLAP

    golden_first = expected[0]
    first_ok = golden_first in captured_top10[:FIRST_WITHIN_TOP]

    passed = overlap_ok and first_ok
    reason = (
        f"overlap {overlap}/{len(expected)} (need >={MIN_OVERLAP}) "
        f"{'OK' if overlap_ok else 'FAIL'}; "
        f"golden #1 {golden_first!r} "
        f"{'found' if first_ok else 'NOT found'} in captured top-{FIRST_WITHIN_TOP} "
        f"{'OK' if first_ok else 'FAIL'}"
    )
    return QueryVerdict(query=golden_query, passed=passed, reason=reason, overlap=overlap, captured_top10=captured_top10)


def evaluate_all(golden: dict[str, Any], evidence_dir: str) -> list[QueryVerdict]:
    golden_queries = [GoldenQuery.from_dict(raw) for raw in golden.get("queries", []) or []]
    verdicts: list[QueryVerdict] = []
    for gq in golden_queries:
        response = load_capture(evidence_dir, gq.id)
        if response is None:
            verdicts.append(
                QueryVerdict(
                    query=gq,
                    passed=False,
                    reason=(
                        f"missing or unreadable capture file 'golden/{gq.id}.json' in evidence dir "
                        f"{evidence_dir!r} — fail closed, not skipped"
                    ),
                )
            )
            continue
        captured_top10 = extract_top_identities(response, TOP_N)
        verdicts.append(evaluate_query(gq, captured_top10))
    return verdicts


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------


def print_report(verdicts: list[QueryVerdict]) -> bool:
    """Print the deterministic per-query verdict table. Returns True iff all passed."""
    print("=" * 72)
    print("Golden-parity search-quality report (parity-with-dev, per-candidate baseline)")
    print("=" * 72)

    for verdict in sorted(verdicts, key=lambda v: v.query.id):
        status = "PASS" if verdict.passed else "FAIL"
        marker = "  " if verdict.passed else "**"
        print(f"{marker}[{status}]{marker} {verdict.query.id} ({verdict.query.kind}): {verdict.query.query!r}")
        print(f"    reason: {verdict.reason}")

    passed_count = sum(1 for v in verdicts if v.passed)
    failed_count = len(verdicts) - passed_count

    print("-" * 72)
    print(f"Golden queries: {len(verdicts)} total, {passed_count} passed, {failed_count} FAILED")

    if failed_count:
        print("=" * 72)
        print("BLOCKING: the following golden queries failed parity-with-dev:")
        for verdict in sorted(verdicts, key=lambda v: v.query.id):
            if not verdict.passed:
                print(f"  - {verdict.query.id}: {verdict.reason}")
        print("=" * 72)

    return failed_count == 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "FINALIZE-time golden-query search-quality parity check for a JustSearch "
            "Sandbox validation round (parity-with-dev, not absolute quality)."
        )
    )
    parser.add_argument(
        "--golden",
        required=True,
        help="Path to the per-candidate golden-parity.json baseline (from gen_golden_parity.py)",
    )
    parser.add_argument(
        "--evidence-dir",
        required=True,
        help="Path to the round's evidence directory (must contain golden/<queryId>.json captures)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        golden = load_golden(args.golden)
    except OSError as exc:
        print(f"ERROR: could not read golden baseline {args.golden!r}: {exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"ERROR: golden baseline {args.golden!r} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    if not os.path.isdir(args.evidence_dir):
        print(f"ERROR: evidence dir not found: {args.evidence_dir!r}", file=sys.stderr)
        return 2

    verdicts = evaluate_all(golden, args.evidence_dir)
    if not verdicts:
        print("ERROR: golden baseline has no queries to check.", file=sys.stderr)
        return 2

    all_passed = print_report(verdicts)
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
