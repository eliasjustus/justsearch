"""Tests for shadow_eval (tempdoc 400 LR5-b)."""

from __future__ import annotations

import json
from unittest.mock import patch

import pytest

from jseval import shadow_eval
from jseval.shadow_eval import (
    _jaccard,
    _kendall_tau_on_shared,
    _top_k_ids,
    run_shadow,
    write_report,
)


class TestJaccard:
    def test_identical(self):
        assert _jaccard(["a", "b", "c"], ["a", "b", "c"]) == 1.0

    def test_disjoint(self):
        assert _jaccard(["a", "b"], ["c", "d"]) == 0.0

    def test_partial(self):
        # |{a,b} ∩ {b,c}| / |{a,b,c}| = 1/3
        assert abs(_jaccard(["a", "b"], ["b", "c"]) - 1 / 3) < 1e-9

    def test_both_empty(self):
        assert _jaccard([], []) == 1.0

    def test_one_empty(self):
        assert _jaccard(["a"], []) == 0.0


class TestKendallTau:
    def test_identical_rank_returns_one(self):
        assert _kendall_tau_on_shared(["a", "b", "c"], ["a", "b", "c"]) == 1.0

    def test_fully_reversed_returns_minus_one(self):
        assert _kendall_tau_on_shared(["a", "b", "c"], ["c", "b", "a"]) == -1.0

    def test_single_shared_returns_none(self):
        assert _kendall_tau_on_shared(["a", "b"], ["a", "c"]) is None

    def test_no_shared_returns_none(self):
        assert _kendall_tau_on_shared(["a", "b"], ["c", "d"]) is None

    def test_partial_shared_three_elements(self):
        # Shared: a, b, c. A ranks them 0,1,2; B ranks them 0,2,1.
        # Pairs: (a,b) → concordant (both +); (a,c) → concordant;
        #        (b,c) → discordant (A: +, B: -).
        # tau = (2 - 1)/3 ≈ 0.333
        result = _kendall_tau_on_shared(["a", "b", "c"], ["a", "c", "b"])
        assert abs(result - 1 / 3) < 1e-9


class TestTopKIds:
    def test_extracts_results_ids(self):
        response = {"results": [{"id": "d1"}, {"id": "d2"}, {"id": "d3"}]}
        assert _top_k_ids(response, 10) == ["d1", "d2", "d3"]

    def test_limits_to_k(self):
        response = {"results": [{"id": f"d{i}"} for i in range(20)]}
        assert _top_k_ids(response, 5) == ["d0", "d1", "d2", "d3", "d4"]

    def test_skips_missing_ids(self):
        response = {"results": [{"id": "d1"}, {}, {"id": "d3"}]}
        assert _top_k_ids(response, 10) == ["d1", "d3"]


