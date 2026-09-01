"""Background search load driven against the Head during ingestion (tempdoc 885).

Lane C's throughput baseline needs the indexing pipeline measured *while foreground search
traffic is present*, because `POST /api/knowledge/search` is the request that writes the
Worker's MMF activity slot (`KnowledgeSearchController.java:304` ->
`KnowledgeServerBootstrap.signalUserActivity()` -> `WorkerSpawner.signalUserActivity()`),
and that slot is what makes `IndexingLoop` breath-hold (`IndexingLoop.java:604-610`).

Two modes:

* ``qpm`` — N queries per minute, evenly spaced (query ``i`` is due at ``start + i * 60/N``).
* ``continuous`` — back-to-back, one request in flight at a time.

The loop runs on a daemon thread for the duration of ingest + readiness/pipeline wait and is
purely additive: it never touches the eval metrics, and with no option passed no thread is
started and no ``search_load`` block is written.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import httpx

log = logging.getLogger(__name__)

#: Per-request timeout. A slower response is counted as an error rather than retried —
#: retrying would distort the offered load the measurement is about.
REQUEST_TIMEOUT_SEC = 30.0

#: Poll granularity while waiting for the next scheduled query, so ``stop()`` stays responsive.
_STOP_POLL_SEC = 0.1


@dataclass(frozen=True)
class SearchLoadSpec:
    """Resolved search-load configuration. ``qpm`` is ``None`` in continuous mode."""

    mode: str
    qpm: int | None = None
    top_k: int = 10
    search_mode: str = "hybrid"


def resolve_spec(qpm: int | None, continuous: bool) -> SearchLoadSpec | None:
    """Turn the two CLI options into a spec (or ``None`` when neither was passed).

    Raises ``ValueError`` when both are given or when ``qpm`` is not positive.
    """
    if qpm is not None and continuous:
        raise ValueError("--search-load-qpm and --search-load continuous are mutually exclusive")
    if continuous:
        return SearchLoadSpec(mode="continuous")
    if qpm is None:
        return None
    if qpm <= 0:
        raise ValueError(f"--search-load-qpm must be positive, got {qpm}")
    return SearchLoadSpec(mode="qpm", qpm=qpm)


def interval_sec(qpm: int) -> float:
    """Even spacing between two queries at ``qpm`` queries per minute."""
    if qpm <= 0:
        raise ValueError(f"qpm must be positive, got {qpm}")
    return 60.0 / qpm


def due_at(start: float, index: int, qpm: int) -> float:
    """Monotonic deadline for the ``index``-th query (0-based) of a ``qpm`` schedule."""
    if index < 0:
        raise ValueError(f"index must be non-negative, got {index}")
    return start + index * interval_sec(qpm)


def wait_sec(now: float, start: float, index: int, qpm: int) -> float:
    """Seconds to wait before issuing query ``index``; 0 when already due or behind."""
    return max(0.0, due_at(start, index, qpm) - now)


def percentile(sorted_values: list[float], pct: float) -> float:
    """Nearest-rank percentile over an already-sorted list (``pct`` in 0..100)."""
    if not sorted_values:
        raise ValueError("percentile of an empty sample")
    if not 0 < pct <= 100:
        raise ValueError(f"pct must be in (0, 100], got {pct}")
    rank = -(-len(sorted_values) * pct // 100)  # ceil without importing math
    return sorted_values[int(rank) - 1]


def summarize(
    spec: SearchLoadSpec,
    latencies_ms: list[float],
    errors: int,
    started_at: str,
    ended_at: str,
    duration_s: float,
    query_pool_size: int,
) -> dict:
    """Build the ``summary.json`` ``search_load`` block."""
    ordered = sorted(latencies_ms)
    block: dict = {
        "mode": spec.mode,
        "qpm": spec.qpm,
        "search_mode": spec.search_mode,
        "queries_issued": len(latencies_ms) + errors,
        "queries_ok": len(latencies_ms),
        "errors": errors,
        "query_pool_size": query_pool_size,
        "started_at": started_at,
        "ended_at": ended_at,
        "duration_s": round(duration_s, 3),
        "latency_ms": None,
    }
    if ordered:
        block["latency_ms"] = {
            "p50": round(percentile(ordered, 50), 3),
            "p95": round(percentile(ordered, 95), 3),
            "max": round(ordered[-1], 3),
        }
    return block


class SearchLoadRunner:
    """Runs the query loop on a daemon thread between :meth:`start` and :meth:`stop`."""

    def __init__(self, base_url: str, queries: list[str], spec: SearchLoadSpec) -> None:
        if not queries:
            raise ValueError("search load needs at least one query")
        self._base_url = base_url
        self._queries = queries
        self._spec = spec
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._latencies_ms: list[float] = []
        self._errors = 0
        self._started_at = ""
        self._ended_at = ""
        self._wall_start = 0.0
        self._wall_end = 0.0

    def start(self) -> None:
        self._started_at = datetime.now(timezone.utc).isoformat()
        self._wall_start = time.monotonic()
        self._thread = threading.Thread(
            target=self._run, name="jseval-search-load", daemon=True,
        )
        self._thread.start()
        log.info(
            "Search load started: mode=%s qpm=%s pool=%d",
            self._spec.mode, self._spec.qpm, len(self._queries),
        )

    def stop(self) -> dict:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=REQUEST_TIMEOUT_SEC + 5.0)
        self._wall_end = time.monotonic()
        self._ended_at = datetime.now(timezone.utc).isoformat()
        block = summarize(
            self._spec,
            self._latencies_ms,
            self._errors,
            self._started_at,
            self._ended_at,
            self._wall_end - self._wall_start,
            len(self._queries),
        )
        log.info(
            "Search load stopped: issued=%d errors=%d latency=%s",
            block["queries_issued"], block["errors"], block["latency_ms"],
        )
        return block

    # -- internals ---------------------------------------------------------

    def _run(self) -> None:
        body_template = {"limit": self._spec.top_k, "mode": self._spec.search_mode}
        try:
            with httpx.Client(base_url=self._base_url, timeout=REQUEST_TIMEOUT_SEC) as client:
                index = 0
                while not self._stop.is_set():
                    if self._spec.qpm is not None and not self._wait_for_slot(index):
                        return
                    query = self._queries[index % len(self._queries)]
                    self._issue(client, {**body_template, "query": query})
                    index += 1
        except Exception:  # pragma: no cover - the loop must never kill the run
            log.exception("Search load thread aborted")

    def _wait_for_slot(self, index: int) -> bool:
        """Sleep until query ``index`` is due. Returns False if stopped while waiting."""
        assert self._spec.qpm is not None
        while not self._stop.is_set():
            remaining = wait_sec(time.monotonic(), self._wall_start, index, self._spec.qpm)
            if remaining <= 0:
                return True
            self._stop.wait(min(remaining, _STOP_POLL_SEC))
        return False

    def _issue(self, client: httpx.Client, body: dict) -> None:
        t0 = time.monotonic()
        try:
            resp = client.post("/api/knowledge/search", json=body)
            resp.raise_for_status()
            self._latencies_ms.append((time.monotonic() - t0) * 1000.0)
        except Exception as e:
            self._errors += 1
            log.debug("Search-load query failed: %s", e)
