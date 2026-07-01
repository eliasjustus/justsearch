"""Tests for provenance.py — evidence extraction."""

from __future__ import annotations

from jseval.provenance import (
    aggregate_run_evidence,
    extract_hit_evidence,
    extract_judge_signals,
    extract_query_evidence,
)


# ---------------------------------------------------------------------------
# Per-hit evidence
# ---------------------------------------------------------------------------

class TestExtractHitEvidence:
    def test_from_trace_object(self):
        # Tempdoc 549 Phase E2: per-hit evidence comes solely from the unified trace slice.
        hit = {
            "trace": [
                {"id": "sparse-retrieval", "rank": 1, "score": 5.5},
                {"id": "dense-retrieval", "rank": 3, "score": 0.85},
                {"id": "fusion", "score": 1.23, "detail": {"rrf": 1.23}},
                {
                    "id": "chunk-merge",
                    "detail": {"chunk_sparse_rank": 2, "chunk_sparse": 4.0},
                },
                {"id": "cross-encoder", "score": 2.5},
            ],
        }
        ev = extract_hit_evidence(hit, splade_executed=False)
        assert ev["sparse_rank"] == 1
        assert ev["sparse_score"] == 5.5
        assert ev["dense_rank"] == 3
        assert ev["dense_score"] == 0.85
        assert ev["fusion_score"] == 1.23
        assert ev["fusion_method"] == "rrf"
        assert ev["chunk_sparse_rank"] == 2
        assert ev["chunk_sparse_score"] == 4.0
        assert ev["ce_score"] == 2.5

    def test_from_trace_fallback(self):
        # Tempdoc 549 Phase E1: debug_scores retired — per-hit evidence falls back to hit.trace
        # (the unified per-doc slice) when provenance is absent.
        hit = {
            "trace": [
                {"id": "sparse-retrieval", "rank": 1, "score": 5.0},
                {"id": "dense-retrieval", "rank": 2, "score": 0.9},
                {"id": "fusion", "score": 1.5, "detail": {"rrf": 1.5}},
            ],
        }
        ev = extract_hit_evidence(hit)
        assert ev["sparse_score"] == 5.0
        assert ev["sparse_rank"] == 1
        assert ev["dense_score"] == 0.9
        assert ev["dense_rank"] == 2
        assert ev["fusion_score"] == 1.5
        assert ev["fusion_method"] == "rrf"

    def test_splade_routing(self):
        hit = {
            "trace": [
                {"id": "splade-retrieval", "rank": 2, "score": 3.0},
                {"id": "sparse-retrieval", "rank": 1, "score": 5.5},
            ],
        }
        ev = extract_hit_evidence(hit, splade_executed=True)
        # When splade is executed, sparse fields route to the splade-retrieval stage.
        assert ev["sparse_rank"] == 2
        assert ev["sparse_score"] == 3.0

    def test_no_evidence(self):
        hit = {}
        ev = extract_hit_evidence(hit)
        assert ev["sparse_rank"] is None
        assert ev["dense_score"] is None
        assert ev["fusion_score"] is None

    def test_trace_cc_method(self):
        hit = {"trace": [{"id": "fusion", "score": 0.88, "detail": {"cc": 0.88}}]}
        ev = extract_hit_evidence(hit)
        assert ev["fusion_score"] == 0.88
        assert ev["fusion_method"] == "cc"


# ---------------------------------------------------------------------------
# Per-hit judge-arbitration signals (tempdoc 643)
# ---------------------------------------------------------------------------

