"""Tests for judge_arbitration_report.py (tempdoc 643 E3)."""

from __future__ import annotations

from jseval.judge_arbitration_report import (
    alpha_branch_breakdown,
    perf_skip_firing_rate,
    regression_rate,
)


def _signal(doc_id, *, bm25_rank=None, splade_rank=None, dense_rank=None, ce_score=None):
    return {
        "docId": doc_id, "bm25_rank": bm25_rank, "splade_rank": splade_rank,
        "dense_rank": dense_rank, "ce_score": ce_score,
    }


def _rec(qid, judge_signals, chunk_merge_applied=None):
    return {"qid": qid, "judgeSignals": judge_signals, "chunkMergeApplied": chunk_merge_applied}


# Legs strongly agree (same top docs both sides) -> jaccard = 1.0 >= 0.5.
AGREEING_LEGS = [
    _signal("a", bm25_rank=1, dense_rank=1, ce_score=0.5),
    _signal("b", bm25_rank=2, dense_rank=2, ce_score=0.5),
]
# Legs disagree entirely -> jaccard = 0.0 < 0.5.
DISAGREEING_LEGS = [
    _signal("a", bm25_rank=1, dense_rank=None, ce_score=-2.0),
    _signal("b", bm25_rank=None, dense_rank=1, ce_score=3.0),
]


class TestAlphaBranchBreakdown:
    def test_confident_ce_and_agreeing_legs_stays_base_alpha(self):
        # CE margin here is large (min-max normalized top1-top2 = 1.0) -> ceConfident=True ->
        # base_alpha branch regardless of leg agreement.
        rec = _rec("q1", AGREEING_LEGS[:1] + [_signal("b", bm25_rank=2, dense_rank=2, ce_score=-5.0)])
        out = alpha_branch_breakdown([rec])
        assert out["n_base_alpha"] == 1
        assert out["n_fusion_protect"] == 0

    def test_unconfident_ce_and_agreeing_legs_fires_fusion_protect(self):
        # ce_score identical (0.5, 0.5) -> min-max normalize degenerates to [0.5, 0.5] -> margin 0.0
        # -> not confident. Legs agree (jaccard 1.0) -> fusion-protect fires.
        rec = _rec("q1", AGREEING_LEGS)
        out = alpha_branch_breakdown([rec])
        assert out["n_fusion_protect"] == 1
        assert out["n_base_alpha"] == 0
        assert out["fusion_protect_rate"] == 1.0

    def test_unconfident_ce_but_disagreeing_legs_stays_base_alpha(self):
        # ce_score very different (-2.0 vs 3.0) -> margin ~1.0 -> actually CONFIDENT here; use a
        # near-tied CE with disagreeing legs to isolate the legs-disagree branch.
        signals = [
            _signal("a", bm25_rank=1, dense_rank=None, ce_score=0.50),
            _signal("b", bm25_rank=None, dense_rank=1, ce_score=0.51),
        ]
        rec = _rec("q1", signals)
        out = alpha_branch_breakdown([rec])
        assert out["n_base_alpha"] == 1
        assert out["n_fusion_protect"] == 0

    def test_fewer_than_two_ce_scored_candidates_short_circuits_to_base_alpha(self):
        rec = _rec("q1", [_signal("a", bm25_rank=1, dense_rank=1, ce_score=0.5)])
        out = alpha_branch_breakdown([rec])
        assert out["n_base_alpha"] == 1
        assert out["n_skipped_lt2_candidates"] == 1
        assert out["n_fusion_protect"] == 0

    def test_chunk_branch_active_short_circuits_to_base_alpha(self):
        rec = _rec("q1", AGREEING_LEGS, chunk_merge_applied=True)
        out = alpha_branch_breakdown([rec])
        assert out["n_base_alpha"] == 1
        assert out["n_skipped_chunk_active"] == 1
        assert out["n_fusion_protect"] == 0

    def test_empty_leg_treated_as_agree_fires_fusion_protect(self):
        # Only one leg present (dense empty) -> jaccard -1.0 -> "no comparable signal" -> treated
        # as agree, mirroring legAgreementJaccard's empty-leg convention.
        signals = [
            _signal("a", bm25_rank=1, dense_rank=None, ce_score=0.5),
            _signal("b", bm25_rank=2, dense_rank=None, ce_score=0.5),
        ]
        rec = _rec("q1", signals)
        out = alpha_branch_breakdown([rec])
        assert out["n_fusion_protect"] == 1

    def test_reports_configured_alphas(self):
        out = alpha_branch_breakdown([_rec("q1", AGREEING_LEGS)], base_alpha=0.5, fusion_protect_alpha=0.85)
        assert out["base_alpha"] == 0.5
        assert out["fusion_protect_alpha"] == 0.85

    def test_empty_input(self):
        out = alpha_branch_breakdown([])
        assert out["n_total"] == 0
        assert out["fusion_protect_rate"] is None


