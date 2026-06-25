"""Non-determinism envelope calibration (tempdoc 400 LR1-b).

Repeats N identical jseval runs against the same cohort and captures
σ per metric. The resulting envelope is persisted per-cohort so that
future runs can embed the envelope into their manifest and consumers
(LR4-b bootstrap CI in Phase 3) can classify a cross-run delta as
noise (inside ±2σ) or signal (outside) by definition rather than by
hope — tempdoc 400 §9.1.

Storage model:

- **Phase 3 (current)** — per-cohort directory at
  ``<data_dir>/cohort_baselines/<cohort_hash>/`` containing
  ``envelope.json`` (this module's concern) + optional
  ``span_distributions.json`` (LR4-g concern). See §26.6 Decision 2.
- **Phase 2 (legacy, read-only shim)** — sidecar file at
  ``<data_dir>/non_determinism_envelopes/<cohort_hash>.json``.
  :func:`read_envelope` still reads these when the new path is
  absent so in-flight envelopes keep working during the transition.
- Schema v1 documented in :data:`ENVELOPE_SCHEMA_VERSION`.
- Single-writer assumed. No concurrent-calibration safety.

Metric set (Phase 2 pre-implementation Q1 finding):

- Include: quality metrics (nDCG@10, P@1, R@10, RR@10, AP@10) +
  latency mean_ms + p50_ms.
- Exclude: latency p95/p99/max. Q1 measured per-run p99 cv ≥ 64%
  dominated by first-query cold-start; including these inflates the
  envelope to uselessness. Layer-4 projections can surface tail
  latency separately without calibrating against it.
"""

from __future__ import annotations

import json
import logging
import os
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

log = logging.getLogger(__name__)

ENVELOPE_SCHEMA_VERSION = 1

# Metrics calibrated into the envelope. Values match the keys written
# into ``summary.json::per_mode.<mode>.aggregate_metrics`` and
# ``latency_stats``.
CALIBRATED_QUALITY_METRICS = (
    "nDCG@10",
    "P@1",
    "R@10",
    "RR@10",
    "AP@10",
)

CALIBRATED_LATENCY_METRICS = (
    "mean_ms",
    "p50_ms",
)

def envelope_registry_dir(data_dir: Path) -> Path:
    """Return the directory that stores per-cohort envelopes.

    Phase 3 layout: ``<data_dir>/cohort_baselines/``. Historically
    (Phase 2): ``<data_dir>/non_determinism_envelopes/``. Consumers
    that want the legacy directory directly can import
    ``jseval.cohort_baselines.legacy_envelope_path``.
    """
    from . import cohort_baselines
    return cohort_baselines.cohort_baselines_dir(data_dir)


def read_envelope(data_dir: Path, cohort_hash: str) -> dict | None:
    """Load the calibrated envelope for a cohort, or None if missing.

    Tries the Phase-3 layout first
    (``<data_dir>/cohort_baselines/<cohort_hash>/envelope.json``); falls
    back to the Phase-2 sidecar (``<data_dir>/non_determinism_envelopes/
    <cohort_hash>.json``) when the new path is absent. Missing envelope
    returns ``None`` (not an error — callers treat it as uncalibrated).
    """
    from . import cohort_baselines

    new_path = cohort_baselines.envelope_path(data_dir, cohort_hash)
    legacy_path = cohort_baselines.legacy_envelope_path(data_dir, cohort_hash)
    for path in (new_path, legacy_path):
        if not path.is_file():
            continue
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            log.debug("envelope sidecar unreadable at %s: %s", path, e)
            continue
    return None


