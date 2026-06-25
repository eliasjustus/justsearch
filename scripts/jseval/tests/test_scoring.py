"""Tests for scoring.py — ir-measures integration."""

from __future__ import annotations

import ir_measures
from ir_measures import AP, P, R, RR, nDCG

from jseval.scoring import DEFAULT_METRICS, evaluate, evaluate_per_query


def _make_run(results: dict[str, list[tuple[str, float]]]) -> list:
    """Build a ScoredDoc list from {qid: [(doc_id, score), ...]}."""
    return [
        ir_measures.ScoredDoc(qid, did, score)
        for qid, docs in results.items()
        for did, score in docs
    ]


class TestEvaluate:
    def test_perfect_ranking(self):
        qrels = {"q1": {"d1": 1, "d2": 1, "d3": 0}}
        run = _make_run({"q1": [("d1", 3.0), ("d2", 2.0), ("d3", 1.0)]})
        result = evaluate(qrels, run)

        assert "nDCG@10" in result
        # Perfect ranking: relevant docs at top
        assert result["nDCG@10"] == pytest.approx(1.0)

    def test_empty_run(self):
        qrels = {"q1": {"d1": 1}}
        run = []
        result = evaluate(qrels, run)

        assert "nDCG@10" in result
        assert result["nDCG@10"] == pytest.approx(0.0)

    def test_all_default_metrics_present(self):
        qrels = {"q1": {"d1": 1}}
        run = _make_run({"q1": [("d1", 1.0)]})
        result = evaluate(qrels, run)

        expected_keys = {"nDCG@10", "AP@10", "RR@10", "R@10", "P@1"}
        assert expected_keys == set(result.keys())

    def test_custom_metrics(self):
        qrels = {"q1": {"d1": 1}}
        run = _make_run({"q1": [("d1", 1.0)]})
        result = evaluate(qrels, run, metrics=[nDCG @ 5, P @ 3])

        assert "nDCG@5" in result
        assert "P@3" in result
        assert "nDCG@10" not in result

    def test_multiple_queries(self):
        qrels = {
            "q1": {"d1": 1, "d2": 0},
            "q2": {"d3": 1, "d4": 0},
        }
        run = _make_run({
            "q1": [("d1", 2.0), ("d2", 1.0)],
            "q2": [("d4", 2.0), ("d3", 1.0)],  # relevant doc at rank 2
        })
        result = evaluate(qrels, run)

        # q1 is perfect, q2 is imperfect → aggregate < 1.0
        assert 0.0 < result["nDCG@10"] < 1.0


class TestEvaluatePerQuery:
    def test_returns_per_qid(self):
        qrels = {
            "q1": {"d1": 1},
            "q2": {"d2": 1},
        }
        run = _make_run({
            "q1": [("d1", 1.0)],
            "q2": [("d2", 1.0)],
        })
        per_query = evaluate_per_query(qrels, run)

        assert "q1" in per_query
        assert "q2" in per_query
        assert "nDCG@10" in per_query["q1"]
        assert per_query["q1"]["nDCG@10"] == pytest.approx(1.0)

    def test_custom_metrics_per_query(self):
        qrels = {"q1": {"d1": 1}}
        run = _make_run({"q1": [("d1", 1.0)]})
        per_query = evaluate_per_query(qrels, run, metrics=[RR @ 10])

        assert "RR@10" in per_query["q1"]
        assert "nDCG@10" not in per_query["q1"]


# Need pytest for approx
import pytest
