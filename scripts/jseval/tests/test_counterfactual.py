"""Tests for counterfactual runner (tempdoc 400 LR5-a)."""

from __future__ import annotations

import json
from unittest.mock import patch

import pytest

from jseval import counterfactual
from jseval.counterfactual import (
    COUNTERFACTUAL_MODES,
    SUPPORTED_FUSION_ALGORITHMS,
    _aggregate_summary,
    _jaccard,
    _pairwise_divergence,
    _top_k_ids,
    build_counterfactual_modes,
    run_counterfactual,
    write_report,
)


class TestModeRegistry:
    def test_all_five_modes_present(self):
        assert set(COUNTERFACTUAL_MODES.keys()) == {
            "lexical_only", "dense_only", "splade_only",
            "hybrid_no_ce", "hybrid_full",
        }

    def test_hybrid_no_ce_disables_cross_encoder(self):
        cfg = COUNTERFACTUAL_MODES["hybrid_no_ce"]
        assert cfg["crossEncoderEnabled"] is False
        assert cfg["fusionAlgorithm"] == "cc"

    def test_hybrid_full_enables_cross_encoder(self):
        cfg = COUNTERFACTUAL_MODES["hybrid_full"]
        assert cfg["crossEncoderEnabled"] is True

    def test_lexical_only_is_sparse_only(self):
        cfg = COUNTERFACTUAL_MODES["lexical_only"]
        assert cfg["sparseEnabled"] is True
        assert cfg["denseEnabled"] is False
        assert cfg["spladeEnabled"] is False

    def test_splade_only_is_splade_only(self):
        cfg = COUNTERFACTUAL_MODES["splade_only"]
        assert cfg["spladeEnabled"] is True
        assert cfg["sparseEnabled"] is False
        assert cfg["denseEnabled"] is False


class TestPairwiseDivergence:
    def test_self_pairs_are_one(self):
        per_query = [
            {"qid": "q1", "modes": {
                "a": {"status": "ok", "top_k_ids": ["d1", "d2"]},
                "b": {"status": "ok", "top_k_ids": ["d3"]},
            }},
        ]
        pw = _pairwise_divergence(per_query, ["a", "b"])
        assert pw["a"]["a"] == 1.0
        assert pw["b"]["b"] == 1.0

    def test_identical_top_k_gives_jaccard_one(self):
        per_query = [
            {"qid": "q1", "modes": {
                "a": {"status": "ok", "top_k_ids": ["d1", "d2"]},
                "b": {"status": "ok", "top_k_ids": ["d1", "d2"]},
            }},
        ]
        pw = _pairwise_divergence(per_query, ["a", "b"])
        assert pw["a"]["b"] == 1.0
        assert pw["b"]["a"] == 1.0

    def test_disjoint_top_k_gives_zero(self):
        per_query = [
            {"qid": "q1", "modes": {
                "a": {"status": "ok", "top_k_ids": ["d1"]},
                "b": {"status": "ok", "top_k_ids": ["d2"]},
            }},
        ]
        pw = _pairwise_divergence(per_query, ["a", "b"])
        assert pw["a"]["b"] == 0.0

    def test_status_error_excluded_from_aggregate(self):
        per_query = [
            {"qid": "q1", "modes": {
                "a": {"status": "error", "dispatch_ms": 1.0},
                "b": {"status": "ok", "top_k_ids": ["d1"]},
            }},
            {"qid": "q2", "modes": {
                "a": {"status": "ok", "top_k_ids": ["d1"]},
                "b": {"status": "ok", "top_k_ids": ["d1"]},
            }},
        ]
        pw = _pairwise_divergence(per_query, ["a", "b"])
        # Only q2 contributes to the a↔b pair; a/b both have ok + identical.
        assert pw["a"]["b"] == 1.0


