---
title: "323: Retrieval Encoder Abstraction — DenseEncoder / SparseEncoder Interfaces"
type: tempdoc
status: done
created: 2026-03-18
depends-on: [322]
---

# 323: Retrieval Encoder Abstraction

## Purpose

Extract `DenseEncoder` / `SparseEncoder` interfaces to replace the scattered
null-check branching (7+ sites) introduced by tempdoc 322's BGE-M3 bolt-on
integration. Currently every consumer checks `if (bgeM3Encoder != null) ...
else if (spladeEncoder != null)` — this doesn't scale to a third model.

## Status: Deferred

**Trigger**: A third retrieval model integration (e.g., Qwen3.5-Embedding,
CSPLADE). Two models don't justify the abstraction cost — three do.

## Why deferred (not cancelled)

The interface extraction looks clean on paper but fights the implementation
details at every integration point:

### 1. SpladeIdfQueryEncoder doesn't fit the interface

SPLADE has *two* sparse encoders: `SpladeEncoder` (ONNX neural, 110M params) and
`SpladeIdfQueryEncoder` (IDF lookup table, zero inference). The IDF encoder shares
SPLADE's tokenizer and vocabulary but is a completely different implementation used
as a lightweight query-time fallback. A `SparseEncoder` interface would need either:
- A `CompositeSparseEncoder` wrapper that internally picks IDF vs ONNX (extra layer)
- Or the interface leaks the IDF/ONNX distinction (defeats the purpose)

### 2. Batch return types diverge

`SpladeEncoder.encodeBatch()` returns `List<Map<String, Float>>` (sparse only).
`BgeM3Encoder.encodeBatch()` returns `List<BgeM3Output>` (dense + sparse).
A `SparseEncoder.encodeBatchSparse()` forces BGE-M3 to discard its dense output.
A `UnifiedEncoder.encodeBothBatch()` requires `instanceof` branching in backfill —
the same pattern we're trying to eliminate.

### 3. GPU lifecycle is implementation-specific

Both encoders have `releaseGpuSession()` and `close()`. These are called from
`onMainClaimedGpu()` for VRAM yielding. The interface can't expose these without
forcing `EmbeddingService` to implement no-op GPU methods. Without them, callers
need `instanceof` checks or a separate `GpuManagedResource` interface.

### 4. `tokenCount()` doesn't generalize

`tokenCount(String)` is on both SPLADE and BGE-M3 encoders (used for
`parent_token_count` metadata). But `EmbeddingService` has no tokenizer — it can't
count tokens. Putting `tokenCount()` on `SparseEncoder` works but it's not a
sparse-encoding capability, it's a tokenizer capability. The interface becomes
a grab-bag of unrelated methods.

### 5. Three fields → three fields

The plan replaces `spladeEncoder + spladeIdfQueryEncoder + bgeM3Encoder` with
`sparseEncoder + denseEncoder + unifiedEncoder`. Same field count. The
`instanceof UnifiedEncoder` check for the single-encode optimization is the same
branching in different clothing.

## What we have instead

The current null-check branching is explicit, confined to ~7 grepable sites, and
each site has clear fallback semantics. It's ugly but debuggable.

## If this tempdoc is activated

The correct approach (informed by the failed analysis above):

1. **Don't abstract at the encoder level.** Abstract at the *retrieval model
   configuration* level — a `RetrievalModelStrategy` that knows which encoders
   to create, how to wire them, and what batch operations to support.

2. **The strategy, not the encoder, is the unit of abstraction.** A SPLADE
   strategy creates `SpladeEncoder` + `SpladeIdfQueryEncoder` + `EmbeddingService`.
   A BGE-M3 strategy creates `BgeM3Encoder` (once, used for both). The strategy
   exposes `encodeSparse()`, `encodeDense()`, `encodeBoth()` without the caller
   knowing the underlying model topology.

3. **The backfill, health check, and SearchOrchestrator take the strategy, not
   individual encoders.** One wiring point, one field, no branching.

This requires a third model to validate — designing from two data points risks
building the wrong abstraction.

## References

- 322: BGE-M3 integration (source of the branching)
- SpladeEncoder: `modules/worker-core/.../splade/SpladeEncoder.java` (1140 lines)
- BgeM3Encoder: `modules/worker-core/.../bgem3/BgeM3Encoder.java` (~575 lines)
- Integration sites: SearchOrchestrator, IndexingLoop, GrpcHealthService,
  KnowledgeServer, DefaultWorkerAppServices, GrpcSearchService

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 61 days at audit time.

