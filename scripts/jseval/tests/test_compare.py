"""Tests for compare_runs.py — statistical comparison and per-query diff."""

from __future__ import annotations

import numpy as np
import pytest

from jseval.compare_runs import (
    _bootstrap_ci,
    compare,
    compare_stage_decomposition,
    per_query_diff,
)


def _summary_with_stages(
    mode: str, stages: dict[str, float], total_p50: float | None = None
) -> dict:
    pm: dict = {"stage_timing_stats": {k: {"p50": v} for k, v in stages.items()}}
    if total_p50 is not None:
        pm["latency_stats"] = {"p50_ms": total_p50}
    return {"per_mode": {mode: pm}}


class TestStageDecomposition:
    """Tempdoc 647: the which-stage-moved query-latency decomposition diff."""

    def test_diffs_and_attributes_primary_mover(self):
        a = _summary_with_stages(
            "hybrid", {"retrieval_ms": 4, "cross_encoder_ms": 150, "unaccounted_ms": 20}, 174)
        b = _summary_with_stages(
            "hybrid", {"retrieval_ms": 5, "cross_encoder_ms": 170, "unaccounted_ms": 22}, 197)
        d = compare_stage_decomposition(a, b, "hybrid")
        assert d["stages"]["cross_encoder_ms"]["delta"] == 20
        assert d["stages"]["retrieval_ms"]["delta"] == 1
        # the cross-encoder is the largest single-stage shift → the primary mover
        assert d["attribution"]["primary_mover"] == "cross_encoder_ms"
        assert d["attribution"]["mover_delta_ms"] == 20
        # total is the run-level p50 delta, reported separately (not a sum of stage deltas)
        assert d["attribution"]["total_p50_delta_ms"] == 23

    def test_regression_flag_at_band(self):
        a = _summary_with_stages("hybrid", {"cross_encoder_ms": 100})
        b = _summary_with_stages("hybrid", {"cross_encoder_ms": 130})  # 1.30x > 1.10 band
        d = compare_stage_decomposition(a, b, "hybrid")
        assert d["stages"]["cross_encoder_ms"]["regressed"] is True

    def test_missing_stage_in_one_run_is_zero_not_crash(self):
        a = _summary_with_stages("hybrid", {"cross_encoder_ms": 150})
        b = _summary_with_stages("hybrid", {"cross_encoder_ms": 150, "retrieval_ms": 5})
        d = compare_stage_decomposition(a, b, "hybrid")
        assert d["stages"]["retrieval_ms"]["a"] == 0 and d["stages"]["retrieval_ms"]["b"] == 5

    def test_empty_when_no_stage_data(self):
        a = {"per_mode": {"hybrid": {}}}
        assert compare_stage_decomposition(a, a, "hybrid") == {}

    def test_empty_when_mode_unresolved(self):
        a = _summary_with_stages("hybrid", {"cross_encoder_ms": 150})
        assert compare_stage_decomposition(a, a, None) == {}

    def test_old_format_without_unaccounted_tolerated(self):
        # runs predating the tempdoc-647 materialization carry only retrieval + CE, no unaccounted_ms
        a = _summary_with_stages("hybrid", {"retrieval_ms": 4, "cross_encoder_ms": 150})
        b = _summary_with_stages("hybrid", {"retrieval_ms": 4, "cross_encoder_ms": 155})
        d = compare_stage_decomposition(a, b, "hybrid")
        assert "unaccounted_ms" not in d["stages"]
        assert d["attribution"]["primary_mover"] == "cross_encoder_ms"

    def test_identical_runs_have_no_mover(self):
        a = _summary_with_stages("hybrid", {"cross_encoder_ms": 150, "retrieval_ms": 4}, 170)
        d = compare_stage_decomposition(a, a, "hybrid")
        assert d["attribution"]["primary_mover"] is None
        assert d["attribution"]["total_p50_delta_ms"] == 0


def _make_run(per_query: dict[str, dict[str, float]]) -> dict:
    return {"per_query_metrics": per_query}


