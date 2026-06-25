"""Tests for stratified_metrics projection (tempdoc 400 LR4-c)."""

from __future__ import annotations

import json

from jseval.projections.stratified_metrics import (
    DECISION_KIND_BUCKETS,
    FIRST_RELEVANT_RANK_BUCKET_EDGES,
    FIRST_RELEVANT_RANK_BUCKETS,
    PROJECTION,
    QUERY_LENGTH_BUCKET_EDGES,
    QUERY_LENGTH_BUCKETS,
    _decision_kind_bucket,
    _first_relevant_bucket,
    _first_relevant_rank,
    _query_length_bucket,
    produce,
)


def _entry(qid: str, query: str, ndcg: float, predicted: list[str]):
    return {
        "qid": qid,
        "query": query,
        "mode": "hybrid",
        "ndcgAtK": ndcg,
        "apAtK": ndcg * 0.6,
        "mrrAtK": ndcg * 0.9,
        "recallAtK": min(1.0, ndcg * 1.1),
        "p1AtK": 1.0 if ndcg >= 0.5 else 0.0,
        "predictedDocIds": predicted,
        "totalRelevant": 1,
    }


class TestBucketing:
    def test_query_length_bucket_short(self):
        assert _query_length_bucket("one") == "short"
        assert _query_length_bucket("one two three four five") == "short"

    def test_query_length_bucket_medium(self):
        assert _query_length_bucket("a b c d e f") == "medium"
        assert _query_length_bucket(" ".join(["w"] * 10)) == "medium"

    def test_query_length_bucket_long(self):
        assert _query_length_bucket(" ".join(["w"] * 11)) == "long"
        assert _query_length_bucket(" ".join(["w"] * 25)) == "long"

    def test_query_length_unknown_for_missing_or_empty(self):
        assert _query_length_bucket(None) == "unknown"
        assert _query_length_bucket("") == "unknown"
        assert _query_length_bucket("   ") == "unknown"

    def test_first_relevant_rank_finds_correct_position(self):
        # doc2 is at rank 2 in predicted.
        assert _first_relevant_rank(
            ["d1", "d2", "d3"], {"d2": 1}) == 2
        assert _first_relevant_rank(
            ["d1", "d2", "d3"], {"d1": 1, "d3": 2}) == 1

    def test_first_relevant_rank_none_when_no_relevant_in_predicted(self):
        assert _first_relevant_rank(["d1", "d2"], {"d3": 1}) is None
        assert _first_relevant_rank(["d1", "d2"], {}) is None

    def test_first_relevant_rank_rel_zero_not_counted(self):
        # rel=0 doesn't count.
        assert _first_relevant_rank(
            ["d1", "d2"], {"d1": 0, "d2": 1}) == 2

    def test_first_relevant_bucket_mapping(self):
        assert _first_relevant_bucket(1) == "top-1"
        assert _first_relevant_bucket(2) == "top-2-5"
        assert _first_relevant_bucket(5) == "top-2-5"
        assert _first_relevant_bucket(6) == "6-10"
        assert _first_relevant_bucket(10) == "6-10"
        assert _first_relevant_bucket(11) == ">10"
        assert _first_relevant_bucket(None) == "unjudged"


