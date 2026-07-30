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
rank equality.

BLOCKING assertion (one, environment-robust):

  - the golden #1 doc must appear somewhere in the captured top-3.

DESCRIPTIVE signal (computed, reported, never suppressed — but not a verdict):

  - per query, how many of the golden top-10 doc identities appear (in any
    order) in the captured top-10, and whether that count is below the
    historical floor of 7.

Overlap@10 USED to be the second blocking half of the rule. It was demoted to
descriptive on 2026-07-30 (owner decision; tempdoc 750's pre-designed option
A4, evidence in tempdoc 798 section B7). The reason is instrument validity, NOT a
weakened bar: the overlap floor was calibrated on a same-machine dev-rebuild
population only — the baseline's own `calibration` block says so — while this
checker applies it across the dev -> Sandbox environment boundary. Measured,
that boundary moves dense scores 1.7e-2 to 6.8e-2 against a calibrated
envelope of 2.0e-4 (two to three orders of magnitude over) on ALL ten queries,
while two runs on ONE dev stack are bit-identical (delta exactly 0.000e+00).
A sub-floor overlap on that population is therefore an uncalibrated
measurement, not a demonstrated ranking regression, and it must not return a
blocking exit code.

What did NOT change, and is what keeps this from being "lower the floor until
it passes": overlap is still computed per query, still printed per query, and
every query below the old floor is still called out in its own prominent
summary block with the [PARITY_OVERLAP_MISS] reason code. Nothing is skipped
or hidden; the number simply no longer decides the exit code. The blocking
assertion that remains is the one the same evidence shows IS environment-
robust — golden #1 in the captured top-3 held on all ten queries, including
the three whose overlap fell below the floor.

Goldens are regenerated per candidate, so this is self-maintaining across
intentional ranking changes — it only blocks when the *installed candidate*
loses the golden top hit relative to the dev build that qualified it.

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

Alongside overlap, a v2 baseline (`formatVersion` 2, produced by the current
gen_golden_parity.py) carries per-hit dense-leg scores and per-leg
(vector/text/splade) top-10s, so this checker also reports, per query, a
score-identity probe (do the SHARED (query, doc) pairs' dense scores match
within the baseline's calibrated envelope, or did the embedding output itself
vary?) and a leg-attribution breakdown (which single retrieval leg diverges,
so a divergence can be root-caused host-side instead of just reported as a
symptom). These sit in the same descriptive tier as overlap. A v1 baseline (no
`formatVersion`, or `formatVersion` 1) silently loses both — it has no
`legScores`/`legTop10` at all, which is what made earlier rounds
unattributable — so the report says so explicitly; keep the baseline at v2.

Pure Python 3 standard library only. No network access.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Any

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

# golden_common.py is the shared identity/leg-score extraction authority for
# both gen_golden_parity.py (baseline generator) and this checker (tempdoc 750
# Part A) -- see that module's docstring. extract_doc_identity/
# extract_top_identities/normalize_identity used to be duplicated here
# verbatim; they are now imported so the two scripts cannot drift.
from golden_common import (  # noqa: E402
    BASELINE_FORMAT_VERSION,
    extract_doc_identity,
    extract_top_identities,
    leg_scores,
    normalize_identity,
)

# Formerly MIN_OVERLAP, the second BLOCKING half of the tolerance rule. Renamed
# with the 2026-07-30 demotion so the name cannot imply an enforced minimum:
# this floor now only decides whether a query's (always computed, always
# printed) overlap is called out as a descriptive finding. See the module
# docstring for why the floor is not valid across the dev -> Sandbox boundary.
OVERLAP_DESCRIPTIVE_FLOOR = 7
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

# Leg-capture modes (tempdoc 750 Part A2): collect-evidence.ps1 additionally
# POSTs each golden query once per single-leg mode and saves it as
# golden/<queryId>.<mode>.json, matching gen_golden_parity.py's LEG_MODES.
# LEG_MODE_DISPLAY maps the API `mode` string to the report label used
# elsewhere for the same leg (SearchTrace stage ids / golden_common's
# LEG_STAGE_IDS use "dense", not "vector", for this leg).
LEG_CAPTURE_MODES = ("vector", "text", "splade")
LEG_MODE_DISPLAY: dict[str, str] = {"vector": "dense", "text": "text", "splade": "splade"}

