"""tempdoc 757: partial-usage capture for resource-exhausted ITT cells and the
conservative-direction rule that decides when a truncated (lower-bound) usage
value may be treated as exact.

Four verification strands:
  1. harness capture (`_record_cell`) — a usd-budget-exhausted cell keeps its
     token count (the is_error early-return no longer drops it); a wall-clock
     cell with no ResultMessage falls back to the accumulated token lower bound;
  2. composer direction check — baseline-arm truncation keeps the efficiency
     intervals available (conservative), with-tool-arm truncation forces them
     unavailable (fail closed);
  3. byte-identity — the `usage_truncated` flag is invisible in the composed
     record for the direction-safe (baseline) case, so pre-757 records project
     with an unchanged semantic_digest (755 optional-field precedent);
  4. roundtrip — the flag survives sanitize -> read_evidence, is emitted only
     when true, and raw vs evidence compose to the same digest.

The `usage_truncated` STAMPING glue lives in the scorer's `solve` closure
(agent_utility_inspect) and is exercised end-to-end by the orchestrator's live
forced-exhaustion smoke; its predicate parts (classify_error_kind on both
exhaustion markers) are unit-covered in test_duration_exhaustion_624.
"""

from __future__ import annotations

import copy

import pytest

from jseval import agent_utility_inspect as aui
from jseval.utility_evidence import read_evidence, sanitize_observation
from jseval.utility_governance import RESOURCE_EXHAUSTION, classify_error_kind
from jseval.utility_recompose import (
    finalize_evidence,
    finalize_observation_groups,
    semantic_digest,
)
from tests.test_agent_utility_inspect import _rmsg, _state
from tests.test_utility_evidence import _observation

_EXHAUSTION = "per-cell wall-clock budget exhausted"
_USD_EXHAUSTION = "result error: None | {'subtype': 'error_max_budget_usd'}"


# --- 1. harness capture (_record_cell) -------------------------------------

def _got(rmsg, *, usage_accum=None):
    got = {
        "attempts": {}, "results": {}, "texts": [], "rmsg": rmsg,
        "mcp_servers": None, "justsearch_tools": [],
    }
    if usage_accum is not None:
        got["usage_accum"] = usage_accum
    return got


def test_usd_budget_exhausted_cell_keeps_its_token_count():
    # A usd-budget cell DOES deliver an is_error ResultMessage carrying usage. The
    # pre-757 early-return dropped `unique_tokens`; capture must keep it (and cost).
    state = _state()
    rmsg = _rmsg(
        is_error=True, subtype="error_max_budget_usd",
        total_cost_usd=0.05, usage={"cache_creation_input_tokens": 7},
    )
    aui._record_cell(state, _got(rmsg), "A", [], None)
    assert state.metadata["cost_usd"] == 0.05
    assert state.metadata["unique_tokens"] == 7          # was None before tempdoc 757


def test_wall_clock_cell_without_rmsg_falls_back_to_accumulated_tokens():
    # No terminal ResultMessage (wall-clock cancel). Tokens come from the streamed
    # per-message accumulation (a lower bound); cost is genuinely unrecoverable -> None.
    state = _state()
    state.metadata["error"] = _EXHAUSTION            # set by solve() before _record_cell
    got = _got(None, usage_accum={"cache_creation_input_tokens": 4, "input_tokens": 30})
    aui._record_cell(state, got, "A", [], None)
    assert state.metadata["unique_tokens"] == 4
    assert state.metadata.get("cost_usd") is None
    assert state.metadata["usage"] == {"cache_creation_input_tokens": 4, "input_tokens": 30}


def test_wall_clock_cell_without_usage_stays_fail_closed():
    # No ResultMessage and nothing accumulated -> honest null, never a fabricated 0.
    state = _state()
    state.metadata["error"] = _EXHAUSTION
    aui._record_cell(state, _got(None, usage_accum={}), "A", [], None)
    assert state.metadata.get("unique_tokens") is None
    assert state.metadata.get("cost_usd") is None


def test_usd_exhaustion_marker_classifies_as_resource_exhaustion():
    # The stamping predicate: usd + wall-clock error texts are both exhaustion-class.
    assert classify_error_kind(_USD_EXHAUSTION) == RESOURCE_EXHAUSTION
    assert classify_error_kind(_EXHAUSTION) == RESOURCE_EXHAUSTION


# --- composer fixtures -----------------------------------------------------

def _cell(condition, qid, *, error=None, correct=None, truncated=False,
          cost=0.1, tok=10):
    obs = _observation(condition, excluded=error is not None, qid=qid)
    obs["error"] = error
    obs["excluded"] = error is not None
    obs["correct"] = (error is None) if correct is None else correct
    obs["cost_usd"] = cost
    obs["unique_tokens"] = tok
    obs["total_time"] = 100.0
    obs["working_time"] = 100.0
    if truncated:
        obs["usage_truncated"] = True
    obs["source"]["cohort"]["query_identity"] = {"sha256": "e" * 64, "row_count": 2}
    obs["source"]["cohort"]["campaign_identity"] = {
        "conditions": ["A", "B"], "seeds": 1,
        "expected_cells": ["A|0|q0", "B|0|q0", "A|0|q1", "B|0|q1"],
    }
    return obs


