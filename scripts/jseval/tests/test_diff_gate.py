"""Tests for diff_gate.py — ratio-based regression gates."""

from __future__ import annotations

from jseval.diff_gate import build_gate_decision, compare_ratio


class TestCompareRatio:
    def test_lower_is_better_ok(self):
        r = compare_ratio(100.0, 105.0, lower_is_better=True, max_ratio=1.10)
        assert r["status"] == "OK"
        assert r["ratio"] == 1.05

    def test_lower_is_better_regressed(self):
        r = compare_ratio(100.0, 115.0, lower_is_better=True, max_ratio=1.10)
        assert r["status"] == "REGRESSED"

    def test_higher_is_better_ok(self):
        r = compare_ratio(100.0, 95.0, lower_is_better=False, min_ratio=0.90)
        assert r["status"] == "OK"

    def test_higher_is_better_regressed(self):
        r = compare_ratio(100.0, 85.0, lower_is_better=False, min_ratio=0.90)
        assert r["status"] == "REGRESSED"

    def test_baseline_zero(self):
        r = compare_ratio(0.0, 10.0)
        assert r["status"] == "SKIP"
        assert r["ratio"] is None

    def test_exact_threshold(self):
        r = compare_ratio(100.0, 110.0, lower_is_better=True, max_ratio=1.10)
        assert r["status"] == "OK"  # 1.10 == 1.10, not >


class TestBuildGateDecision:
    def test_all_ok(self):
        d = build_gate_decision([
            {"status": "OK", "metric": "a"},
            {"status": "OK", "metric": "b"},
        ])
        assert d["gate_status"] == "pass"
        assert d["regression_count"] == 0

    def test_one_regression(self):
        d = build_gate_decision([
            {"status": "OK", "metric": "a"},
            {"status": "REGRESSED", "metric": "b"},
        ])
        assert d["gate_status"] == "fail"
        assert d["regression_count"] == 1

    def test_empty(self):
        d = build_gate_decision([])
        assert d["gate_status"] == "pass"
