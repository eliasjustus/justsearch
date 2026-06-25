"""Manifest-hash bisection runner (tempdoc 400 LR5-d).

Given two runs A and B whose aggregate metrics differ by more than
±2σ of the cohort's non-determinism envelope (§9.1), this module
walks the axis-wise diff between their manifests and attributes the
observed delta to individual cohort-identity axes (e.g.
``commit_metadata.field_catalog_hash``, ``policy_hash``,
``eval_protocol_hash``, `corpus_identity.signature``).

The algorithm per §13.9 C2 is single-axis: for each differing axis,
build a synthetic manifest = A with that one axis swapped to B's
value, hash it, and look up a cached run with matching hash. If the
cached run's metric differs from A's metric by more than the
envelope threshold, the axis is flagged. When no single-axis swap
reproduces the delta inside envelope tolerance, the projection
emits ``MULTI_AXIS_INTERACTION`` with the candidate axis set.

Run cache:

- ``<output_dir>/_index/manifests.jsonl`` — append-only JSONL with
  ``{manifest_hash, run_dir, git_sha, timestamp, dataset, mode}``
  rows; populated by :func:`register_run` (called from
  ``jseval.run.execute_run`` on manifest write).
- Cache lookup is read-only: if a synthetic hash has no registered
  run, the bisection reports ``no-cached-run`` for that axis and
  treats it as an inconclusive candidate (NOT as a drift signal).

Known limitations (preserved from §13.9 C2):

- Single-axis bisection only; O(n²) 2-axis combinations are out
  of scope. ``MULTI_AXIS_INTERACTION`` gets operator attention.
- NULL-valued axes are treated as distinct values (so a schema
  addition counts as an axis diff).
- This module does not run synthetic manifests; it only analyzes
  cached runs. A future commit can add an executor that fills the
  cache on demand.
"""

from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

SCHEMA_VERSION = 1
INDEX_SUBDIR = "_index"
INDEX_FILENAME = "manifests.jsonl"
DEFAULT_METRIC_KEY = "nDCG@10"
DEFAULT_SIGMA_MULTIPLIER = 2.0

# Axes considered for bisection. These mirror the cohort-identity
# keys in jseval.manifest (commit_metadata identity fields +
# corpus_identity + policy_hash + eval_protocol_hash + git_sha +
# dataset + query_count + doc_count + model_fingerprints). Volatile
# fields (timestamp, run_id, envelope) are intentionally excluded
# because they do not change the cohort.
_COHORT_IDENTITY_AXES: tuple[str, ...] = (
    "git_sha",
    "dataset",
    "doc_count",
    "query_count",
    "policy_hash",
    "eval_protocol_hash",
    "corpus_identity",
    "model_fingerprints",
    "commit_metadata",
)


def index_path(output_dir: Path) -> Path:
    return output_dir / INDEX_SUBDIR / INDEX_FILENAME


def register_run(
    output_dir: Path,
    *,
    manifest_hash: str,
    run_dir: Path,
    git_sha: str | None,
    dataset: str | None,
    mode: str | None,
    timestamp: str | None,
) -> Path:
    """Append one manifest-index row.

    Idempotent on exact duplicate ``(manifest_hash, run_dir)`` pairs
    — re-registering the same run does not add a second row. This
    matters when ``jseval run`` is retried after a failure partway
    through the manifest-write step.
    """
    path = index_path(output_dir)
    path.parent.mkdir(parents=True, exist_ok=True)
    new_row = {
        "manifest_hash": manifest_hash,
        "run_dir": str(run_dir),
        "git_sha": git_sha,
        "dataset": dataset,
        "mode": mode,
        "timestamp": timestamp or datetime.now(timezone.utc).isoformat(),
    }
    if path.is_file():
        existing = load_index(output_dir)
        for row in existing:
            if (row.get("manifest_hash") == manifest_hash
                    and row.get("run_dir") == new_row["run_dir"]):
                return path
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(new_row, ensure_ascii=False) + "\n")
    return path


def load_index(output_dir: Path) -> list[dict]:
    path = index_path(output_dir)
    if not path.is_file():
        return []
    rows: list[dict] = []
    try:
        with path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    log.debug("bisection: skipping unparseable index row")
    except OSError:
        return []
    return rows


def find_run_by_hash(output_dir: Path, manifest_hash: str) -> dict | None:
    """Return the most recent index row whose manifest_hash matches."""
    rows = [r for r in load_index(output_dir)
            if r.get("manifest_hash") == manifest_hash]
    if not rows:
        return None
    rows.sort(key=lambda r: r.get("timestamp") or "", reverse=True)
    return rows[0]


