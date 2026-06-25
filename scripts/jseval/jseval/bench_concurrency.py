"""Concurrent-query benchmark runner (tempdoc 400 LR5-c).

Given a dict of ``{query_id: query_text}``, spawns ``N`` concurrent
query streams against ``/api/knowledge/search`` and records per-stream
latency timelines so operators can see how the Worker search pipeline
degrades under load.

Per §8.5 LR5-c the expected output is
``<run_dir>/concurrency-<N>.json`` with aggregate latency stats and
per-stream query records. Queue-depth sampling is deferred to
LR3-a; the metrics/traces files mirrored by
:func:`jseval.artifacts.write_run` already capture ``lease.acquire``
spans (Phase-2 LR2-b) which operators can join against this
projection to reconstruct the lease-queue behaviour under load.

Design:

- Streams share a single ``httpx.Client`` with tuned connection
  pooling so the concurrency is genuine (one TCP conn per stream,
  not one per request).
- Query distribution: round-robin (stream ``i`` handles queries
  ``i, i+N, i+2N, …``). Even distribution + deterministic mapping
  makes the benchmark reproducible.
- Timing: per-query ``dispatch_ms`` (perf_counter) and wall-clock
  ``start_ts`` / ``end_ts`` captured on each stream. Aggregate p50 /
  p95 / p99 computed across all queries, and per-stream p50 / max
  for hot-spot visibility.
- Error handling: mirrors :func:`jseval.retriever.execute_query` —
  retries + optional swallow; a failed query contributes a ``null``
  latency row rather than aborting the stream.
"""

from __future__ import annotations

import json
import logging
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import httpx

from .retriever import _build_request, execute_query

log = logging.getLogger(__name__)

SCHEMA_VERSION = 1


def _split_queries_round_robin(
    queries: dict[str, str], n_streams: int,
) -> list[list[tuple[str, str]]]:
    """Return ``n_streams`` disjoint, evenly-sized query chunks.

    Round-robin keeps the load balanced even when per-query latency
    is skewed (e.g. short queries cluster at low qids): stream 0 sees
    qids 1, N+1, 2N+1, …; stream 1 sees 2, N+2, 2N+2, …
    """
    ordered = list(queries.items())
    buckets: list[list[tuple[str, str]]] = [[] for _ in range(n_streams)]
    for idx, item in enumerate(ordered):
        buckets[idx % n_streams].append(item)
    return buckets


def _percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    s = sorted(values)
    k = (len(s) - 1) * pct
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def _stream_worker(
    stream_id: int,
    assigned: list[tuple[str, str]],
    client: httpx.Client,
    request_builder,
    max_retries: int,
    allow_errors: bool,
) -> dict:
    """Drive one concurrent query stream. Returns the stream record."""
    records: list[dict] = []
    ok = 0
    errors = 0
    stream_start_ts = datetime.now(timezone.utc).isoformat()
    stream_t0 = time.perf_counter()
    for qid, qtext in assigned:
        body = request_builder(qtext)
        q_t0 = time.perf_counter()
        response = execute_query(client, qid, body, max_retries, allow_errors)
        q_t1 = time.perf_counter()
        dispatch_ms = (q_t1 - q_t0) * 1000.0
        if response is None:
            errors += 1
            records.append({
                "qid": qid, "stream_id": stream_id,
                "dispatch_ms": dispatch_ms, "status": "error",
            })
        else:
            ok += 1
            records.append({
                "qid": qid, "stream_id": stream_id,
                "dispatch_ms": dispatch_ms, "status": "ok",
                "took_ms": response.get("tookMs"),
                "total_hits": response.get("totalHits"),
            })
    stream_wall_ms = (time.perf_counter() - stream_t0) * 1000.0
    stream_end_ts = datetime.now(timezone.utc).isoformat()
    successful_latencies = [r["dispatch_ms"] for r in records
                            if r["status"] == "ok"]
    return {
        "stream_id": stream_id,
        "start_ts": stream_start_ts,
        "end_ts": stream_end_ts,
        "wall_ms": stream_wall_ms,
        "query_count": len(assigned),
        "ok": ok,
        "errors": errors,
        "p50_ms": _percentile(successful_latencies, 0.50),
        "max_ms": max(successful_latencies) if successful_latencies else None,
        "records": records,
    }


