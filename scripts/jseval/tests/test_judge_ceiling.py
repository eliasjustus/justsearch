"""Tests for the LLM judge-ceiling probe pure core (tempdoc 636 §5)."""

from __future__ import annotations

from jseval.judge_ceiling import (
    AIUnavailable,
    _score_ranking,
    assemble_pool,
    ce_replay_rankings,
    ce_replay_report,
    judge_ceiling_report,
    load_ce_scores,
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

    def test_capped_run_qrels_does_not_dilute_the_score(self):
        # A capped run's qrels.json still holds the corpus's FULL query set even though only a
        # subset was judged -- passing the full qrels through unfiltered must not silently score
        # every un-judged corpus query as 0 and fold it into the mean (a real, confirmed bug:
        # tempdoc 643 sibling-thread ce-replay cross-check, ~8x dilution on a 40-of-300 run).
        full_corpus_qrels = {**QRELS, "q3": {"g": 1}, "q4": {"g": 1}, "q5": {"g": 1}}
        rankings = {"q1": ["g", "x"], "q2": ["g", "y"]}  # only 2 of the 5 corpus queries judged
        assert _score_ranking(full_corpus_qrels, rankings) == 1.0  # not 1.0 * 2/5 = 0.4

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

    def test_one_query_failing_does_not_abort_the_others(self):
        # Tempdoc 643 U1 (2026-07-01, first live run): a single query's malformed/truncated LLM
        # response is a real, observed per-query failure mode, not every response fits any fixed
        # token budget. It must not discard the other already-paid-for queries' real results.
        def flaky(query, cand_ids, texts):
            if query == "query one":
                raise AIUnavailable("truncated JSON")
            return _oracle(query, cand_ids, texts)

        rep = judge_ceiling_report(POOL, QUERIES, QRELS, flaky, final_ndcg=0.3, ceiling=0.7)
        assert rep["status"] == "ok"
        assert rep["n_queries"] == 1
        assert rep["n_skipped"] == 1
        assert rep["skipped_qids"] == ["q1"]

    def test_all_queries_failing_raises_aiunavailable(self):
        # Genuine unavailability (every attempted query fails) must still degrade the whole
        # probe -- only an ISOLATED per-query hiccup should be tolerated, not systemic failure.
        def always_fails(query, cand_ids, texts):
            raise AIUnavailable("connection refused")

        try:
            judge_ceiling_report(POOL, QUERIES, QRELS, always_fails, final_ndcg=0.3, ceiling=0.7)
            assert False, "expected AIUnavailable"
        except AIUnavailable:
            pass


class TestAssemblePool:
    def test_unions_leg_modes(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("vector", [
            {"qid": "q1", "predictedDocIds": ["a", "b"], "recallAtK": 1.0}])
        synthetic_run_dir.with_per_query("lexical", [
            {"qid": "q1", "predictedDocIds": ["b", "c"], "recallAtK": 1.0}])
        pool = assemble_pool(synthetic_run_dir.run_dir)
        assert set(pool["q1"]) == {"a", "b", "c"}


def _judge_signal(doc_id, ce_score):
    return {"docId": doc_id, "ce_score": ce_score}


class TestLoadCeScores:
    def test_reads_ce_scores_from_final_mode_judge_signals(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("hybrid", [
            {"qid": "q1", "predictedDocIds": ["a", "b"],
             "judgeSignals": [_judge_signal("a", 0.9), _judge_signal("b", 0.1)]},
        ])
        scores = load_ce_scores(synthetic_run_dir.run_dir)
        assert scores == {"q1": {"a": 0.9, "b": 0.1}}

    def test_prefers_hybrid_falls_back_to_full(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("full", [
            {"qid": "q1", "predictedDocIds": ["a"], "judgeSignals": [_judge_signal("a", 0.5)]},
        ])
        scores = load_ce_scores(synthetic_run_dir.run_dir)
        assert scores == {"q1": {"a": 0.5}}

    def test_no_final_mode_present_returns_empty(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("vector", [{"qid": "q1", "predictedDocIds": ["a"]}])
        assert load_ce_scores(synthetic_run_dir.run_dir) == {}

    def test_missing_ce_score_is_skipped(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("hybrid", [
            {"qid": "q1", "predictedDocIds": ["a", "b"],
             "judgeSignals": [_judge_signal("a", None), _judge_signal("b", 0.2)]},
        ])
        scores = load_ce_scores(synthetic_run_dir.run_dir)
        assert scores == {"q1": {"b": 0.2}}


class TestCeReplayRankings:
    def test_scored_candidates_sorted_by_ce_score_descending(self):
        pool = {"q1": ["a", "b", "c"]}
        ce_scores = {"q1": {"a": 0.1, "b": 0.9, "c": 0.5}}
        out = ce_replay_rankings(pool, ce_scores)
        assert out["q1"] == ["b", "c", "a"]

    def test_unscored_candidates_keep_pool_order_appended_after_scored(self):
        # "x" was never in the CE's window (no score) -> stays at the back, in original order.
        pool = {"q1": ["x", "a", "y", "b"]}
        ce_scores = {"q1": {"a": 0.1, "b": 0.9}}
        out = ce_replay_rankings(pool, ce_scores)
        assert out["q1"] == ["b", "a", "x", "y"]

    def test_no_scores_at_all_keeps_original_pool_order(self):
        pool = {"q1": ["a", "b", "c"]}
        out = ce_replay_rankings(pool, {})
        assert out["q1"] == ["a", "b", "c"]


class TestCeReplayReport:
    def test_perfect_replay_captures_full_ceiling(self):
        pool = {"q1": ["x", "g"], "q2": ["g", "y"]}
        ce_scores = {"q1": {"g": 1.0, "x": 0.0}, "q2": {"g": 1.0, "y": 0.0}}
        rep = ce_replay_report(pool, ce_scores, QRELS, final_ndcg=0.30, ceiling=0.70)
        assert rep["ce_replay_ndcg"] == 1.0
        assert abs(rep["headroom_realized"] - 0.70) < 1e-9
        assert abs(rep["capture_fraction"] - 1.0) < 1e-9
        assert rep["n_ce_scored_queries"] == 2

    def test_no_ce_scores_falls_back_to_pool_order(self):
        # No judgeSignals captured at all (e.g. an older run) -> replay == pool order verbatim.
        pool = {"q1": ["x", "g"]}
        rep = ce_replay_report(pool, {}, {"q1": {"g": 1}}, final_ndcg=0.5, ceiling=0.5)
        assert rep["n_ce_scored_queries"] == 0
        assert rep["ce_replay_ndcg"] is not None  # still scores the (unscored) pool order

    def test_missing_final_ndcg_yields_none_headroom(self):
        pool = {"q1": ["g", "x"]}
        ce_scores = {"q1": {"g": 1.0, "x": 0.0}}
        rep = ce_replay_report(pool, ce_scores, QRELS, final_ndcg=None, ceiling=0.7)
        assert rep["headroom_realized"] is None
        assert rep["capture_fraction"] is None


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
