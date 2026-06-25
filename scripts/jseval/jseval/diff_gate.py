"""Ratio-based regression gate for benchmark comparison."""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger(__name__)


def compare_ratio(
    baseline: float,
    candidate: float,
    *,
    lower_is_better: bool = True,
    max_ratio: float = 1.10,
    min_ratio: float = 0.90,
) -> dict:
    """Compare candidate vs baseline as a ratio.

    For lower-is-better metrics (latency), regressed if ratio > max_ratio.
    For higher-is-better metrics (throughput), regressed if ratio < min_ratio.
    """
    if baseline == 0:
        return {
            "baseline": baseline, "candidate": candidate,
            "ratio": None, "status": "SKIP",
        }
    ratio = candidate / baseline
    if lower_is_better:
        regressed = ratio > max_ratio
    else:
        regressed = ratio < min_ratio
    return {
        "baseline": baseline,
        "candidate": candidate,
        "ratio": round(ratio, 4),
        "status": "REGRESSED" if regressed else "OK",
    }


def build_gate_decision(comparisons: list[dict]) -> dict:
    """Aggregate comparisons into a pass/fail gate decision."""
    regressions = [c for c in comparisons if c.get("status") == "REGRESSED"]
    return {
        "gate_status": "fail" if regressions else "pass",
        "regression_count": len(regressions),
        "comparison_count": len(comparisons),
        "comparisons": comparisons,
    }


def diff_files(
    baseline_path: Path,
    candidate_path: Path,
    metric_configs: list[tuple[str, bool, float]],
) -> dict:
    """Compare two JSON artifact files using metric configs.

    metric_configs: list of (metric_key, lower_is_better, threshold).
    threshold is max_ratio for lower-is-better, min_ratio for higher-is-better.
    """
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))

    comparisons: list[dict] = []
    for key, lower_is_better, threshold in metric_configs:
        b_val = _extract_metric(baseline, key)
        c_val = _extract_metric(candidate, key)
        if b_val is None or c_val is None:
            comparisons.append({
                "metric": key, "status": "SKIP",
                "baseline": b_val, "candidate": c_val, "ratio": None,
            })
            continue
        kwargs = {"lower_is_better": lower_is_better}
        if lower_is_better:
            kwargs["max_ratio"] = threshold
        else:
            kwargs["min_ratio"] = threshold
        comp = compare_ratio(b_val, c_val, **kwargs)
        comp["metric"] = key
        comparisons.append(comp)

    return build_gate_decision(comparisons)


def _extract_metric(data: dict, key: str) -> float | None:
    """Extract a metric value, supporting dotted paths like 'statistics.docs_per_s.median'."""
    parts = key.split(".")
    current = data
    for part in parts:
        if not isinstance(current, dict):
            return None
        current = current.get(part)
    if isinstance(current, (int, float)):
        return float(current)
    return None
