"""LR4-g drift-baseline calibration (Phase 6 / 6.2).

Orchestrates the explicit opt-in baseline capture for the
encoder-drift projection. Replaces the removed
"first-run-silently-writes-baseline" behaviour (see
post-implementation-critique C-1.8.1).

Usage:

    jseval calibrate-drift-baseline \\
        --cohort-hash HASH \\
        --data-dir DIR \\
        --from-runs RUN1 RUN2 [RUN3...]

- Each ``RUN*`` is a path to a ``<run_dir>`` produced by a prior
  ``jseval run`` whose ``manifest.json`` has ``manifest_hash == HASH``.
- The calibrator merges per-encoder ``encoder.ort_run`` span
  durations from every input run into a single baseline document
  written at
  ``<data_dir>/cohort_baselines/<HASH>/span_distributions.json``.
- A minimum of ``MIN_RUNS_FOR_BASELINE=3`` is enforced so one
  cold-start outlier can't define the reference (see the critique
  rationale for why the first-run-silent-baseline was dropped).

This module is intentionally stateless (no subprocess spawning) —
operators pick which runs to include. That keeps the calibrator
focused on the merge logic and lets the shell / Make / operator
choose how to produce the warm runs.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from . import cohort_baselines
from .projections.encoder_drift import (
    MAX_SAMPLES_PER_ENCODER,
    ORT_RUN_SPAN_NAME,
    SCHEMA_VERSION,
    _extract_encoder_durations,
)

log = logging.getLogger(__name__)

MIN_RUNS_FOR_BASELINE = 3


def _read_cohort_from_manifest(run_dir: Path) -> tuple[str | None, str | None]:
    manifest_path = run_dir / "manifest.json"
    try:
        doc = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None, None
    cohort_hash = doc.get("manifest_hash")
    git_sha = doc.get("git_sha") or (doc.get("commit_metadata") or {}).get("commit_id")
    return cohort_hash, git_sha


def calibrate(
    *,
    data_dir: Path,
    cohort_hash: str,
    from_runs: list[Path],
    force: bool = False,
) -> dict:
    """Merge span distributions from ``from_runs`` into the baseline.

    Returns a dict:
        {
          "status": "ok" | "insufficient-runs" | "cohort-mismatch"
                  | "no-encoder-spans" | "baseline-exists",
          "cohort_hash": str,
          "n_runs_merged": int,
          "encoders": {name: n_samples},
          "baseline_path": str,
        }

    Failure modes:

    - ``insufficient-runs``: ``len(from_runs) < MIN_RUNS_FOR_BASELINE``.
      Caller retries with more runs. Enforced to keep one cold-start
      outlier from defining the cohort baseline.
    - ``cohort-mismatch``: at least one ``from_runs`` entry has
      ``manifest.manifest_hash != cohort_hash``. Caller double-checks
      the inputs.
    - ``no-encoder-spans``: merged run dirs collectively contain zero
      ``encoder.ort_run`` spans with durations. Almost always means
      tracing was off (``JUSTSEARCH_INDEX_TRACING_LEVEL`` defaults to
      ``"none"``).
    - ``baseline-exists``: a baseline file already exists for this
      cohort and ``force=False``. Caller passes ``force=True`` to
      overwrite explicitly.

    This is a pure function over ``from_runs`` + filesystem; it
    doesn't spawn any subprocesses and doesn't read eval-history.db.
    """
    if len(from_runs) < MIN_RUNS_FOR_BASELINE:
        return {
            "status": "insufficient-runs",
            "cohort_hash": cohort_hash,
            "n_runs_merged": len(from_runs),
            "n_runs_required": MIN_RUNS_FOR_BASELINE,
            "encoders": {},
        }

    existing_path = cohort_baselines.span_distributions_path(data_dir, cohort_hash)
    if existing_path.is_file() and not force:
        return {
            "status": "baseline-exists",
            "cohort_hash": cohort_hash,
            "baseline_path": str(existing_path),
            "hint": "pass force=True (CLI: --force) to overwrite",
        }

    # Validate cohort match + merge durations.
    encoder_totals: dict[str, list[float]] = {}
    seen_git_shas: set[str] = set()
    for run_dir in from_runs:
        mh, sha = _read_cohort_from_manifest(run_dir)
        if mh != cohort_hash:
            return {
                "status": "cohort-mismatch",
                "cohort_hash": cohort_hash,
                "mismatch_run": str(run_dir),
                "mismatch_manifest_hash": mh,
            }
        if sha:
            seen_git_shas.add(sha)
        durations = _extract_encoder_durations(run_dir / "traces.ndjson")
        for name, values in durations.items():
            encoder_totals.setdefault(name, []).extend(values)

    if not encoder_totals:
        return {
            "status": "no-encoder-spans",
            "cohort_hash": cohort_hash,
            "n_runs_merged": len(from_runs),
            "encoders": {},
            "hint": (
                "ensure JUSTSEARCH_INDEX_TRACING_LEVEL=detailed was set "
                "when the input runs executed — no encoder.ort_run "
                "spans were found in any from_runs' traces.ndjson"
            ),
        }

    # Cap per-encoder sample count (prevent huge baseline files).
    encoders_block: dict[str, dict] = {}
    for name, values in encoder_totals.items():
        capped = values[:MAX_SAMPLES_PER_ENCODER]
        encoders_block[name] = {
            "durations_ms": capped,
            "n_samples": len(values),
            "capped_to": len(capped),
        }

    document = {
        "schema_version": SCHEMA_VERSION,
        "cohort_hash": cohort_hash,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "git_sha": sorted(seen_git_shas)[0] if seen_git_shas else None,
        "n_runs_merged": len(from_runs),
        "source_run_dirs": [str(p) for p in from_runs],
        "encoders": encoders_block,
    }

    cohort_baselines.ensure_cohort_dir(data_dir, cohort_hash)
    path = cohort_baselines.span_distributions_path(data_dir, cohort_hash)
    path.write_text(
        json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return {
        "status": "ok",
        "cohort_hash": cohort_hash,
        "n_runs_merged": len(from_runs),
        "encoders": {name: b["n_samples"] for name, b in encoders_block.items()},
        "baseline_path": str(path),
    }
