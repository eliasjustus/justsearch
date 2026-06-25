"""Phase 3 observability nightly gate (tempdoc 400 §26.6 Decision 4).

Phase 6 / 6.13: module relocated from ``scripts/ci/phase3_observability_gate.py``
into the ``jseval`` package so operators can invoke it via the same
``jseval`` CLI as every other Phase 3/4/5/6 subcommand. The public
surface (``evaluate`` + ``REQUIRED_PROJECTIONS`` + helper functions)
is unchanged; the argparse shim was dropped in favour of the
``jseval gate`` Click subcommand in :mod:`jseval.cli`.

Validates the calibrated sidecar + the latest eval-results run
directory produced by the Phase 3 nightly workflow:

1. Exactly one ``cohort_baselines/<hash>/envelope.json`` exists.
2. Envelope schema_version == 1; contains ``metrics.full.nDCG@10.stdev``.
3. σ(nDCG@10) is within ``[baseline - tolerance, baseline + tolerance]``.
4. The latest run directory has:
   - non-null ``manifest.non_determinism_envelope``.
   - A populated ``projections/`` directory with the LR4-* outputs
     actually produced (no hard-fail on any single projection status,
     but at least ``contract_violations`` + ``rate_timeline`` +
     ``stratified_metrics`` + ``bootstrap_ci`` must exist).

Exit codes (see :func:`jseval.cli.cmd_gate`):

- 0 — gate passed.
- 1 — any hard assertion failed (σ outside band, manifest missing
  envelope, required projection absent).
- 2 — data-layout problem (no eval-results run found, no calibration
  sidecar) — usually an infra issue, not a quality drift.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

REQUIRED_PROJECTIONS = (
    "contract_violations",
    "rate_timeline",
    "stratified_metrics",
    "bootstrap_ci",
)


def _find_envelope(data_dir: Path) -> tuple[Path, dict] | None:
    """Return (envelope_path, envelope_doc) for the calibrated cohort."""
    baselines = data_dir / "cohort_baselines"
    if not baselines.is_dir():
        return None
    for cohort_dir in sorted(baselines.iterdir()):
        if not cohort_dir.is_dir():
            continue
        env_path = cohort_dir / "envelope.json"
        if not env_path.is_file():
            continue
        try:
            return env_path, json.loads(env_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
    return None


def _latest_run_dir(data_dir: Path) -> Path | None:
    """Return the most recently named run directory under data_dir/eval-results."""
    eval_results = data_dir / "eval-results"
    if not eval_results.is_dir():
        return None
    candidates = [p for p in eval_results.iterdir()
                  if p.is_dir() and (p / "summary.json").is_file()]
    if not candidates:
        return None
    candidates.sort(key=lambda p: p.name, reverse=True)
    return candidates[0]


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def evaluate(
    data_dir: Path,
    baseline_stdev: float,
    tolerance_pct: float,
) -> dict:
    report: dict = {
        "data_dir": str(data_dir),
        "baseline_stdev": baseline_stdev,
        "tolerance_pct": tolerance_pct,
        "checks": [],
        "exit_code": 0,
    }

    envelope_hit = _find_envelope(data_dir)
    if envelope_hit is None:
        report["checks"].append({
            "name": "envelope-present",
            "status": "fail",
            "detail": "no cohort_baselines/<h>/envelope.json found",
        })
        report["exit_code"] = 2
        return report
    env_path, envelope = envelope_hit
    report["envelope_path"] = str(env_path)
    report["cohort_hash"] = envelope.get("cohort_hash")
    schema_version = envelope.get("schema_version")
    if schema_version != 1:
        report["checks"].append({
            "name": "envelope-schema",
            "status": "fail",
            "detail": f"schema_version={schema_version}; expected 1",
        })
        report["exit_code"] = 1
    else:
        report["checks"].append({
            "name": "envelope-schema",
            "status": "ok",
        })

    metrics_block = (envelope.get("metrics") or {}).get("full") or {}
    ndcg_block = metrics_block.get("nDCG@10") or {}
    measured_stdev = ndcg_block.get("stdev")
    if not isinstance(measured_stdev, (int, float)):
        report["checks"].append({
            "name": "ndcg10-stdev-present",
            "status": "fail",
            "detail": "metrics.full.nDCG@10.stdev missing or non-numeric",
        })
        report["exit_code"] = 1
    else:
        report["measured_stdev"] = float(measured_stdev)
        tolerance = baseline_stdev * (tolerance_pct / 100.0)
        low, high = baseline_stdev - tolerance, baseline_stdev + tolerance
        within = low <= measured_stdev <= high
        report["expected_range"] = [low, high]
        report["checks"].append({
            "name": "ndcg10-stdev-within-tolerance",
            "status": "ok" if within else "fail",
            "detail": (f"measured={measured_stdev:.6f} "
                       f"baseline={baseline_stdev:.6f} "
                       f"band=[{low:.6f}, {high:.6f}]"),
        })
        if not within:
            report["exit_code"] = 1

    run_dir = _latest_run_dir(data_dir)
    if run_dir is None:
        report["checks"].append({
            "name": "run-dir-present",
            "status": "fail",
            "detail": "no eval-results run directory with summary.json",
        })
        report["exit_code"] = 2
        return report
    report["run_dir"] = str(run_dir)

    manifest = _load_json(run_dir / "manifest.json")
    if not isinstance(manifest, dict):
        report["checks"].append({
            "name": "run-manifest-present",
            "status": "fail",
            "detail": "run manifest.json missing or unparseable",
        })
        report["exit_code"] = 1
    else:
        envelope_embed = manifest.get("non_determinism_envelope")
        if envelope_embed is None:
            report["checks"].append({
                "name": "manifest-envelope-embedded",
                "status": "fail",
                "detail": "manifest.non_determinism_envelope is null",
            })
            report["exit_code"] = 1
        else:
            report["checks"].append({
                "name": "manifest-envelope-embedded",
                "status": "ok",
            })

    projections_dir = run_dir / "projections"
    present = set()
    if projections_dir.is_dir():
        present = {p.stem for p in projections_dir.glob("*.json")}
    report["projections_present"] = sorted(present)
    missing = [p for p in REQUIRED_PROJECTIONS if p not in present]
    if missing:
        report["checks"].append({
            "name": "required-projections-present",
            "status": "fail",
            "detail": f"missing projections: {missing}",
        })
        report["exit_code"] = 1
    else:
        report["checks"].append({
            "name": "required-projections-present",
            "status": "ok",
        })

    return report
