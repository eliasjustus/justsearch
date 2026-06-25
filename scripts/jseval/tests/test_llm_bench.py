"""Tests for llm_bench.py — LLM inference benchmark."""

from __future__ import annotations

from jseval.llm_bench import PROFILES, _compute_metrics, format_console


class TestProfiles:
    def test_smoke_profile(self):
        assert PROFILES["smoke"]["doc_count"] == 5
        assert PROFILES["smoke"]["warmup"] == 1
        assert PROFILES["smoke"]["timed"] == 2

    def test_regression_profile(self):
        assert PROFILES["regression"]["timed"] == 5

    def test_full_profile(self):
        assert PROFILES["full"]["doc_count"] == 20
        assert PROFILES["full"]["timed"] == 10


class TestComputeMetrics:
    def test_all_successful(self):
        timings = [
            {"e2e_ms": 100, "ttft_ms": 20, "success": True,
             "chunk_count": 5, "completion_tokens": 50, "token_rate_tps": 40.0},
            {"e2e_ms": 150, "ttft_ms": 30, "success": True,
             "chunk_count": 6, "completion_tokens": 60, "token_rate_tps": 45.0},
            {"e2e_ms": 200, "ttft_ms": 25, "success": True,
             "chunk_count": 7, "completion_tokens": 70, "token_rate_tps": 50.0},
        ]
        m = _compute_metrics(timings, PROFILES["smoke"])
        assert m["request_count"] == 3
        assert m["success_count"] == 3
        assert m["success_rate"] == 1.0
        assert "e2e_latency_p50_ms" in m
        assert "ttft_p50_ms" in m
        assert "token_rate_median_tps" in m

    def test_with_failures(self):
        timings = [
            {"e2e_ms": 100, "ttft_ms": 20, "success": True,
             "chunk_count": 5, "token_rate_tps": 40.0},
            {"e2e_ms": 5000, "ttft_ms": None, "success": False,
             "chunk_count": 0, "token_rate_tps": None, "error": "timeout"},
        ]
        m = _compute_metrics(timings, PROFILES["smoke"])
        assert m["request_count"] == 2
        assert m["success_count"] == 1
        assert m["error_count"] == 1

    def test_empty(self):
        m = _compute_metrics([], PROFILES["smoke"])
        assert m["request_count"] == 0
        assert m["success_rate"] == 0


class TestFormatConsole:
    def test_with_metrics(self):
        result = {
            "request_count": 5,
            "success_count": 5,
            "success_rate": 1.0,
            "e2e_latency_p50_ms": 150,
            "e2e_latency_p95_ms": 250,
            "e2e_latency_p99_ms": 300,
            "ttft_p50_ms": 30,
            "ttft_p95_ms": 50,
            "ttft_p99_ms": 60,
            "token_rate_median_tps": 42.5,
        }
        output = format_console(result)
        assert "5 requests" in output
        assert "p50=150ms" in output
        assert "42.5 tok/s" in output

    def test_error_result(self):
        output = format_console({"error": "No documents found"})
        assert "error" in output.lower()
