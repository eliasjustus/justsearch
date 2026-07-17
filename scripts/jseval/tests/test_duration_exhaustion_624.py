"""tempdoc 624 (2026-07-17): the duration metric family + the
resource-exhaustion-as-failure primary ITT outcome rule.

Covers the four verification categories the increment requires:
  1. the fail-closed error classifier (both known exhaustion shapes + fail-closed),
  2. the duration stats block + its mandatory censoring-emission rule,
  3. ITT scoring with exhausted cells (scored-incorrect + retained) vs `other`
     errors (dropped as missing data), and
  4. the outcome-rule provenance stamp.
"""

from __future__ import annotations

import copy
import json

import pytest

from jseval.utility_comparison import _stats_from_pairs
from jseval.utility_evidence import _error_class, read_evidence, sanitize_observation
from jseval.utility_governance import (
    OTHER_ERROR,
    RESOURCE_EXHAUSTION,
    classify_error_kind,
    loss_accounting_from_observations,
)
from jseval.utility_recompose import (
    OUTCOME_RULE,
    finalize_evidence,
    finalize_observation_groups,
    semantic_digest,
)
from tests.test_utility_evidence import _observation


# --- 1. classifier (fail-closed) -------------------------------------------

@pytest.mark.parametrize(
    "text, expected",
    [
        ("per-cell wall-clock budget exhausted", RESOURCE_EXHAUSTION),
        ("prefix per-cell wall-clock budget exhausted suffix", RESOURCE_EXHAUSTION),
        ("result error: None | {'subtype': 'error_max_budget_usd'}", RESOURCE_EXHAUSTION),
        (None, None),                       # not errored
        ("forced timeout", OTHER_ERROR),    # bucketed/generic timeout is NOT exhaustion
        ("timeout", OTHER_ERROR),           # the evidence-bucketed class alone is fail-closed
        ("some unknown executor failure", OTHER_ERROR),
        ("", OTHER_ERROR),                  # errored-but-empty text is still `other`
    ],
)
def test_classify_error_kind_is_fail_closed(text, expected):
    assert classify_error_kind(text) == expected


# --- 2. duration stats + censoring emission rule ---------------------------

def _pair(seed, *, a_correct, c_correct, a_dur, c_dur, a_censored, c_censored):
    return {
        "seed": seed,
        "a_correct": a_correct, "c_correct": c_correct,
        "a_cost": 0.1, "c_cost": 0.1, "a_tok": 10, "c_tok": 10,
        "a_turns": 3, "c_turns": 2,
        "a_dur": a_dur, "c_dur": c_dur,
        "a_censored": a_censored, "c_censored": c_censored,
        "a_tool_calls": None, "c_tool_calls": None,
    }


def test_duration_block_carries_mandatory_censoring_fields():
    pairs = {
        "0:q0": _pair(0, a_correct=False, c_correct=True,
                      a_dur=100.0, c_dur=50.0, a_censored=True, c_censored=False),
        "0:q1": _pair(0, a_correct=True, c_correct=True,
                      a_dur=80.0, c_dur=40.0, a_censored=False, c_censored=False),
    }
    stats = _stats_from_pairs(pairs)
    assert "duration" in stats
    duration = stats["duration"]
    # Every arm is emitted through _censored_distribution -> a bare median
    # (a median without its censoring context) is structurally impossible.
    for arm in ("baseline", "with_tool"):
        assert {"n_censored", "completion_rate", "median", "mean", "p95", "n"} <= set(
            duration[arm]
        )
    assert duration["baseline"]["median"] == 90.0   # median([100, 80])
    assert duration["baseline"]["n_censored"] == 1
    assert duration["baseline"]["completion_rate"] == 0.5   # (2 - 1) / 2
    assert duration["with_tool"]["n_censored"] == 0
    assert duration["with_tool"]["completion_rate"] == 1.0
    assert "delta_mean" in duration


def test_duration_block_omitted_when_no_cell_carries_time():
    pairs = {
        "0:q0": _pair(0, a_correct=True, c_correct=True,
                      a_dur=None, c_dur=None, a_censored=False, c_censored=False),
    }
    stats = _stats_from_pairs(pairs)
    assert "duration" not in stats   # byte-identical to before the family existed


