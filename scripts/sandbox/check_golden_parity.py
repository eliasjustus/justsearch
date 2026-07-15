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

Before the tolerance comparison, three additional preconditions also fail
closed (model-identity audit, 2026-07-14) so a phantom ranking regression
never gets reported when parity simply wasn't measurable:

  - **Model identity**: the baseline's `embeddingFingerprint` (SHA-256 of the
    loaded embedding model file — verified against the registry hash) must
    match the round's `embeddingFingerprintCurrent`, read from the round's
    saved `evidence/api-api-knowledge-status.json` (collect-evidence.ps1's
    API-ladder capture of `/api/knowledge/status`). On the CPU path, dev and
    a downloaded install do NOT load byte-identical weights (shipped manifest
    selects FP32, dev's selects FP16) — comparing across that boundary would
    show a phantom regression that is really a model-identity difference.
  - **Corpus sanity**: the round's `indexedDocuments` must be at least half
    the baseline's — catches a round validated against an under-staged index
    (e.g. only bundled help docs, no real corpus).
  - **Dense-leg check**: none of the round's captured `golden/<id>.json`
    responses may show a `dense-retrieval` stage with status other than
    `executed` — a skipped dense leg means hybrid silently collapsed to BM25,
    which a prior sandbox round hit (NO_EMBEDDING_MODEL, no models staged).

A baseline generated before this provenance was recorded (no
`embeddingFingerprint` / `indexedDocuments` fields) still runs — the
identity/corpus preconditions are skipped with a printed WARNING naming what
couldn't be checked, rather than crashing.

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

# collect-evidence.ps1's Get-SanitizedFileName turns "/api/knowledge/status"
# into "api-api-knowledge-status.json" (leading slash stripped, remaining
# slashes -> dashes, "api-" prefix). Verified against collect-evidence.ps1's
# API sanity ladder (line ~151/160).
KNOWLEDGE_STATUS_EVIDENCE_FILENAME = "api-api-knowledge-status.json"

# Fail if the round's indexedDocuments is below this fraction of the
# baseline's — a simple, deliberately generous corpus-sanity floor (not a
# tolerance-grade metric; the tolerance comparison below is what judges
# ranking quality).
MIN_CORPUS_RATIO = 0.5


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
    """Load and return the golden-parity baseline. Caller handles I/O/JSON errors.

    `utf-8-sig` transparently strips a UTF-8 BOM when present and is a no-op
    otherwise, so this reads both BOM-less (Python-written) and BOM-prefixed
    (PowerShell-written, e.g. via `Out-File -Encoding utf8`) JSON alike.
    """
    with open(path, "r", encoding="utf-8-sig") as fh:
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
        # utf-8-sig: this capture is written by collect-evidence.ps1, which
        # writes UTF-8 WITH a BOM (Windows PowerShell 5.1's `Out-File
        # -Encoding utf8`). Plain "utf-8" fails at char 0 on that BOM
        # (JSONDecodeError: Expecting value: line 1 column 1) and this
        # except clause would silently turn that into a false "capture
        # unreadable" — utf-8-sig avoids the false negative outright.
        with open(capture_path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return None


def load_evidence_json(evidence_dir: str, filename: str) -> dict[str, Any] | None:
    """Load one of collect-evidence.ps1's saved API-ladder JSON snapshots
    (e.g. `api-api-knowledge-status.json`, the raw body of `/api/knowledge/status`).

    Returns None if the file is missing or unreadable, OR if the ladder
    recorded an error body instead of a real response (collect-evidence.ps1
    writes `{path, url, statusCode, exception, responseBody}` on a failed GET
    — a dict with no `embeddingFingerprintCurrent`/`indexedDocuments` key,
    which the callers below already treat as "field not present").
    """
    path = os.path.join(evidence_dir, filename)
    if not os.path.isfile(path):
        return None
    try:
        # utf-8-sig: collect-evidence.ps1's API-ladder snapshots are also
        # written UTF-8-with-BOM; see load_capture's comment for why plain
        # utf-8 here silently misreports a real, present fingerprint as
        # absent.
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return None


# ---------------------------------------------------------------------------
# Fail-closed preconditions (run BEFORE the tolerance comparison)
# ---------------------------------------------------------------------------


def check_model_identity(golden: dict[str, Any], evidence_dir: str) -> str | None:
    """Model-identity precondition. Returns a FAIL message, or None if this
    precondition is satisfied (possibly after printing a WARNING for a
    legacy baseline that predates provenance tracking)."""
    baseline_fingerprint = golden.get("embeddingFingerprint")
    if not baseline_fingerprint:
        print(
            "WARNING: baseline has no 'embeddingFingerprint' (generated before provenance "
            "tracking) — skipping the model-identity precondition. Regenerate the baseline "
            "with the current gen_golden_parity.py to restore this check.",
            file=sys.stderr,
        )
        return None

    status = load_evidence_json(evidence_dir, KNOWLEDGE_STATUS_EVIDENCE_FILENAME)
    round_fingerprint = (status or {}).get("embeddingFingerprintCurrent")

    if not round_fingerprint:
        return (
            "round has no embedding model / fingerprint (dense retrieval was not active) — "
            "parity is not measurable."
        )

    if round_fingerprint != baseline_fingerprint:
        return (
            f"model identity differs (baseline {baseline_fingerprint} vs round "
            f"{round_fingerprint}) — the round ran different embedding weights (e.g. CPU FP32 "
            "vs GPU FP16); parity is not measurable across a model change. Regenerate the "
            "baseline against the same model variant the round used."
        )

    return None


def check_corpus_sanity(golden: dict[str, Any], evidence_dir: str) -> str | None:
    """Corpus-sanity precondition. Returns a FAIL message, or None if this
    precondition is satisfied (possibly after printing a WARNING for a
    legacy baseline that predates provenance tracking)."""
    baseline_docs = golden.get("indexedDocuments")
    if not baseline_docs:
        print(
            "WARNING: baseline has no 'indexedDocuments' (generated before provenance "
            "tracking) — skipping the corpus-sanity precondition. Regenerate the baseline "
            "with the current gen_golden_parity.py to restore this check.",
            file=sys.stderr,
        )
        return None

    status = load_evidence_json(evidence_dir, KNOWLEDGE_STATUS_EVIDENCE_FILENAME)
    round_docs = (status or {}).get("indexedDocuments")

    if round_docs is None:
        return (
            "round has no readable indexedDocuments count in its evidence — the corpus was "
            "not staged/ingested; parity is not measurable."
        )

    if round_docs < baseline_docs * MIN_CORPUS_RATIO:
        return (
            f"round indexed {round_docs} docs vs baseline's {baseline_docs} — the corpus was "
            "not staged/ingested; parity is not measurable."
        )

    return None


def check_dense_leg(golden: dict[str, Any], evidence_dir: str) -> str | None:
    """Dense-retrieval-leg precondition. Scans every captured golden response
    for a `dense-retrieval` stage that did not execute. Returns a FAIL
    message on the first one found, or None if every capture that exists and
    parses shows dense retrieval executed (a missing/unreadable capture is
    not this precondition's concern — evaluate_all's fail-closed missing-
    capture path already reports that separately)."""
    golden_queries = [GoldenQuery.from_dict(raw) for raw in golden.get("queries", []) or []]
    for gq in golden_queries:
        response = load_capture(evidence_dir, gq.id)
        if response is None:
            continue
        stages = (response.get("searchTrace") or {}).get("stages") or []
        for stage in stages:
            if stage.get("id") == "dense-retrieval" and stage.get("status") != "executed":
                reason = stage.get("reason") or stage.get("status") or "unknown"
                return (
                    f"dense retrieval was skipped in the round (query {gq.id!r}, reason: "
                    f"{reason}) — hybrid collapsed to BM25; parity is not measurable."
                )
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

    identity_failure = check_model_identity(golden, args.evidence_dir)
    if identity_failure:
        print(f"BLOCKING (model identity): {identity_failure}", file=sys.stderr)
        return 1

    corpus_failure = check_corpus_sanity(golden, args.evidence_dir)
    if corpus_failure:
        print(f"BLOCKING (corpus sanity): {corpus_failure}", file=sys.stderr)
        return 1

    dense_leg_failure = check_dense_leg(golden, args.evidence_dir)
    if dense_leg_failure:
        print(f"BLOCKING (dense-retrieval leg): {dense_leg_failure}", file=sys.stderr)
        return 1

    verdicts = evaluate_all(golden, args.evidence_dir)
    if not verdicts:
        print("ERROR: golden baseline has no queries to check.", file=sys.stderr)
        return 2

    all_passed = print_report(verdicts)
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
