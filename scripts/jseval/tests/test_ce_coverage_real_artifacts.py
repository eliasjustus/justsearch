"""Integration check: run the ce_coverage classifier against REAL campaign artifacts.

The 2026-08-19 A/B campaign that motivated register F-052 left its run dirs under
``scripts/jseval/../../tmp/ce-ab`` (gitignored working data). These tests read them when present
and skip otherwise, so CI never depends on them.

What they pin, on measured data rather than hand-built shapes:

* the delivered-evidence reader separates the arms exactly as the campaign measured them — the
  contaminated ``legal-armA2`` run reranked 98/200 queries where its clean sibling
  ``legal-armA`` reranked 199/200;
* those artifacts predate the cross-encoder reason channel, so the guard reports ``unevaluable``
  rather than passing them — the back-compat stand-down must be LOUD, not clean-looking. This is
  the honest limit of a post-hoc read: with no recorded reason, a 51%-CE-less run is
  indistinguishable from a run that legitimately skipped 51% of its queries, and only a run
  produced by the fixed artifact writer can be judged;
* a real run with full coverage (``scifact-armA``) still verdicts ``ok`` even on the old shape,
  because a query that reranked needs no reason to explain it.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jseval.ce_coverage import (
    ce_coverage_verdict,
    state_from_per_query_record,
    states_from_per_query_records,
)

_CAMPAIGN_ROOT = Path(__file__).resolve().parents[3] / "tmp" / "ce-ab"

# (arm dir, run dir, expected CE-applied queries, expected total, expected verdict)
_RUNS = [
    ("legal-armA2", "20260819T011320_mixed_legal-clerc-200", 98, 200, "unevaluable"),
    ("legal-armB", "20260819T011059_mixed_legal-clerc-200", 167, 200, "unevaluable"),
    ("legal-armA", "20260819T010858_mixed_legal-clerc-200", 199, 200, "unevaluable"),
    ("enron-armA", "20260819T014032_mixed_enron-qa", 283, 300, "unevaluable"),
    ("scifact-armA", "20260819T015101_scifact", 300, 300, "ok"),
]


def _load(arm: str, run: str):
    run_dir = _CAMPAIGN_ROOT / arm / run
    per_query = run_dir / "hybrid_per_query.json"
    summary = run_dir / "summary.json"
    if not per_query.is_file() or not summary.is_file():
        pytest.skip(f"campaign artifact not present: {run_dir}")
    return (
        json.loads(per_query.read_text(encoding="utf-8")),
        json.loads(summary.read_text(encoding="utf-8")),
    )


@pytest.mark.parametrize(("arm", "run", "applied", "total", "verdict"), _RUNS)
def test_real_run(arm, run, applied, total, verdict):
    records, summary = _load(arm, run)
    states = states_from_per_query_records(records)
    assert len(states) == total
    # The delivered-evidence reader, on real data: how many queries were actually CE-reranked.
    assert sum(1 for s in states if s.applied) == applied

    observed = (summary["per_mode"]["hybrid"].get("pipeline_tracking") or {}).get("observed") or []
    # Every one of these runs reports `cross_encoder` as an observed leg — the F-052 hole: the
    # signal a gate reads is identical for the 49%-covered run and the 100%-covered one.
    assert "cross_encoder" in observed

    result = ce_coverage_verdict(states, ce_requested=True)
    assert result.verdict == verdict, result.reasons


def test_contaminated_and_clean_arms_are_indistinguishable_to_every_pre_existing_signal():
    """The premise of the guard, asserted against the two measured legal arms."""
    contaminated, contaminated_summary = _load(
        "legal-armA2", "20260819T011320_mixed_legal-clerc-200")
    clean, clean_summary = _load("legal-armA", "20260819T010858_mixed_legal-clerc-200")

    for summary in (contaminated_summary, clean_summary):
        mode = summary["per_mode"]["hybrid"]
        assert mode["comparable"] is True
        assert mode["comparability_reasons"] == []
        assert mode["ann_proof_status"] == "PASS"
        assert mode["error_count"] == 0

    # ... while the delivered CE evidence differs by half the query set.
    def _covered(records):
        return sum(1 for r in records if state_from_per_query_record(r).applied)

    assert _covered(contaminated) == 98
    assert _covered(clean) == 199
