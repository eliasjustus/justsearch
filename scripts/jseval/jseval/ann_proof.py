"""ANN proof computation (hybrid/dense evidence rate verification)."""

from __future__ import annotations

from .types import AnnProofResult

THRESHOLD = 0.95

# Which evidence checks apply to each mode.  Modes not listed here
# get all checks (conservative default).
_MODE_REQUIRED_CHECKS: dict[str, set[str]] = {
    "lexical": set(),           # pure BM25 — no vector checks needed
    "splade": {"vector_evidence_available_rate"},
    "bm25_splade": {"vector_evidence_available_rate"},
    "hybrid": {"hybrid_effective_rate", "dense_vector_evidence_available_rate"},
    "vector": {"dense_vector_evidence_available_rate"},
    "dense_splade": {"vector_evidence_available_rate",
                     "dense_vector_evidence_available_rate"},
    "full": {"hybrid_effective_rate", "vector_evidence_available_rate",
             "dense_vector_evidence_available_rate"},
}

ALL_PROOF_KEYS = {
    "hybrid_effective_rate",
    "vector_evidence_available_rate",
    "dense_vector_evidence_available_rate",
}


def compute_ann_proof(
    run_evidence: dict,
    embedding_enabled: bool,
    *,
    mode: str | None = None,
    pipeline_tracking: dict | None = None,
) -> AnnProofResult:
    """Compute ANN proof from per-run evidence.

    Returns PASS if all relevant evidence rates are >= 0.95, no ANN-disabled
    queries, and no pipeline leg mismatches.
    Returns NOT_APPLICABLE if embedding is disabled or mode needs no checks.
    Returns FAIL otherwise with diagnostic reasons.
    """
    if not embedding_enabled:
        return AnnProofResult(status="NOT_APPLICABLE")

    # Determine which checks apply to this mode
    required = _MODE_REQUIRED_CHECKS.get(mode, ALL_PROOF_KEYS) if mode else ALL_PROOF_KEYS
    if not required and not (pipeline_tracking and pipeline_tracking.get("mismatch_reasons")):
        return AnnProofResult(status="NOT_APPLICABLE")

    reasons: list[str] = []
    rates: dict[str, float] = {}

    # Compute hybrid effective rate from mode counts
    mode_counts = run_evidence.get("effective_mode_counts", {})
    total = run_evidence.get("query_count", 0)
    hybrid_count = mode_counts.get("HYBRID", 0)
    rates["hybrid_effective_rate"] = hybrid_count / total if total > 0 else 0.0

    # Copy evidence rates from run evidence
    rates["vector_evidence_available_rate"] = run_evidence.get(
        "vector_evidence_available_rate", 0.0,
    )
    rates["dense_vector_evidence_available_rate"] = run_evidence.get(
        "dense_vector_evidence_available_rate", 0.0,
    )

    # Check only the rates relevant to this mode
    for key in sorted(required):
        rate = rates.get(key, 0.0)
        if rate < THRESHOLD:
            reasons.append(f"{key}={rate:.3f} < {THRESHOLD}")

    # Check for any ANN-disabled queries (hybrid fallback reasons)
    fallback_counts = run_evidence.get("hybrid_fallback_reason_counts", {})
    ann_disabled = sum(fallback_counts.values())
    if ann_disabled > 0:
        reasons.append(f"ann_disabled_count={ann_disabled}")

    # Fail if requested pipeline legs were not observed (e.g. dense broken)
    if pipeline_tracking:
        for mismatch in pipeline_tracking.get("mismatch_reasons", []):
            reasons.append(f"pipeline_mismatch: {mismatch}")

    return AnnProofResult(
        status="PASS" if not reasons else "FAIL",
        reasons=reasons,
        rates=rates,
    )
