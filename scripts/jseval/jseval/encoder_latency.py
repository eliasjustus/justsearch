"""Per-encoder ONNX-call latency for the run summary (tempdoc 930 §18.1 row 7).

Replaces the retired ``encoder_drift`` PSI projection. That projection compared this
run's ``encoder.ort_run`` span durations against a per-cohort baseline of >= 3 warm
runs — and no such baseline ever existed on any machine, so the projection could only
ever report ``no-baseline``. The risk it targeted (a silently slower ONNX path, e.g. a
CPU fallback) is real, so the signal survives in the cheapest form that needs no
calibration: a fixed, absolute p50/p95 per encoder written into every run summary.

No threshold, no gate, no baseline — informational. A reader compares two runs' numbers
directly, which is what the PSI score was being converted back into anyway.

Input source is unchanged from the projection: ``encoder.ort_run`` spans in the
telemetry NDJSON, keyed by the ``encoder.name`` attribute. The reader points at
``<data_dir>/telemetry/`` rather than a run dir because the summary is composed BEFORE
:func:`jseval.artifacts.write_run` mirrors telemetry into the run dir; rotated siblings
are picked up via the same :func:`jseval.artifacts.collect_rotated_siblings` the mirror
uses, so a run that crossed the rotation threshold is not silently truncated.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Iterable, Iterator

log = logging.getLogger(__name__)

TRACES_FILENAME = "traces.ndjson"
ORT_RUN_SPAN_NAME = "encoder.ort_run"
ENCODER_NAME_ATTR = "encoder.name"


def _iter_spans(traces_path: Path) -> Iterator[dict]:
    if not traces_path.is_file():
        return
    try:
        with traces_path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    continue
    except OSError:
        return


def _parse_iso_ms(value: object) -> float | None:
    """Parse an ISO-8601 timestamp emitted by ``NdjsonSpanExporter`` into epoch ms.

    Returns ``None`` on any parse failure so the caller can drop the span without
    raising. Format produced by the exporter: ``2026-04-22T11:54:10.046Z``
    (``.046+00:00`` is also accepted).
    """
    if not isinstance(value, str):
        return None
    s = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        return datetime.fromisoformat(s).timestamp() * 1000.0
    except ValueError:
        return None


def span_duration_ms(span: dict) -> float | None:
    """Return a span's duration in milliseconds.

    Prefers the structural ``duration_ms`` field emitted by ``NdjsonSpanExporter``;
    falls back to parsing ``start`` + ``end`` ISO timestamps for traces written before
    that field landed (tempdoc 400 §23.8 D-1). Fallback precision is millisecond, which
    is lossy for sub-ms calls — the p50/p95 of such a run reads as an integer floor.
    """
    raw = span.get("duration_ms")
    if raw is not None:
        try:
            return float(raw)
        except (TypeError, ValueError):
            pass
    start_ms = _parse_iso_ms(span.get("start"))
    end_ms = _parse_iso_ms(span.get("end"))
    if start_ms is None or end_ms is None:
        return None
    # ms-precision source => duration can round negative by < 1ms on same-ms
    # start/end pairs; clamp to non-negative.
    return max(0.0, end_ms - start_ms)


def extract_encoder_durations(traces_paths: Iterable[Path]) -> dict[str, list[float]]:
    """Map ``encoder.name`` -> durations_ms over every ``encoder.ort_run`` span."""
    result: dict[str, list[float]] = {}
    for path in traces_paths:
        for span in _iter_spans(path):
            if span.get("name") != ORT_RUN_SPAN_NAME:
                continue
            attrs = span.get("attrs") or {}
            encoder = attrs.get(ENCODER_NAME_ATTR)
            if not encoder:
                continue
            duration_ms = span_duration_ms(span)
            if duration_ms is None:
                continue
            result.setdefault(encoder, []).append(duration_ms)
    return result


def _percentile(sorted_values: list[float], p: float) -> float:
    """Nearest-rank percentile over an already-sorted, non-empty list.

    Same formula as :func:`jseval.run._compute_latency_stats` (``run.py:768-770``) so a
    reader comparing encoder p95 against query p95 is comparing like with like: nearest
    rank, not interpolated, so the number is an observed call duration.
    """
    idx = int(p / 100 * (len(sorted_values) - 1) + 0.5)
    return sorted_values[min(idx, len(sorted_values) - 1)]


def _round_ms(value: float) -> float:
    return round(value, 3)


def collect_traces_paths(data_dir: Path) -> list[Path]:
    """Every ``traces.ndjson`` (rotated siblings first, active last) under ``data_dir``."""
    from .artifacts import collect_rotated_siblings

    telemetry_dir = Path(data_dir) / "telemetry"
    if not telemetry_dir.is_dir():
        return []
    return collect_rotated_siblings(telemetry_dir, TRACES_FILENAME)


def build_block(data_dir: Path) -> dict:
    """Build the ``summary.json`` ``encoder_latency`` block.

    Shape::

        {"encoders": {"<encoder.name>": {"n": int, "p50_ms": float, "p95_ms": float}}}

    ``encoders`` is empty when the Worker emitted no ``encoder.ort_run`` spans (the
    common case without ``JUSTSEARCH_INDEX_TRACING_LEVEL=detailed``) or when there is
    no telemetry directory at all. Reading telemetry must never fail a run, so every
    error path degrades to the empty block.
    """
    try:
        durations = extract_encoder_durations(collect_traces_paths(data_dir))
    except Exception:  # pragma: no cover - reading telemetry must never fail a run
        log.debug("encoder_latency: telemetry read failed", exc_info=True)
        durations = {}

    encoders: dict[str, dict] = {}
    for name in sorted(durations):
        values = sorted(durations[name])
        if not values:
            continue
        encoders[name] = {
            "n": len(values),
            "p50_ms": _round_ms(_percentile(values, 50)),
            "p95_ms": _round_ms(_percentile(values, 95)),
        }
    return {"encoders": encoders}