class TestExtractJudgeSignals:
    def test_bm25_and_splade_kept_separate(self):
        # Unlike extract_hit_evidence's splade_executed collapsing, both legs' own ranks
        # must survive distinctly when BOTH run in the same hybrid query.
        hit = {
            "trace": [
                {"id": "sparse-retrieval", "rank": 1, "score": 5.5},
                {"id": "splade-retrieval", "rank": 4, "score": 2.1},
                {"id": "dense-retrieval", "rank": 2, "score": 0.9},
                {"id": "fusion", "score": 1.2},
                {"id": "cross-encoder", "score": 3.7},
            ],
        }
        sig = extract_judge_signals(hit)
        assert sig["bm25_rank"] == 1
        assert sig["bm25_score"] == 5.5
        assert sig["splade_rank"] == 4
        assert sig["splade_score"] == 2.1
        assert sig["dense_rank"] == 2
        assert sig["dense_score"] == 0.9
        assert sig["fusion_score"] == 1.2
        assert sig["ce_score"] == 3.7

    def test_no_trace_returns_all_none(self):
        sig = extract_judge_signals({})
        assert sig == {
            "bm25_rank": None, "bm25_score": None,
            "splade_rank": None, "splade_score": None,
            "dense_rank": None, "dense_score": None,
            "fusion_score": None, "ce_score": None,
        }

    def test_no_cross_encoder_stage(self):
        hit = {"trace": [{"id": "sparse-retrieval", "rank": 1, "score": 1.0}]}
        sig = extract_judge_signals(hit)
        assert sig["ce_score"] is None
        assert sig["bm25_rank"] == 1

    def test_branch_fusion_preferred_over_stale_fusion(self):
        # Tempdoc 643 critical-analysis-pass correction: when chunk-branch fusion ran, "fusion"
        # reflects only the whole-doc branch's own internal score (stale) -- the true final
        # score is on "branch-fusion", which must win.
        hit = {
            "trace": [
                {"id": "fusion", "score": 0.42},
                {"id": "branch-fusion", "score": 0.91},
            ],
        }
        sig = extract_judge_signals(hit)
        assert sig["fusion_score"] == 0.91

    def test_falls_back_to_fusion_when_no_branch_fusion(self):
        hit = {"trace": [{"id": "fusion", "score": 1.23}]}
        sig = extract_judge_signals(hit)
        assert sig["fusion_score"] == 1.23

    def test_falls_back_to_fusion_when_branch_fusion_present_but_scoreless(self):
        # HitProvenanceProjector.attachBranchFusion can emit a "branch-fusion" stage with no
        # score (fusionScore()==null) -- must fall through to "fusion", not report None.
        hit = {
            "trace": [
                {"id": "fusion", "score": 1.5},
                {"id": "branch-fusion"},
            ],
        }
        sig = extract_judge_signals(hit)
        assert sig["fusion_score"] == 1.5

    def test_fusion_score_none_when_neither_stage_present(self):
        hit = {"trace": [{"id": "sparse-retrieval", "score": 1.0}]}
        sig = extract_judge_signals(hit)
        assert sig["fusion_score"] is None


# ---------------------------------------------------------------------------
# Per-query evidence
# ---------------------------------------------------------------------------

class TestExtractQueryEvidence:
    def test_basic_fields(self):
        # Tempdoc 549 Phase E4: query-level signals come from the unified trace (scalars + stages).
        response = {
            "searchTrace": {
                "effectiveMode": "HYBRID",
                "degradation": {"vectorBlocked": False, "hybridFallback": True,
                                "hybridFallbackReason": "NO_VECTORS"},
                "stages": [{"id": "chunk-merge", "status": "executed"}],
            },
            "tookMs": 45,
            "totalHits": 10,
            "results": [],
        }
        qe = extract_query_evidence(response)
        assert qe["effective_mode"] == "HYBRID"
        assert qe["vector_blocked"] is False
        assert qe["hybrid_fallback"] is True
        assert qe["hybrid_fallback_reason"] == "NO_VECTORS"
        assert qe["took_ms"] == 45

    def test_component_statuses(self):
        # Component statuses come from the trace's stage nodes (mapped wireIds).
        response = {
            "results": [],
            "searchTrace": {
                "stages": [
                    {"id": "cross-encoder", "status": "executed", "reason": None},
                    {"id": "splade-retrieval", "status": "skipped", "reason": "NO_MODEL"},
                ],
            },
        }
        qe = extract_query_evidence(response)
        assert qe["components"]["cross_encoder"]["status"] == "executed"
        assert qe["components"]["splade"]["reason"] == "NO_MODEL"

    def test_evidence_availability(self):
        response = {
            "results": [
                {"trace": [{"id": "sparse-retrieval", "rank": 1, "score": 5.0}]},
                {"trace": [{"id": "dense-retrieval", "rank": 2, "score": 0.8}]},
            ],
        }
        qe = extract_query_evidence(response)
        assert qe["has_sparse_evidence"] is True
        assert qe["has_dense_evidence"] is True
        assert qe["has_vector_evidence"] is True


