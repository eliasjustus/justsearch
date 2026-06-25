"""Tests for rag_eval.py — RAG quality evaluation."""

from __future__ import annotations

from jseval.rag_eval import RAG_METRICS, diff_rag_eval, format_console


class TestRagMetrics:
    def test_has_seven_metrics(self):
        assert len(RAG_METRICS) == 7

    def test_thresholds_valid(self):
        for metric, direction, threshold in RAG_METRICS:
            assert direction in ("min", "max")
            assert 0 < threshold


class TestDiffRagEval:
    def test_all_pass(self):
        baseline = {
            "fact_coverage_mean": 0.8, "faithfulness_mean": 0.9,
            "retrieval_recall_mean": 0.95, "answer_similarity_mean": 0.85,
            "forbidden_fact_rate_mean": 0.05, "citation_precision_mean": 0.9,
            "citation_recall_mean": 0.88,
        }
        candidate = dict(baseline)  # identical
        decision = diff_rag_eval(baseline, candidate)
        assert decision["gate_status"] == "pass"
        assert decision["regression_count"] == 0

    def test_one_regression(self):
        baseline = {
            "fact_coverage_mean": 0.8, "faithfulness_mean": 0.9,
            "retrieval_recall_mean": 0.95, "answer_similarity_mean": 0.85,
            "forbidden_fact_rate_mean": 0.05, "citation_precision_mean": 0.9,
            "citation_recall_mean": 0.88,
        }
        candidate = dict(baseline)
        candidate["fact_coverage_mean"] = 0.5  # big regression
        decision = diff_rag_eval(baseline, candidate)
        assert decision["gate_status"] == "fail"
        assert decision["regression_count"] >= 1

    def test_forbidden_fact_rate_regression(self):
        baseline = {"forbidden_fact_rate_mean": 0.05}
        candidate = {"forbidden_fact_rate_mean": 0.15}  # 3x, above 2.0 max
        decision = diff_rag_eval(baseline, candidate)
        regressed = [c for c in decision["comparisons"]
                     if c.get("metric") == "forbidden_fact_rate_mean"
                     and c["status"] == "REGRESSED"]
        assert len(regressed) == 1

    def test_missing_metric_skipped(self):
        decision = diff_rag_eval({"fact_coverage_mean": 0.8}, {})
        skipped = [c for c in decision["comparisons"] if c["status"] == "SKIP"]
        assert len(skipped) >= 1


class TestFormatConsole:
    def test_output(self):
        decision = {
            "gate_status": "pass",
            "comparisons": [
                {"metric": "fact_coverage_mean", "status": "OK", "ratio": 1.0},
            ],
        }
        output = format_console(decision)
        assert "PASS" in output
        assert "fact_coverage_mean" in output
