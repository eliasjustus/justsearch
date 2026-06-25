"""Tests for relevance_gate.py — the Q-010 relevance ratchet (tempdoc 580 §4c)."""

from __future__ import annotations

from jseval.relevance_gate import evaluate, project_release_to_baselines

BASELINES = {
    "tolerance_default_abs": 0.02,
    "baselines": {
        "beir/scifact": {"mode": "hybrid", "nDCG@10": 0.758, "tolerance_abs": 0.02},
        "mixed/courtlistener-200": {"mode": "full", "nDCG@10": 0.925, "tolerance_abs": 0.02},
    },
}


def _summary(mode: str, ndcg: float) -> dict:
    return {"per_mode": {mode: {"aggregate_metrics": {"nDCG@10": ndcg}}}}


def test_no_regression_passes():
    report = evaluate(BASELINES, _summary("hybrid", 0.760), "beir/scifact")
    assert report["exit_code"] == 0
    assert report["checks"][0]["status"] == "ok"


def test_within_tolerance_passes():
    # 0.745 is below the 0.758 baseline but above the floor (0.758 - 0.02 = 0.738).
    report = evaluate(BASELINES, _summary("hybrid", 0.745), "beir/scifact")
    assert report["exit_code"] == 0


def test_regression_below_floor_fails():
    # 0.730 < floor 0.738 → regression.
    report = evaluate(BASELINES, _summary("hybrid", 0.730), "beir/scifact")
    assert report["exit_code"] == 1
    assert report["checks"][0]["status"] == "fail"


def test_exactly_at_floor_passes():
    # current == floor is NOT a regression (strict less-than law).
    report = evaluate(BASELINES, _summary("hybrid", 0.738), "beir/scifact")
    assert report["exit_code"] == 0


def test_unpinned_dataset_does_not_gate():
    report = evaluate(BASELINES, _summary("full", 0.10), "mixed/cord19-qddf")
    assert report["exit_code"] == 0
    assert report["checks"][0]["status"] == "skip"


def test_missing_mode_metric_is_data_error():
    # Pinned mode is "full" but the run only has "hybrid".
    report = evaluate(BASELINES, _summary("hybrid", 0.90), "mixed/courtlistener-200")
    assert report["exit_code"] == 2
    assert report["checks"][0]["status"] == "fail"


def test_default_tolerance_used_when_absent():
    baselines = {
        "tolerance_default_abs": 0.05,
        "baselines": {"x/y": {"mode": "full", "nDCG@10": 0.50}},
    }
    # 0.46 is within 0.50 - 0.05 = 0.45 floor → passes on the default tolerance.
    report = evaluate(baselines, _summary("full", 0.46), "x/y")
    assert report["exit_code"] == 0
    assert report["tolerance_abs"] == 0.05


# --- projection from a release object (tempdoc 623 T-5) ---------------------

_RELEASE = {
    "release_id": "rel-test",
    "cohort": {"git_sha": "1b43bbe45f"},
    "measured": {
        "beir/scifact": {"config_mode": "hybrid", "metrics": {"nDCG@10": 0.755}},
        "mixed/courtlistener-200": {"config_mode": "hybrid", "metrics": {"nDCG@10": 0.620}},
        # an extraction-style corpus (no nDCG) must be skipped by this ratchet:
        "ocr/messy": {"config_mode": "hybrid", "metrics": {"WER": 0.08}},
    },
}


def test_projection_shape_matches_evaluate_contract():
    proj = project_release_to_baselines(_RELEASE, tolerance_default_abs=0.02)
    assert proj["projected_from_release"] is True
    b = proj["baselines"]
    assert b["beir/scifact"]["nDCG@10"] == 0.755
    assert b["beir/scifact"]["mode"] == "hybrid"
    assert "rel-test" in b["beir/scifact"]["src"]
    # non-nDCG corpus is not gated by the retrieval ratchet
    assert "ocr/messy" not in b


def test_projection_per_corpus_tolerance_override():
    proj = project_release_to_baselines(
        _RELEASE, tolerance_default_abs=0.02,
        per_corpus_tolerance={"mixed/courtlistener-200": 0.05},
    )
    assert proj["baselines"]["mixed/courtlistener-200"]["tolerance_abs"] == 0.05
    assert proj["baselines"]["beir/scifact"]["tolerance_abs"] == 0.02


def test_projected_floor_drives_evaluate_end_to_end():
    """The whole point: a projected floor gates exactly like a hand-typed one."""
    proj = project_release_to_baselines(_RELEASE, tolerance_default_abs=0.02)
    # courtlistener floor is now the hybrid 0.620 (re-rooted from 0.925, U2/§C-5).
    ok = evaluate(proj, _summary("hybrid", 0.615), "mixed/courtlistener-200")
    assert ok["exit_code"] == 0  # 0.615 ≥ 0.620 - 0.02 = 0.600
    bad = evaluate(proj, _summary("hybrid", 0.590), "mixed/courtlistener-200")
    assert bad["exit_code"] == 1  # 0.590 < 0.600 floor → regression