class TestAggregateSummary:
    def test_counts_ok_and_errors(self):
        per_query = [
            {"qid": "q1", "modes": {
                "a": {"status": "ok", "dispatch_ms": 10.0, "top_k_ids": []},
            }},
            {"qid": "q2", "modes": {
                "a": {"status": "error", "dispatch_ms": 5.0},
            }},
        ]
        summary = _aggregate_summary(per_query, ["a"])
        assert summary["a"]["ok"] == 1
        assert summary["a"]["errors"] == 1
        assert summary["a"]["mean_dispatch_ms"] == 10.0

    def test_no_queries_returns_none_means(self):
        summary = _aggregate_summary([], ["a"])
        assert summary["a"]["ok"] == 0
        assert summary["a"]["mean_dispatch_ms"] is None


class TestRunCounterfactual:
    def _mock_execute(self, responses_by_mode: dict[str, list[str]]):
        """Build mock execute_query that returns per-mode fake rankings."""
        def _side_effect(client, qid, body, max_retries, allow_errors):
            pipeline = body.get("pipeline") or {}
            mode = _infer_mode(pipeline)
            ids = responses_by_mode.get(mode, [])
            return {
                "tookMs": 5, "totalHits": len(ids),
                "results": [{"id": i} for i in ids],
            }
        return _side_effect

    @patch("jseval.counterfactual.execute_query")
    def test_default_runs_all_five_modes(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            "lexical_only": ["d1"], "dense_only": ["d2"],
            "splade_only": ["d3"], "hybrid_no_ce": ["d4"],
            "hybrid_full": ["d5"],
        })
        result = run_counterfactual(
            {"q1": "t"}, base_url="http://localhost:33221",
        )
        assert len(result["modes"]) == 5
        for mode in result["modes"]:
            assert result["per_query"][0]["modes"][mode]["status"] == "ok"

    @patch("jseval.counterfactual.execute_query")
    def test_subset_modes(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            "lexical_only": ["d1"], "hybrid_full": ["d2"],
        })
        result = run_counterfactual(
            {"q1": "t"}, base_url="http://localhost:33221",
            modes=["lexical_only", "hybrid_full"],
        )
        assert result["modes"] == ["lexical_only", "hybrid_full"]
        assert set(result["per_query"][0]["modes"].keys()) == {
            "lexical_only", "hybrid_full",
        }

    def test_unknown_mode_raises(self):
        with pytest.raises(ValueError):
            run_counterfactual(
                {"q1": "t"}, base_url="http://localhost:33221",
                modes=["lexical_only", "UNKNOWN"],
            )

    @patch("jseval.counterfactual.execute_query")
    def test_schema_shape(self, mock_exec):
        mock_exec.side_effect = self._mock_execute({
            m: ["d1"] for m in COUNTERFACTUAL_MODES
        })
        result = run_counterfactual(
            {"q1": "t"}, base_url="http://localhost:33221",
        )
        assert result["schema_version"] == 1
        assert set(result.keys()) >= {
            "schema_version", "start_ts", "end_ts", "wall_ms", "modes",
            "top_k", "query_count", "pairwise_divergence", "summary",
            "per_query",
        }


class TestWriteReport:
    def test_writes_counterfactual_json(self, tmp_path):
        path = write_report({"schema_version": 1}, tmp_path)
        assert path.name == "counterfactual.json"


