"""Shared ratchet-gate kernel (tempdoc 640 K).

The three engine-quality gates — relevance (`relevance_gate`), performance (`perf_gate`), and
recall-leak (`leak_gate`) — and the new LLM-generation gate all duplicate the same CLI orchestration:
load the baselines file (optionally projecting floors from a `current_release` pointer), locate the run
dir, read the run's source artifact, call the family's `evaluate`, then emit a 0/1/2 report. They
differ only in the *source* (per-mode/per-run `summary.json` vs a projection artifact vs a bench file),
the comparator (which lives inside each family's `evaluate`), and a few family-specific options. This
module owns the SHARED parts so a new family reuses them instead of forking a fourth copy; the
per-family `evaluate`/`project` functions stay in their own modules.

The pure helpers (`load_baselines_doc`, `build_summary`) are unit-testable; `resolve_run_dir` /
`finalize_report` do CLI I/O (echo + `sys.exit`) and are exercised by the live gate runs.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Callable

import click

from . import gate as _gate


def load_baselines_doc(
    baselines_path: str | Path,
    *,
    project_release: Callable[[dict, dict], dict] | None = None,
) -> dict:
    """Load a ratchet baselines file, projecting floors from ``current_release`` when present.

    Faithful lift of the relevance/perf gates' inline logic (tempdoc 623 T-5): a ``current_release``
    pointer + ``fallback_baselines`` means the per-corpus floors are PROJECTED live from the canonical
    release (never hand-typed), so the heterogeneous-commit fork is structurally impossible.
    ``project_release(release_doc, baselines_doc) -> {"baselines": {...}, ...}`` is the family's release
    projector (the second arg lets relevance read its ``tolerance_*`` from the baselines doc; perf ignores
    it). Pass ``None`` for a gate with no release projection (leak / llm-gen), leaving inline baselines as-is.
    """
    path = Path(baselines_path)
    doc = json.loads(path.read_text(encoding="utf-8"))
    current_release = doc.get("current_release")
    if current_release and project_release is not None:
        release_path = (path.parent / current_release).resolve()
        if release_path.is_file():
            projected = project_release(json.loads(release_path.read_text(encoding="utf-8")), doc)
            merged = dict(doc.get("fallback_baselines") or {})
            merged.update(projected.get("baselines") or {})  # release wins over fallback
            doc = {**doc, **projected, "baselines": merged}
        else:
            # Pointer set but the release file is missing — degrade to fallback, never crash.
            doc = {**doc, "baselines": doc.get("fallback_baselines") or {}}
    return doc


def resolve_run_dir(run_dir: str | None, data_dir: str | Path) -> Path:
    """Return the explicit ``--run-dir`` or the latest eval-results run; echo + ``exit 2`` if none."""
    rd = Path(run_dir) if run_dir else _gate._latest_run_dir(Path(data_dir))
    if rd is None:
        click.echo(json.dumps(
            {"exit_code": 2, "error": "no eval-results run with summary.json"}, indent=2), err=True)
        sys.exit(2)
    return rd


def build_summary(report: dict, summary_fields: tuple[str, ...]) -> dict:
    """The compact gate summary echoed to stderr — pure, so it is unit-testable.

    ``summary_fields`` are the extra report keys a family surfaces beyond exit_code/dataset/checks
    (relevance+leak: ``current``/``baseline``/``floor``; perf: ``mode``).
    """
    return {
        "exit_code": report["exit_code"],
        "dataset": report.get("dataset"),
        **{k: report.get(k) for k in summary_fields},
        "checks": {c["name"]: c["status"] for c in report.get("checks", [])},
    }


def finalize_report(
    report: dict,
    *,
    run_dir: Path,
    baselines_path: str | Path,
    report_out: str | None,
    summary_fields: tuple[str, ...] = (),
) -> None:
    """Attach provenance, optionally write the full report, echo the compact summary, ``sys.exit``."""
    report["run_dir"] = str(run_dir)
    report["baselines_path"] = str(baselines_path)
    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False), encoding="utf-8")
    click.echo(json.dumps(build_summary(report, summary_fields), indent=2), err=True)
    sys.exit(report["exit_code"])


def run_gate(
    *,
    baselines_path: str | Path,
    data_dir: str | Path,
    run_dir: str | None,
    dataset: str,
    read_inputs: Callable[[Path], Any],
    evaluate: Callable[..., dict],
    project_release: Callable[[dict, dict], dict] | None = None,
    report_out: str | None = None,
    summary_fields: tuple[str, ...] = (),
) -> None:
    """Compose the shared flow for a SIMPLE gate (no family-specific guards / --update-baseline):
    load baselines → locate run → ``read_inputs(run_dir)`` → ``evaluate(baselines_doc, *inputs, dataset)``
    → finalize. Gates with extra steps (perf's dataset guard + --update-baseline) call the helpers
    directly instead. ``read_inputs`` returns a tuple spliced as positional args before ``dataset``.
    """
    baselines_doc = load_baselines_doc(baselines_path, project_release=project_release)
    rd = resolve_run_dir(run_dir, data_dir)
    inputs = read_inputs(rd)
    args = inputs if isinstance(inputs, tuple) else (inputs,)
    report = evaluate(baselines_doc, *args, dataset)
    finalize_report(report, run_dir=rd, baselines_path=baselines_path,
                    report_out=report_out, summary_fields=summary_fields)