# Typed reason vocabulary (tempdoc 750 Part A4): every BLOCKING precondition
# and every descriptive attribution signal below tags its report line with
# one of these, mirroring the product layer's SearchReasonCode closed-
# vocabulary pattern instead of ad-hoc strings.
#
# BLOCKING codes — each one returns a non-zero exit.
PARITY_MODEL_MISMATCH = "PARITY_MODEL_MISMATCH"
PARITY_CORPUS_MISMATCH = "PARITY_CORPUS_MISMATCH"
PARITY_DENSE_LEG_SKIPPED = "PARITY_DENSE_LEG_SKIPPED"
PARITY_CAPTURE_MISSING = "PARITY_CAPTURE_MISSING"
PARITY_BASELINE_INCOMPLETE = "PARITY_BASELINE_INCOMPLETE"
PARITY_FIRST_HIT_MISS = "PARITY_FIRST_HIT_MISS"

# DESCRIPTIVE codes — reported prominently, never suppressed, never blocking.
PARITY_EMBEDDING_VARIANCE = "PARITY_EMBEDDING_VARIANCE"
PARITY_LEG_DIVERGENCE = "PARITY_LEG_DIVERGENCE"
PARITY_UNCALIBRATED_POPULATION = "PARITY_UNCALIBRATED_POPULATION"
# PARITY_OVERLAP_MISS was a BLOCKING code until 2026-07-30; it is now
# descriptive. The code itself is deliberately kept (not renamed away) so a
# round's log still names the same finding an operator has seen before, and so
# the demotion is legible rather than looking like the finding disappeared.
PARITY_OVERLAP_MISS = "PARITY_OVERLAP_MISS"

PARITY_BLOCKING_REASON_CODES: tuple[str, ...] = (
    PARITY_MODEL_MISMATCH,
    PARITY_CORPUS_MISMATCH,
    PARITY_DENSE_LEG_SKIPPED,
    PARITY_CAPTURE_MISSING,
    PARITY_BASELINE_INCOMPLETE,
    PARITY_FIRST_HIT_MISS,
)

PARITY_DESCRIPTIVE_REASON_CODES: tuple[str, ...] = (
    PARITY_EMBEDDING_VARIANCE,
    PARITY_LEG_DIVERGENCE,
    PARITY_UNCALIBRATED_POPULATION,
    PARITY_OVERLAP_MISS,
)

PARITY_REASON_CODES: tuple[str, ...] = PARITY_BLOCKING_REASON_CODES + PARITY_DESCRIPTIVE_REASON_CODES


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass
class GoldenQuery:
    id: str
    query: str
    kind: str
    expected_top10: list[str] = field(default_factory=list)
    # tempdoc 750 Part A: present only in a v2 (formatVersion 2) baseline.
    # leg_scores: {identity: {"sparse"?, "dense"?, "splade"?, "fusion"?: float}}
    # leg_top10: {"vector": [identities], "text": [...], "splade": [...]}
    leg_scores: dict[str, dict[str, float]] = field(default_factory=dict)
    leg_top10: dict[str, list[str]] = field(default_factory=dict)

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "GoldenQuery":
        return GoldenQuery(
            id=raw.get("id", ""),
            query=raw.get("query", ""),
            kind=raw.get("kind", ""),
            expected_top10=list(raw.get("expectedTop10", []) or []),
            leg_scores=dict(raw.get("legScores", {}) or {}),
            leg_top10=dict(raw.get("legTop10", {}) or {}),
        )


@dataclass
class QueryVerdict:
    query: GoldenQuery
    # BLOCKING verdict only: the capture was readable, the baseline had
    # something to compare against, and the golden #1 doc landed in the
    # captured top-FIRST_WITHIN_TOP. Overlap does NOT feed this (2026-07-30
    # demotion) -- see `overlap_ok` for the descriptive half.
    passed: bool
    reason: str
    overlap: int | None = None
    # DESCRIPTIVE: overlap >= OVERLAP_DESCRIPTIVE_FLOOR. False means "called
    # out prominently in the report", never "blocks". None means overlap was
    # not computable (missing capture / empty baseline expectedTop10).
    overlap_ok: bool | None = None
    # Which PARITY_BLOCKING_REASON_CODES member made this verdict block, or
    # None when it did not block.
    blocking_code: str | None = None
    captured_top10: list[str] | None = None
    # tempdoc 750 Part A1: {identity: {"sparse"?, "dense"?, "splade"?,
    # "fusion"?: float}} for the captured top-10's hits, keyed the same way as
    # GoldenQuery.leg_scores. None iff the capture itself was missing/
    # unreadable (evaluate_all's fail-closed branch never reaches this far).
    captured_leg_scores: dict[str, dict[str, float]] | None = None


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


