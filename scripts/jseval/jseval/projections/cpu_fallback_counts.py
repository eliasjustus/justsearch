"""CPU fallback count projection (tempdoc 400 LR4-f).

Reads ``cpu_fallback.triggered`` span events from
``<run_dir>/traces.ndjson`` and aggregates by
``(fallback.encoder, fallback.cause)``. Closes §4.3 (no visibility
into GPU-OOM fallback behavior on the jseval side).

Event shape (agreed contract with LR2-c runtime instrumentation):

.. code-block:: json

    {
      "trace_id": "...",
      "span_id": "...",
      "name": "encoder.ort_run",
      "events": [
        {
          "name": "cpu_fallback.triggered",
          "attrs": {
            "fallback.cause": "gpu-bfc-oom",
            "fallback.encoder": "BgeM3Encoder"
          }
        }
      ]
    }

A healthy GPU run produces zero events — the projection returns an
empty-but-well-shaped result in that case. §23.3 noted this as the
primary verification gap of Phase 1: synthetic NDJSON is the ONLY
viable test path short of injecting GPU OOM, which this projection
embraces (see the unit-test file).
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from pathlib import Path
from typing import Iterable

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "cpu_fallback_counts"
SCHEMA_VERSION = 1
FALLBACK_EVENT_NAME = "cpu_fallback.triggered"
MAX_SAMPLES_PER_GROUP = 10


def _iter_spans(traces_path: Path) -> Iterable[dict]:
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
    except OSError as e:
        log.debug("cpu_fallback_counts: failed to read %s: %s", traces_path, e)


def aggregate(traces_path: Path) -> dict:
    """Aggregate cpu_fallback.triggered events.

    Returns:
        total_fallbacks (int)
        by_encoder (dict[str, int])
        by_cause (dict[str, int])
        by_encoder_and_cause (dict[str, int])
        samples (list of up to ``MAX_SAMPLES_PER_GROUP`` per encoder,
        first-seen order) — each with trace_id, span_id, span_name,
        attrs.
    """
    total = 0
    by_encoder: Counter[str] = Counter()
    by_cause: Counter[str] = Counter()
    by_both: Counter[str] = Counter()
    samples_by_encoder: dict[str, list[dict]] = {}

    for span in _iter_spans(traces_path):
        events = span.get("events") or []
        for ev in events:
            if ev.get("name") != FALLBACK_EVENT_NAME:
                continue
            attrs = ev.get("attrs") or {}
            encoder = attrs.get("fallback.encoder") or "<unknown>"
            cause = attrs.get("fallback.cause") or "<unknown>"
            total += 1
            by_encoder[encoder] += 1
            by_cause[cause] += 1
            by_both[f"{encoder} || {cause}"] += 1
            bucket = samples_by_encoder.setdefault(encoder, [])
            if len(bucket) < MAX_SAMPLES_PER_GROUP:
                bucket.append({
                    "trace_id": span.get("trace_id"),
                    "span_id": span.get("span_id"),
                    "span_name": span.get("name"),
                    "attrs": attrs,
                })
    return {
        "total_fallbacks": total,
        "by_encoder": dict(by_encoder),
        "by_cause": dict(by_cause),
        "by_encoder_and_cause": dict(by_both),
        "samples": samples_by_encoder,
    }


def produce(run_dir: Path) -> dict:
    """LR4-f projection entrypoint. Delegates to :func:`aggregate`."""
    return aggregate(run_dir / "traces.ndjson")


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="CPU-fallback event counts grouped by encoder + cause (LR4-f).",
    produce=produce,
)
