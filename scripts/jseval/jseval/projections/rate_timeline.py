"""Rate-based timeline + stall tagging (tempdoc 400 LR4-d).

Reads ``metrics.ndjson`` (+ ``metrics-worker.ndjson`` when present) from
the run directory, derives per-tick rate deltas for a fixed set of
cumulative counters, and flags ticks whose rate falls more than
``2σ`` below a rolling-mean baseline as stalls. Closes §4.7 (no
visibility into "why did indexing throughput dip").

Counters (§26.3 P-beta spec-tightening, locked 2026-04-22):

- ``worker.documents.indexed.total``
- ``worker.batches.submitted.total``
- ``worker.commits.total``
- ``worker.documents.failed.total``

Method:

- Rate at tick ``i`` = ``(value[i] - value[i-1]) / (ts[i] - ts[i-1])``
  in units/sec; monotonic counters only (negative deltas are dropped —
  usually indicates a reset, not a stall).
- Rolling mean + stdev over the preceding ``window_size=10`` ticks
  (bootstrap: first 10 ticks use the cumulative partial window).
- Stall threshold: ``rate < rolling_mean - 2 * rolling_stdev`` AND
  ``rolling_mean > 0`` (avoid flagging a never-active counter). σ is
  computed with ``ddof=1``; a window of 1 produces no stall calls.
- Near-flat-baseline fallback: when ``rolling_stdev == 0`` (all window
  rates equal), a drop below ``0.5 * rolling_mean`` still flags as a
  stall. Real workloads rarely produce zero stdev, but synthetic
  fixtures and highly-batched systems can — this keeps a drop from
  100 → 5 visible even when the prior window was uniform.

Output schema v1:

.. code-block:: json

    {
      "counters": {
        "worker.documents.indexed.total": {
          "total_ticks": 30,
          "stalls": [
            {"ts": "...", "rate": 0.12, "rolling_mean": 5.1,
             "rolling_stdev": 0.8, "sigma_below": 6.2}
          ],
          "final_value": 10000,
          "duration_s": 120.0,
          "avg_rate": 82.3
        }
      },
      "window_size": 10,
      "stall_sigma_threshold": 2.0
    }
"""

from __future__ import annotations

import json
import logging
import math
import statistics
from datetime import datetime
from pathlib import Path
from typing import Iterable

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "rate_timeline"
SCHEMA_VERSION = 1
DEFAULT_WINDOW_SIZE = 10
DEFAULT_STALL_SIGMA = 2.0
# Phase 6 / 6.3: flat-baseline fallback threshold — when the rolling
# window has zero stdev (all rates equal), a "stall" is any rate
# below this fraction of the rolling mean. Set to 0.5 by default
# because (a) a ≥50% drop is unambiguous degradation in almost any
# pipeline, and (b) smaller drops on exactly-uniform baselines are
# statistically indistinguishable from tick-boundary rounding — 0.5
# keeps the projection's false-positive rate low on synthetic
# fixtures while still flagging genuine stalls. Operators can
# override via the kwarg on produce() or by importing the module
# and rebinding the constant.
DEFAULT_FLAT_BASELINE_DROP_THRESHOLD = 0.5

TRACKED_COUNTERS: tuple[str, ...] = (
    "worker.documents.indexed.total",
    "worker.batches.submitted.total",
    "worker.commits.total",
    "worker.documents.failed.total",
)


def _parse_ts(raw) -> float | None:
    """Return POSIX seconds for an ISO-8601 ``t`` string (or None)."""
    if not isinstance(raw, str):
        return None
    try:
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        return datetime.fromisoformat(raw).timestamp()
    except ValueError:
        return None


def _iter_metric_records(paths: Iterable[Path]):
    """Yield {name, value, ts_posix, ts_raw} records from metrics NDJSON files.

    Each NDJSON line is expected to be a metric-export record. The jseval
    telemetry stack emits either point-style records (``{"name": ...,
    "value": ..., "t": "ISO-8601"}``) or counter-delta records. This
    reader tolerates both shapes and skips records without a numeric
    value.
    """
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
                        record = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    name = record.get("name")
                    value = record.get("value")
                    ts_raw = record.get("t")
                    if not isinstance(name, str):
                        continue
                    if value is None:
                        continue
                    try:
                        value_f = float(value)
                    except (TypeError, ValueError):
                        continue
                    yield {
                        "name": name,
                        "value": value_f,
                        "ts_posix": _parse_ts(ts_raw),
                        "ts_raw": ts_raw,
                    }
        except OSError:
            continue


def _group_by_counter(
    records, *, tracked: tuple[str, ...] | None = None,
) -> dict[str, list[dict]]:
    """Bucket records by counter name, keeping only the tracked counters.

    Phase 6 / 6.3: ``tracked`` override lets produce() swap in a
    different counter set without editing the module constant.
    """
    names = tracked if tracked is not None else TRACKED_COUNTERS
    grouped: dict[str, list[dict]] = {name: [] for name in names}
    for rec in records:
        if rec["name"] in grouped:
            grouped[rec["name"]].append(rec)
    # Sort each counter by timestamp (unparseable ts → POSIX 0, stable order).
    for name in grouped:
        grouped[name].sort(key=lambda r: r["ts_posix"] or 0.0)
    return grouped