def load_leg_capture(evidence_dir: str, query_id: str, mode: str) -> dict[str, Any] | None:
    """Load one per-leg-mode capture (tempdoc 750 Part A2): `golden/<queryId>.
    <mode>.json`, written by collect-evidence.ps1's per-leg capture loop
    alongside the hybrid `golden/<queryId>.json` capture.

    Returns None if the file is missing or unreadable. Unlike load_capture,
    this is NOT a fail-closed concern -- an OLD (pre-750) evidence set simply
    predates these files entirely, so their absence means "leg attribution
    unavailable" for the descriptive signal, never a BLOCKING failure.
    """
    capture_path = os.path.join(evidence_dir, "golden", f"{query_id}.{mode}.json")
    if not os.path.isfile(capture_path):
        return None
    try:
        # utf-8-sig: same BOM rationale as load_capture/load_evidence_json --
        # these are also written by collect-evidence.ps1.
        with open(capture_path, "r", encoding="utf-8-sig") as fh:
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
    """Apply the tolerance rule. BLOCKING: golden #1 within the captured top
    FIRST_WITHIN_TOP. DESCRIPTIVE: top-10 overlap vs
    OVERLAP_DESCRIPTIVE_FLOOR — computed and reported on every query, but not
    part of `passed` (2026-07-30 demotion; module docstring has the why).
    Neither half is exact rank equality."""
    expected = golden_query.expected_top10
    if not expected:
        return QueryVerdict(
            query=golden_query,
            passed=False,
            reason=(
                f"[{PARITY_BASELINE_INCOMPLETE}] golden baseline has an empty expectedTop10 for "
                "this query — nothing to compare"
            ),
            overlap=0,
            overlap_ok=None,
            blocking_code=PARITY_BASELINE_INCOMPLETE,
            captured_top10=captured_top10,
        )

    overlap = len(set(expected) & set(captured_top10))
    overlap_ok = overlap >= OVERLAP_DESCRIPTIVE_FLOOR

    golden_first = expected[0]
    first_ok = golden_first in captured_top10[:FIRST_WITHIN_TOP]

    overlap_note = (
        "OK" if overlap_ok else f"BELOW FLOOR [{PARITY_OVERLAP_MISS}] (descriptive, does not block)"
    )
    reason = (
        f"BLOCKING: golden #1 {golden_first!r} "
        f"{'found' if first_ok else 'NOT found'} in captured top-{FIRST_WITHIN_TOP} "
        f"{'OK' if first_ok else f'FAIL [{PARITY_FIRST_HIT_MISS}]'}; "
        f"descriptive: overlap {overlap}/{len(expected)} "
        f"(floor >={OVERLAP_DESCRIPTIVE_FLOOR}) {overlap_note}"
    )
    return QueryVerdict(
        query=golden_query,
        passed=first_ok,
        reason=reason,
        overlap=overlap,
        overlap_ok=overlap_ok,
        blocking_code=None if first_ok else PARITY_FIRST_HIT_MISS,
        captured_top10=captured_top10,
    )


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
                        f"[{PARITY_CAPTURE_MISSING}] missing or unreadable capture file "
                        f"'golden/{gq.id}.json' in evidence dir {evidence_dir!r} — fail closed, "
                        "not skipped"
                    ),
                    blocking_code=PARITY_CAPTURE_MISSING,
                )
            )
            continue
        captured_top10 = extract_top_identities(response, TOP_N)
        verdict = evaluate_query(gq, captured_top10)

        # tempdoc 750 Part A1: record each captured top-10 hit's per-leg
        # scores (keyed by the same normalized identity as GoldenQuery.
        # leg_scores) so the score-identity probe below can compare shared
        # (query, doc) pairs without re-reading the capture file. Report
        # payload only -- does not affect `verdict.passed`.
        captured_leg_scores: dict[str, dict[str, float]] = {}
        for hit in (response.get("results") or [])[:TOP_N]:
            identity = extract_doc_identity(hit)
            if identity is not None:
                captured_leg_scores[identity] = leg_scores(hit)
        verdict.captured_leg_scores = captured_leg_scores

        verdicts.append(verdict)
    return verdicts