class TestProduce:
    def test_empty_run_dir(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert result["modes"] == {}
        assert QUERY_LENGTH_BUCKETS == tuple(
            result["bucket_definitions"]["query_length"])
        assert FIRST_RELEVANT_RANK_BUCKETS == tuple(
            result["bucket_definitions"]["first_relevant_rank"])

    def test_stratification_produces_expected_cells(self, synthetic_run_dir):
        entries = [
            _entry("q1", "one two", 0.95, ["d1", "d2"]),       # short, top-1
            _entry("q2", "one two three", 0.60, ["d3", "d2"]), # short, top-2-5
            _entry("q3", "w " * 11, 0.40, ["d4", "d5"]),        # long, unjudged
        ]
        qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}, "q3": {}}
        synthetic_run_dir.with_per_query("hybrid", entries).with_qrels(qrels)
        result = produce(synthetic_run_dir.run_dir)
        hy = result["modes"]["hybrid"]
        # 3 distinct strata cells: (short,top-1), (short,top-2-5),
        # (long,unjudged).
        assert set(hy["strata"].keys()) == {
            "short || top-1",
            "short || top-2-5",
            "long || unjudged",
        }
        # Count in each cell = 1
        for cell in hy["strata"].values():
            assert cell["count"] == 1
            assert cell["nDCG@10"] is not None

    def test_marginals_sum_matches_total(self, synthetic_run_dir):
        entries = [
            _entry("q1", "one two", 0.80, ["d1"]),
            _entry("q2", "one two", 0.70, ["d1"]),
            _entry("q3", "one two three four five six", 0.50, ["d1"]),
            _entry("q4", "one two three four five six", 0.60, ["d2"]),
        ]
        qrels = {
            "q1": {"d1": 1}, "q2": {"d1": 1},
            "q3": {"d1": 1}, "q4": {"d1": 1},
        }
        synthetic_run_dir.with_per_query("hybrid", entries).with_qrels(qrels)
        result = produce(synthetic_run_dir.run_dir)
        hy = result["modes"]["hybrid"]
        # By query-length: 2 short (q1,q2), 2 medium (q3,q4).
        lens = hy["marginals"]["by_query_length"]
        assert lens["short"]["count"] == 2
        assert lens["medium"]["count"] == 2
        assert "long" not in lens or lens.get("long", {}).get("count", 0) == 0
        # Means correct per marginal.
        assert abs(lens["short"]["nDCG@10"] - 0.75) < 1e-9
        assert abs(lens["medium"]["nDCG@10"] - 0.55) < 1e-9

    def test_missing_qrels_file_all_unjudged(self, synthetic_run_dir):
        entries = [_entry("q1", "one two", 0.8, ["d1"])]
        synthetic_run_dir.with_per_query("hybrid", entries)
        # No qrels.json.
        result = produce(synthetic_run_dir.run_dir)
        hy = result["modes"]["hybrid"]
        # All go into unjudged bucket.
        assert "unjudged" in hy["marginals"]["by_first_relevant_rank"]
        assert hy["marginals"]["by_first_relevant_rank"]["unjudged"]["count"] == 1

    def test_missing_per_query_status(self, synthetic_run_dir):
        # Create only qrels.json; no per_query.json.
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}})
        result = produce(synthetic_run_dir.run_dir)
        # No modes discovered → empty modes dict (not an error).
        assert result["modes"] == {}

    def test_multi_mode(self, synthetic_run_dir):
        entries = [_entry("q1", "alpha beta", 0.7, ["d1"])]
        synthetic_run_dir.with_per_query("hybrid", entries)
        synthetic_run_dir.with_per_query("lexical", entries)
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}})
        result = produce(synthetic_run_dir.run_dir)
        assert set(result["modes"].keys()) == {"hybrid", "lexical"}

    def test_unknown_query_text(self, synthetic_run_dir):
        entries = [
            {"qid": "q1", "query": None, "ndcgAtK": 0.5, "predictedDocIds": ["d1"]},
            {"qid": "q2", "query": "", "ndcgAtK": 0.6, "predictedDocIds": ["d1"]},
        ]
        synthetic_run_dir.with_per_query("hybrid", entries)
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}, "q2": {"d1": 1}})
        result = produce(synthetic_run_dir.run_dir)
        hy = result["modes"]["hybrid"]
        assert hy["marginals"]["by_query_length"]["unknown"]["count"] == 2


class TestConfigurableBucketEdges:
    """Phase 6 / 6.12: operators can override default bucket edges."""

    def test_default_edges_match_documented(self):
        assert QUERY_LENGTH_BUCKET_EDGES == (5, 10)
        assert FIRST_RELEVANT_RANK_BUCKET_EDGES == (1, 5, 10)

    def test_custom_query_length_edges_promote_token_counts(self):
        # Tight edges (2, 4): 2-token query falls into "short", 3-4 into
        # "medium", 5+ into "long".
        assert _query_length_bucket("one two", edges=(2, 4)) == "short"
        assert _query_length_bucket("one two three", edges=(2, 4)) == "medium"
        assert _query_length_bucket("one two three four five",
                                    edges=(2, 4)) == "long"

    def test_custom_first_relevant_edges(self):
        # Tight edges (1, 3, 5): rank 4 → "6-10", rank 2 → "top-2-5".
        assert _first_relevant_bucket(1, edges=(1, 3, 5)) == "top-1"
        assert _first_relevant_bucket(2, edges=(1, 3, 5)) == "top-2-5"
        assert _first_relevant_bucket(4, edges=(1, 3, 5)) == "6-10"
        assert _first_relevant_bucket(6, edges=(1, 3, 5)) == ">10"

    def test_produce_override_emits_edges_in_output(self, synthetic_run_dir):
        entries = [_entry("q1", "alpha beta", 0.7, ["d1"])]
        synthetic_run_dir.with_per_query("hybrid", entries)
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}})

        result = produce(
            synthetic_run_dir.run_dir,
            query_length_edges=(3, 7),
            first_relevant_rank_edges=(2, 5, 20),
        )
        defs = result["bucket_definitions"]
        assert defs["edges"]["query_length"] == [3, 7]
        assert defs["edges"]["first_relevant_rank"] == [2, 5, 20]

    def test_produce_default_edges_emitted(self, synthetic_run_dir):
        """When no overrides, output echoes the module defaults."""
        entries = [_entry("q1", "one two", 0.5, ["d1"])]
        synthetic_run_dir.with_per_query("hybrid", entries)
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}})
        result = produce(synthetic_run_dir.run_dir)
        defs = result["bucket_definitions"]
        assert defs["edges"]["query_length"] == list(QUERY_LENGTH_BUCKET_EDGES)
        assert defs["edges"]["first_relevant_rank"] == list(
            FIRST_RELEVANT_RANK_BUCKET_EDGES
        )

    def test_custom_edges_redistribute_buckets(self, synthetic_run_dir):
        """Override shifts a medium query into 'short' and validates
        that downstream strata reflect the rebucketed assignment."""
        entries = [
            # 7 tokens: medium under default (5, 10), short under (8, 12).
            _entry("q1", " ".join(["w"] * 7), 0.7, ["d1"]),
        ]
        synthetic_run_dir.with_per_query("hybrid", entries)
        synthetic_run_dir.with_qrels({"q1": {"d1": 1}})

        default_result = produce(synthetic_run_dir.run_dir)
        assert "medium" in default_result["modes"]["hybrid"]["marginals"][
            "by_query_length"
        ]

        override_result = produce(
            synthetic_run_dir.run_dir,
            query_length_edges=(8, 12),
        )
        assert "short" in override_result["modes"]["hybrid"]["marginals"][
            "by_query_length"
        ]