# ---------------------------------------------------------------------------
# Run-level aggregation
# ---------------------------------------------------------------------------

class TestAggregateRunEvidence:
    def test_basic_rates(self):
        evidences = [
            {"has_vector_evidence": True, "has_sparse_evidence": True,
             "has_dense_evidence": True, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None},
            {"has_vector_evidence": True, "has_sparse_evidence": True,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None},
            {"has_vector_evidence": False, "has_sparse_evidence": True,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": "NO_VECTORS", "components": {}, "error": None},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["query_count"] == 3
        assert agg["vector_evidence_available_rate"] == pytest.approx(2 / 3)
        assert agg["sparse_evidence_available_rate"] == pytest.approx(1.0)
        assert agg["dense_vector_evidence_available_rate"] == pytest.approx(1 / 3)

    def test_mode_counts(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["effective_mode_counts"]["HYBRID"] == 1
        assert agg["effective_mode_counts"]["TEXT"] == 1

    def test_error_count(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": None,
             "hybrid_fallback_reason": None, "components": {}, "error": "timeout"},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["error_count"] == 1

    def test_empty_evidences(self):
        agg = aggregate_run_evidence([])
        assert agg["query_count"] == 0
        assert agg["error_count"] == 0
        assert agg["zero_hit_query_count"] == 0
        assert agg["chunk_merge_applied_count"] == 0
        assert agg["identity_resolution_error_count"] == 0

    def test_zero_hit_query_count(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 0, "chunk_merge_applied": False,
             "identity_resolution_errors": 0},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 5, "chunk_merge_applied": False,
             "identity_resolution_errors": 0},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 0, "chunk_merge_applied": False,
             "identity_resolution_errors": 0},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["zero_hit_query_count"] == 2
        assert agg["zero_hit_query_rate"] == pytest.approx(2 / 3)

    def test_chunk_merge_rate(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 10, "chunk_merge_applied": True,
             "identity_resolution_errors": 0},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 10, "chunk_merge_applied": False,
             "identity_resolution_errors": 0},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["chunk_merge_applied_count"] == 1
        assert agg["chunk_merge_applied_rate"] == pytest.approx(0.5)

    def test_identity_resolution_error_count(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 10, "chunk_merge_applied": False,
             "identity_resolution_errors": 2},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "total_hits": 10, "chunk_merge_applied": False,
             "identity_resolution_errors": 1},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["identity_resolution_error_count"] == 3


