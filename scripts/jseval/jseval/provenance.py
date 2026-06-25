"""Extract per-hit and per-run evidence from raw search responses."""

from __future__ import annotations

from collections import Counter

_COMPONENTS = (
    "splade", "dense", "hybrid", "chunk_merge", "branch_fusion",
    "lambdamart", "cross_encoder", "expansion", "query_classification",
)

# Tempdoc 549 Phase D: jseval component name -> unified-trace stage wireId. Names that already
# match (chunk_merge->chunk-merge etc. differ only by separator) are mapped explicitly.
_COMPONENT_TO_STAGE = {
    "splade": "splade-retrieval",
    "dense": "dense-retrieval",
    "hybrid": "fusion",
    "chunk_merge": "chunk-merge",
    "branch_fusion": "branch-fusion",
    "lambdamart": "lambdamart",
    "cross_encoder": "cross-encoder",
    "expansion": "expansion",
    "query_classification": "query-understanding",
}


def extract_hit_evidence(hit: dict, splade_executed: bool = False) -> dict:
    """Extract per-hit evidence from a single search hit.

    Tempdoc 549 Phase E1/E2: both the stringly-typed ``debugScores`` map and the leg-keyed
    ``provenance`` are retired. Per-hit evidence is read solely from the unified ``hit.trace``
    slice (the per-doc slice of the stage vocabulary — covers the BM25 sparse-only shortcut via
    its always-on structural sparse stage).
    """
    trace = hit.get("trace") or []
    if trace:
        return _extract_from_trace(trace, splade_executed)
    return _empty_hit_evidence()


def extract_query_evidence(response: dict) -> dict:
    """Extract per-query evidence from a raw search response dict.

    Tempdoc 549 Phase E4: query-level signals are read SOLELY from the unified ``searchTrace``
    (the single canonical source) — the legacy ``introspection`` aggregate, ``pipelineExecution``,
    and flat degradation fields were retired. The trace carries effectiveMode/decisionKind/qpp/
    degradation as trace-level fields plus the per-stage nodes.
    """
    trace = response.get("searchTrace") or {}
    tdegr = trace.get("degradation") or {}
    tstages = {s.get("id"): s for s in (trace.get("stages") or [])}
    chunk_stage = tstages.get("chunk-merge") or {}

    splade_executed = bool(tdegr.get("spladeExecuted"))

    hits = response.get("results") or []
    hit_evidences = [extract_hit_evidence(h, splade_executed) for h in hits]

    # Component statuses from the unified trace's stages (the single source).
    components: dict[str, dict[str, str | None]] = {}
    for comp in _COMPONENTS:
        stage = tstages.get(_COMPONENT_TO_STAGE.get(comp, comp))
        if stage:
            components[comp] = {"status": stage.get("status"), "reason": stage.get("reason")}

    return {
        "effective_mode": trace.get("effectiveMode"),
        "vector_blocked": bool(tdegr.get("vectorBlocked")),
        "vector_blocked_reason": tdegr.get("vectorBlockedReason"),
        "hybrid_fallback": bool(tdegr.get("hybridFallback")),
        "hybrid_fallback_reason": tdegr.get("hybridFallbackReason"),
        "chunk_merge_applied": chunk_stage.get("status") == "executed",
        "chunk_merge_reason": chunk_stage.get("reason"),
        "took_ms": response.get("tookMs"),
        "total_hits": response.get("totalHits"),
        "components": components,
        "hit_evidences": hit_evidences,
        "has_vector_evidence": any(
            _has_any_evidence(he) for he in hit_evidences
        ),
        "has_sparse_evidence": any(
            _has_sparse_evidence(he) for he in hit_evidences
        ),
        "has_dense_evidence": any(
            _has_dense_evidence(he) for he in hit_evidences
        ),
        "error": response.get("error"),
        "identity_resolution_errors": response.get("identity_resolution_errors", 0),
        "stage_timing": _extract_stage_timing(response),
    }


