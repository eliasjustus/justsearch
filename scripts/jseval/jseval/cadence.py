"""NRT/commit cadence instrumentation for a run (tempdoc 885 item 19).

Item 19 compares two NRT/commit cadence arms by measurement. The comparison needs three
things a run did not previously record:

* ``reopen_total`` / ``commit_total`` — how often the Worker reopened its NRT reader and how
  often it committed, over the whole run. Both are cumulative Worker counters
  (``IndexRuntimeMetricCatalog``), read at end of run out of the telemetry NDJSON the Worker
  already writes — the same stream :mod:`jseval.metrics_reader` serves.
* ``segments_since_reopen`` — the last observed value of the gauge that says how far the
  reader has drifted behind the writer.
* ``first_search_after_indexing`` — the latency of the FIRST search issued after a batch of
  newly indexed files. That is where reopen-on-demand moves the segment-open cost, so it has
  to be measured SEPARATELY from ordinary search-load latency (which is dominated by steady
  state). Opt-in via ``--first-search-probe``; ``None`` when the probe did not run.

Everything degrades to ``None``: a run against a Worker that does not publish these metrics
(or with no telemetry dir at all) still emits the block, with null values.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from . import metrics_reader, search_load

log = logging.getLogger(__name__)

#: Worker metric names (mirror ``IndexRuntimeMetricCatalog``). Absent at runtime => null.
#: They live in the ``index.runtime.*`` catalog and not in ``worker.*`` because
#: ``index.runtime.commit_count`` was already the all-paths commit counter there (fed from
#: ``RuntimeSession.commitCount``); a second ``worker.index.commit_total`` would have been a
#: fork of it. Note ``worker.commits.total`` is a DIFFERENT quantity — it counts only the
#: IndexingLoop-attributed commits, not the commit timer, gRPC deletes or prune.
REOPEN_TOTAL = "index.runtime.reopen_count"
COMMIT_TOTAL = "index.runtime.commit_count"
SEGMENTS_SINCE_REOPEN = "index.runtime.segments_since_reopen"

#: Cumulative counters (keep the max seen) vs. point-in-time gauges (keep the last seen).
_COUNTERS = (REOPEN_TOTAL, COMMIT_TOTAL)
_GAUGES = (SEGMENTS_SINCE_REOPEN,)

#: Newly indexed files that must accumulate before the probe issues its one search.
#: 50 is roughly one watcher batch on the item-19 corpora — small enough that a run sees
#: several probes, large enough that a trickle of stragglers does not fire one per poll.
DEFAULT_BATCH_MIN_FILES = 50

#: How often the probe re-reads the indexed-document count while waiting for a batch.
PROBE_POLL_SEC = 1.0


@dataclass(frozen=True)
class FirstSearchProbeSpec:
    """Resolved ``--first-search-probe`` configuration."""

    min_new_files: int = DEFAULT_BATCH_MIN_FILES


def resolve_probe_spec(enabled: bool, min_new_files: int) -> FirstSearchProbeSpec | None:
    """Turn the two CLI options into a spec, or ``None`` when the probe was not requested."""
    if not enabled:
        return None
    if min_new_files <= 0:
        raise ValueError(f"--first-search-probe-files must be positive, got {min_new_files}")
    return FirstSearchProbeSpec(min_new_files=min_new_files)


def _numeric(value: Any) -> float | None:
    """Coerce a metric ``value`` to float; ``None`` for missing/malformed (never raises)."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _tidy(value: float | None) -> float | int | None:
    """Report an integral metric as an int so the comparison table reads as counts."""
    if value is None:
        return None
    return int(value) if float(value).is_integer() else value


def collect_worker_metrics(data_dir: Path) -> dict[str, float | None]:
    """Read the cadence metrics out of ``<data_dir>/telemetry/`` at end of run.

    Counters are cumulative, so the max observed value is the end-of-run total; the gauge
    keeps its last observation (``read_merged`` returns records in timestamp order). A
    missing telemetry dir, a missing metric, or a malformed value all yield ``None``.
    """
    values: dict[str, float | None] = {
        name: None for name in (*_COUNTERS, *_GAUGES)
    }
    try:
        records = metrics_reader.read_merged(Path(data_dir))
    except Exception:  # pragma: no cover - reading telemetry must never fail a run
        log.debug("Cadence metrics unavailable under %s", data_dir, exc_info=True)
        return values

    for record in records:
        name = record.get("name")
        if name not in values:
            continue
        parsed = _numeric(record.get("value"))
        if parsed is None:
            continue
        if name in _COUNTERS:
            current = values[name]
            values[name] = parsed if current is None else max(current, parsed)
        else:
            values[name] = parsed
    return values


