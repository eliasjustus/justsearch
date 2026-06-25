"""Encoder distribution-drift projection via PSI (tempdoc 400 LR4-g).

Closes §4.1 / §9.1 on the inference side: cross-run shifts in
encoder per-call latency distributions now surface as a per-encoder
drift flag, regardless of aggregate means. Compares the current run's
``encoder.ort_run`` span-duration distribution against a persisted
cohort baseline using Population Stability Index (PSI).

PSI definition (10 equal-population bins, Laplace-smoothed):

.. math::

    PSI = \\sum_{i=1}^{B} (p^{curr}_i - p^{ref}_i)
           \\cdot \\ln\\left(\\frac{p^{curr}_i}{p^{ref}_i}\\right)

Thresholds follow the common convention: ``< 0.1`` no drift;
``0.1 - 0.2`` minor drift; ``> 0.2`` flag for investigation.
This projection emits the raw ``psi_score`` plus a boolean
``drift_flagged`` (PSI > 0.2).

First-time baseline: when
``<data_dir>/cohort_baselines/<cohort_hash>/span_distributions.json``
does not exist, the projection writes the current run's per-encoder
duration samples there (capped at ``MAX_SAMPLES_PER_ENCODER``) and
flags ``no-baseline`` for every encoder in the current run. The
second run for the same cohort is the first one that can compute a
PSI number.

Baseline facet file shape v1:

.. code-block:: json

    {
      "schema_version": 1,
      "cohort_hash": "<hash>",
      "captured_at": "<ISO-8601>",
      "git_sha": "<sha>",
      "encoders": {
        "BgeM3Encoder": {"durations_ms": [1.2, 1.4, ...]},
        "SpladeEncoder": {"durations_ms": [...]}
      }
    }
"""

from __future__ import annotations

import json
import logging
import math
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "encoder_drift"
SCHEMA_VERSION = 1

ORT_RUN_SPAN_NAME = "encoder.ort_run"
ENCODER_NAME_ATTR = "encoder.name"
DEFAULT_BINS = 10
PSI_DRIFT_THRESHOLD = 0.2
LAPLACE_EPSILON = 1e-6
MAX_SAMPLES_PER_ENCODER = 5000  # cap baseline file size


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
    except OSError:
        return


def _parse_iso_ms(value: object) -> Optional[float]:
    """Parse an ISO-8601 timestamp emitted by NdjsonSpanExporter into
    milliseconds since epoch. Returns ``None`` on any parse failure so the
    caller can drop the span without raising. Format produced by the
    exporter: ``2026-04-22T11:54:10.046Z`` (``.046+00:00`` also accepted)."""
    if not isinstance(value, str):
        return None
    # datetime.fromisoformat accepts "+00:00" but not the trailing "Z" form
    # until 3.11; normalize defensively.
    s = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        return datetime.fromisoformat(s).timestamp() * 1000.0
    except ValueError:
        return None