def _stratum(observations):
    record = finalize_observation_groups([observations], composed_at="t")
    return record, record["estimands"]["intention_to_treat"]["strata"][0]


# --- 2. composer direction check -------------------------------------------

def test_baseline_arm_truncation_keeps_efficiency_available():
    # A/q0 exhausted with a truncated (lower-bound) cost+tokens present. Understating
    # the BASELINE arm is conservative for a B-favouring efficiency claim, so the
    # efficiency intervals stay AVAILABLE (usage_complete holds; values are non-None).
    obs = [
        _cell("A", "q0", error=_EXHAUSTION, truncated=True),
        _cell("A", "q1"),
        _cell("B", "q0"),
        _cell("B", "q1"),
    ]
    _, stratum = _stratum(obs)
    assert stratum["usage_complete"] is True
    for metric in ("cost_usd", "provider_cache_creation_input_tokens"):
        block = stratum[metric]
        assert block.get("available") is not False
        assert len(block["delta_ci"]) == 2


def test_with_tool_arm_truncation_forces_efficiency_unavailable():
    # B/q0 exhausted with a truncated cost+tokens. Understating the WITH-TOOL arm
    # would over-state B's efficiency advantage -> anti-conservative -> fail closed,
    # even though every value is present (usage would otherwise be "complete").
    obs = [
        _cell("A", "q0"),
        _cell("A", "q1"),
        _cell("B", "q0", error=_EXHAUSTION, truncated=True),
        _cell("B", "q1"),
    ]
    _, stratum = _stratum(obs)
    for metric in ("cost_usd", "provider_cache_creation_input_tokens"):
        block = stratum[metric]
        assert block["available"] is False
        assert "anti-conservative" in block["reason"]


# --- 3. byte-identity (755-style optional-field digest stability) ----------

def test_baseline_truncation_flag_is_record_invisible():
    # The composed record never carries `usage_truncated`; for the direction-safe
    # (baseline) case it does not change availability either. So a pre-757-shaped
    # record (same cost/tokens, NO flag) has the identical semantic_digest -- the
    # new optional field adds no digest surface.
    with_flag = [
        _cell("A", "q0", error=_EXHAUSTION, truncated=True),
        _cell("A", "q1"), _cell("B", "q0"), _cell("B", "q1"),
    ]
    without_flag = [
        _cell("A", "q0", error=_EXHAUSTION, truncated=False),
        _cell("A", "q1"), _cell("B", "q0"), _cell("B", "q1"),
    ]
    record_with, _ = _stratum(with_flag)
    record_without, _ = _stratum(without_flag)
    assert semantic_digest(record_with) == semantic_digest(record_without)
    # And the flag is nowhere in the composed record.
    assert "usage_truncated" not in _flatten_keys(record_with)


def _flatten_keys(obj) -> set:
    keys: set = set()
    if isinstance(obj, dict):
        for key, value in obj.items():
            keys.add(key)
            keys |= _flatten_keys(value)
    elif isinstance(obj, list):
        for item in obj:
            keys |= _flatten_keys(item)
    return keys


# --- 4. per-protocol secondary unaffected by truncated cells ---------------

def test_per_protocol_view_unaffected_by_truncation():
    # `measured` (the per-protocol secondary) is built from successful_summaries,
    # which drops every excluded/exhausted cell -- so the truncation flag can never
    # reach it. The block is byte-identical whether or not the flag is set.
    with_flag = [
        _cell("A", "q0", error=_EXHAUSTION, truncated=True),
        _cell("A", "q1"), _cell("B", "q0"), _cell("B", "q1"),
    ]
    without_flag = copy.deepcopy(with_flag)
    without_flag[0].pop("usage_truncated")
    record_with, _ = _stratum(with_flag)
    record_without, _ = _stratum(without_flag)
    assert record_with["measured"] == record_without["measured"]


# --- 5. sanitize <-> read_evidence roundtrip -------------------------------

def test_usage_truncated_emitted_only_when_true():
    truncated = sanitize_observation(_cell("A", "q0", error=_EXHAUSTION, truncated=True))
    clean = sanitize_observation(_cell("A", "q1"))
    assert truncated["usage_truncated"] is True
    assert "usage_truncated" not in clean          # omitted-when-absent -> byte-identity


def test_usage_truncated_survives_sanitize_read_roundtrip(tmp_path):
    import json

    obs = [
        _cell("A", "q0", error=_EXHAUSTION, truncated=True),
        _cell("A", "q1"), _cell("B", "q0"), _cell("B", "q1"),
    ]
    raw = finalize_observation_groups([obs], composed_at="one")

    path = tmp_path / "observations.v1.jsonl"
    path.write_text(
        "".join(json.dumps(sanitize_observation(item), sort_keys=True) + "\n" for item in obs),
        encoding="utf-8",
    )
    read_back = {(o["condition"], o["qid"]): o for o in read_evidence(path)}
    assert read_back[("A", "q0")]["usage_truncated"] is True
    assert read_back[("A", "q1")].get("usage_truncated") is None

    evidence = finalize_evidence([path], composed_at="two")
    assert evidence["semantic_digest"] == raw["semantic_digest"]