def summarize_first_search(
    latencies_ms: list[float],
    errors: int,
    min_new_files: int,
    batches_fired: int,
) -> dict:
    """Build the ``first_search_after_indexing`` block.

    Field names mirror :func:`jseval.search_load.summarize` so the two latency populations
    read the same way; ``latency_ms.p95`` is the item-19
    ``first_search_after_indexing_p95_ms`` column.
    """
    ordered = sorted(latencies_ms)
    block: dict = {
        "min_new_files": min_new_files,
        "batches_fired": batches_fired,
        "probes_ok": len(ordered),
        "errors": errors,
        "latency_ms": None,
    }
    if ordered:
        block["latency_ms"] = {
            "p50": round(search_load.percentile(ordered, 50), 3),
            "p95": round(search_load.percentile(ordered, 95), 3),
            "max": round(ordered[-1], 3),
        }
    return block


def build_block(
    metrics: Mapping[str, Any] | None,
    first_search: dict | None = None,
) -> dict:
    """Build the ``summary.json`` ``cadence`` block from a metric-name -> value mapping."""
    source: Mapping[str, Any] = metrics or {}
    return {
        "reopen_total": _tidy(_numeric(source.get(REOPEN_TOTAL))),
        "commit_total": _tidy(_numeric(source.get(COMMIT_TOTAL))),
        "segments_since_reopen": _tidy(_numeric(source.get(SEGMENTS_SINCE_REOPEN))),
        "first_search_after_indexing": first_search,
    }


class BatchTrigger:
    """Fires once per batch of ``min_new_files`` newly indexed documents.

    Stateful on purpose: the first observation only establishes the baseline (a run against
    an index that already holds documents must not fire immediately), and every fire moves
    the baseline forward so the same batch cannot fire twice.
    """

    def __init__(self, min_new_files: int = DEFAULT_BATCH_MIN_FILES) -> None:
        if min_new_files <= 0:
            raise ValueError(f"min_new_files must be positive, got {min_new_files}")
        self._min_new_files = min_new_files
        self._baseline: int | None = None
        self.fired = 0

    def observe(self, indexed_docs: int) -> bool:
        if self._baseline is None or indexed_docs < self._baseline:
            # First observation, or the index shrank (rebuild/reset): re-baseline, don't fire.
            self._baseline = indexed_docs
            return False
        if indexed_docs - self._baseline < self._min_new_files:
            return False
        self._baseline = indexed_docs
        self.fired += 1
        return True


class FirstSearchProbe:
    """Issues ONE search per freshly indexed batch, on a daemon thread.

    Shares :mod:`jseval.search_load`'s client and request shape, so the probe query is
    byte-identical to a search-load query — only its latency is bookkept separately.
    """

    def __init__(
        self,
        base_url: str,
        queries: list[str],
        spec: FirstSearchProbeSpec,
        *,
        poll_sec: float = PROBE_POLL_SEC,
    ) -> None:
        if not queries:
            raise ValueError("first-search probe needs at least one query")
        self._base_url = base_url
        self._queries = queries
        self._spec = spec
        self._poll_sec = poll_sec
        self._trigger = BatchTrigger(spec.min_new_files)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._latencies_ms: list[float] = []
        self._errors = 0

    def start(self) -> None:
        self._thread = threading.Thread(
            target=self._run, name="jseval-first-search-probe", daemon=True,
        )
        self._thread.start()
        log.info(
            "First-search probe started: one query per %d newly indexed docs",
            self._spec.min_new_files,
        )

    def stop(self) -> dict:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=search_load.REQUEST_TIMEOUT_SEC + 5.0)
        block = summarize_first_search(
            self._latencies_ms, self._errors, self._spec.min_new_files, self._trigger.fired,
        )
        log.info(
            "First-search probe stopped: batches=%d ok=%d errors=%d latency=%s",
            block["batches_fired"], block["probes_ok"], block["errors"], block["latency_ms"],
        )
        return block

    # -- internals ---------------------------------------------------------

    def _run(self) -> None:
        # Lazy: `ingest` pulls in the readiness stack, and importing it at module load
        # would make `jseval.cadence` unusable from the (import-light) summary path.
        from . import ingest as ingest_mod

        try:
            with search_load.open_client(self._base_url) as client:
                index = 0
                while not self._stop.is_set():
                    if self._trigger.observe(ingest_mod._get_indexed_doc_count(self._base_url)):
                        query = self._queries[index % len(self._queries)]
                        latency = search_load.issue_search(
                            client, search_load.search_body(query),
                        )
                        if latency is None:
                            self._errors += 1
                        else:
                            self._latencies_ms.append(latency)
                        index += 1
                    self._stop.wait(self._poll_sec)
        except Exception:  # pragma: no cover - the probe must never kill the run
            log.exception("First-search probe thread aborted")