def test_completion_rate_never_negative_with_null_censored_durations():
    # Finding 2: a censored cell that recorded no wall-clock must not push
    # completion_rate below 0. 1 clean-with-time + 2 censored-without-time.
    pairs = {
        "0:q0": _pair(0, a_correct=True, c_correct=True,
                      a_dur=100.0, c_dur=100.0, a_censored=False, c_censored=False),
        "0:q1": _pair(0, a_correct=False, c_correct=True,
                      a_dur=None, c_dur=100.0, a_censored=True, c_censored=False),
        "0:q2": _pair(0, a_correct=False, c_correct=True,
                      a_dur=None, c_dur=100.0, a_censored=True, c_censored=False),
    }
    stats = _stats_from_pairs(pairs)
    baseline = stats["duration"]["baseline"]
    assert baseline["n"] == 1                    # only the clean cell has a time
    assert baseline["n_censored"] == 0           # censored cells contributed no duration
    assert baseline["n_missing_duration"] == 2   # surfaced separately
    assert baseline["completion_rate"] == 1.0    # NOT -1.0
    assert 0.0 <= baseline["completion_rate"] <= 1.0
    # A censored cell that DID record a (right-censored) time still counts.
    with_time = {
        "0:q0": _pair(0, a_correct=True, c_correct=True,
                      a_dur=100.0, c_dur=100.0, a_censored=False, c_censored=False),
        "0:q1": _pair(0, a_correct=False, c_correct=True,
                      a_dur=290.0, c_dur=100.0, a_censored=True, c_censored=False),
    }
    baseline2 = _stats_from_pairs(with_time)["duration"]["baseline"]
    assert baseline2["n"] == 2 and baseline2["n_censored"] == 1
    assert baseline2["completion_rate"] == 0.5
    assert "n_missing_duration" not in baseline2   # omitted when none missing


# --- 3. ITT scoring with exhausted cells vs `other` errors -----------------

_EXHAUSTION_ERROR = "per-cell wall-clock budget exhausted"
_OTHER_ERROR = "some infrastructure failure"


def _cell(condition, qid, *, error=None, total_time=100.0):
    obs = _observation(condition, excluded=error is not None, qid=qid)
    obs["error"] = error
    obs["excluded"] = error is not None
    obs["correct"] = error is None
    obs["total_time"] = total_time
    obs["working_time"] = total_time
    obs["source"]["cohort"]["query_identity"] = {"sha256": "e" * 64, "row_count": 2}
    obs["source"]["cohort"]["campaign_identity"] = {
        "conditions": ["A", "B"], "seeds": 1,
        "expected_cells": ["A|0|q0", "B|0|q0", "A|0|q1", "B|0|q1"],
    }
    return obs


def _itt_stratum(observations):
    record = finalize_observation_groups([observations], composed_at="t")
    return record, record["estimands"]["intention_to_treat"]["strata"][0]


def test_itt_scores_exhaustion_incorrect_and_retained():
    # A/q0 exhausted, A/q1 clean-correct; B/q0 clean-correct, B/q1 clean-correct.
    obs = [
        _cell("A", "q0", error=_EXHAUSTION_ERROR),
        _cell("A", "q1"),
        _cell("B", "q0"),
        _cell("B", "q1"),
    ]
    record, stratum = _itt_stratum(obs)
    loss = stratum["per_arm_loss"]
    # Exhaustion is retained (n_completed=clean=1) and visible (n_exhausted=1),
    # never a residual exclusion.
    assert loss["A"]["n_completed"] == 1
    assert loss["A"]["n_exhausted"] == 1
    assert loss["A"]["n_excluded"] == 0
    assert loss["A"]["exclusion_rate"] == 0.0
    # Both q0 and q1 pairs are retained (no `other` error), so all pairs stay.
    assert stratum["n_paired_observations"] == 2
    assert stratum["n_per_protocol_pairs"] == 2
    assert stratum["paired_retention"] == 1.0
    assert stratum["excluded_jaccard"] == 1.0
    # A/q0 exhausted -> scored incorrect; A/q1 correct -> A accuracy = 0.5.
    assert stratum["accuracy"]["baseline"] == 0.5
    assert stratum["accuracy"]["with_tool"] == 1.0
    # Duration present with q0's A-side censored.
    assert stratum["duration"]["baseline"]["n_censored"] == 1
    # comparability is not voided by exhaustion.
    assert record["comparability"]["comparable"] is True


def test_itt_drops_other_errors_as_missing_data():
    # A/q1 has an `other` error; its q1 pair must be dropped from the estimand,
    # but stay a residual exclusion in the comparability loss accounting.
    obs = [
        _cell("A", "q0"),
        _cell("A", "q1", error=_OTHER_ERROR),
        _cell("B", "q0"),
        _cell("B", "q1"),
    ]
    record, stratum = _itt_stratum(obs)
    loss = stratum["per_arm_loss"]
    assert loss["A"]["n_excluded"] == 1          # residual exclusion
    assert loss["A"].get("n_exhausted", 0) == 0  # no exhaustion in this record -> omitted
    assert "n_exhausted" not in loss["A"]
    # q1 pair dropped as missing data; only q0 remains in the ITT paired set.
    assert stratum["n_paired_observations"] == 2
    assert stratum["n_per_protocol_pairs"] == 1
    assert stratum["paired_retention"] == 0.5
    # residual asymmetry: A dropped q1, B dropped nothing.
    assert stratum["excluded_jaccard"] == 0.0


