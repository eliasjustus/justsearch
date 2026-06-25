"""Tests for gradle_bench.py — engine indexing and kNN benchmarks."""

from __future__ import annotations

import json
from unittest.mock import patch

from jseval.gradle_bench import (
    _select_median_repeat,
    format_engine_console,
    format_knn_console,
)


class TestSelectMedianRepeat:
    def test_single_repeat(self):
        result = _select_median_repeat("doc-20000", [{"worst_p95_ms": 5}])
        assert result["case_id"] == "doc-20000"
        assert result["ok"] is True

    def test_picks_median(self):
        results = [
            {"worst_p95_ms": 10, "scenarios": [{"p95_ms": 10}]},
            {"worst_p95_ms": 5, "scenarios": [{"p95_ms": 5}]},
            {"worst_p95_ms": 8, "scenarios": [{"p95_ms": 8}]},
        ]
        selected = _select_median_repeat("doc-20000", results)
        # Median of [10, 5, 8] sorted = [5, 8, 10], median index = 1 → value 8
        # Closest to 8 is index 2 (the one with worst_p95_ms=8)
        assert selected["result"]["worst_p95_ms"] == 8
        assert selected["repeat_count"] == 3

    def test_two_repeats(self):
        results = [
            {"worst_p95_ms": 10, "scenarios": [{"p95_ms": 10}]},
            {"worst_p95_ms": 6, "scenarios": [{"p95_ms": 6}]},
        ]
        selected = _select_median_repeat("test", results)
        assert selected["repeat_count"] == 2


class TestFormatEngineConsole:
    def test_output(self):
        result = {
            "total_runs": 3,
            "statistics": {
                "docs_per_s": {"median": 3500.0, "min": 3200.0, "max": 3800.0},
                "time_to_searchable_ms": {"median": 1500, "min": 1300, "max": 1700},
            },
        }
        output = format_engine_console(result)
        assert "Claim A" in output
        assert "3500" in output


class TestFormatKnnConsole:
    def test_output(self):
        result = {
            "cases": [
                {"case_id": "doc-20000", "ok": True,
                 "result": {"worst_p95_ms": 5.2}},
                {"case_id": "chunk-200000", "ok": True,
                 "result": {"worst_p95_ms": 12.1}},
            ],
        }
        output = format_knn_console(result)
        assert "Track G" in output
        assert "doc-20000" in output
        assert "chunk-200000" in output
