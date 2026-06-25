"""Tests for bench_concurrency (tempdoc 400 LR5-c)."""

from __future__ import annotations

import json
import threading
import time
from unittest.mock import patch

import pytest

from jseval.bench_concurrency import (
    _percentile,
    _split_queries_round_robin,
    run_benchmark,
    write_benchmark,
)


class TestRoundRobinSplit:
    def test_single_stream_gets_everything(self):
        q = {"q1": "a", "q2": "b", "q3": "c"}
        buckets = _split_queries_round_robin(q, 1)
        assert len(buckets) == 1
        assert buckets[0] == [("q1", "a"), ("q2", "b"), ("q3", "c")]

    def test_round_robin_distribution(self):
        q = {f"q{i}": f"text{i}" for i in range(6)}
        buckets = _split_queries_round_robin(q, 3)
        # stream 0 gets q0, q3
        # stream 1 gets q1, q4
        # stream 2 gets q2, q5
        assert [b[0] for b in buckets[0]] == ["q0", "q3"]
        assert [b[0] for b in buckets[1]] == ["q1", "q4"]
        assert [b[0] for b in buckets[2]] == ["q2", "q5"]

    def test_uneven_split(self):
        q = {"q0": "a", "q1": "b", "q2": "c", "q3": "d", "q4": "e"}
        buckets = _split_queries_round_robin(q, 3)
        # 5 queries / 3 streams: sizes 2,2,1
        sizes = sorted(len(b) for b in buckets)
        assert sizes == [1, 2, 2]


class TestPercentile:
    def test_empty_returns_none(self):
        assert _percentile([], 0.5) is None

    def test_single_value(self):
        assert _percentile([42.0], 0.5) == 42.0

    def test_p50_of_known_series(self):
        # p50 over {1..9} = 5
        assert _percentile(list(range(1, 10)), 0.5) == 5.0

    def test_p99_above_max(self):
        # p99 of {1..10} should be between 9 and 10.
        result = _percentile(list(range(1, 11)), 0.99)
        assert 9.0 <= result <= 10.0