# ---------------------------------------------------------------------------
# Attribution signals (tempdoc 750 Part A -- descriptive report payload only;
# neither signal affects a QueryVerdict.passed or the process exit code)
# ---------------------------------------------------------------------------


def compute_dense_score_signal(
    golden_query: GoldenQuery,
    captured_leg_scores: dict[str, dict[str, float]],
    envelope_abs: float | None,
) -> str:
    """Signal 1 -- score-identity probe (tempdoc 750 Part A1).

    A dense-leg score for a (query, doc) pair is a pure function of the two
    embeddings, so comparing it on the pairs shared by BOTH the baseline's
    `legScores` and the captured top-10's per-hit trace discriminates the two
    divergence classes: scores differ -> embedding-output variance; scores
    match while the top-10 SETS differ -> selection-side (HNSW) noise, not an
    embedding regression. Requires a v2 baseline (only it carries
    `legScores`) -- caller only invokes this once that's established.
    """
    shared_deltas: list[float] = []
    for identity, baseline_legs in golden_query.leg_scores.items():
        baseline_dense = baseline_legs.get("dense")
        if baseline_dense is None:
            continue
        captured_legs = captured_leg_scores.get(identity)
        if captured_legs is None:
            continue
        captured_dense = captured_legs.get("dense")
        if captured_dense is None:
            continue
        shared_deltas.append(abs(baseline_dense - captured_dense))

    if not shared_deltas:
        return (
            "dense-score identity: no shared score-bearing pairs (baseline and captured "
            "top-10 share no identity with a dense score)"
        )

    max_delta = max(shared_deltas)
    pair_count = len(shared_deltas)

    if envelope_abs is None:
        return (
            f"dense-score identity: max |delta| {max_delta:.2e} over {pair_count} shared pairs "
            "-> baseline missing calibration.denseScoreEnvelopeAbs, cannot evaluate envelope"
        )

    if max_delta > envelope_abs:
        return (
            f"dense-score identity: max |delta| {max_delta:.2e} over {pair_count} shared pairs "
            f"-> [{PARITY_EMBEDDING_VARIANCE}]"
        )

    return (
        f"dense-score identity: max |delta| {max_delta:.2e} over {pair_count} shared pairs "
        "-> scores consistent (divergence, if any, is selection-side)"
    )


def compute_leg_attribution(golden_query: GoldenQuery, evidence_dir: str) -> dict[str, int] | None:
    """Signal 2 -- per-leg attribution (tempdoc 750 Part A2).

    For each single-leg mode with BOTH a baseline `legTop10` entry and a
    readable round capture (`golden/<id>.<mode>.json`), computes the overlap
    of the two top-10 identity lists. Returns {display_label: overlap_count};
    None if no leg is comparable at all (old archived evidence predates the
    per-leg capture files, or a v1 baseline has no `legTop10`).
    """
    overlaps: dict[str, int] = {}
    for mode in LEG_CAPTURE_MODES:
        baseline_list = golden_query.leg_top10.get(mode)
        if not baseline_list:
            continue
        capture = load_leg_capture(evidence_dir, golden_query.id, mode)
        if capture is None:
            continue
        captured_top10 = extract_top_identities(capture, TOP_N)
        overlaps[LEG_MODE_DISPLAY[mode]] = len(set(baseline_list) & set(captured_top10))
    return overlaps or None


def format_leg_attribution_line(overlaps: dict[str, int] | None) -> str:
    """Render Signal 2's result as a report line. `overlaps` is None ->
    leg captures were unavailable for this query, never a failure by itself."""
    if overlaps is None:
        return "leg attribution unavailable (no per-leg captures in this evidence set)"

    min_overlap = min(overlaps.values())
    diverging = sorted(label for label, count in overlaps.items() if count == min_overlap)
    parts = ", ".join(f"{label} ({overlaps[label]}/{TOP_N})" for label in sorted(overlaps))
    return f"diverging leg(s): {parts} -> [{PARITY_LEG_DIVERGENCE}: {', '.join(diverging)}]"


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------