class TestStageTiming:
    def test_extract_stage_timing(self):
        # Tempdoc 549 Phase E3: stage timing comes from the unified trace's per-stage ms
        # (FUSION.ms = retrieval phase, Phase D0); pipelineExecution retired.
        response = {
            "results": [],
            "searchTrace": {
                "stages": [
                    {"id": "fusion", "status": "executed", "ms": 5},
                    {"id": "chunk-merge", "status": "executed", "ms": 2},
                    {"id": "cross-encoder", "status": "executed", "ms": 45},
                ],
            },
        }
        qe = extract_query_evidence(response)
        assert qe["stage_timing"]["retrieval_ms"] == 5
        assert qe["stage_timing"]["cross_encoder_ms"] == 45
        assert qe["stage_timing"]["lambdamart_ms"] is None

    def test_aggregate_stage_timing(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 5, "cross_encoder_ms": 40}},
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "HYBRID",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 10, "cross_encoder_ms": 80}},
        ]
        agg = aggregate_run_evidence(evidences)
        st = agg["stage_timing_stats"]
        assert "retrieval_ms" in st
        assert st["retrieval_ms"]["mean"] == 7.5
        assert "cross_encoder_ms" in st

    def test_no_timing_data(self):
        evidences = [
            {"has_vector_evidence": False, "has_sparse_evidence": False,
             "has_dense_evidence": False, "effective_mode": "TEXT",
             "hybrid_fallback_reason": None, "components": {}, "error": None,
             "stage_timing": {}},
        ]
        agg = aggregate_run_evidence(evidences)
        assert agg["stage_timing_stats"] == {}

    def test_unaccounted_remainder_closes_the_decomposition(self):
        # tempdoc 647: took_ms encloses the stages, so the remainder is the CPT "unaccounted" node and
        # the per-entry shares close to 1. Remainders: 180-(4+150)=26, 182-(6+154)=22 → mean 24.
        evidences = [
            {"components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 4, "cross_encoder_ms": 150}, "took_ms": 180},
            {"components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 6, "cross_encoder_ms": 154}, "took_ms": 182},
        ]
        st = aggregate_run_evidence(evidences)["stage_timing_stats"]
        assert "unaccounted_ms" in st
        assert st["unaccounted_ms"]["mean"] == 24.0
        assert st["unaccounted_ms"]["p50"] >= 0  # non-negative by construction
        total_share = (st["retrieval_ms"]["share"] + st["cross_encoder_ms"]["share"]
                       + st["unaccounted_ms"]["share"])
        assert abs(total_share - 1.0) < 1e-3  # the parts close to the whole

    def test_unaccounted_clamps_subms_rounding_negatives(self):
        # Σ stages slightly exceeds took_ms (sub-ms rounding at tiny stages) → clamp to 0 and count it.
        evidences = [
            {"components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 5, "cross_encoder_ms": 176}, "took_ms": 180},
        ]
        st = aggregate_run_evidence(evidences)["stage_timing_stats"]
        assert st["unaccounted_ms"]["p50"] == 0
        assert st["unaccounted_ms"]["clamped_negative_count"] == 1

    def test_no_shares_or_remainder_without_took_ms(self):
        # Backward compatibility: legacy inputs without took_ms get neither shares nor a remainder.
        evidences = [
            {"components": {}, "error": None,
             "stage_timing": {"retrieval_ms": 5, "cross_encoder_ms": 40}},
        ]
        st = aggregate_run_evidence(evidences)["stage_timing_stats"]
        assert "unaccounted_ms" not in st
        assert "share" not in st["retrieval_ms"]


class TestUnifiedTraceFirst:
    """Tempdoc 549 Phase E4: extract_query_evidence reads SOLELY the unified SearchTrace."""

    def test_reads_query_signals_from_trace(self):
        # A stray legacy `introspection` key (if any) is ignored — the trace is the only source.
        response = {
            "results": [],
            "searchTrace": {
                "version": 1,
                "effectiveMode": "hybrid",
                "decisionKind": "multi_leg",
                "degradation": {
                    "vectorBlocked": True,
                    "vectorBlockedReason": "ENCODER_UNAVAILABLE",
                    "spladeExecuted": True,
                },
                "stages": [
                    {"id": "dense-retrieval", "status": "skipped", "reason": "ENCODER_UNAVAILABLE"},
                    {"id": "splade-retrieval", "status": "executed"},
                    {"id": "chunk-merge", "status": "executed", "reason": None},
                    {"id": "cross-encoder", "status": "executed"},
                ],
            },
            # Legacy introspection with the OPPOSITE values — must be ignored when the trace exists.
            "introspection": {
                "effectiveMode": "TEXT",
                "degradation": {"vectorBlocked": False, "spladeExecuted": False},
                "chunkMerge": {"applied": False},
            },
        }
        ev = extract_query_evidence(response)
        assert ev["effective_mode"] == "hybrid"
        assert ev["vector_blocked"] is True
        assert ev["vector_blocked_reason"] == "ENCODER_UNAVAILABLE"
        assert ev["chunk_merge_applied"] is True
        # Component statuses come from the trace stages (mapped names).
        assert ev["components"]["splade"]["status"] == "executed"
        assert ev["components"]["dense"]["status"] == "skipped"
        assert ev["components"]["cross_encoder"]["status"] == "executed"

    def test_no_trace_yields_empty_query_signals(self):
        # Tempdoc 549 Phase E4: with no trace there is no fallback — signals are empty/None.
        ev = extract_query_evidence({"results": []})
        assert ev["effective_mode"] is None
        assert ev["vector_blocked"] is False
        assert ev["chunk_merge_applied"] is False
        assert ev["components"] == {}


import pytest
