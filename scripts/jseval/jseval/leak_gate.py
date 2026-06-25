"""Recall-leak ratchet (tempdoc 636 / register D-005).

The recall-survival sibling of :mod:`jseval.relevance_gate`. Where the
relevance ratchet fails when a corpus's *nDCG@10 mean* drops below a pinned
floor, this fails when a corpus's **recall-leak rate** rises above a pinned
*ceiling* — i.e. when a pipeline change starts dropping correct documents that
a retrieval leg already found, before the judge can rank them (the tempdoc 636
"cascade-leak"; the literature's *bounded recall problem*).

It reads the cross-mode ``staged_recall_accounting`` projection
(``<run_dir>/projections/staged_recall_accounting.json``) rather than the
per-mode ``aggregate_metrics`` — the leak rate is inherently cross-mode (it
compares each leg vs the final list), so it cannot live in the per-mode cohort
envelope; a focused gate over the projection output is the clean fit.

``evaluate`` is a pure function over already-parsed dicts so it is
unit-testable without a live eval run.

Exit codes (mirroring :mod:`jseval.relevance_gate`):

- 0 — no regression (or the dataset is not pinned → does not gate).
- 1 — regression: current ``leak_rate`` > (pinned ceiling + tolerance).
- 2 — data problem (projection missing / status != ok / ``leak_rate`` absent).
"""

from __future__ import annotations

from typing import Any

from . import metric_families as _mf

# The leak family is registered in the metric-family registry (tempdoc 640) as a cross-mode
# *projection* metric — registered so the family concept is unified across all three gates, but its
# gate stays projection-sourced (NOT migrated into the per-mode/per-run record; the leak-fold finding).
DEFAULT_TOLERANCE_ABS = _mf.BY_NAME["leak"].tolerance_abs


def _leak_rate(projection_doc: dict) -> Any:
    """Read ``aggregate.leak_rate`` from a staged_recall_accounting projection."""
    if (projection_doc or {}).get("status") != "ok":
        return None
    return (projection_doc.get("aggregate") or {}).get("leak_rate")


def derive_baselines(
    projections_by_dataset: dict,
    *,
    tolerance_default_abs: float = DEFAULT_TOLERANCE_ABS,
    per_corpus_tolerance: dict | None = None,
) -> dict:
    """Derive the leak-gate baselines dict from measured projections.

    The recall-survival sibling of :func:`relevance_gate.project_release_to_baselines`
    (tempdoc 623's anti-fork discipline): a corpus's leak *ceiling* is its **measured**
    ``leak_rate`` in a multi-mode run, never a hand-typed number — so there is no table
    of values to drift. The measured rate is the ``leak_rate_max`` baseline; ``evaluate``
    adds ``tolerance_abs`` on top (limit = measured + tolerance), so a future change only
    fails when it raises the leak rate *beyond* the tolerated slack (generous by default,
    to ignore GPU-embedding noise — the §Review-fix-#2 lesson).

    :param projections_by_dataset: ``{<dataset>: parsed staged_recall_accounting.json}``.
    :returns: the ``leak-gate-baseline.v1`` shape :func:`evaluate` already consumes.
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    baselines: dict[str, dict] = {}
    for dataset, proj in (projections_by_dataset or {}).items():
        measured = _leak_rate(proj)
        if not isinstance(measured, (int, float)):
            continue  # skip non-ok / missing projections (mirrors the relevance ratchet)
        baselines[dataset] = {
            "leak_rate_max": float(measured),
            "tolerance_abs": per_corpus_tolerance.get(dataset, tolerance_default_abs),
            "src": "measured from staged_recall_accounting projection",
        }
    return {
        "schema": "leak-gate-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "derived_from_runs": True,
        "baselines": baselines,
    }


def evaluate(baselines: dict, projection_doc: dict, dataset: str) -> dict:
    """Compare a run's leak_rate against the pinned ceiling for ``dataset``.

    :param baselines: ``{"baselines": {<dataset>: {leak_rate_max, tolerance_abs}}}``.
    :param projection_doc: parsed ``staged_recall_accounting.json``.
    :param dataset: the dataset slug (e.g. ``mixed/enron-qa``).
    :returns: a report dict with ``exit_code`` and ``checks``.
    """
    report: dict = {"dataset": dataset, "checks": [], "exit_code": 0}

    pinned = (baselines.get("baselines") or {}).get(dataset)
    if pinned is None:
        report["checks"].append({
            "name": "baseline-pinned",
            "status": "skip",
            "detail": f"no pinned leak ceiling for {dataset}; not gated",
        })
        return report  # un-pinned datasets do not gate (exit 0)

    ceiling = pinned.get("leak_rate_max")
    tolerance = pinned.get(
        "tolerance_abs", baselines.get("tolerance_default_abs", DEFAULT_TOLERANCE_ABS)
    )
    report["mode"] = "staged_recall_accounting"
    report["baseline"] = ceiling
    report["tolerance_abs"] = tolerance

    # Distinguish a malformed baseline (operator error) from a bad projection
    # (eval-data problem) — both are exit 2, but conflating their messages sent a
    # past debugging round chasing the wrong side.
    if not isinstance(ceiling, (int, float)):
        report["checks"].append({
            "name": "ceiling-valid",
            "status": "fail",
            "detail": f"pinned baseline for {dataset} has no numeric leak_rate_max",
        })
        report["exit_code"] = 2
        return report

    current = _leak_rate(projection_doc)
    if not isinstance(current, (int, float)):
        report["checks"].append({
            "name": "projection-present",
            "status": "fail",
            "detail": "staged_recall_accounting projection missing leak_rate (status != ok?)",
        })
        report["exit_code"] = 2
        return report

    limit = ceiling + tolerance
    regressed = current > limit
    report["current"] = float(current)
    report["floor"] = limit  # the ceiling+tolerance the run must stay at/under
    report["checks"].append({
        "name": "leak-rate-no-regression",
        "status": "fail" if regressed else "ok",
        "detail": (
            f"current={current:.4f} ceiling={ceiling:.4f} "
            f"limit={limit:.4f} (tolerance={tolerance})"
        ),
    })
    if regressed:
        report["exit_code"] = 1
    return report