def run_benchmark(
    queries: dict[str, str],
    *,
    concurrency: int,
    base_url: str,
    mode: str = "hybrid",
    top_k: int = 10,
    pipeline: dict | None = None,
    max_retries: int = 5,
    allow_errors: bool = False,
    timeout: float = 90.0,
    max_connections: int | None = None,
    warmup: int = 0,
) -> dict:
    """Run the concurrent benchmark; return the LR5-c schema-v1 doc.

    Phase 6 / 6.10 changes:

    - **Default pool size = concurrency (was 2× concurrency).** The
      prior default could exceed backend socket limits at high N;
      1:1 keeps the benchmark's concurrency promise without silent
      backend-side throttling. Override via ``max_connections`` when
      the operator genuinely wants overprovisioning.
    - **Warmup queries.** When ``warmup > 0``, each stream issues
      ``warmup`` DISCARDED queries before the timed measurement begins.
      Amortizes httpx connection establishment + backend JIT warmup
      so p95/p99 aren't cold-start-inflated.
    """
    if concurrency < 1:
        raise ValueError("concurrency must be >= 1")
    if warmup < 0:
        raise ValueError("warmup must be >= 0")
    if concurrency > len(queries):
        log.warning(
            "concurrency=%d > query count %d; downscaling",
            concurrency, len(queries),
        )
        concurrency = max(1, len(queries))

    buckets = _split_queries_round_robin(queries, concurrency)

    def _builder(qtext):
        return _build_request(qtext, mode, top_k, debug=False,
                              pipeline=pipeline)

    effective_connections = (
        max_connections if max_connections is not None else concurrency
    )
    limits = httpx.Limits(
        max_connections=effective_connections,
        max_keepalive_connections=effective_connections,
    )

    # Phase 6 / 6.10: warmup pass — N discarded queries per stream.
    # Uses the first `warmup` queries from each bucket (or cycles if
    # the bucket is smaller than warmup). Timed benchmark still
    # processes the FULL bucket afterwards.
    if warmup > 0:
        with httpx.Client(
            base_url=base_url, timeout=timeout, limits=limits,
        ) as warmup_client:
            def _warmup_stream(stream_id, assigned_bucket):
                for i in range(warmup):
                    if not assigned_bucket:
                        break
                    qid, qtext = assigned_bucket[i % len(assigned_bucket)]
                    try:
                        execute_query(
                            warmup_client, f"warmup-{qid}",
                            _builder(qtext), max_retries=1, allow_errors=True,
                        )
                    except Exception:  # noqa: BLE001 - warmup errors ignored
                        pass
            with ThreadPoolExecutor(max_workers=concurrency,
                                    thread_name_prefix="warmup") as pool:
                for i, bucket in enumerate(buckets):
                    pool.submit(_warmup_stream, i, bucket)

    t0 = time.perf_counter()
    wall_start = datetime.now(timezone.utc).isoformat()
    with httpx.Client(base_url=base_url, timeout=timeout, limits=limits) as client:
        with ThreadPoolExecutor(max_workers=concurrency,
                                thread_name_prefix="bench") as pool:
            futures = {
                pool.submit(
                    _stream_worker,
                    i, bucket, client, _builder,
                    max_retries, allow_errors,
                ): i
                for i, bucket in enumerate(buckets)
            }
            stream_results: list[dict] = []
            for fut in as_completed(futures):
                stream_results.append(fut.result())
    wall_ms = (time.perf_counter() - t0) * 1000.0
    wall_end = datetime.now(timezone.utc).isoformat()

    # Aggregate across all queries (not per-stream).
    all_successful = [
        rec["dispatch_ms"]
        for sr in stream_results
        for rec in sr["records"]
        if rec["status"] == "ok"
    ]
    total_queries = sum(sr["query_count"] for sr in stream_results)
    total_ok = sum(sr["ok"] for sr in stream_results)
    total_errors = sum(sr["errors"] for sr in stream_results)
    qps = (total_ok / (wall_ms / 1000.0)) if wall_ms > 0 else 0.0

    stream_results.sort(key=lambda sr: sr["stream_id"])
    return {
        "schema_version": SCHEMA_VERSION,
        "concurrency": concurrency,
        "max_connections": effective_connections,
        "warmup": warmup,
        "start_ts": wall_start,
        "end_ts": wall_end,
        "wall_ms": wall_ms,
        "query_count": total_queries,
        "ok": total_ok,
        "errors": total_errors,
        "qps": qps,
        "aggregate_latency_ms": {
            "count": len(all_successful),
            "p50": _percentile(all_successful, 0.50),
            "p95": _percentile(all_successful, 0.95),
            "p99": _percentile(all_successful, 0.99),
            "mean": statistics.mean(all_successful) if all_successful else None,
            "max": max(all_successful) if all_successful else None,
        },
        "streams": stream_results,
    }


def write_benchmark(result: dict, run_dir: Path) -> Path:
    """Write the benchmark result as
    ``<run_dir>/concurrency-<N>.json``.
    """
    concurrency = int(result.get("concurrency") or 0)
    path = run_dir / f"concurrency-{concurrency}.json"
    path.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path
