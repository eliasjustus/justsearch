"""Bootstrap confidence intervals per mode × metric (tempdoc 400 LR4-b).

For each ``<mode>_per_query.json`` in the run directory, resample the
per-query metric vector ``B`` times (default 1000) with replacement,
compute the mean per resample, and report the 95% quantile-based CI
on the mean. Closes §4.4 (within-run uncertainty quantification).

Notes:

- The spec references ``ranx.compare``'s paired-bootstrap; this
  implementation uses numpy directly so LR4-b has no optional-dep
  requirement. For within-run uncertainty on a single system the
  unpaired bootstrap on the per-query metric vector is statistically
  equivalent to ranx's mode=1 path (see ranx/comparison.py).
- Metrics: ``nDCG@10``, ``AP@10``, ``RR@10``, ``R@10``, ``P@1`` —
  the jseval per-query shape.
- Seeding: fixed per-mode seed derived from the mode name so CIs are
  reproducible across reruns of the projection.
- N < 5 per-query: projection emits the metric row with
  ``ci_status="insufficient"`` rather than computing; downstream
  consumers can skip these.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Iterable

import numpy as np

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "bootstrap_ci"
SCHEMA_VERSION = 1
DEFAULT_RESAMPLES = 1000
DEFAULT_CI_LEVEL = 0.95
MIN_QUERIES_FOR_CI = 5

# Per-query JSON key → canonical metric label. Mirrors
# `artifacts.py::_build_per_query_entries` output.
_METRIC_KEYS = {
    "ndcgAtK": "nDCG@10",
    "apAtK": "AP@10",
    "mrrAtK": "RR@10",
    "recallAtK": "R@10",
    "p1AtK": "P@1",
}


def _mode_seed(mode: str) -> int:
    """Stable 32-bit seed derived from the mode label."""
    h = 0
    for ch in mode:
        h = (h * 131 + ord(ch)) & 0xFFFFFFFF
    # Avoid 0 (numpy complains) and keep in unsigned 32-bit range.
    return h or 1


def _load_per_query(path: Path) -> list[dict] | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        log.debug("bootstrap_ci: could not read %s: %s", path, e)
        return None


def _extract_metric_values(
    entries: list[dict], json_key: str,
) -> list[float]:
    """Return numeric metric values across all entries (missing → skipped)."""
    values: list[float] = []
    for entry in entries:
        raw = entry.get(json_key)
        if raw is None:
            continue
        try:
            values.append(float(raw))
        except (TypeError, ValueError):
            continue
    return values


def bootstrap_ci(
    values: Iterable[float],
    *,
    n_resamples: int = DEFAULT_RESAMPLES,
    ci_level: float = DEFAULT_CI_LEVEL,
    rng_seed: int | None = None,
) -> dict:
    """Quantile bootstrap CI on the mean of ``values``.

    Returns a dict with ``mean``, ``ci_low``, ``ci_high``,
    ``n_queries``, ``n_resamples``, ``ci_level``, ``ci_status``.
    When ``len(values) < MIN_QUERIES_FOR_CI``, ``ci_status`` is
    ``"insufficient"`` and ``ci_low``/``ci_high`` are ``None``.
    """
    arr = np.asarray(list(values), dtype=np.float64)
    n = len(arr)
    if n == 0:
        return {
            "mean": None,
            "ci_low": None,
            "ci_high": None,
            "n_queries": 0,
            "n_resamples": 0,
            "ci_level": ci_level,
            "ci_status": "empty",
        }
    mean = float(arr.mean())
    if n < MIN_QUERIES_FOR_CI:
        return {
            "mean": mean,
            "ci_low": None,
            "ci_high": None,
            "n_queries": n,
            "n_resamples": 0,
            "ci_level": ci_level,
            "ci_status": "insufficient",
        }
    rng = np.random.default_rng(rng_seed)
    resample_means = np.empty(n_resamples, dtype=np.float64)
    for i in range(n_resamples):
        idx = rng.integers(0, n, size=n)
        resample_means[i] = arr[idx].mean()
    alpha = 1.0 - ci_level
    low = float(np.quantile(resample_means, alpha / 2.0))
    high = float(np.quantile(resample_means, 1.0 - alpha / 2.0))
    return {
        "mean": mean,
        "ci_low": low,
        "ci_high": high,
        "n_queries": n,
        "n_resamples": n_resamples,
        "ci_level": ci_level,
        "ci_status": "ok",
    }


def _discover_mode_files(run_dir: Path) -> list[tuple[str, Path]]:
    """Find every ``<mode>_per_query.json`` in ``run_dir``."""
    results: list[tuple[str, Path]] = []
    for path in sorted(run_dir.glob("*_per_query.json")):
        mode = path.stem.rsplit("_per_query", 1)[0]
        if mode:
            results.append((mode, path))
    return results


def produce(
    run_dir: Path,
    *,
    n_resamples: int = DEFAULT_RESAMPLES,
    ci_level: float = DEFAULT_CI_LEVEL,
) -> dict:
    """Compute bootstrap CI per mode × metric in ``run_dir``.

    Emits the LR4-b schema-v1 document:

    .. code-block:: json

        {
          "modes": {
            "<mode>": {
              "<metric>": {
                "mean": 0.70,
                "ci_low": 0.65,
                "ci_high": 0.75,
                "n_queries": 50,
                "n_resamples": 1000,
                "ci_level": 0.95,
                "ci_status": "ok"
              }
            }
          },
          "n_resamples": 1000,
          "ci_level": 0.95
        }

    Empty-shape result when no per-query files are present; this
    matches the Phase-1 projection contract (see LR6-c).
    """
    mode_files = _discover_mode_files(run_dir)
    modes_block: dict[str, dict] = {}
    for mode, path in mode_files:
        entries = _load_per_query(path)
        if entries is None:
            continue
        per_metric: dict[str, dict] = {}
        seed = _mode_seed(mode)
        for json_key, metric_label in _METRIC_KEYS.items():
            values = _extract_metric_values(entries, json_key)
            per_metric[metric_label] = bootstrap_ci(
                values,
                n_resamples=n_resamples,
                ci_level=ci_level,
                rng_seed=seed,
            )
        modes_block[mode] = per_metric
    return {
        "modes": modes_block,
        "n_resamples": n_resamples,
        "ci_level": ci_level,
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Paired bootstrap 95% CI per mode × metric (LR4-b).",
    produce=produce,
)
