"""Recall-completeness ratchet (tempdoc 701 / register D-005 recall-survival sibling).

The floor-shaped twin of :mod:`jseval.leak_gate`. Where the leak ratchet fails when a corpus's
**recall-leak rate** rises above a pinned *ceiling*, this fails when a corpus's
**leg_union_recall** — the fraction of judged-relevant documents that ANY retrieval leg
surfaced before fusion/ranking, i.e. representation-completeness — drops below a pinned
*floor*. Both read the SAME cross-mode ``staged_recall_accounting`` projection
(``<run_dir>/projections/staged_recall_accounting.json``); they simply move in opposite
directions (higher union-recall is better, so it gates like :mod:`jseval.relevance_gate`'s
nDCG@10 floor, not like leak's ceiling).

``evaluate`` is a pure function over already-parsed dicts so it is unit-testable without a
live eval run.

Exit codes (mirroring :mod:`jseval.leak_gate` / :mod:`jseval.relevance_gate`):

- 0 — no regression (or the dataset is not pinned → does not gate).
- 1 — regression: current ``leg_union_recall`` < (pinned floor - tolerance).
- 2 — data problem (projection missing / status != ok / ``leg_union_recall`` absent).
"""

from __future__ import annotations

from typing import Any

from . import metric_families as _mf

# The union-recall family is registered in the metric-family registry (tempdoc 640/701) as a
# cross-mode *projection* metric — registered so the family concept is unified across all the
# ratchet gates, but (like leak) its gate stays projection-sourced (NOT migrated into the
# per-mode/per-run record).
DEFAULT_TOLERANCE_ABS = _mf.BY_NAME["union-recall"].tolerance_abs


def _union_recall(projection_doc: dict) -> Any:
    """Read ``aggregate.leg_union_recall`` from a staged_recall_accounting projection."""
    if (projection_doc or {}).get("status") != "ok":
        return None
    return (projection_doc.get("aggregate") or {}).get("leg_union_recall")


def project_release_to_baselines(
    release: dict,
    *,
    tolerance_default_abs: float = DEFAULT_TOLERANCE_ABS,
    per_corpus_tolerance: dict | None = None,
) -> dict:
    """Project a ``release.v1`` object's optional ``union_recall`` section into baseline floors.

    The union-recall-gate twin of :func:`leak_gate.project_release_to_baselines` (tempdoc 683
    pattern): when the canonical release carries per-corpus measured union-recall rates
    (composed from runs with ``staged_recall_accounting`` projections), the floor projects live
    from the release; a corpus absent from the section is simply not projected (the pointer
    file's ``fallback_baselines`` then governs via :func:`ratchet_kernel.load_baselines_doc`'s
    merge).
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    cohort = release.get("cohort") or {}
    src_tag = release.get("release_id") or (cohort.get("git_sha") or "")[:10]
    baselines: dict[str, dict] = {}
    for dataset, entry in (release.get("union_recall") or {}).items():
        rate = (entry or {}).get("leg_union_recall")
        if not isinstance(rate, (int, float)):
            continue
        baselines[dataset] = {
            "leg_union_recall_min": float(rate),
            "tolerance_abs": per_corpus_tolerance.get(dataset, tolerance_default_abs),
            "src": f"projected from release {src_tag}".strip(),
        }
    return {
        "schema": "union-recall-gate-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "projected_from_release": True,
        "baselines": baselines,
    }


def derive_baselines(
    projections_by_dataset: dict,
    *,
    tolerance_default_abs: float = DEFAULT_TOLERANCE_ABS,
    per_corpus_tolerance: dict | None = None,
) -> dict:
    """Derive the union-recall-gate baselines dict from measured projections.

    A corpus's completeness *floor* is its **measured** ``leg_union_recall`` in a multi-mode
    run, never a hand-typed number — so there is no table of values to drift (mirrors
    :func:`leak_gate.derive_baselines`'s anti-fork discipline, tempdoc 623). The measured rate
    is the ``leg_union_recall_min`` baseline; ``evaluate`` subtracts ``tolerance_abs`` from it
    (limit = measured - tolerance), so a future change only fails when it drops union-recall
    *below* the tolerated slack.

    :param projections_by_dataset: ``{<dataset>: parsed staged_recall_accounting.json}``.
    :returns: the ``union-recall-gate-baseline.v1`` shape :func:`evaluate` already consumes.
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    baselines: dict[str, dict] = {}
    for dataset, proj in (projections_by_dataset or {}).items():
        measured = _union_recall(proj)
        if not isinstance(measured, (int, float)):
            continue  # skip non-ok / missing projections (mirrors the leak/relevance ratchets)
        baselines[dataset] = {
            "leg_union_recall_min": float(measured),
            "tolerance_abs": per_corpus_tolerance.get(dataset, tolerance_default_abs),
            "src": "measured from staged_recall_accounting projection",
        }
    return {
        "schema": "union-recall-gate-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "derived_from_runs": True,
        "baselines": baselines,
    }


def evaluate(baselines: dict, projection_doc: dict, dataset: str) -> dict:
    """Compare a run's leg_union_recall against the pinned floor for ``dataset``.

    :param baselines: ``{"baselines": {<dataset>: {leg_union_recall_min, tolerance_abs}}}``.
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
            "detail": f"no pinned union-recall floor for {dataset}; not gated",
        })
        return report  # un-pinned datasets do not gate (exit 0)

    floor_pin = pinned.get("leg_union_recall_min")
    tolerance = pinned.get(
        "tolerance_abs", baselines.get("tolerance_default_abs", DEFAULT_TOLERANCE_ABS)
    )
    report["mode"] = "staged_recall_accounting"
    report["baseline"] = floor_pin
    report["tolerance_abs"] = tolerance

    # Distinguish a malformed baseline (operator error) from a bad projection (eval-data
    # problem) — both are exit 2, but conflating their messages sent a past leak-gate debugging
    # round chasing the wrong side (mirrored here deliberately).
    if not isinstance(floor_pin, (int, float)):
        report["checks"].append({
            "name": "floor-valid",
            "status": "fail",
            "detail": f"pinned baseline for {dataset} has no numeric leg_union_recall_min",
        })
        report["exit_code"] = 2
        return report

    current = _union_recall(projection_doc)
    if not isinstance(current, (int, float)):
        report["checks"].append({
            "name": "projection-present",
            "status": "fail",
            "detail": "staged_recall_accounting projection missing leg_union_recall (status != ok?)",
        })
        report["exit_code"] = 2
        return report

    floor = floor_pin - tolerance
    regressed = current < floor
    report["current"] = float(current)
    report["floor"] = floor
    report["checks"].append({
        "name": "union-recall-no-regression",
        "status": "fail" if regressed else "ok",
        "detail": (
            f"current={current:.4f} baseline={floor_pin:.4f} "
            f"floor={floor:.4f} (tolerance={tolerance})"
        ),
    })
    if regressed:
        report["exit_code"] = 1
    return report