def _detect_stalls(
    ticks: list[dict],
    *,
    window_size: int = DEFAULT_WINDOW_SIZE,
    sigma_threshold: float = DEFAULT_STALL_SIGMA,
    flat_baseline_drop_threshold: float = DEFAULT_FLAT_BASELINE_DROP_THRESHOLD,
) -> tuple[list[dict], float, float, float]:
    """Walk monotonic deltas; emit stall records and summary metrics.

    Returns ``(stall_list, final_value, duration_s, avg_rate)``.

    Phase 6 / 6.3: ``flat_baseline_drop_threshold`` is now explicit.
    When the rolling window is perfectly flat (stdev=0), a stall is
    flagged if ``rate < threshold * rolling_mean``. Tune down toward
    0.0 (more false negatives) or up toward 1.0 (more false positives,
    any drop flags) as the pipeline warrants. See module docstring
    for the 0.5 default rationale.
    """
    if len(ticks) < 2:
        final = ticks[0]["value"] if ticks else 0.0
        return [], final, 0.0, 0.0

    rates: list[tuple[float, dict]] = []
    for prev, curr in zip(ticks, ticks[1:]):
        if prev["ts_posix"] is None or curr["ts_posix"] is None:
            continue
        dt = curr["ts_posix"] - prev["ts_posix"]
        if dt <= 0:
            continue
        delta = curr["value"] - prev["value"]
        # Monotonic counters: negative deltas indicate reset, not stall.
        if delta < 0:
            continue
        rates.append((delta / dt, curr))

    stalls: list[dict] = []
    for i, (rate, tick) in enumerate(rates):
        if i < 2:
            # Need at least 2 prior rates to compute stdev; bootstrap
            # tolerates partial windows below window_size.
            continue
        window = [r for r, _ in rates[max(0, i - window_size):i]]
        if not window:
            continue
        rolling_mean = statistics.mean(window)
        if rolling_mean <= 0:
            continue
        try:
            rolling_stdev = statistics.stdev(window)
        except statistics.StatisticsError:
            rolling_stdev = 0.0
        if rolling_stdev > 0:
            threshold = rolling_mean - sigma_threshold * rolling_stdev
        else:
            # Near-flat baseline — fall back to the configured relative
            # drop threshold. Synthetic fixtures and highly-batched
            # systems can produce perfectly uniform rates; we still want
            # stall visibility without a σ-divide.
            threshold = flat_baseline_drop_threshold * rolling_mean
        if rate < threshold:
            # sigma_below=None when stdev=0 — the stall was flagged via
            # the relative-drop fallback, not a σ comparison. JSON-safe.
            sigma_below: float | None = (
                (rolling_mean - rate) / rolling_stdev
                if rolling_stdev > 0
                else None
            )
            stalls.append({
                "ts": tick.get("ts_raw"),
                "rate": rate,
                "rolling_mean": rolling_mean,
                "rolling_stdev": rolling_stdev,
                "sigma_below": sigma_below,
            })

    total_dt = 0.0
    if len(ticks) >= 2 and ticks[0]["ts_posix"] and ticks[-1]["ts_posix"]:
        total_dt = max(0.0, ticks[-1]["ts_posix"] - ticks[0]["ts_posix"])
    total_delta = max(0.0, ticks[-1]["value"] - ticks[0]["value"])
    avg_rate = (total_delta / total_dt) if total_dt > 0 else 0.0
    return stalls, ticks[-1]["value"], total_dt, avg_rate


def produce(
    run_dir: Path,
    *,
    window_size: int = DEFAULT_WINDOW_SIZE,
    sigma_threshold: float = DEFAULT_STALL_SIGMA,
    flat_baseline_drop_threshold: float = DEFAULT_FLAT_BASELINE_DROP_THRESHOLD,
    counters: tuple[str, ...] | list[str] | None = None,
) -> dict:
    """Compute rate + stall summary for every tracked counter.

    Phase 6 / 6.3: ``counters`` and ``flat_baseline_drop_threshold``
    are now explicit. Pass ``counters=("my.custom.total", ...)`` to
    override the module-level ``TRACKED_COUNTERS`` default.

    Empty input → ``counters`` is populated with zeroed entries for
    each tracked counter so consumers can rely on a stable shape.
    """
    metric_paths = [
        run_dir / "metrics.ndjson",
        run_dir / "metrics-worker.ndjson",
    ]
    tracked = tuple(counters) if counters is not None else TRACKED_COUNTERS
    grouped = _group_by_counter(
        _iter_metric_records(metric_paths), tracked=tracked,
    )
    counters_block: dict[str, dict] = {}
    for name in tracked:
        ticks = grouped.get(name) or []
        stalls, final, duration, avg_rate = _detect_stalls(
            ticks,
            window_size=window_size,
            sigma_threshold=sigma_threshold,
            flat_baseline_drop_threshold=flat_baseline_drop_threshold,
        )
        counters_block[name] = {
            "total_ticks": len(ticks),
            "stalls": stalls,
            "stall_count": len(stalls),
            "final_value": final,
            "duration_s": duration,
            "avg_rate": avg_rate,
        }
    return {
        "counters": counters_block,
        "window_size": window_size,
        "stall_sigma_threshold": sigma_threshold,
        "flat_baseline_drop_threshold": flat_baseline_drop_threshold,
        "tracked_counters": list(tracked),
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Rate-based timeline + stall tagging for worker counters (LR4-d).",
    produce=produce,
)