def test_loss_accounting_splits_exhaustion_from_residual():
    obs = [
        _cell("A", "q0", error=_EXHAUSTION_ERROR),
        _cell("A", "q1", error=_OTHER_ERROR),
        _cell("B", "q0"),
        _cell("B", "q1"),
    ]
    arms = loss_accounting_from_observations(obs)
    assert arms["A"].n_exhausted == 1
    assert arms["A"].n_excluded == 1              # residual (`other`) only
    assert arms["A"].excluded_query_ids == {"q1"}  # exhausted q0 is NOT excluded
    assert arms["A"].n_completed == 0             # clean count only
    assert arms["A"].n_attempted == 2


# --- Finding 1: raw vs evidence-roundtrip classification consistency --------

def test_error_class_maps_exhaustion_markers_to_distinct_categories():
    # The two exhaustion shapes bucket to their OWN categories...
    assert _error_class("per-cell wall-clock budget exhausted") == "wall_clock_budget_exhausted"
    assert _error_class("result error: None | {'subtype': 'error_max_budget_usd'}") == "usd_budget_exhausted"
    # ...and classify_error_kind accepts those bucketed categories as exhaustion.
    assert classify_error_kind("wall_clock_budget_exhausted") == RESOURCE_EXHAUSTION
    assert classify_error_kind("usd_budget_exhausted") == RESOURCE_EXHAUSTION
    # ...but a GENERIC infra timeout/budget bucket stays `other` (fail-closed).
    assert _error_class("connection timed out") == "timeout"
    assert classify_error_kind("timeout") == OTHER_ERROR
    assert classify_error_kind("budget") == OTHER_ERROR


def test_raw_and_evidence_roundtrip_agree_on_exhaustion(tmp_path):
    """Finding 1 regression: an exhaustion-bearing group composed from raw
    observations and through the sanitize->evidence->read_evidence roundtrip must
    yield the SAME semantic_digest, n_exhausted, and ITT accuracy -- otherwise the
    exhaustion-as-failure rule no-ops on the evidence path and a raw-composed
    record can never pass the publication builder's evidence-recompose check."""
    obs = [
        _cell("A", "q0", error=_EXHAUSTION_ERROR),   # raw wall-clock marker
        _cell("A", "q1"),
        _cell("B", "q0"),
        _cell("B", "q1"),
    ]
    raw = finalize_observation_groups([obs], composed_at="one")

    path = tmp_path / "observations.v1.jsonl"
    path.write_text(
        "".join(json.dumps(sanitize_observation(item), sort_keys=True) + "\n" for item in obs),
        encoding="utf-8",
    )
    # The sanitized evidence carries the DISTINCT exhaustion category, not a
    # generic bucket -- so read_evidence -> classify recovers exhaustion.
    assert read_evidence(path)[0]["error"] == "wall_clock_budget_exhausted"
    evidence = finalize_evidence([path], composed_at="two")

    assert evidence["semantic_digest"] == raw["semantic_digest"]
    raw_loss = raw["estimands"]["intention_to_treat"]["strata"][0]["per_arm_loss"]
    ev_loss = evidence["estimands"]["intention_to_treat"]["strata"][0]["per_arm_loss"]
    assert ev_loss["A"]["n_exhausted"] == raw_loss["A"]["n_exhausted"] == 1
    raw_acc = raw["estimands"]["intention_to_treat"]["strata"][0]["accuracy"]
    ev_acc = evidence["estimands"]["intention_to_treat"]["strata"][0]["accuracy"]
    assert ev_acc["baseline"] == raw_acc["baseline"]
    assert ev_acc["with_tool"] == raw_acc["with_tool"]


# --- 4. outcome-rule provenance --------------------------------------------

def test_composed_record_carries_outcome_rule_provenance():
    obs = [_observation("A"), _observation("B")]
    for item in obs:
        item["total_time"] = 12.0
    record = finalize_observation_groups([obs], composed_at="t")
    assert record["outcome_rule"] == OUTCOME_RULE
    assert record["outcome_rule"]["name"] == "resource-exhaustion-as-failure"
    assert record["outcome_rule"]["adopted"] == "2026-07-17"
    assert record["outcome_rule"]["post_hoc_for"]
    # The stamp is pure self-description: dropping it does not move the digest.
    stripped = copy.deepcopy(record)
    del stripped["outcome_rule"]
    assert semantic_digest(stripped) == record["semantic_digest"]