class TestPerfSkipFiringRate:
    def test_agreeing_legs_fires(self):
        out = perf_skip_firing_rate([_rec("q1", AGREEING_LEGS)])
        assert out["n_fires"] == 1
        assert out["firing_rate"] == 1.0

    def test_disagreeing_legs_does_not_fire(self):
        out = perf_skip_firing_rate([_rec("q1", DISAGREEING_LEGS)])
        assert out["n_fires"] == 0

    def test_chunk_branch_active_never_fires_even_if_legs_would_agree(self):
        # Stricter than the blend gate: chunk-active disqualifies regardless of leg signal.
        out = perf_skip_firing_rate([_rec("q1", AGREEING_LEGS, chunk_merge_applied=True)])
        assert out["n_fires"] == 0

    def test_fires_independent_of_ce_confidence_unlike_blend_gate(self):
        # Same AGREEING_LEGS shape that produced n_fusion_protect=1 above (CE unconfident) --
        # here the CE score doesn't matter at all; perf-skip only reads leg agreement.
        confident_but_agreeing = [
            _signal("a", bm25_rank=1, dense_rank=1, ce_score=-5.0),
            _signal("b", bm25_rank=2, dense_rank=2, ce_score=5.0),
        ]
        out = perf_skip_firing_rate([_rec("q1", confident_but_agreeing)])
        assert out["n_fires"] == 1  # fires even though a blend-gate CE margin here would be confident

    def test_empty_input(self):
        out = perf_skip_firing_rate([])
        assert out["n_total"] == 0
        assert out["firing_rate"] is None


class TestRegressionRate:
    def test_changed_and_unchanged_queries(self, synthetic_run_dir, tmp_path):
        run_a = synthetic_run_dir
        run_a.with_per_query("hybrid", [
            {"qid": "q1", "predictedDocIds": ["a", "b", "c"]},
            {"qid": "q2", "predictedDocIds": ["x", "y"]},
        ])
        run_b_dir = tmp_path / "run_b"
        run_b_dir.mkdir()
        (run_b_dir / "hybrid_per_query.json").write_text(
            '[{"qid": "q1", "predictedDocIds": ["b", "a", "c"]}, '
            '{"qid": "q2", "predictedDocIds": ["x", "y"]}]',
            encoding="utf-8",
        )
        out = regression_rate(run_a.run_dir, run_b_dir, mode="hybrid")
        assert out["n_common_queries"] == 2
        assert out["n_changed"] == 1  # q1 reordered
        assert out["n_unchanged"] == 1  # q2 identical
        assert out["changed_rate"] == 0.5

    def test_no_common_queries(self, synthetic_run_dir, tmp_path):
        run_a = synthetic_run_dir
        run_a.with_per_query("hybrid", [{"qid": "q1", "predictedDocIds": ["a"]}])
        run_b_dir = tmp_path / "run_b"
        run_b_dir.mkdir()
        (run_b_dir / "hybrid_per_query.json").write_text(
            '[{"qid": "q2", "predictedDocIds": ["a"]}]', encoding="utf-8")
        out = regression_rate(run_a.run_dir, run_b_dir, mode="hybrid")
        assert out["n_common_queries"] == 0
        assert out["changed_rate"] is None
