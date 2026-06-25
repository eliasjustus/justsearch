"""Tests for extraction_gate.py — the retrieval-aware extraction gate (tempdoc 580 Track D / F-009).

The load-bearing property: the verdict follows DOWNSTREAM nDCG, never the OCR word-overlap.
``test_ocr_up_but_ndcg_down_*`` are the InduOCRBench trap (§14.4) — a candidate that scores
higher on OCR accuracy but lower on retrieval MUST be rejected.
"""

from __future__ import annotations

from jseval.extraction_gate import evaluate


def _summary(metrics: dict) -> dict:
    """metrics: {mode: ndcg} -> a summary.json-shaped dict."""
    return {
        "per_mode": {m: {"aggregate_metrics": {"nDCG@10": v}} for m, v in metrics.items()}
    }


def test_clear_downstream_win_ships():
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.730})  # +0.030 >> min_improvement 0.005
    report = evaluate(base, cand, "hybrid")
    assert report["verdict"] == "ship"
    assert report["exit_code"] == 0
    assert report["delta"] == 0.03


def test_regression_is_rejected():
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.680})  # -0.020 < -regression_tol 0.005
    report = evaluate(base, cand, "hybrid")
    assert report["verdict"] == "reject-regression"
    assert report["exit_code"] == 1


def test_neutral_change_holds():
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.702})  # +0.002 — within band, no clear win
    report = evaluate(base, cand, "hybrid")
    assert report["verdict"] == "neutral-hold"
    assert report["exit_code"] == 3


def test_guard_mode_regression_rejects_even_if_primary_wins():
    base = _summary({"hybrid": 0.700, "full": 0.800})
    cand = _summary({"hybrid": 0.730, "full": 0.760})  # primary up, guard down -0.040
    report = evaluate(base, cand, "hybrid", guard_modes=["full"])
    assert report["verdict"] == "reject-regression"
    assert report["exit_code"] == 1
    # the guard-mode check is the one that failed
    assert any(c["name"] == "no-regression:full" and c["status"] == "fail"
               for c in report["checks"])


def test_missing_primary_metric_is_data_error():
    base = _summary({"hybrid": 0.700})
    cand = _summary({"full": 0.730})  # candidate lacks the primary mode
    report = evaluate(base, cand, "hybrid")
    assert report["verdict"] == "data-missing"
    assert report["exit_code"] == 2


def test_ocr_up_but_ndcg_down_is_rejected_the_induocr_trap():
    # The InduOCRBench phenomenon: OCR accuracy improves but retrieval degrades.
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.680})
    report = evaluate(base, cand, "hybrid",
                      ocr_overlap={"baseline": 0.80, "candidate": 0.92})
    # Verdict follows nDCG — rejected — DESPITE the +0.12 OCR-accuracy gain.
    assert report["verdict"] == "reject-regression"
    assert report["exit_code"] == 1
    # And the divergence is flagged as a warning.
    assert report["ocr_overlap"]["agrees_with_ndcg"] is False
    assert any(c["name"] == "ocr-overlap-is-informational" and c["status"] == "warn"
               for c in report["checks"])


def test_ocr_and_ndcg_agree_is_informational_only():
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.730})
    report = evaluate(base, cand, "hybrid",
                      ocr_overlap={"baseline": 0.80, "candidate": 0.88})
    assert report["verdict"] == "ship"
    assert report["ocr_overlap"]["agrees_with_ndcg"] is True
    assert any(c["name"] == "ocr-overlap-is-informational" and c["status"] == "info"
               for c in report["checks"])


def test_ocr_overlap_never_overrides_a_neutral_ndcg():
    # A big OCR gain must NOT manufacture a ship when nDCG is flat.
    base = _summary({"hybrid": 0.700})
    cand = _summary({"hybrid": 0.701})
    report = evaluate(base, cand, "hybrid",
                      ocr_overlap={"baseline": 0.70, "candidate": 0.95})
    assert report["verdict"] == "neutral-hold"
    assert report["exit_code"] == 3