class TestDecisionKindBucket:
    """Tempdoc 525: SearchIntrospection.decision.kind as a stratification dimension."""

    def test_known_values_map_to_themselves(self):
        for kind in ("empty_query", "blocked", "sparse_shortcut", "multi_leg"):
            assert _decision_kind_bucket(kind) == kind

    def test_unknown_value_falls_through(self):
        assert _decision_kind_bucket(None) == "unknown"
        assert _decision_kind_bucket("") == "unknown"
        assert _decision_kind_bucket("not_a_real_kind") == "unknown"
        assert _decision_kind_bucket(42) == "unknown"  # type: ignore[arg-type]

    def test_bucket_constant_includes_unknown(self):
        assert "unknown" in DECISION_KIND_BUCKETS
        assert "empty_query" in DECISION_KIND_BUCKETS
        assert "blocked" in DECISION_KIND_BUCKETS
        assert "sparse_shortcut" in DECISION_KIND_BUCKETS
        assert "multi_leg" in DECISION_KIND_BUCKETS

    def test_produce_emits_by_decision_kind_marginal(self, synthetic_run_dir):
        entries = [
            {**_entry("q1", "alpha", 0.8, ["d1"]), "decision_kind": "sparse_shortcut"},
            {**_entry("q2", "beta", 0.6, ["d2"]), "decision_kind": "sparse_shortcut"},
            {**_entry("q3", "gamma", 0.4, ["d3"]), "decision_kind": "multi_leg"},
            {**_entry("q4", "delta", 0.2, ["d4"]), "decision_kind": None},
        ]
        synthetic_run_dir.with_per_query("hybrid", entries).with_qrels(
            {"q1": {"d1": 1}, "q2": {"d2": 1}, "q3": {"d3": 1}, "q4": {"d4": 1}}
        )
        result = produce(synthetic_run_dir.run_dir)
        marginals = result["modes"]["hybrid"]["marginals"]
        assert "by_decision_kind" in marginals
        bd = marginals["by_decision_kind"]
        assert bd["sparse_shortcut"]["count"] == 2
        assert bd["multi_leg"]["count"] == 1
        assert bd["unknown"]["count"] == 1
        assert "decision_kind" in result["bucket_definitions"]


class TestProjectionRegistration:
    def test_module_export(self):
        assert PROJECTION.name == "stratified_metrics"
        assert PROJECTION.schema_version == 1

    def test_bootstrap_registers_and_writes(self, synthetic_run_dir):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        synthetic_run_dir.with_per_query("hybrid", [
            _entry("q1", "one", 0.5, ["d1"]),
        ]).with_qrels({"q1": {"d1": 1}})
        run_all_discovered(synthetic_run_dir.run_dir)
        assert "stratified_metrics" in registry()
        out = (synthetic_run_dir.run_dir / "projections" /
               "stratified_metrics.json")
        doc = json.loads(out.read_text(encoding="utf-8"))
        assert doc["schema_version"] == 1
        assert "hybrid" in doc["modes"]
        reset_registry_for_tests()