def print_overlap_policy_note(golden: dict[str, Any]) -> None:
    """State, in the round's own log, WHY overlap@10 is descriptive.

    An operator reading a log that shows sub-floor overlap next to a green
    exit code must be able to see the policy rather than infer that coverage
    was quietly dropped. This prints the population the floor WAS calibrated
    on (from the baseline's own `calibration` block, so it cites the artifact
    rather than a hardcoded claim) and the boundary it is being applied
    across."""
    calibration = golden.get("calibration") or {}
    population = calibration.get("population") or "same-machine dev rebuilds"
    print(
        f"[{PARITY_UNCALIBRATED_POPULATION}] overlap@10 is DESCRIPTIVE, not blocking "
        "(owner decision 2026-07-30; tempdoc 750 option A4):"
    )
    print(
        f"  the >={OVERLAP_DESCRIPTIVE_FLOOR}/{TOP_N} overlap floor was calibrated ONLY on the "
        f"{population!r} population, but this"
    )
    print(
        "  comparison crosses the dev -> Sandbox environment boundary, which that calibration "
        "never sampled."
    )
    print(
        "  A sub-floor overlap here is an UNCALIBRATED MEASUREMENT, not a demonstrated ranking "
        "regression, so it"
    )
    print(
        "  is reported in full above and below — never suppressed — but does not decide the exit "
        "code. The blocking"
    )
    print(
        f"  assertion is the environment-robust one: golden #1 within the captured "
        f"top-{FIRST_WITHIN_TOP}."
    )