class TestRunBenchmark:
    def _mock_execute_query(self, delay_ms: float = 5.0, fail: bool = False):
        """Return a mock execute_query that simulates per-query latency."""
        def _side_effect(client, qid, body, max_retries, allow_errors):
            time.sleep(delay_ms / 1000.0)
            if fail:
                if allow_errors:
                    return None
                raise RuntimeError(f"simulated failure for {qid}")
            return {
                "tookMs": int(delay_ms),
                "totalHits": 42,
                "results": [],
            }
        return _side_effect

    @patch("jseval.bench_concurrency.execute_query")
    def test_single_stream_sequential(self, mock_exec):
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1)
        queries = {f"q{i}": f"text{i}" for i in range(10)}
        result = run_benchmark(
            queries, concurrency=1, base_url="http://localhost:33221",
        )
        assert result["concurrency"] == 1
        assert result["query_count"] == 10
        assert result["ok"] == 10
        assert result["errors"] == 0
        assert len(result["streams"]) == 1
        assert result["streams"][0]["query_count"] == 10

    @patch("jseval.bench_concurrency.execute_query")
    def test_parallelism_verified_via_concurrent_callers(self, mock_exec):
        """Phase 6 / 6.10: direct parallelism assertion replaces the
        flake-prone timing threshold of the prior test. We count the
        maximum number of in-flight `execute_query` calls via a
        thread-safe counter + the CPython GIL; at concurrency=4 with
        a blocking mock, the counter should peak at 4.
        """
        import threading

        in_flight = 0
        max_in_flight = 0
        lock = threading.Lock()
        release = threading.Event()
        enter_count = threading.Semaphore(0)

        def _blocking(client, qid, body, max_retries, allow_errors):
            nonlocal in_flight, max_in_flight
            with lock:
                in_flight += 1
                max_in_flight = max(max_in_flight, in_flight)
            enter_count.release()
            # Wait until main thread observes enough in-flight calls,
            # then let everyone through.
            release.wait(timeout=5.0)
            with lock:
                in_flight -= 1
            return {"tookMs": 1, "totalHits": 0, "results": []}

        mock_exec.side_effect = _blocking

        def _run():
            return run_benchmark(
                {f"q{i}": f"t{i}" for i in range(8)},
                concurrency=4, base_url="http://localhost:33221",
            )

        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(_run)
            # Wait for all 4 streams to enter the mock concurrently.
            for _ in range(4):
                assert enter_count.acquire(timeout=5.0)
            # Now release — streams complete + benchmark finishes.
            release.set()
            result = fut.result(timeout=10.0)

        assert max_in_flight == 4  # exactly the requested concurrency
        assert result["ok"] == 8

    @patch("jseval.bench_concurrency.execute_query")
    def test_error_counted_when_allow_errors(self, mock_exec):
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1, fail=True)
        queries = {f"q{i}": f"text{i}" for i in range(5)}
        result = run_benchmark(
            queries, concurrency=2, base_url="http://localhost:33221",
            allow_errors=True,
        )
        assert result["errors"] == 5
        assert result["ok"] == 0
        # Errors have dispatch_ms but status=error.
        for sr in result["streams"]:
            for rec in sr["records"]:
                assert rec["status"] == "error"

    @patch("jseval.bench_concurrency.execute_query")
    def test_concurrency_clamped_to_query_count(self, mock_exec):
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1)
        queries = {"q0": "a", "q1": "b"}
        result = run_benchmark(
            queries, concurrency=10, base_url="http://localhost:33221",
        )
        # Only 2 streams actually run because there are 2 queries.
        assert result["concurrency"] == 2

    @patch("jseval.bench_concurrency.execute_query")
    def test_schema_shape(self, mock_exec):
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1)
        queries = {f"q{i}": f"text{i}" for i in range(8)}
        result = run_benchmark(
            queries, concurrency=4, base_url="http://localhost:33221",
        )
        assert result["schema_version"] == 1
        assert "aggregate_latency_ms" in result
        agg = result["aggregate_latency_ms"]
        assert set(agg.keys()) >= {"p50", "p95", "p99", "mean", "max", "count"}
        assert all("stream_id" in s for s in result["streams"])
        assert all(s["query_count"] > 0 for s in result["streams"])
        # Streams sorted by stream_id
        assert [s["stream_id"] for s in result["streams"]] == list(range(4))

    def test_invalid_concurrency_raises(self):
        with pytest.raises(ValueError):
            run_benchmark({"q1": "t"}, concurrency=0,
                          base_url="http://localhost:33221")

    def test_negative_warmup_raises(self):
        with pytest.raises(ValueError):
            run_benchmark({"q1": "t"}, concurrency=1,
                          base_url="http://localhost:33221",
                          warmup=-1)

    @patch("jseval.bench_concurrency.execute_query")
    def test_default_max_connections_matches_concurrency(self, mock_exec):
        """Phase 6 / 6.10: pool size defaults to concurrency (was 2×)."""
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1)
        queries = {f"q{i}": f"t{i}" for i in range(4)}
        result = run_benchmark(queries, concurrency=4,
                                base_url="http://localhost:33221")
        assert result["max_connections"] == 4

    @patch("jseval.bench_concurrency.execute_query")
    def test_explicit_max_connections_override(self, mock_exec):
        """Phase 6 / 6.10: operators can still overprovision."""
        mock_exec.side_effect = self._mock_execute_query(delay_ms=1)
        queries = {f"q{i}": f"t{i}" for i in range(4)}
        result = run_benchmark(
            queries, concurrency=4, base_url="http://localhost:33221",
            max_connections=16,
        )
        assert result["max_connections"] == 16

    @patch("jseval.bench_concurrency.execute_query")
    def test_warmup_runs_but_is_not_counted_in_ok(self, mock_exec):
        """Phase 6 / 6.10: warmup pass issues discarded queries, then
        the timed benchmark processes the full bucket. ok_count reflects
        the timed pass only."""
        call_count = 0

        def _side_effect(client, qid, body, max_retries, allow_errors):
            nonlocal call_count
            call_count += 1
            return {"tookMs": 1, "totalHits": 0, "results": []}
        mock_exec.side_effect = _side_effect

        queries = {f"q{i}": f"t{i}" for i in range(8)}
        result = run_benchmark(
            queries, concurrency=2, base_url="http://localhost:33221",
            warmup=2,
        )
        assert result["warmup"] == 2
        assert result["ok"] == 8  # only the timed pass counts
        # warmup=2 per stream × 2 streams = 4 extra calls; plus 8 timed = 12.
        assert call_count == 12


class TestWriteBenchmark:
    def test_writes_to_concurrency_filename(self, tmp_path):
        result = {
            "concurrency": 3, "query_count": 30, "ok": 30, "errors": 0,
            "qps": 5.0, "schema_version": 1,
            "aggregate_latency_ms": {"p50": 10, "p95": 20, "p99": 30,
                                     "mean": 12, "max": 35, "count": 30},
            "streams": [],
        }
        path = write_benchmark(result, tmp_path)
        assert path == tmp_path / "concurrency-3.json"
        doc = json.loads(path.read_text(encoding="utf-8"))
        assert doc["concurrency"] == 3
