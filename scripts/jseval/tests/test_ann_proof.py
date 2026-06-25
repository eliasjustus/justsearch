"""Tests for ann_proof.py — ANN proof computation."""

from __future__ import annotations

from jseval.ann_proof import THRESHOLD, compute_ann_proof


def _good_evidence(**overrides) -> dict:
    """Run evidence where all rates are above threshold."""
    base = {
        "query_count": 100,
        "effective_mode_counts": {"HYBRID": 100},
        "vector_evidence_available_rate": 1.0,
        "dense_vector_evidence_available_rate": 1.0,
        "hybrid_fallback_reason_counts": {},
    }
    base.update(overrides)
    return base


class TestComputeAnnProof:
    def test_embedding_disabled(self):
        result = compute_ann_proof({}, embedding_enabled=False)
        assert result.status == "NOT_APPLICABLE"
        assert result.reasons == []

    def test_all_rates_above_threshold(self):
        result = compute_ann_proof(_good_evidence(), embedding_enabled=True)
        assert result.status == "PASS"
        assert result.reasons == []
        assert result.rates["hybrid_effective_rate"] == 1.0

    def test_hybrid_rate_below_threshold(self):
        evidence = _good_evidence(
            effective_mode_counts={"HYBRID": 90, "TEXT": 10},
        )
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "FAIL"
        assert any("hybrid_effective_rate" in r for r in result.reasons)
        assert result.rates["hybrid_effective_rate"] == 0.9

    def test_dense_rate_below_threshold(self):
        evidence = _good_evidence(dense_vector_evidence_available_rate=0.5)
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "FAIL"
        assert any("dense_vector_evidence_available_rate" in r for r in result.reasons)

    def test_vector_rate_below_threshold(self):
        evidence = _good_evidence(vector_evidence_available_rate=0.8)
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "FAIL"
        assert any("vector_evidence_available_rate" in r for r in result.reasons)

    def test_ann_disabled_count(self):
        evidence = _good_evidence(
            hybrid_fallback_reason_counts={"NO_VECTORS": 3},
        )
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "FAIL"
        assert any("ann_disabled_count" in r for r in result.reasons)

    def test_zero_queries(self):
        evidence = {
            "query_count": 0,
            "effective_mode_counts": {},
            "vector_evidence_available_rate": 0,
            "dense_vector_evidence_available_rate": 0,
            "hybrid_fallback_reason_counts": {},
        }
        result = compute_ann_proof(evidence, embedding_enabled=True)
        # Should not crash with division by zero
        assert result.status == "FAIL"
        assert result.rates["hybrid_effective_rate"] == 0.0

    def test_exactly_at_threshold(self):
        evidence = _good_evidence(
            effective_mode_counts={"HYBRID": 95, "TEXT": 5},
            vector_evidence_available_rate=THRESHOLD,
            dense_vector_evidence_available_rate=THRESHOLD,
        )
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "PASS"

    def test_lexical_mode_skips_all_checks(self):
        evidence = _good_evidence(
            effective_mode_counts={"TEXT": 100},
            vector_evidence_available_rate=0.0,
            dense_vector_evidence_available_rate=0.0,
        )
        result = compute_ann_proof(evidence, embedding_enabled=True, mode="lexical")
        assert result.status == "NOT_APPLICABLE"

    def test_bm25_splade_mode_skips_dense_check(self):
        evidence = _good_evidence(
            effective_mode_counts={"HYBRID": 100},
            vector_evidence_available_rate=1.0,
            dense_vector_evidence_available_rate=0.0,
        )
        result = compute_ann_proof(evidence, embedding_enabled=True, mode="bm25_splade")
        assert result.status == "PASS"

    def test_full_mode_fails_on_missing_dense(self):
        evidence = _good_evidence(
            dense_vector_evidence_available_rate=0.0,
        )
        result = compute_ann_proof(evidence, embedding_enabled=True, mode="full")
        assert result.status == "FAIL"
        assert any("dense_vector_evidence" in r for r in result.reasons)

    def test_pipeline_mismatch_fails(self):
        evidence = _good_evidence()
        pt = {"mismatch_reasons": ["requested_dense_but_not_observed"]}
        result = compute_ann_proof(
            evidence, embedding_enabled=True, mode="full", pipeline_tracking=pt,
        )
        assert result.status == "FAIL"
        assert any("pipeline_mismatch" in r for r in result.reasons)

    def test_no_mode_checks_all(self):
        """Without mode, all checks apply (backward-compatible)."""
        evidence = _good_evidence(dense_vector_evidence_available_rate=0.5)
        result = compute_ann_proof(evidence, embedding_enabled=True)
        assert result.status == "FAIL"
        assert any("dense_vector_evidence" in r for r in result.reasons)