def write_envelope(data_dir: Path, cohort_hash: str, envelope: dict) -> Path:
    """Persist an envelope to the new per-cohort facet layout.

    Writes always use the Phase-3 path
    (``cohort_baselines/<hash>/envelope.json``); legacy sidecars are
    only read, never written, so re-calibration naturally migrates a
    cohort forward.
    """
    from . import cohort_baselines

    cohort_baselines.ensure_cohort_dir(data_dir, cohort_hash)
    path = cohort_baselines.envelope_path(data_dir, cohort_hash)
    path.write_text(
        json.dumps(envelope, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def _git_sha_full() -> str | None:
    """Return the full 40-char git SHA at calibration time, or None."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=5, check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip() or None
    except Exception as e:
        log.debug("git rev-parse failed: %s", e)
    return None


def _jseval_dir() -> Path:
    """Working directory for the jseval subprocess (same as the CLI)."""
    return Path(__file__).resolve().parent.parent


def _run_single(
    *,
    dataset: str,
    modes: list[str],
    max_queries: int,
    data_dir: Path,
    run_num: int,
    total: int,
) -> Path:
    """Invoke ``jseval run --start-backend --clean --pipeline`` once.

    Returns the path to the latest eval-results dir matching the dataset.
    Every calibration run uses ``--clean --pipeline`` so the measured
    variance captures the full ingest+enrich+query pipeline, not just
    query-side non-determinism.
    """
    cmd = [
        sys.executable, "-m", "jseval", "run",
        "--dataset", dataset,
        "--modes", ",".join(modes),
        "--max-queries", str(max_queries),
        "--start-backend",
        "--clean",
        "--pipeline",
    ]
    env = dict(os.environ)
    env["JUSTSEARCH_DATA_DIR"] = str(data_dir)
    log.info(
        "calibration run %d/%d: %s", run_num, total, " ".join(cmd),
    )
    proc = subprocess.run(
        cmd, cwd=str(_jseval_dir()), env=env, check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"calibration run {run_num}/{total} failed with exit {proc.returncode}",
        )
    results_dir = _jseval_dir() / "tmp" / "eval-results"
    candidates = sorted(
        (p for p in results_dir.iterdir() if p.is_dir() and dataset in p.name),
        key=lambda p: p.stat().st_mtime,
    )
    if not candidates:
        raise RuntimeError(
            f"calibration run {run_num}/{total} produced no results directory",
        )
    return candidates[-1]


def _collect_metrics(run_dir: Path) -> dict[str, dict[str, float]]:
    """Extract calibrated metric values per mode from ``summary.json``.

    Returns ``{mode: {metric: value}}``. Metrics outside the calibrated
    set are silently ignored so the envelope schema stays tight.
    """
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    per_mode_out: dict[str, dict[str, float]] = {}
    for mode, data in (summary.get("per_mode") or {}).items():
        aggregate = data.get("aggregate_metrics") or {}
        latency = data.get("latency_stats") or {}
        metrics: dict[str, float] = {}
        for m in CALIBRATED_QUALITY_METRICS:
            if m in aggregate and isinstance(aggregate[m], (int, float)):
                metrics[m] = float(aggregate[m])
        for m in CALIBRATED_LATENCY_METRICS:
            if m in latency and isinstance(latency[m], (int, float)):
                metrics[m] = float(latency[m])
        # tempdoc 640: also calibrate per-mode metrics the family registry marks calibratable
        # (e.g. the CE-stage p50 promoted into aggregate_metrics) beyond the legacy quality set —
        # the registry is the single source of truth; this loop adds whatever it declares.
        from . import metric_families as _mf

        for source_path, keys in _mf.calibrated_per_mode_metrics().items():
            src = data.get(source_path) or {}
            for m in keys:
                v = src.get(m)
                if m not in metrics and isinstance(v, (int, float)):
                    metrics[m] = float(v)
        if metrics:
            per_mode_out[mode] = metrics
    return per_mode_out


def _read_cohort_hash(run_dir: Path) -> str:
    """Extract ``manifest_hash`` from the run's manifest.json."""
    return json.loads(
        (run_dir / "manifest.json").read_text(encoding="utf-8"),
    )["manifest_hash"]


def compute_envelope(
    per_run_metrics: list[dict[str, dict[str, float]]],
    *,
    cohort_hash: str,
    n_runs: int,
) -> dict:
    """Compute mean + stdev per mode per metric across N runs.

    Uses sample standard deviation (ddof=1) via :func:`statistics.stdev`.
    When a metric has fewer than 2 samples, the entry is omitted (no
    variance computable from a single sample).

    The returned document matches schema v1 documented in the module
    docstring.
    """
    modes: set[str] = set()
    for run in per_run_metrics:
        modes.update(run.keys())

    metrics_out: dict[str, dict[str, dict[str, float]]] = {}
    for mode in sorted(modes):
        metric_names: set[str] = set()
        for run in per_run_metrics:
            if mode in run:
                metric_names.update(run[mode].keys())
        mode_out: dict[str, dict[str, float]] = {}
        for metric in sorted(metric_names):
            vals = [
                run[mode][metric]
                for run in per_run_metrics
                if mode in run and metric in run[mode]
            ]
            if len(vals) >= 2:
                mode_out[metric] = {
                    "mean": statistics.mean(vals),
                    "stdev": statistics.stdev(vals),
                    "n": len(vals),
                }
        if mode_out:
            metrics_out[mode] = mode_out

    return {
        "cohort_hash": cohort_hash,
        "schema_version": ENVELOPE_SCHEMA_VERSION,
        "calibrated_at": datetime.now(timezone.utc).isoformat(),
        "git_sha": _git_sha_full(),
        "n_runs": n_runs,
        "metrics": metrics_out,
    }


def calibrate(
    *,
    dataset: str,
    modes: list[str],
    runs: int,
    data_dir: Path,
    max_queries: int,
) -> dict:
    """Repeat ``runs`` identical jseval smokes and persist the envelope.

    Each run uses ``--start-backend --clean --pipeline`` so the measured
    variance spans the full ingest+enrich+query pipeline. The first
    run's ``manifest_hash`` becomes the cohort identity; subsequent
    runs must match or the calibration aborts (that's a Phase 2.0
    regression signal — cohort hash should be stable across identical
    reruns since :mod:`jseval.manifest` excludes runtime state).

    Returns the envelope dict and writes it to the sidecar registry.
    """
    if runs < 2:
        raise ValueError("runs must be >= 2 to compute stdev")
    per_run: list[dict[str, dict[str, float]]] = []
    cohort_hash: str | None = None
    for i in range(runs):
        run_dir = _run_single(
            dataset=dataset,
            modes=modes,
            max_queries=max_queries,
            data_dir=data_dir,
            run_num=i + 1,
            total=runs,
        )
        this_hash = _read_cohort_hash(run_dir)
        if cohort_hash is None:
            cohort_hash = this_hash
            log.info("cohort identified: %s", cohort_hash[:16])
        elif this_hash != cohort_hash:
            raise RuntimeError(
                "cohort_hash unstable across identical reruns — run 1 "
                f"produced {cohort_hash[:16]} but run {i + 1} produced "
                f"{this_hash[:16]}. Phase 2.0 LR1-a cohort-identity "
                "fix has regressed. Invoke "
                "scripts/jseval/regression/manifest_hash_stability.py "
                "and tempdoc 400 §24 to diagnose.",
            )
        per_run.append(_collect_metrics(run_dir))

    assert cohort_hash is not None  # runs>=2 guard above ensures the loop executed
    envelope = compute_envelope(per_run, cohort_hash=cohort_hash, n_runs=runs)
    write_envelope(data_dir, cohort_hash, envelope)
    log.info(
        "envelope written for cohort %s (%d modes, %d metrics total)",
        cohort_hash[:16],
        len(envelope["metrics"]),
        sum(len(v) for v in envelope["metrics"].values()),
    )
    return envelope