class TestFusionAlgorithmConfigurable:
    """Phase 6 / 6.4: fusion_algorithm parameterizes hybrid modes."""

    def test_default_cc(self):
        modes = build_counterfactual_modes()
        assert modes["hybrid_no_ce"]["fusionAlgorithm"] == "cc"
        assert modes["hybrid_full"]["fusionAlgorithm"] == "cc"

    def test_rrf_applied_to_hybrid_modes(self):
        modes = build_counterfactual_modes("rrf")
        assert modes["hybrid_no_ce"]["fusionAlgorithm"] == "rrf"
        assert modes["hybrid_full"]["fusionAlgorithm"] == "rrf"
        # Non-hybrid modes unchanged ("none" fusion).
        assert modes["lexical_only"]["fusionAlgorithm"] == "none"
        assert modes["dense_only"]["fusionAlgorithm"] == "none"
        assert modes["splade_only"]["fusionAlgorithm"] == "none"

    def test_unknown_algorithm_raises(self):
        with pytest.raises(ValueError) as exc:
            build_counterfactual_modes("lambdamart")
        assert "unknown fusion_algorithm" in str(exc.value)

    def test_supported_algorithms_set(self):
        assert SUPPORTED_FUSION_ALGORITHMS == frozenset({"cc", "rrf"})

    @patch("jseval.counterfactual.execute_query")
    def test_run_counterfactual_threads_fusion_algorithm(self, mock_exec):
        captured_pipelines: list[dict] = []

        def _capture(client, qid, body, max_retries, allow_errors):
            captured_pipelines.append(body.get("pipeline"))
            return {"tookMs": 1, "totalHits": 0, "results": []}
        mock_exec.side_effect = _capture

        result = run_counterfactual(
            {"q1": "t"}, base_url="http://localhost:33221",
            modes=["hybrid_no_ce", "hybrid_full"],
            fusion_algorithm="rrf",
        )
        # Both captured hybrid pipelines should report rrf.
        for p in captured_pipelines:
            assert p["fusionAlgorithm"] == "rrf"
        # Result echoes the fusion_algorithm choice.
        assert result["fusion_algorithm"] == "rrf"

    @patch("jseval.counterfactual.execute_query")
    def test_default_run_uses_cc_and_echoes_it(self, mock_exec):
        mock_exec.side_effect = lambda *args, **kwargs: {
            "tookMs": 1, "totalHits": 0, "results": [],
        }
        result = run_counterfactual(
            {"q1": "t"}, base_url="http://localhost:33221",
            modes=["hybrid_full"],
        )
        assert result["fusion_algorithm"] == "cc"


# ---------------------------------------------------------------------------
# Helpers used only by tests
# ---------------------------------------------------------------------------

def _infer_mode(pipeline: dict) -> str:
    """Recover the mode name from a pipeline config for mock routing."""
    sparse = pipeline.get("sparseEnabled", False)
    dense = pipeline.get("denseEnabled", False)
    splade = pipeline.get("spladeEnabled", False)
    ce = pipeline.get("crossEncoderEnabled", False)
    if sparse and not dense and not splade:
        return "lexical_only"
    if dense and not sparse and not splade:
        return "dense_only"
    if splade and not sparse and not dense:
        return "splade_only"
    if sparse and dense and splade and not ce:
        return "hybrid_no_ce"
    if sparse and dense and splade and ce:
        return "hybrid_full"
    return "unknown"


class TestInferModeRoundTrip:
    """Phase 6 / 6.12: sanity-check that the test helper and
    `build_counterfactual_modes` stay in sync.

    If production ever adds a new mode or changes an existing pipeline
    config without updating ``_infer_mode``, the mock will silently
    route to "unknown" and every downstream test degrades to empty
    rankings. The round-trip assertion makes that drift a test failure.
    """

    @pytest.mark.parametrize("fusion", sorted(SUPPORTED_FUSION_ALGORITHMS))
    def test_every_mode_roundtrips(self, fusion):
        modes = build_counterfactual_modes(fusion)
        for mode_name, pipeline in modes.items():
            recovered = _infer_mode(pipeline)
            assert recovered == mode_name, (
                f"fusion={fusion}: _infer_mode returned {recovered!r} "
                f"for pipeline {pipeline!r}, expected {mode_name!r}"
            )

    def test_unknown_pipeline_yields_unknown(self):
        # Sanity: the fallback path is reachable (guards against a bug
        # where _infer_mode always returns a named mode).
        assert _infer_mode({}) == "unknown"
