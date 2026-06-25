"""Tests for the LLM judge-ceiling probe pure core (tempdoc 636 §5)."""

from __future__ import annotations

from jseval.judge_ceiling import (
    AIUnavailable,
    _score_ranking,
    assemble_pool,
    judge_ceiling_report,
    make_chat_rank_fn,
    served_model_name,
)

QRELS = {"q1": {"g": 1}, "q2": {"g": 1}}
POOL = {"q1": ["g", "x"], "q2": ["y", "g"]}
QUERIES = {"q1": "query one", "q2": "query two"}


def _oracle(query, cand_ids, texts):
    """Perfect judge: gold docs first (preserving order otherwise)."""
    gold = {"g"}
    return sorted(cand_ids, key=lambda d: (0 if d in gold else 1))


def _identity(query, cand_ids, texts):
    """Returns the candidates exactly as presented (position-sensitive)."""
    return list(cand_ids)


class TestScoreRanking:
    def test_gold_rank1_is_perfect(self):
        assert _score_ranking(QRELS, {"q1": ["g", "x"], "q2": ["g", "y"]}) == 1.0

    def test_empty_is_none(self):
        assert _score_ranking(QRELS, {}) is None


class TestJudgeCeilingReport:
    def test_perfect_oracle_captures_full_ceiling(self):
        rep = judge_ceiling_report(
            POOL, QUERIES, QRELS, _oracle, final_ndcg=0.30, ceiling=0.70)
        assert rep["llm_ndcg"] == 1.0
        assert abs(rep["headroom_realized"] - 0.70) < 1e-9
        assert abs(rep["capture_fraction"] - 1.0) < 1e-9
        # oracle is order-insensitive → top-1 agreement perfect.
        assert rep["position_sensitivity"]["top1_agreement"] == 1.0
        assert rep["n_queries"] == 2

    def test_identity_ranker_is_position_sensitive(self):
        # identity returns input order → forward vs reversed disagree on top-1.
        rep = judge_ceiling_report(
            POOL, QUERIES, QRELS, _identity, final_ndcg=0.30, ceiling=0.70)
        assert rep["position_sensitivity"]["top1_agreement"] == 0.0

    def test_missing_final_ndcg_yields_none_headroom(self):
        rep = judge_ceiling_report(POOL, QUERIES, QRELS, _oracle, final_ndcg=None, ceiling=0.7)
        assert rep["headroom_realized"] is None
        assert rep["capture_fraction"] is None

    def test_unjudged_queries_skipped(self):
        pool = {**POOL, "q3": ["a", "b"]}  # q3 not in qrels
        rep = judge_ceiling_report(pool, QUERIES, QRELS, _oracle, final_ndcg=0.3, ceiling=0.7)
        assert rep["n_queries"] == 2


class TestAssemblePool:
    def test_unions_leg_modes(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("vector", [
            {"qid": "q1", "predictedDocIds": ["a", "b"], "recallAtK": 1.0}])
        synthetic_run_dir.with_per_query("lexical", [
            {"qid": "q1", "predictedDocIds": ["b", "c"], "recallAtK": 1.0}])
        pool = assemble_pool(synthetic_run_dir.run_dir)
        assert set(pool["q1"]) == {"a", "b", "c"}


class TestLiveAdapterDegrades:
    def test_unreachable_llm_raises_aiunavailable(self):
        # Nothing listening on this port → AIUnavailable (graceful-degradation contract).
        rank_fn = make_chat_rank_fn("http://127.0.0.1:9", timeout=0.5)
        try:
            rank_fn("q", ["a", "b"], {"a": "x", "b": "y"})
            assert False, "expected AIUnavailable"
        except AIUnavailable:
            pass

    def test_served_model_name_degrades_to_none(self):
        # Self-preference guardrail helper is advisory — never raises on unreachable.
        assert served_model_name("http://127.0.0.1:9", timeout=0.5) is None
