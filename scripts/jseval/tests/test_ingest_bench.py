"""Tests for ingest_bench.py — ingest throughput benchmark."""

from __future__ import annotations

from unittest.mock import patch

from jseval.ingest_bench import execute_ingest_bench, format_console


class TestExecuteIngestBench:
    @patch("jseval.ingest_bench.ingest.ingest_and_wait")
    def test_single_run(self, mock_ingest, tmp_path):
        mock_ingest.return_value = {
            "readiness_passed": True,
            "docs_indexed": 100,
            "elapsed_sec": 5.0,
            "docs_per_sec": 20.0,
        }
        result = execute_ingest_bench("http://localhost:8080", tmp_path, runs=1)
        assert result["total_runs"] == 1
        assert result["successful_runs"] == 1
        assert result["statistics"]["docs_per_sec"]["median"] == 20.0

    @patch("jseval.ingest_bench.ingest.ingest_and_wait")
    def test_multiple_runs(self, mock_ingest, tmp_path):
        mock_ingest.side_effect = [
            {"readiness_passed": True, "docs_indexed": 100,
             "elapsed_sec": 5.0, "docs_per_sec": 20.0},
            {"readiness_passed": True, "docs_indexed": 100,
             "elapsed_sec": 4.0, "docs_per_sec": 25.0},
            {"readiness_passed": True, "docs_indexed": 100,
             "elapsed_sec": 4.5, "docs_per_sec": 22.0},
        ]
        result = execute_ingest_bench("http://localhost:8080", tmp_path, runs=3)
        assert result["total_runs"] == 3
        assert result["successful_runs"] == 3
        assert result["statistics"]["docs_per_sec"]["median"] == 22.0

    @patch("jseval.ingest_bench.ingest.ingest_and_wait")
    def test_failed_run(self, mock_ingest, tmp_path):
        mock_ingest.return_value = {
            "readiness_passed": False,
            "failure_reasons": ["timeout"],
            "docs_indexed": 0,
            "elapsed_sec": 60.0,
            "docs_per_sec": 0,
        }
        result = execute_ingest_bench("http://localhost:8080", tmp_path, runs=1)
        assert result["successful_runs"] == 0


class TestFormatConsole:
    def test_output(self):
        result = {
            "successful_runs": 3, "total_runs": 3,
            "statistics": {
                "docs_per_sec": {"median": 22.0, "min": 20.0, "max": 25.0},
                "elapsed_sec": {"median": 4.5, "min": 4.0, "max": 5.0},
            },
        }
        output = format_console(result)
        assert "3/3" in output
        assert "22.0" in output