class TestRunShadow:
    def _mock_execute(self, id_source: dict):
        """Build a mock that returns per-qid top-K ids from id_source."""
        def _side_effect(client, qid, body, max_retries, allow_errors):
            policy = body.get("pipeline")
            tag = policy.get("tag") if isinstance(policy, dict) else None
            ids = id_source.get((qid, tag)) or id_source.get(qid, [])
            return {
                "tookMs": 10,
                "totalHits": len(ids),
                "results": [{"id": i} for i in ids],
            }
        return _side_effect

    @patch("jseval.shadow_eval.execute_query")
    def test_identical_policies_produce_identical_records(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            "q1": ["d1", "d2", "d3"],
            "q2": ["d4", "d5"],
        })
        result = run_shadow(
            {"q1": "text1", "q2": "text2"},
            policy_a={"tag": "x"}, policy_b={"tag": "x"},
            base_url="http://localhost:33221",
        )
        assert result["query_count"] == 2
        assert result["summary"]["identical_top_k"] == 2
        assert result["summary"]["disjoint"] == 0
        assert result["summary"]["mean_jaccard"] == 1.0

    @patch("jseval.shadow_eval.execute_query")
    def test_divergent_policies_produce_divergence(self, mock_exec):
        # A returns [d1, d2, d3]; B returns [d3, d4, d5].
        mock_exec.side_effect = self._mock_execute({
            ("q1", "a"): ["d1", "d2", "d3"],
            ("q1", "b"): ["d3", "d4", "d5"],
        })
        result = run_shadow(
            {"q1": "text1"},
            policy_a={"tag": "a"}, policy_b={"tag": "b"},
            base_url="http://localhost:33221",
        )
        div = result["divergences"][0]
        assert div["status"] == "partial-overlap"
        # |{d1,d2,d3} ∩ {d3,d4,d5}| / |{d1..d5}| = 1/5
        assert abs(div["top_k_jaccard"] - 0.2) < 1e-9

    @patch("jseval.shadow_eval.execute_query")
    def test_disjoint_top_k_marked(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            ("q1", "a"): ["d1", "d2"],
            ("q1", "b"): ["d3", "d4"],
        })
        result = run_shadow(
            {"q1": "text1"},
            policy_a={"tag": "a"}, policy_b={"tag": "b"},
            base_url="http://localhost:33221",
        )
        assert result["divergences"][0]["status"] == "disjoint"
        assert result["summary"]["disjoint"] == 1

    @patch("jseval.shadow_eval.execute_query")
    def test_query_order_preserved(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            "q3": ["d3"], "q1": ["d1"], "q2": ["d2"],
        })
        queries = {"q3": "t3", "q1": "t1", "q2": "t2"}  # Insertion order.
        result = run_shadow(
            queries,
            policy_a={"tag": "a"}, policy_b={"tag": "a"},
            base_url="http://localhost:33221",
        )
        # Divergence records preserve dict insertion order.
        assert [r["qid"] for r in result["divergences"]] == ["q3", "q1", "q2"]

    @patch("jseval.shadow_eval.execute_query")
    def test_error_status_counted(self, mock_exec):
        def _fail(client, qid, body, max_retries, allow_errors):
            return None
        mock_exec.side_effect = _fail
        result = run_shadow(
            {"q1": "t1"},
            policy_a={"tag": "a"}, policy_b={"tag": "a"},
            base_url="http://localhost:33221",
            allow_errors=True,
        )
        assert result["summary"]["errors"] == 1
        assert result["divergences"][0]["status"] == "error"

    @patch("jseval.shadow_eval.execute_query")
    def test_schema_shape(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({"q1": ["d1"]})
        result = run_shadow(
            {"q1": "t1"},
            policy_a={"tag": "x"}, policy_b={"tag": "x"},
            base_url="http://localhost:33221",
        )
        assert result["schema_version"] == 1
        assert "policy_a" in result and "policy_b" in result
        assert "records_a" in result and "records_b" in result
        assert "divergences" in result
        assert "summary" in result


class TestWriteReport:
    def test_writes_file(self, tmp_path):
        path = write_report({"schema_version": 1, "x": 1}, tmp_path)
        assert path.name == "shadow-eval.json"
        assert json.loads(path.read_text(encoding="utf-8")) == {
            "schema_version": 1, "x": 1,
        }


class TestFailFastInvariants:
    """Phase 6 / 6.9: pre-policy-B qid-sequence + error-rate guards."""

    @patch("jseval.shadow_eval.execute_query")
    def test_max_error_rate_raises_after_policy_a(self, mock_exec):
        """Error rate blown during policy A aborts before B runs."""
        a_calls = [0]

        def _side_effect(client, qid, body, max_retries, allow_errors):
            a_calls[0] += 1
            # Every request fails; rate = 100% > 50% threshold.
            return None
        mock_exec.side_effect = _side_effect

        with pytest.raises(RuntimeError) as exc:
            run_shadow(
                {"q1": "t1", "q2": "t2", "q3": "t3"},
                policy_a={"tag": "a"}, policy_b={"tag": "b"},
                base_url="http://localhost:33221",
                allow_errors=True,
                max_error_rate=0.5,
            )
        assert "error-rate invariant violated" in str(exc.value)
        # Only policy A executed (3 calls); policy B never ran.
        assert a_calls[0] == 3

    @patch("jseval.shadow_eval.execute_query")
    def test_max_error_rate_none_disables_guard(self, mock_exec):
        def _side_effect(client, qid, body, max_retries, allow_errors):
            return None
        mock_exec.side_effect = _side_effect
        # No max_error_rate → all errors tolerated, completes both policies.
        result = run_shadow(
            {"q1": "t1"},
            policy_a={"tag": "a"}, policy_b={"tag": "b"},
            base_url="http://localhost:33221",
            allow_errors=True,
        )
        # Both policies ran; the full summary has errors counted.
        assert result["summary"]["errors"] >= 1

    @patch("jseval.shadow_eval.execute_query")
    def test_max_error_rate_exact_threshold_not_violated(self, mock_exec):
        calls = [0]

        def _side_effect(client, qid, body, max_retries, allow_errors):
            calls[0] += 1
            # Every other query fails; rate = 50%. Threshold = 0.5 means
            # rate > 0.5 fails → exactly 50% is OK.
            return None if calls[0] % 2 == 1 else {
                "tookMs": 1, "totalHits": 0, "results": [],
            }
        mock_exec.side_effect = _side_effect
        result = run_shadow(
            {"q1": "t1", "q2": "t2", "q3": "t3", "q4": "t4"},
            policy_a={"tag": "a"}, policy_b={"tag": "b"},
            base_url="http://localhost:33221",
            allow_errors=True,
            max_error_rate=0.5,
        )
        # Completes without raising.
        assert result["schema_version"] == 1