def _canonical_hash(document: Any) -> str:
    """SHA-256 over canonical JSON of ``document``."""
    blob = json.dumps(
        document, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def diff_axes(manifest_a: dict, manifest_b: dict) -> list[str]:
    """Return the cohort-identity axes that differ between A and B.

    NULL-as-distinct-value: when an axis is present in one manifest
    but absent in the other, it still counts as a diff. Nested dicts
    compared by full-value equality (not recursive path).
    """
    diffs: list[str] = []
    for axis in _COHORT_IDENTITY_AXES:
        a = manifest_a.get(axis)
        b = manifest_b.get(axis)
        if a != b:
            diffs.append(axis)
    return diffs


def build_synthetic_manifest(
    base: dict, overlay: dict, axis: str,
) -> dict:
    """Return a copy of ``base`` with ``axis`` replaced by ``overlay[axis]``."""
    synthetic = dict(base)
    synthetic[axis] = overlay.get(axis)
    return synthetic


def synthetic_manifest_hash(synthetic: dict) -> str:
    """Compute the cohort hash of a synthetic manifest.

    Only cohort-identity fields are hashed — matches the semantic of
    :func:`jseval.manifest._compute_cohort_hash`. Volatile fields are
    stripped before hashing so the synthetic hash lines up with a
    real manifest produced by a run at the same identity.
    """
    identity = {k: synthetic.get(k) for k in _COHORT_IDENTITY_AXES
                if k in synthetic}
    return _canonical_hash(identity)


def _extract_metric(run_dir: Path, metric: str, mode: str | None) -> float | None:
    """Read ``metric`` for ``mode`` from ``<run_dir>/summary.json``."""
    path = run_dir / "summary.json"
    try:
        summary = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    per_mode = summary.get("per_mode") or {}
    modes = [mode] if mode else list(per_mode.keys())
    for m in modes:
        block = per_mode.get(m) or {}
        agg = block.get("aggregate_metrics") or {}
        val = agg.get(metric)
        if isinstance(val, (int, float)):
            return float(val)
    return None


def bisect(
    manifest_a: dict,
    manifest_b: dict,
    *,
    envelope: dict,
    output_dir: Path,
    metric: str = DEFAULT_METRIC_KEY,
    mode: str | None = None,
    sigma_multiplier: float = DEFAULT_SIGMA_MULTIPLIER,
) -> dict:
    """Bisect the A→B cohort delta by single-axis swap.

    ``envelope`` is the cohort non-determinism envelope for *A*
    (schema v1; see :mod:`jseval.calibrate`). Its ``metrics.<mode>
    .<metric>.stdev`` sets the significance threshold. When the
    envelope has no stdev for ``(mode, metric)``, every axis-swap
    lookup falls back to ``status="no-envelope"``.
    """
    run_a_hash = manifest_a.get("manifest_hash")
    run_b_hash = manifest_b.get("manifest_hash")
    axes = diff_axes(manifest_a, manifest_b)

    # Resolve envelope σ.
    chosen_mode = mode
    metrics_block = (envelope or {}).get("metrics") or {}
    if chosen_mode is None:
        modes_in_env = list(metrics_block.keys())
        chosen_mode = modes_in_env[0] if len(modes_in_env) == 1 else None
    sigma: float | None = None
    if chosen_mode and chosen_mode in metrics_block:
        stats = metrics_block[chosen_mode].get(metric) or {}
        raw = stats.get("stdev")
        if isinstance(raw, (int, float)):
            sigma = float(raw)

    # A / B baseline metrics (from their run dirs).
    run_a_row = find_run_by_hash(output_dir, run_a_hash) if run_a_hash else None
    run_b_row = find_run_by_hash(output_dir, run_b_hash) if run_b_hash else None
    metric_a: float | None = None
    metric_b: float | None = None
    if run_a_row:
        metric_a = _extract_metric(Path(run_a_row["run_dir"]), metric, chosen_mode)
    if run_b_row:
        metric_b = _extract_metric(Path(run_b_row["run_dir"]), metric, chosen_mode)

    attributions: list[dict] = []
    for axis in axes:
        synthetic = build_synthetic_manifest(manifest_a, manifest_b, axis)
        s_hash = synthetic_manifest_hash(synthetic)
        row = find_run_by_hash(output_dir, s_hash)
        entry: dict = {
            "axis": axis,
            "synthetic_hash": s_hash,
            "cached_run": row["run_dir"] if row else None,
        }
        if row is None:
            entry["status"] = "no-cached-run"
            attributions.append(entry)
            continue
        syn_metric = _extract_metric(Path(row["run_dir"]), metric, chosen_mode)
        entry["synthetic_metric"] = syn_metric
        if syn_metric is None or metric_a is None:
            entry["status"] = "no-metric"
        elif sigma is None or sigma <= 0:
            entry["status"] = "no-envelope"
            entry["delta"] = syn_metric - metric_a
        else:
            delta = syn_metric - metric_a
            threshold = sigma * sigma_multiplier
            entry["delta"] = delta
            entry["sigma"] = sigma
            entry["threshold"] = threshold
            entry["status"] = ("attributed"
                               if abs(delta) > threshold else "within-envelope")
        attributions.append(entry)

    attributed = [a for a in attributions if a.get("status") == "attributed"]
    no_cached = [a for a in attributions if a.get("status") == "no-cached-run"]
    if not axes:
        status = "identical-cohorts"
    elif attributed:
        status = "single-axis" if len(attributed) == 1 else "multi-axis"
    elif len(no_cached) == len(axes):
        status = "no-cached-runs"
    else:
        status = "MULTI_AXIS_INTERACTION"

    return {
        "schema_version": SCHEMA_VERSION,
        "status": status,
        "cohort_a": run_a_hash,
        "cohort_b": run_b_hash,
        "metric": metric,
        "mode": chosen_mode,
        "metric_a": metric_a,
        "metric_b": metric_b,
        "envelope_sigma": sigma,
        "axes_diff": axes,
        "attributions": attributions,
    }


def write_report(result: dict, output_path: Path) -> Path:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return output_path


def synthesize_and_bisect(
    manifest_a: dict,
    manifest_b: dict,
    *,
    envelope: dict,
    output_dir: Path,
    data_dir: Path,
    dataset: str,
    modes: str,
    max_queries: int = 50,
    metric: str = DEFAULT_METRIC_KEY,
    mode: str | None = None,
    sigma_multiplier: float = DEFAULT_SIGMA_MULTIPLIER,
    python_executable: str | None = None,
    base_url: str | None = None,
    dry_run: bool = False,
) -> dict:
    """Synthesize missing cohort runs then bisect (Phase 6 / 6.5).

    For every axis that differs between A and B, builds a synthetic
    manifest (A with that axis swapped to B's value), checks the
    manifest index for a matching cached run, and — if missing —
    spawns `jseval run` with `JUSTSEARCH_MANIFEST_OVERRIDE` pointing
    at the synthetic manifest. After each synthesis the manifest
    index picks up the new row via the standard :func:`register_run`
    flow; the final :func:`bisect` call sees a populated cache.

    **Safety.** Every spawned `jseval run` must also export
    ``JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1`` per the run.py guard.
    Synthetic manifests are marked `synthetic: true` in the persisted
    manifest.json so consumers can filter them out of cross-cohort
    views.

    ``dry_run=True`` returns the planned synthesis operations without
    executing — useful for tests + for operators previewing the
    plan before committing compute.
    """
    import os as _os
    import subprocess as _subprocess
    import sys as _sys

    axes = diff_axes(manifest_a, manifest_b)
    if not axes:
        return {
            "status": "identical-cohorts",
            "synthesized": [],
            "bisection": bisect(
                manifest_a, manifest_b,
                envelope=envelope, output_dir=output_dir,
                metric=metric, mode=mode,
                sigma_multiplier=sigma_multiplier,
            ),
        }

    synthesize_plan: list[dict] = []
    for axis in axes:
        synthetic = build_synthetic_manifest(manifest_a, manifest_b, axis)
        s_hash = synthetic_manifest_hash(synthetic)
        cached = find_run_by_hash(output_dir, s_hash)
        synthesize_plan.append({
            "axis": axis,
            "synthetic_hash": s_hash,
            "cached_run": cached["run_dir"] if cached else None,
            "needs_synthesis": cached is None,
        })

    if dry_run:
        return {
            "status": "dry-run",
            "synthesized": synthesize_plan,
        }

    synthesis_dir = output_dir / "_synthetic"
    synthesis_dir.mkdir(parents=True, exist_ok=True)
    synthesized: list[dict] = []
    python_exec = python_executable or _sys.executable
    for plan in synthesize_plan:
        if not plan["needs_synthesis"]:
            synthesized.append({**plan, "status": "cache-hit"})
            continue
        # Materialize the synthetic manifest JSON.
        syn_manifest = build_synthetic_manifest(
            manifest_a, manifest_b, plan["axis"],
        )
        syn_manifest["manifest_hash"] = plan["synthetic_hash"]
        override_file = (
            synthesis_dir / f"override-{plan['axis']}-{plan['synthetic_hash'][:16]}.json"
        )
        override_file.write_text(
            json.dumps(syn_manifest, indent=2, sort_keys=True, ensure_ascii=False),
            encoding="utf-8",
        )

        env = dict(_os.environ)
        env["JUSTSEARCH_MANIFEST_OVERRIDE"] = str(override_file)
        env["JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS"] = "1"
        env["JUSTSEARCH_DATA_DIR"] = str(data_dir)
        cmd = [
            python_exec, "-m", "jseval", "run",
            "--dataset", dataset,
            "--modes", modes,
            "--max-queries", str(max_queries),
            "--output-dir", str(output_dir),
        ]
        if base_url:
            cmd += ["--base-url", base_url]
        log.info("synthesize: axis=%s hash=%s cmd=%s",
                 plan["axis"], plan["synthetic_hash"][:16], " ".join(cmd))
        try:
            proc = _subprocess.run(cmd, env=env, check=False, timeout=3600)
            returncode = proc.returncode
        except _subprocess.TimeoutExpired:
            returncode = -1
        synthesized.append({
            **plan, "status": "ok" if returncode == 0 else "failed",
            "returncode": returncode,
            "override_path": str(override_file),
        })

    final_bisection = bisect(
        manifest_a, manifest_b,
        envelope=envelope, output_dir=output_dir,
        metric=metric, mode=mode,
        sigma_multiplier=sigma_multiplier,
    )
    return {
        "status": "ok",
        "synthesized": synthesized,
        "bisection": final_bisection,
    }
