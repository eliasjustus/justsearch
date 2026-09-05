"""Tempdoc 931: the C0 skip-rate roll-up and the per-query dense-stage fields it reads."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

from jseval import artifacts

_SCRIPT = Path(__file__).resolve().parents[1] / "915_c0_skip_rate.py"


def _load_script():
    spec = importlib.util.spec_from_file_location("c0_skip_rate", _SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


def test_trace_stage_of_returns_the_dense_node_and_empty_without_trace():
    resp = {
        "searchTrace": {
            "stages": [
                {"id": "cross-encoder", "status": "executed"},
                {"id": "dense-retrieval", "status": "skipped", "reason": "SKIPPED_NO_DISCRIMINATIVE_TERM"},
            ]
        }
    }
    stage = artifacts._trace_stage_of(resp, artifacts.DENSE_STAGE_WIRE_ID)
    assert stage["status"] == "skipped"
    assert stage["reason"] == "SKIPPED_NO_DISCRIMINATIVE_TERM"
    assert artifacts._trace_stage_of({}, artifacts.DENSE_STAGE_WIRE_ID) == {}
    assert artifacts._trace_stage_of({"searchTrace": "junk"}, artifacts.DENSE_STAGE_WIRE_ID) == {}


def test_per_query_entries_persist_dense_status_and_reason():
    # Minimal shape of what _build_per_query_entries consumes; mirrors the CE pair.
    mode_result = {
        "per_query_metrics": {"q1": {"nDCG@10": 1.0, "R@10": 1.0}},
        "raw_responses": [
            {
                "query_id": "q1",
                "results": [],
                "searchTrace": {
                    "stages": [
                        {"id": "dense-retrieval", "status": "skipped", "reason": "SKIPPED_SHORT_QUERY"},
                    ]
                },
            }
        ],
        "scored_docs": [],
    }
    entries = artifacts._build_per_query_entries("hybrid", mode_result, {"q1": {"d": 1}})
    assert len(entries) == 1
    assert entries[0]["denseStatus"] == "skipped"
    assert entries[0]["denseReason"] == "SKIPPED_SHORT_QUERY"


def test_skip_rate_rollup(tmp_path: Path):
    mod = _load_script()
    run = tmp_path / "20260905T000000_mixed_demo"
    run.mkdir()
    entries = [
        {"qid": "a", "denseStatus": "executed", "denseReason": None},
        {"qid": "b", "denseStatus": "skipped", "denseReason": "SKIPPED_NO_DISCRIMINATIVE_TERM"},
        {"qid": "c", "denseStatus": "skipped", "denseReason": "SKIPPED_SHORT_QUERY"},
        {"qid": "d", "denseStatus": "skipped", "denseReason": "SKIPPED_VECTOR_BLOCKED"},
        {"qid": "e"},  # pre-931 record: no dense field at all
    ]
    (run / "hybrid_per_query.json").write_text(json.dumps(entries), encoding="utf-8")
    (run / "summary.json").write_text(
        json.dumps(
            {
                "dataset": "mixed/demo",
                "git_sha": "abc",
                "per_mode": {"hybrid": {"aggregate_metrics": {"nDCG@10": 0.5, "R@10": 0.75}, "comparable": True}},
            }
        ),
        encoding="utf-8",
    )
    row = mod.summarize_run(run)
    assert row["queries"] == 5
    assert row["dense_unreported"] == 1
    assert row["dense_reported"] == 4
    assert row["dense_executed"] == 1
    # Only the two planner-typed reasons count as planner skips; a blocked leg does not.
    assert row["planner_skips"] == 2
    assert row["planner_skip_rate"] == pytest.approx(0.5)
    assert row["skip_reasons"]["SKIPPED_VECTOR_BLOCKED"] == 1
    assert row["ndcg10"] == 0.5 and row["r10"] == 0.75 and row["comparable"] is True
    text = mod.render([row])
    assert "mixed/demo" in text and "0.500" in text and "WARNING" in text