def print_report(golden: dict[str, Any], evidence_dir: str, verdicts: list[QueryVerdict]) -> bool:
    """Print the deterministic per-query verdict table plus the tempdoc 750
    Part A attribution signals (v2 baselines only), then an attribution
    summary, the descriptive overlap findings + policy note, and the
    totals/BLOCKING block.

    Returns True iff no query BLOCKED — i.e. every capture was readable and
    every golden #1 landed in the captured top-FIRST_WITHIN_TOP. Overlap and
    the two attribution signals are report payload and never influence this
    return value (2026-07-30 demotion).
    """
    is_v2 = golden.get("formatVersion") == BASELINE_FORMAT_VERSION
    envelope_abs = (golden.get("calibration") or {}).get("denseScoreEnvelopeAbs")

    print("=" * 72)
    print("Golden-parity search-quality report (parity-with-dev, per-candidate baseline)")
    print("=" * 72)

    emb_variance_count = 0
    emb_consistent_count = 0
    emb_no_pairs_count = 0
    leg_divergence_count = 0
    leg_unavailable_count = 0

    for verdict in sorted(verdicts, key=lambda v: v.query.id):
        if not verdict.passed:
            status, marker = "BLOCK", "**"
        elif verdict.overlap_ok is False:
            # Passes the blocking assertion, but carries a descriptive finding
            # -- given its own label so it is never read as an unqualified pass.
            status, marker = "PASS (overlap below floor)", "!!"
        else:
            status, marker = "PASS", "  "
        print(f"{marker}[{status}]{marker} {verdict.query.id} ({verdict.query.kind}): {verdict.query.query!r}")
        print(f"    reason: {verdict.reason}")

        if is_v2 and verdict.captured_leg_scores is not None:
            dense_line = compute_dense_score_signal(verdict.query, verdict.captured_leg_scores, envelope_abs)
            print(f"    {dense_line}")
            if f"[{PARITY_EMBEDDING_VARIANCE}]" in dense_line:
                emb_variance_count += 1
            elif "scores consistent" in dense_line:
                emb_consistent_count += 1
            elif "no shared score-bearing pairs" in dense_line:
                emb_no_pairs_count += 1

            # Leg attribution is diagnostic -- it exists to root-cause a
            # divergence. Since the 2026-07-30 demotion a sub-floor overlap is
            # a divergence that no longer blocks, so it must still get the
            # attribution line; printing it only for blocking failures is what
            # left rounds 5 and 6 unattributable.
            if not verdict.passed or verdict.overlap_ok is False:
                leg_overlaps = compute_leg_attribution(verdict.query, evidence_dir)
                print(f"    {format_leg_attribution_line(leg_overlaps)}")
                if leg_overlaps is None:
                    leg_unavailable_count += 1
                else:
                    leg_divergence_count += 1

    passed_count = sum(1 for v in verdicts if v.passed)
    blocked_count = len(verdicts) - passed_count
    overlap_findings = [v for v in verdicts if v.overlap_ok is False]

    print("-" * 72)
    print("attribution summary")
    print("-" * 72)
    if not is_v2:
        print(
            f"[{PARITY_UNCALIBRATED_POPULATION}] score/leg signals unavailable: v1 baseline "
            "(regenerate with gen_golden_parity v2)"
        )
    else:
        print(
            f"dense-score identity: {emb_variance_count} flagged [{PARITY_EMBEDDING_VARIANCE}], "
            f"{emb_consistent_count} consistent, {emb_no_pairs_count} with no shared pairs"
        )
        measured = emb_variance_count + emb_consistent_count
        if measured > 0 and emb_variance_count == measured:
            # Every measurable query out of envelope is an ENVIRONMENT-level fact, not a
            # per-query anomaly: the same weights are producing different embeddings on the
            # two sides, and only the near-tie queries turn that into a ranking miss. Say it
            # here, or a reader has to count rows to notice. (Tempdoc 750 P2: a threshold
            # applied across an axis its calibration never sampled is itself a finding.)
            print(
                "  ^ SYSTEMATIC: every measurable query is out of envelope -- an "
                "environment-level embedding difference (same weights, different inference "
                "path), not a per-query ranking anomaly. Check the baseline's calibration "
                "block for whether this population was ever sampled."
            )
        print(
            f"leg attribution: {leg_divergence_count} diverging queries attributed to a diverging "
            f"leg, {leg_unavailable_count} diverging queries with leg attribution unavailable"
        )

    print("-" * 72)
    print("descriptive findings (reported, NOT blocking)")
    print("-" * 72)
    if overlap_findings:
        print(
            f"[{PARITY_OVERLAP_MISS}] {len(overlap_findings)} of {len(verdicts)} golden queries are "
            f"below the >={OVERLAP_DESCRIPTIVE_FLOOR}/{TOP_N} overlap floor:"
        )
        for verdict in sorted(overlap_findings, key=lambda v: v.query.id):
            print(
                f"  - {verdict.query.id} ({verdict.query.kind}): overlap {verdict.overlap}/{TOP_N} "
                f"(floor >={OVERLAP_DESCRIPTIVE_FLOOR})"
            )
    else:
        print(
            f"[{PARITY_OVERLAP_MISS}] none: every golden query is at or above the "
            f">={OVERLAP_DESCRIPTIVE_FLOOR}/{TOP_N} overlap floor."
        )
    print_overlap_policy_note(golden)

    print("-" * 72)
    print(
        f"Golden queries: {len(verdicts)} total, {passed_count} passed the BLOCKING assertion, "
        f"{blocked_count} BLOCKED; {len(overlap_findings)} with a descriptive overlap finding"
    )

    if blocked_count:
        print("=" * 72)
        print("BLOCKING: the following golden queries failed the environment-robust assertion:")
        for verdict in sorted(verdicts, key=lambda v: v.query.id):
            if not verdict.passed:
                print(f"  - {verdict.query.id}: {verdict.reason}")
        print("=" * 72)

    return blocked_count == 0


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
        print(f"BLOCKING [{PARITY_MODEL_MISMATCH}] (model identity): {identity_failure}", file=sys.stderr)
        return 1

    corpus_failure = check_corpus_sanity(golden, args.evidence_dir)
    if corpus_failure:
        print(f"BLOCKING [{PARITY_CORPUS_MISMATCH}] (corpus sanity): {corpus_failure}", file=sys.stderr)
        return 1

    dense_leg_failure = check_dense_leg(golden, args.evidence_dir)
    if dense_leg_failure:
        print(f"BLOCKING [{PARITY_DENSE_LEG_SKIPPED}] (dense-retrieval leg): {dense_leg_failure}", file=sys.stderr)
        return 1

    verdicts = evaluate_all(golden, args.evidence_dir)
    if not verdicts:
        print("ERROR: golden baseline has no queries to check.", file=sys.stderr)
        return 2

    all_passed = print_report(golden, args.evidence_dir, verdicts)
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
