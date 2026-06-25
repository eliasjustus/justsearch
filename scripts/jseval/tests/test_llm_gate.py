"""Tests for llm_gate.py — the LLM-generation-latency ratchet (tempdoc 640 L)."""

from __future__ import annotations

from jseval import llm_gate

_BENCH = {"statistics": {
    "ttft_p50_ms": {"median": 120.0},
    "e2e_latency_p50_ms": {"median": 5300.0},
    "token_rate_median_tps": {"median": None},  # null here (un-pinned baseline) -> skipped, not gated
}}


def test_within_floor_passes():
    base = {"metrics": {"ttft_p50_ms": 120.0, "e2e_latency_p50_ms": 5300.0}}
    rep = llm_gate.evaluate(base, _BENCH)
    assert rep["exit_code"] == 0
    assert {c["status"] for c in rep["checks"]} == {"ok"}


def test_ttft_regression_fails():
    # band 1.4: a bench TTFT of 200 vs floor 120 -> ratio 1.67 > 1.4 -> regression
    rep = llm_gate.evaluate({"metrics": {"ttft_p50_ms": 120.0}},
                            {"statistics": {"ttft_p50_ms": {"median": 200.0}}})
    assert rep["exit_code"] == 1
    assert next(c for c in rep["checks"] if c["name"] == "ttft_p50_ms")["status"] == "fail"


def test_within_band_edge_passes():
    # exactly 1.4x is not a regression (compare_ratio is strict >)
    rep = llm_gate.evaluate({"metrics": {"ttft_p50_ms": 100.0}},
                            {"statistics": {"ttft_p50_ms": {"median": 140.0}}})
    assert rep["exit_code"] == 0


def test_tokens_rate_is_higher_better(tmp_path=None):
    # tokens/sec is higher-is-better: a DROP below band 0.7 is the regression (tempdoc 640 D).
    base = {"metrics": {"token_rate_median_tps": 50.0}}
    # 40/50 = 0.8 >= 0.7 -> ok
    assert llm_gate.evaluate(base, {"statistics": {"token_rate_median_tps": {"median": 40.0}}})["exit_code"] == 0
    # 30/50 = 0.6 < 0.7 -> regression
    assert llm_gate.evaluate(base, {"statistics": {"token_rate_median_tps": {"median": 30.0}}})["exit_code"] == 1


def test_unpinned_skips():
    assert llm_gate.evaluate({}, _BENCH)["exit_code"] == 0


def test_missing_metric_is_data_error():
    rep = llm_gate.evaluate({"metrics": {"ttft_p50_ms": 120.0}}, {"statistics": {}})
    assert rep["exit_code"] == 2


def test_project_from_bench_is_measured():
    proj = llm_gate.project_bench_to_llm_baselines(_BENCH, src="abc1234")
    assert proj["projected_from_bench"] is True
    assert proj["metrics"]["ttft_p50_ms"] == 120.0
    assert proj["metrics"]["e2e_latency_p50_ms"] == 5300.0
    assert "abc1234" in proj["src"]
    # tokens/sec (null) is not projected
    assert "token_rate_median_tps" not in proj["metrics"]
