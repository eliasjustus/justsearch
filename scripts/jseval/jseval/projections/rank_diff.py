"""Auto rank-diff against the latest prior in-cohort run (tempdoc 400 LR4-e).

Closes §4.1 (no automatic cross-run per-query regression surface).
Queries the run's sibling directories for the most recent prior run
sharing the same ``manifest_hash`` (exact match per §26.6 Decision 3);
runs :func:`jseval.compare_runs.per_query_diff` per mode; emits
``{mode}_regressions.json`` as the projection output (one doc per
mode). Adds the combined shape under ``modes`` so a single
``rank_diff.json`` projection artifact keeps the registry's
name-to-file invariant.

Discovery algorithm:

- Load ``<run_dir>/manifest.json`` to get the current cohort hash.
- Scan ``<run_dir>.parent`` for siblings whose ``manifest.json``
  carries the same ``manifest_hash``.
- Candidates with dir-name timestamps less than the current run's
  qualify as "prior". The latest such sibling (lexicographic sort
  of the timestamp prefix) is the comparand.
- Zero prior siblings → ``status="no-prior-cohort"`` (not an error).

Per-mode behavior:

- For each ``{mode}_per_query.json`` in the current run, look for the
  matching file in the prior run. Missing in either side → skip that
  mode with ``status="missing-mode"``.
- Qrels loaded from the current ``<run_dir>/qrels.json`` if present;
  empty dict fallback when absent.
- ``per_query_diff`` threshold = 0.01 (matches Phase-1 default);
  metric = ``ndcgAtK`` (the primary quality signal from §26.5).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from .. import compare_runs
from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "rank_diff"
SCHEMA_VERSION = 1
DEFAULT_METRIC_KEY = "ndcgAtK"
DEFAULT_REGRESSION_THRESHOLD = 0.01


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _discover_modes(run_dir: Path) -> list[str]:
    modes: list[str] = []
    for path in sorted(run_dir.glob("*_per_query.json")):
        mode = path.stem.rsplit("_per_query", 1)[0]
        if mode:
            modes.append(mode)
    return modes


def _find_prior_run(run_dir: Path, cohort_hash: str) -> Path | None:
    """Return the latest sibling directory with a matching manifest_hash.

    Siblings are ranked by directory name (timestamp-prefix sort);
    the comparand is the most-recent sibling preceding the current
    run. Returns ``None`` when no prior matching cohort exists.
    """
    parent = run_dir.parent
    current_name = run_dir.name
    candidates: list[tuple[str, Path]] = []
    try:
        entries = [p for p in parent.iterdir() if p.is_dir()]
    except OSError:
        return None
    for entry in entries:
        if entry.name == current_name:
            continue
        if entry.name >= current_name:
            continue  # Later-or-equal to current; not a prior.
        manifest = _load_json(entry / "manifest.json")
        if not isinstance(manifest, dict):
            continue
        if manifest.get("manifest_hash") != cohort_hash:
            continue
        candidates.append((entry.name, entry))
    if not candidates:
        return None
    # Sort by name descending (latest prior first).
    candidates.sort(key=lambda pair: pair[0], reverse=True)
    return candidates[0][1]


def produce(
    run_dir: Path,
    *,
    metric_key: str = DEFAULT_METRIC_KEY,
    regression_threshold: float = DEFAULT_REGRESSION_THRESHOLD,
) -> dict:
    """Run-level rank-diff projection; see module docstring for
    the complete contract.
    """
    manifest = _load_json(run_dir / "manifest.json")
    if not isinstance(manifest, dict):
        return {
            "status": "no-manifest",
            "reason": "run manifest missing; cannot identify cohort",
            "modes": {},
        }
    cohort_hash = manifest.get("manifest_hash")
    if not cohort_hash:
        return {
            "status": "no-cohort-hash",
            "reason": "manifest has no manifest_hash field",
            "modes": {},
        }
    prior_dir = _find_prior_run(run_dir, cohort_hash)
    if prior_dir is None:
        return {
            "status": "no-prior-cohort",
            "cohort_hash": cohort_hash,
            "modes": {},
        }
    qrels = _load_json(run_dir / "qrels.json") or {}
    modes = _discover_modes(run_dir)
    per_mode: dict[str, dict] = {}
    for mode in modes:
        current_path = run_dir / f"{mode}_per_query.json"
        prior_path = prior_dir / f"{mode}_per_query.json"
        current_entries = _load_json(current_path)
        prior_entries = _load_json(prior_path)
        if not isinstance(current_entries, list):
            per_mode[mode] = {"status": "missing-mode",
                              "reason": f"current {current_path.name} unreadable"}
            continue
        if not isinstance(prior_entries, list):
            per_mode[mode] = {"status": "missing-mode",
                              "reason": f"prior {prior_path.name} unreadable"}
            continue
        regressions = compare_runs.per_query_diff(
            prior_entries,
            current_entries,
            qrels,
            metric=metric_key,
            threshold=regression_threshold,
        )
        per_mode[mode] = {
            "status": "ok",
            "regressions": regressions,
            "regression_count": len(regressions),
            "total_compared": len(current_entries),
            "threshold": regression_threshold,
        }
    return {
        "status": "ok",
        "cohort_hash": cohort_hash,
        "prior_run": prior_dir.name,
        "metric": metric_key,
        "modes": per_mode,
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Auto per-query rank-diff against latest prior in-cohort run (LR4-e).",
    produce=produce,
)