# Tempdoc 549 Phase E3: stage timing now comes from the unified trace's per-stage `ms`
# (pipelineExecution retired). FUSION.ms carries the retrieval-phase elapsed (Phase D0).
_STAGE_TIMING_MAP = {
    "fusion": "retrieval_ms",
    "chunk-merge": "chunk_merge_ms",
    "cross-encoder": "cross_encoder_ms",
    "lambdamart": "lambdamart_ms",
    "branch-fusion": "branch_fusion_ms",
}
_STAGE_TIMING_PY_KEYS = tuple(_STAGE_TIMING_MAP.values())


def _extract_stage_timing(response: dict) -> dict:
    """Extract pipeline stage timing from the unified trace's per-stage ms."""
    trace = response.get("searchTrace") or {}
    by_id = {s.get("id"): s for s in (trace.get("stages") or [])}
    return {
        py_key: (by_id.get(wire_id) or {}).get("ms")
        for wire_id, py_key in _STAGE_TIMING_MAP.items()
    }


def aggregate_run_evidence(query_evidences: list[dict]) -> dict:
    """Aggregate per-query evidence into per-run evidence rates."""
    total = len(query_evidences)
    if total == 0:
        return {
            "query_count": 0,
            "vector_evidence_available_rate": 0.0,
            "sparse_evidence_available_rate": 0.0,
            "dense_vector_evidence_available_rate": 0.0,
            "effective_mode_counts": {},
            "hybrid_fallback_reason_counts": {},
            "component_status_counts": {},
            "error_count": 0,
            "zero_hit_query_count": 0,
            "zero_hit_query_rate": 0.0,
            "chunk_merge_applied_count": 0,
            "chunk_merge_applied_rate": 0.0,
            "identity_resolution_error_count": 0,
        }

    mode_counter: Counter = Counter()
    fallback_counter: Counter = Counter()
    component_counters: dict[str, Counter] = {}
    vector_count = 0
    sparse_count = 0
    dense_count = 0
    error_count = 0
    zero_hit_count = 0
    chunk_merge_count = 0
    id_resolution_errors = 0

    for qe in query_evidences:
        if qe.get("has_vector_evidence"):
            vector_count += 1
        if qe.get("has_sparse_evidence"):
            sparse_count += 1
        if qe.get("has_dense_evidence"):
            dense_count += 1
        if qe.get("error"):
            error_count += 1
        if (qe.get("total_hits") or 0) == 0:
            zero_hit_count += 1
        if qe.get("chunk_merge_applied"):
            chunk_merge_count += 1
        id_resolution_errors += qe.get("identity_resolution_errors", 0)

        em = qe.get("effective_mode")
        if em:
            mode_counter[em] += 1

        fb_reason = qe.get("hybrid_fallback_reason")
        if fb_reason:
            fallback_counter[fb_reason] += 1

        for comp, comp_data in qe.get("components", {}).items():
            if comp not in component_counters:
                component_counters[comp] = Counter()
            status = comp_data.get("status")
            if status:
                component_counters[comp][status] += 1

    # Aggregate pipeline stage timing
    stage_timing_stats = _aggregate_stage_timing(query_evidences)

    return {
        "query_count": total,
        "vector_evidence_available_rate": vector_count / total,
        "sparse_evidence_available_rate": sparse_count / total,
        "dense_vector_evidence_available_rate": dense_count / total,
        "effective_mode_counts": dict(mode_counter),
        "hybrid_fallback_reason_counts": dict(fallback_counter),
        "component_status_counts": {k: dict(v) for k, v in component_counters.items()},
        "error_count": error_count,
        "zero_hit_query_count": zero_hit_count,
        "zero_hit_query_rate": zero_hit_count / total,
        "chunk_merge_applied_count": chunk_merge_count,
        "chunk_merge_applied_rate": chunk_merge_count / total,
        "identity_resolution_error_count": id_resolution_errors,
        "stage_timing_stats": stage_timing_stats,
    }