class TestCompare:
    def test_identical_runs(self):
        pq = {"q1": {"nDCG@10": 0.8}, "q2": {"nDCG@10": 0.6}}
        qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}}
        result = compare(_make_run(pq), _make_run(pq), qrels, ["nDCG@10"])

        assert result["nDCG@10"]["delta"] == pytest.approx(0.0)
        # p-value should be 1.0 or NaN for identical data (std=0)
        assert result["nDCG@10"]["p_value"] == pytest.approx(1.0)

    def test_better_run_b(self):
        pq_a = {"q1": {"nDCG@10": 0.5}, "q2": {"nDCG@10": 0.4}, "q3": {"nDCG@10": 0.3}}
        pq_b = {"q1": {"nDCG@10": 0.8}, "q2": {"nDCG@10": 0.7}, "q3": {"nDCG@10": 0.6}}
        qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}, "q3": {"d3": 1}}
        result = compare(_make_run(pq_a), _make_run(pq_b), qrels, ["nDCG@10"])

        assert result["nDCG@10"]["delta"] == pytest.approx(0.3)
        assert result["nDCG@10"]["p_value"] < 0.05  # significant improvement

    def test_cohens_d_z(self):
        # Large consistent improvement → large effect size
        pq_a = {f"q{i}": {"nDCG@10": 0.3} for i in range(20)}
        pq_b = {f"q{i}": {"nDCG@10": 0.8} for i in range(20)}
        qrels = {f"q{i}": {f"d{i}": 1} for i in range(20)}
        result = compare(_make_run(pq_a), _make_run(pq_b), qrels, ["nDCG@10"])

        # All deltas are 0.5 with std=0, so d_z should be 0 (or very large)
        # Actually std=0 → d_z=0 by our guard clause
        # Let's use slight variance instead
        pq_b2 = {f"q{i}": {"nDCG@10": 0.8 + (i % 3) * 0.01} for i in range(20)}
        result2 = compare(_make_run(pq_a), _make_run(pq_b2), qrels, ["nDCG@10"])
        assert result2["nDCG@10"]["cohens_d_z"] > 1.0  # large effect

    def test_all_default_metrics(self):
        pq = {"q1": {"nDCG@10": 0.8, "AP@10": 0.6, "RR@10": 1.0, "R@10": 0.5, "P@1": 1.0}}
        qrels = {"q1": {"d1": 1}}
        result = compare(_make_run(pq), _make_run(pq), qrels)
        assert len(result) == 5


class TestBootstrapCi:
    def test_deterministic(self):
        deltas = np.array([0.1, 0.2, 0.15, 0.18, 0.12])
        ci1 = _bootstrap_ci(deltas)
        ci2 = _bootstrap_ci(deltas)
        assert ci1 == ci2  # deterministic seed

    def test_positive_deltas(self):
        deltas = np.array([0.1, 0.2, 0.15, 0.18, 0.12, 0.11, 0.19, 0.14])
        low, high = _bootstrap_ci(deltas)
        assert low > 0  # CI should be above zero
        assert high > low

    def test_single_value(self):
        deltas = np.array([0.5])
        low, high = _bootstrap_ci(deltas)
        assert low == pytest.approx(0.5)
        assert high == pytest.approx(0.5)


class TestPerQueryDiff:
    def test_shows_rank_changes(self):
        a = [{"qid": "q1", "ndcgAtK": 0.85, "predictedDocIds": ["d1", "d2", "d3"]}]
        b = [{"qid": "q1", "ndcgAtK": 0.42, "predictedDocIds": ["d3", "d2", "d1"]}]
        qrels = {"q1": {"d1": 2, "d2": 1, "d3": 0}}

        diffs = per_query_diff(a, b, qrels, threshold=0.01)
        assert len(diffs) == 1
        assert diffs[0]["qid"] == "q1"
        assert diffs[0]["delta"] < 0

        # d1 (relevant, rel=2) moved from rank 1 to rank 3
        changes = diffs[0]["rank_changes"]
        d1_change = next(c for c in changes if c["doc_id"] == "d1")
        assert d1_change["rank_a"] == 1
        assert d1_change["rank_b"] == 3

    def test_threshold_filters(self):
        a = [{"qid": "q1", "ndcgAtK": 0.85, "predictedDocIds": ["d1"]}]
        b = [{"qid": "q1", "ndcgAtK": 0.84, "predictedDocIds": ["d1"]}]  # tiny drop
        qrels = {"q1": {"d1": 1}}

        diffs = per_query_diff(a, b, qrels, threshold=0.05)
        assert len(diffs) == 0  # delta=-0.01 is within threshold

    def test_sorted_by_worst_regression(self):
        a = [
            {"qid": "q1", "ndcgAtK": 0.80, "predictedDocIds": ["d1"]},
            {"qid": "q2", "ndcgAtK": 0.90, "predictedDocIds": ["d2"]},
        ]
        b = [
            {"qid": "q1", "ndcgAtK": 0.70, "predictedDocIds": ["d1"]},  # -0.10
            {"qid": "q2", "ndcgAtK": 0.50, "predictedDocIds": ["d2"]},  # -0.40
        ]
        qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}}

        diffs = per_query_diff(a, b, qrels, threshold=0.01)
        assert len(diffs) == 2
        assert diffs[0]["qid"] == "q2"  # worst regression first
