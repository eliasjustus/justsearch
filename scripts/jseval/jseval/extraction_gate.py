"""Retrieval-aware extraction gate (tempdoc 580 Track D / F-009).

Decides whether to adopt a candidate VLM extraction profile (e.g. PaddleOCR-VL-1.6)
over the baseline (Qwen-VL) by comparing the **downstream retrieval nDCG@10** of two
eval runs over the *same* extraction-exercising corpus — one indexed with each profile.

This is the §14.4 lesson made mechanical. The in-window evidence (InduOCRBench,
arXiv 2605.00911) is that *high OCR accuracy does not necessarily translate into strong
downstream RAG performance* — "structural and semantic errors can cause substantial
retrieval failures even when WER/CER remains low." So the **gate metric is downstream
nDCG, NOT the OCR word-overlap** that :mod:`jseval.extract_eval` measures. The OCR
overlap is accepted here only as an *informational* signal, and the gate explicitly
flags the case where it **disagrees** with nDCG (the exact InduOCRBench failure: OCR
score up, retrieval down) — the trap a naive "ship on the OCR leaderboard number"
pilot would fall into.

The verdict is conservative because an extraction swap is expensive (new model
download + a full reindex): adopt ONLY on a clear downstream win, hold on neutral.

Exit codes (see :func:`jseval.cli.cmd_extraction_gate`):

- 0 — ``ship``: candidate improves downstream nDCG@10 by >= ``min_improvement_abs``
  on the primary mode and does not regress any guard mode.
- 1 — ``reject-regression``: candidate regresses downstream nDCG@10 beyond tolerance
  on any checked mode.
- 2 — data problem (a run summary is missing nDCG@10 for a checked mode).
- 3 — ``neutral-hold``: no regression, but no clear win — don't pay the swap cost.

The core :func:`evaluate` is a pure function over already-parsed dicts so it is
unit-testable without a live eval run.
"""

from __future__ import annotations

from typing import Any, Optional

# Reuse the ONE wire-path reader so the nDCG@10 summary location stays single-sourced
# with the relevance ratchet (anti-drift: a schema change moves both gates together).
from .relevance_gate import _ndcg_for_mode

# Below this absolute magnitude an OCR-overlap delta is treated as "flat" and cannot
# disagree with nDCG (avoids flagging noise as the InduOCRBench divergence).
_OCR_NOISE_FLOOR = 0.01


def evaluate(
    baseline_summary: dict,
    candidate_summary: dict,
    mode: str,
    *,
    guard_modes: Optional[list[str]] = None,
    min_improvement_abs: float = 0.005,
    regression_tol_abs: float = 0.005,
    ocr_overlap: Optional[dict] = None,
) -> dict:
    """Compare a candidate extraction profile's downstream nDCG@10 against the baseline.

    :param baseline_summary: parsed ``summary.json`` from the baseline-profile run.
    :param candidate_summary: parsed ``summary.json`` from the candidate-profile run.
    :param mode: the primary retrieval mode whose nDCG@10 decides the ship verdict.
    :param guard_modes: extra modes that must merely not regress (default: none).
    :param min_improvement_abs: minimum nDCG@10 gain on ``mode`` to justify the swap.
    :param regression_tol_abs: a drop larger than this on any checked mode is a rejection.
    :param ocr_overlap: optional ``{"baseline": float, "candidate": float}`` word-overlap
        from :mod:`jseval.extract_eval`. INFORMATIONAL ONLY — never decides the verdict;
        used to surface the §14.4 OCR-vs-retrieval divergence.
    :returns: a report dict with ``verdict``, ``exit_code`` and ``checks``.
    """
    report: dict = {
        "mode": mode,
        "min_improvement_abs": min_improvement_abs,
        "regression_tol_abs": regression_tol_abs,
        "checks": [],
        "exit_code": 0,
    }

    base = _ndcg_for_mode(baseline_summary, mode)
    cand = _ndcg_for_mode(candidate_summary, mode)
    if not isinstance(base, (int, float)) or not isinstance(cand, (int, float)):
        report["checks"].append({
            "name": "primary-metric-present",
            "status": "fail",
            "detail": f"baseline or candidate summary missing nDCG@10 for mode '{mode}'",
        })
        report["verdict"] = "data-missing"
        report["exit_code"] = 2
        return report

    delta = float(cand) - float(base)
    report["baseline_ndcg"] = float(base)
    report["candidate_ndcg"] = float(cand)
    report["delta"] = round(delta, 6)

    # Guard modes: any regression beyond tolerance on a checked mode is a hard reject.
    regressed_modes: list[str] = []
    for gm in [mode, *(guard_modes or [])]:
        gb = _ndcg_for_mode(baseline_summary, gm)
        gc = _ndcg_for_mode(candidate_summary, gm)
        if not isinstance(gb, (int, float)) or not isinstance(gc, (int, float)):
            report["checks"].append({
                "name": f"guard-metric-present:{gm}",
                "status": "fail",
                "detail": f"summary missing nDCG@10 for guard mode '{gm}'",
            })
            report["verdict"] = "data-missing"
            report["exit_code"] = 2
            return report
        gdelta = float(gc) - float(gb)
        if gdelta < -regression_tol_abs:
            regressed_modes.append(gm)
        report["checks"].append({
            "name": f"no-regression:{gm}",
            "status": "fail" if gdelta < -regression_tol_abs else "ok",
            "detail": f"baseline={gb:.4f} candidate={gc:.4f} delta={gdelta:+.4f}",
        })

    if regressed_modes:
        report["verdict"] = "reject-regression"
        report["exit_code"] = 1
    elif delta >= min_improvement_abs:
        report["verdict"] = "ship"
        report["exit_code"] = 0
    else:
        report["verdict"] = "neutral-hold"
        report["exit_code"] = 3

    report["checks"].append({
        "name": "primary-improvement",
        "status": "ok" if report["verdict"] == "ship" else "info",
        "detail": (
            f"delta={delta:+.4f} on '{mode}' "
            f"(min_improvement={min_improvement_abs}); verdict={report['verdict']}"
        ),
    })

    # §14.4 / InduOCRBench: the OCR word-overlap is informational. The gate metric is
    # downstream nDCG. Surface — and flag — the case where the two DISAGREE.
    if ocr_overlap and isinstance(ocr_overlap.get("baseline"), (int, float)) \
            and isinstance(ocr_overlap.get("candidate"), (int, float)):
        ocr_delta = float(ocr_overlap["candidate"]) - float(ocr_overlap["baseline"])
        ocr_meaningful = abs(ocr_delta) >= _OCR_NOISE_FLOOR
        ndcg_meaningful = abs(delta) >= regression_tol_abs
        disagree = (
            ocr_meaningful
            and ndcg_meaningful
            and (ocr_delta > 0) != (delta > 0)
        )
        report["ocr_overlap"] = {
            "baseline": float(ocr_overlap["baseline"]),
            "candidate": float(ocr_overlap["candidate"]),
            "delta": round(ocr_delta, 6),
            "agrees_with_ndcg": not disagree,
        }
        report["checks"].append({
            "name": "ocr-overlap-is-informational",
            "status": "warn" if disagree else "info",
            "detail": (
                "OCR word-overlap moved OPPOSITE to downstream nDCG "
                f"(ocr_delta={ocr_delta:+.4f}, ndcg_delta={delta:+.4f}) — the InduOCRBench "
                "trap. The gate verdict follows nDCG, NOT the OCR number (§14.4)."
                if disagree
                else f"ocr_delta={ocr_delta:+.4f} (informational; verdict follows nDCG)"
            ),
        })

    return report
