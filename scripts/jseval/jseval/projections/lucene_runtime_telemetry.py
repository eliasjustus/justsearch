"""Tempdoc 406 substrate observability projection.

Aggregates ``index.runtime.*`` metrics from ``metrics-worker.ndjson`` into
per-metric summaries:

- **Counters** (``index.runtime.{hard_delete_total, soft_delete_total,
  backpressure_total, drain_timeout_total, swap_started_total,
  validation_failure_total}``): final cumulative value per (name, tag-tuple).
- **Histograms** (``index.runtime.{commit_ms, swap_duration_ms,
  write_barrier_wait_us}``): merged percentile estimates across emitted
  samples (uses the last record's percentiles per (name, tag-tuple) as a
  representative — emitter is OTel periodic, so the last batch carries the
  full window).

Used by the "did the 406 substrate behave as designed" validation pipeline:
- Lock contention (write_barrier_wait_us percentiles) — should be < 100µs
  uncontended; spikes only during swaps.
- Drain mechanics (swap_started_total / swap_duration_ms / drain_timeout_total)
  — should produce non-zero signals when an admin reload is triggered.
- Commit stability (commit_ms p99) — should not regress vs baseline.

Output schema v1:

.. code-block:: json

    {
      "counters": {
        "index.runtime.swap_started_total": [
          {"tags": {"reason": "admin_triggered"}, "final_value": 1}
        ],
        ...
      },
      "histograms": {
        "index.runtime.commit_ms": [
          {"tags": {"reason": "drain"}, "p50": 12, "p95": 45, "p99": 120,
           "samples": 17}
        ],
        ...
      },
      "summary": {
        "swap_count_total": 1,
        "drain_timeout_total": 0,
        "commit_ms_p99_max": 120,
        "write_barrier_wait_us_p95_max": 8
      }
    }
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from .base import Projection, register

log = logging.getLogger(__name__)

PROJECTION_NAME = "lucene_runtime_telemetry"
SCHEMA_VERSION = 1

LUCENE_RUNTIME_PREFIX = "index.runtime."

# Metric names categorised by emit-shape.
COUNTER_METRICS = (
    "index.runtime.hard_delete_total",
    "index.runtime.soft_delete_total",
    "index.runtime.backpressure_total",
    "index.runtime.drain_timeout_total",
    "index.runtime.swap_started_total",
    "index.runtime.validation_failure_total",
)

HISTOGRAM_METRICS = (
    "index.runtime.commit_ms",
    "index.runtime.swap_duration_ms",
    "index.runtime.write_barrier_wait_us",
)


def _iter_metric_records(paths):
    """Yield raw metric records from NDJSON files, tolerating missing files."""
    for path in paths:
        if not path.is_file():
            continue
        try:
            with path.open("r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        continue
        except OSError as exc:
            log.warning("failed to read %s: %s", path, exc)


def _tag_key(tags):
    """Stable hashable tag key (sorted tuple of items)."""
    if not isinstance(tags, dict):
        return ()
    return tuple(sorted((str(k), str(v)) for k, v in tags.items()))


def produce(run_dir: Path) -> dict:
    """Compute Lucene runtime summary for a run directory.

    Reads ``<run_dir>/telemetry/metrics-worker.ndjson`` (preferred) plus
    ``<run_dir>/metrics-worker.ndjson`` (legacy fallback) and the Head's
    ``metrics.ndjson`` (no Lucene metrics expected there, but harmless).
    """
    metric_paths = [
        run_dir / "telemetry" / "metrics-worker.ndjson",
        run_dir / "metrics-worker.ndjson",
        run_dir / "telemetry" / "metrics.ndjson",
        run_dir / "metrics.ndjson",
    ]

    counters_acc: dict[str, dict[tuple, dict]] = {}
    histograms_acc: dict[str, dict[tuple, dict]] = {}

    for record in _iter_metric_records(metric_paths):
        name = record.get("name")
        if not isinstance(name, str) or not name.startswith(LUCENE_RUNTIME_PREFIX):
            continue
        rtype = record.get("type")
        tags = record.get("tags") or {}
        tag_key = _tag_key(tags)

        if rtype == "counter":
            value = record.get("value")
            try:
                value_f = float(value) if value is not None else None
            except (TypeError, ValueError):
                continue
            if value_f is None:
                continue
            entry = counters_acc.setdefault(name, {}).setdefault(
                tag_key, {"tags": dict(tags), "final_value": 0.0, "samples": 0}
            )
            # Counters are cumulative — keep the max seen value.
            if value_f > entry["final_value"]:
                entry["final_value"] = value_f
            entry["samples"] += 1
        elif rtype == "histogram":
            entry = histograms_acc.setdefault(name, {}).setdefault(
                tag_key,
                {"tags": dict(tags), "p50": None, "p95": None, "p99": None, "samples": 0},
            )
            # Use the last record's percentiles (OTel periodic export rolls
            # over each interval; the last record has the freshest window).
            for pkey in ("p50", "p95", "p99"):
                v = record.get(pkey)
                if v is not None:
                    try:
                        entry[pkey] = float(v)
                    except (TypeError, ValueError):
                        pass
            # Track sample count from buckets if present (sum of bucket counts).
            buckets = record.get("buckets")
            if isinstance(buckets, list):
                try:
                    entry["samples"] += sum(int(b) for b in buckets)
                except (TypeError, ValueError):
                    pass

    # Format output for stable shape — every known metric appears even if empty.
    counters_out: dict[str, list] = {}
    for name in COUNTER_METRICS:
        per_tag = counters_acc.get(name, {})
        counters_out[name] = list(per_tag.values()) if per_tag else []
    histograms_out: dict[str, list] = {}
    for name in HISTOGRAM_METRICS:
        per_tag = histograms_acc.get(name, {})
        histograms_out[name] = list(per_tag.values()) if per_tag else []

    # Top-level convenience signals for compare_runs / dashboard summary.
    swap_count = sum(int(e["final_value"]) for e in counters_out["index.runtime.swap_started_total"])
    drain_timeout = sum(int(e["final_value"]) for e in counters_out["index.runtime.drain_timeout_total"])
    commit_p99_max = max(
        (e["p99"] for e in histograms_out["index.runtime.commit_ms"] if e.get("p99") is not None),
        default=0.0,
    )
    write_barrier_p95_max = max(
        (e["p95"] for e in histograms_out["index.runtime.write_barrier_wait_us"] if e.get("p95") is not None),
        default=0.0,
    )

    return {
        "counters": counters_out,
        "histograms": histograms_out,
        "summary": {
            "swap_count_total": swap_count,
            "drain_timeout_total": drain_timeout,
            "commit_ms_p99_max": commit_p99_max,
            "write_barrier_wait_us_p95_max": write_barrier_p95_max,
        },
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Tempdoc 406 substrate observability — drain / swap / lock / commit metrics.",
    produce=produce,
)
register(PROJECTION)