def _aggregate_stage_timing(query_evidences: list[dict]) -> dict:
    """Aggregate per-query stage timing into per-stage p50/mean/p95."""
    stage_keys = list(_STAGE_TIMING_PY_KEYS)
    collected: dict[str, list[float]] = {k: [] for k in stage_keys}

    for qe in query_evidences:
        st = qe.get("stage_timing") or {}
        for key in stage_keys:
            val = st.get(key)
            if val is not None:
                collected[key].append(val)

    result: dict[str, dict] = {}
    for key, values in collected.items():
        if not values:
            continue
        values.sort()
        n = len(values)
        result[key] = {
            "mean": round(sum(values) / n, 1),
            "p50": values[n // 2],
            "p95": values[min(int(n * 0.95 + 0.5), n - 1)],
        }
    return result


# ---------------------------------------------------------------------------
# Provenance extraction (structured)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Unified-trace extraction (per-hit slice; tempdoc 549 — the sole per-hit evidence source)
# ---------------------------------------------------------------------------

def _extract_from_trace(trace: list, splade_executed: bool) -> dict:
    """Per-hit evidence from the unified ``hit.trace`` (list of HitStage {id, rank, score, detail}).

    Structural rank/score come from each stage directly; the chunk sub-ranks/scores and the fusion
    method live in the numeric ``detail`` map (present only when include_detail was requested).
    """
    by_id = {s.get("id"): s for s in trace if isinstance(s, dict)}
    sparse = by_id.get("sparse-retrieval") or {}
    splade = by_id.get("splade-retrieval") or {}
    dense = by_id.get("dense-retrieval") or {}
    fusion = by_id.get("fusion") or {}
    chunk_detail = (by_id.get("chunk-merge") or {}).get("detail") or {}
    fusion_detail = fusion.get("detail") or {}
    ce = by_id.get("cross-encoder") or {}

    sparse_source = splade if splade_executed else sparse
    fusion_method = "rrf" if "rrf" in fusion_detail else ("cc" if "cc" in fusion_detail else None)

    return {
        "sparse_rank": _to_int(sparse_source.get("rank")),
        "sparse_score": sparse_source.get("score"),
        "dense_rank": _to_int(dense.get("rank")),
        "dense_score": dense.get("score"),
        "chunk_sparse_rank": _to_int(chunk_detail.get("chunk_sparse_rank")),
        "chunk_sparse_score": chunk_detail.get("chunk_sparse"),
        "chunk_dense_rank": _to_int(chunk_detail.get("chunk_vector_rank")),
        "chunk_dense_score": chunk_detail.get("chunk_vector"),
        "fusion_score": fusion.get("score"),
        "fusion_method": fusion_method,
        "ce_score": ce.get("score"),
    }


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _empty_hit_evidence() -> dict:
    return {
        "sparse_rank": None, "sparse_score": None,
        "dense_rank": None, "dense_score": None,
        "chunk_sparse_rank": None, "chunk_sparse_score": None,
        "chunk_dense_rank": None, "chunk_dense_score": None,
        "fusion_score": None, "fusion_method": None,
        "ce_score": None,
    }


def _has_any_evidence(he: dict) -> bool:
    return any(
        v is not None and v != 0
        for k, v in he.items()
        if k != "fusion_method"
    )


def _has_sparse_evidence(he: dict) -> bool:
    return any(
        (he.get(k) or 0) > 0
        for k in ("sparse_rank", "sparse_score", "chunk_sparse_rank", "chunk_sparse_score")
    )


def _has_dense_evidence(he: dict) -> bool:
    return any(
        (he.get(k) or 0) > 0
        for k in ("dense_rank", "dense_score", "chunk_dense_rank", "chunk_dense_score")
    )


def _to_int(val) -> int | None:
    if val is None:
        return None
    try:
        return int(val)
    except (ValueError, TypeError):
        return None