def _span_duration_ms(span: dict) -> Optional[float]:
    """Return a span's duration in milliseconds.

    Tempdoc 400 §23.8 D-1. Prefers the structural ``duration_ms`` field
    emitted by :class:`NdjsonSpanExporter` (producer side fix landed
    alongside this function); falls back to parsing ``start``+``end``
    ISO timestamps for traces.ndjson files produced before that landed.
    Fallback precision is millisecond — adequate for the drift PSI's
    10-bin histogram, lossy for sub-ms calls.
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
    # ms-precision source → duration can round negative by < 1ms on
    # same-ms start/end pairs; clamp to non-negative.
    return max(0.0, end_ms - start_ms)


def _extract_encoder_durations(traces_path: Path) -> dict[str, list[float]]:
    """Map ``encoder.name`` → list of duration_ms for ``encoder.ort_run`` spans."""
    result: dict[str, list[float]] = {}
    for span in _iter_spans(traces_path):
        if span.get("name") != ORT_RUN_SPAN_NAME:
            continue
        attrs = span.get("attrs") or {}
        encoder = attrs.get(ENCODER_NAME_ATTR)
        if not encoder:
            continue
        duration_ms = _span_duration_ms(span)
        if duration_ms is None:
            continue
        result.setdefault(encoder, []).append(duration_ms)
    return result


def psi(reference: list[float], current: list[float],
        *, bins: int = DEFAULT_BINS) -> float:
    """Population Stability Index over equal-population bins of ``reference``.

    Uses Laplace smoothing so empty bins do not blow up the log. When
    ``reference`` has fewer than ``bins`` distinct values, the bin count
    collapses to the number of distinct values. Returns 0.0 for empty
    inputs.
    """
    if not reference or not current:
        return 0.0
    ref_sorted = sorted(reference)
    # Derive quantile edges at (i / bins) boundaries. Use numpy-like
    # computation without importing numpy — keeps the projection's
    # dependency footprint minimal.
    edges: list[float] = []
    n_ref = len(ref_sorted)
    for i in range(1, bins):
        idx = int(i * n_ref / bins)
        if idx >= n_ref:
            idx = n_ref - 1
        edges.append(ref_sorted[idx])
    # Deduplicate + sort — edges collapsed if reference has fewer
    # distinct values than `bins`.
    edges = sorted(set(edges))
    if not edges:
        return 0.0
    effective_bins = len(edges) + 1

    def _bucket(value: float) -> int:
        # Linear search is fine for DEFAULT_BINS=10.
        for i, edge in enumerate(edges):
            if value <= edge:
                return i
        return len(edges)

    ref_counts = [0] * effective_bins
    cur_counts = [0] * effective_bins
    for v in reference:
        ref_counts[_bucket(v)] += 1
    for v in current:
        cur_counts[_bucket(v)] += 1

    total_ref = len(reference)
    total_cur = len(current)
    score = 0.0
    for i in range(effective_bins):
        p_ref = (ref_counts[i] + LAPLACE_EPSILON) / (total_ref + effective_bins * LAPLACE_EPSILON)
        p_cur = (cur_counts[i] + LAPLACE_EPSILON) / (total_cur + effective_bins * LAPLACE_EPSILON)
        score += (p_cur - p_ref) * math.log(p_cur / p_ref)
    return score


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _resolve_data_dir(run_dir: Path) -> Path | None:
    """Infer the data_dir for baseline lookups.

    Priority:
    1. ``JUSTSEARCH_DATA_DIR`` env var (same mechanism Phase-2 uses
       in jseval.run).
    2. ``run_dir.parent`` if it looks like the eval-results root
       (last-ditch fallback; cohort_baselines/ will be alongside
       the run directories).

    Returns ``None`` when neither source is available — the projection
    then reports ``status="no-data-dir"`` and skips baseline I/O.
    """
    env = os.environ.get("JUSTSEARCH_DATA_DIR")
    if env:
        return Path(env)
    return run_dir.parent  # eval-results root as best-effort fallback


def _load_baseline(data_dir: Path, cohort_hash: str) -> dict | None:
    from .. import cohort_baselines

    path = cohort_baselines.span_distributions_path(data_dir, cohort_hash)
    return _load_json(path)


def _write_baseline(data_dir: Path, cohort_hash: str, baseline: dict) -> Path:
    from .. import cohort_baselines

    cohort_baselines.ensure_cohort_dir(data_dir, cohort_hash)
    path = cohort_baselines.span_distributions_path(data_dir, cohort_hash)
    path.write_text(
        json.dumps(baseline, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def _build_baseline(
    cohort_hash: str,
    encoder_durations: dict[str, list[float]],
    git_sha: str | None,
) -> dict:
    encoders_block = {}
    for name, values in encoder_durations.items():
        encoders_block[name] = {
            "durations_ms": values[:MAX_SAMPLES_PER_ENCODER],
            "n_samples": len(values),
        }
    return {
        "schema_version": SCHEMA_VERSION,
        "cohort_hash": cohort_hash,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "git_sha": git_sha,
        "encoders": encoders_block,
    }


def produce(run_dir: Path) -> dict:
    manifest = _load_json(run_dir / "manifest.json") or {}
    cohort_hash = manifest.get("manifest_hash")
    git_sha = manifest.get("git_sha") or (
        manifest.get("commit_metadata") or {}
    ).get("commit_id")

    encoder_durations = _extract_encoder_durations(run_dir / "traces.ndjson")

    if not cohort_hash:
        return {
            "status": "no-cohort-hash",
            "encoders": {},
            "threshold": PSI_DRIFT_THRESHOLD,
        }
    if not encoder_durations:
        return {
            "status": "no-encoder-spans",
            "cohort_hash": cohort_hash,
            "encoders": {},
            "threshold": PSI_DRIFT_THRESHOLD,
        }

    data_dir = _resolve_data_dir(run_dir)
    baseline = _load_baseline(data_dir, cohort_hash) if data_dir else None

    encoders_block: dict[str, dict] = {}
    if baseline is None:
        # Phase 6 / 6.2: baseline capture is NO LONGER automatic.
        # Previously the first run silently wrote its distributions as
        # the baseline (see post-implementation-critique C-1.8.1 —
        # cold-start outliers poisoned the cohort permanently).
        # Operators now run `jseval calibrate-drift-baseline
        # --cohort-hash H --from-runs RUN1 RUN2 ...` explicitly to
        # merge N warm runs into the baseline. Until that lands, each
        # encoder reports status="no-baseline" and the projection
        # returns status="no-baseline".
        for name, current in encoder_durations.items():
            encoders_block[name] = {
                "status": "no-baseline",
                "current_n": len(current),
                "psi_score": None,
                "drift_flagged": False,
            }
        return {
            "status": "no-baseline" if data_dir else "no-data-dir",
            "cohort_hash": cohort_hash,
            "encoders": encoders_block,
            "threshold": PSI_DRIFT_THRESHOLD,
            "baseline_hint": (
                "run `jseval calibrate-drift-baseline --cohort-hash "
                f"{cohort_hash[:16]}... --from-runs <RUN_DIR>...` to "
                "capture the cohort baseline from N warm runs"
            ),
        }

    baseline_encoders = (baseline.get("encoders") or {})
    for name, current in encoder_durations.items():
        ref_block = baseline_encoders.get(name) or {}
        ref_values = ref_block.get("durations_ms") or []
        if not ref_values:
            encoders_block[name] = {
                "status": "no-baseline",
                "current_n": len(current),
                "psi_score": None,
                "drift_flagged": False,
            }
            continue
        score = psi(ref_values, current)
        encoders_block[name] = {
            "status": "ok",
            "reference_n": len(ref_values),
            "current_n": len(current),
            "psi_score": score,
            "drift_flagged": score > PSI_DRIFT_THRESHOLD,
        }

    return {
        "status": "ok",
        "cohort_hash": cohort_hash,
        "encoders": encoders_block,
        "threshold": PSI_DRIFT_THRESHOLD,
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Encoder per-call latency distribution drift via PSI (LR4-g).",
    produce=produce,
)
