---
title: "Tempdoc 295 — Search Degradation Contract Evolution"
---

# Tempdoc 295 — Search Degradation Contract Evolution

**Status:** Open
**Created:** 2026-03-14
**Goal:** Evolve the search pipeline's degradation signaling contract to cover the new retrieval stages, ensuring that API consumers can distinguish between "stage ran and contributed" vs "stage ran but contributed nothing" vs "stage was skipped."

## Context

The search pipeline has a well-established degradation signaling pattern: each component reports `executed` / `skipped` + reason in `pipelineExecution`, and skip reason fields (`hybridFallback`, `spladeSkipReason`, `crossEncoderSkipReason`, etc.) provide per-leg detail. The canonical contract is in `docs/reference/contracts/search-and-rag-reason-codes.md`.

The recently added stages (chunk branch retrieval, 3-way CC fusion, branch fusion) have partial coverage: `chunkMergeApplied` + `chunkMergeReason` cover the top-level gate, but branch fusion internals are not signaled.

## Known Gaps

### G1: Branch fusion has no separate degradation signal

When `chunkMergeApplied=true`, branch fusion ran — but the API doesn't distinguish:
- Chunk branch contributed hits that changed the ranking
- Chunk branch returned empty (whole-doc result used as-is)
- Chunk branch ran but was entirely dominated by whole-doc branch after CC fusion

### G2: Active fusion strategy not signaled in response

The response doesn't indicate whether CC or RRF was used for branch fusion, or what the effective CC weights were. Eval tools that analyze response quality have no way to correlate ranking behavior with configuration without checking the server config separately.

### G3: SPLADE parent-length modulation not signaled

When SPLADE weight is reduced for long documents (via `spladeParentLengthMultiplier`), this isn't visible in the degradation signals. A hit from a 5,000-token document had its SPLADE weight zeroed — this is a significant ranking decision that isn't surfaced.

### G4: Reason codes contract doc may need extension

`docs/reference/contracts/search-and-rag-reason-codes.md` covers the current degradation signals. New signals from G1–G3 should be added to the contract.

## Action Items

- [ ] Design signal fields for branch fusion contribution (`branchFusionApplied`, `branchFusionStrategy`, `chunkBranchHitCount`)
- [ ] Add branch fusion component to `pipelineExecution` report
- [ ] Consider adding per-hit `spladeWeightModulated` flag or similar for transparency
- [ ] Update `docs/reference/contracts/search-and-rag-reason-codes.md` with new signals
- [ ] Update `docs/reference/contracts/search-pipeline-invariants.md` if new invariants emerge
- [ ] Review whether the eval harness should consume the new degradation signals for quality analysis
