---
title: "Tempdoc 294 — Search Pipeline Instrumentation"
---

# Tempdoc 294 — Search Pipeline Instrumentation

**Status:** Complete
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Add per-stage timing, logging, and observability to the search pipeline's recently added stages, enabling operators and eval tools to diagnose performance and behavior at granular level.

## Context

The search pipeline now has 18+ stages spanning two processes. Most stages have adequate instrumentation (OTel spans, timing fields in the gRPC response, DEBUG logging). However, the recently added chunk branch retrieval (stage 3a) and branch fusion (stage 3b) are instrumented as a single undifferentiated `search/chunk_merge` OTel span with a single `chunkMergeMs` timing field. Internal structure is opaque.

## Investigation Findings (2026-03-14)

### Current state of chunk merge instrumentation

**`executeChunkBranchFusion()` (lines 1041–1086):**
- Three chunk legs (`searchChunksText`, `searchChunkVector`, `searchChunksSplade`) run **sequentially** — no parallel execution, no individual timing, no logging at all.
- No OTel span inside this method.
- No log when the method returns an empty parent result.

**`mergeChunkResults()` (lines 914–1039):**
- Calls `executeChunkBranchFusion` (initial pass), checks for empty results (silent fallback at line 968), optionally retries with doubled budget (silent at line 975), then runs branch fusion (no timing, no logging).
- No log at any decision point.

**Chunk merge gate (lines 556–604):**
- Skip reasons (`SKIPPED_DISABLED`, `SKIPPED_NO_CHUNK_DOCS`, etc.) are set on the proto response but never logged server-side.

**OTel span `"search/chunk_merge"` (lines 562–588):**
- ZERO `setAttribute()` calls — only records wall-clock duration. Compare to `"search/retrieval"` which sets `search.took_ms` and `search.mode`.

**`ComponentTiming` proto (indexing.proto lines 110–114):**
- Only 3 fields: `retrieval_ms` (1), `chunk_merge_ms` (2), `chunk_count` (3). Next available: field 4.

### Existing conventions

- **Timing:** `System.nanoTime()` start/stop, `/1_000_000` for ms, stored in local `long`.
- **Logging:** SLF4J positional `{}`. `log.debug` for routine flow, `log.info` for notable corrections, `log.warn` for soft failures. Query text wrapped in `redact(new SensitiveQuery(...))`.
- **Proto:** `SearchResponse` highest field is 22; `ComponentTiming` highest is 3.

## Gaps (all resolved except G6)

| Gap | Description | Fix |
|-----|-------------|-----|
| **G1** | No per-leg timing inside chunk branch | A2 (per-leg nanoTime + summary log) + A3 (proto fields) + A4 (OTel span attributes) |
| **G2** | No log when chunk merge is skipped | A1 (gate skip log) |
| **G3** | No log when chunk branch returns empty | A1 (empty initial + empty retry logs) |
| **G4** | No log when retry path fires | A1 (retry trigger log) |
| **G5** | OTel span has zero attributes | A4 (6 span attributes: merge_ms, count, bm25_ms, knn_ms, splade_ms, retry) |
| **G6** | Soft timeout for chunk branch | Deferred — needs perf data from A2 |

## Action Items

### A1: Add DEBUG logging to chunk merge decision points (G2, G3, G4)

- [x] Gate skip: `log.debug("Chunk merge skipped: {}", chunkMergeReason)` after the else-if chain
- [x] Empty initial chunk branch: log with budget before fallback to whole-doc
- [x] Retry trigger: log with hit count, limit, old/new budget before retry call
- [x] Empty retry chunk branch: log with retry budget before fallback

### A2: Add per-leg timing inside `executeChunkBranchFusion()` (G1)

- [x] `System.nanoTime()` wrap around each of 3 chunk leg calls
- [x] Summary DEBUG log after fusion+collapse: `"Chunk branch: bm25={}ms/{} hits, knn={}ms/{} hits, splade={}ms/{} hits, fused={}, collapsed={}, budget={}"`
- [x] Expanded `ChunkBranchResult` record with `bm25Ns`, `knnNs`, `spladeNs`
- [x] New `ChunkMergeResult` record to return timing + retry flag from `mergeChunkResults`
- [x] Retry timing is accumulated (initial + retry pass) so per-leg totals reflect all work done
- [x] `hitCount` helper for null-safe hit count in log messages

### A3: Add per-leg timing to `ComponentTiming` proto (G1)

- [x] 4 new proto fields: `chunk_bm25_ms` (4), `chunk_knn_ms` (5), `chunk_splade_ms` (6), `chunk_retry` (7)
- [x] Wired from `SearchOrchestrator` → proto builder
- [x] Wired from proto → `KnowledgeHttpApiAdapter` → `PipelineExecution` response (4 new fields: `chunkBm25Ms`, `chunkKnnMs`, `chunkSpladeMs`, `chunkRetry`)

### A4: Add attributes to `search/chunk_merge` OTel span (G5)

- [x] 6 span attributes: `search.chunk_merge_ms`, `search.chunk_count`, `search.chunk_bm25_ms`, `search.chunk_knn_ms`, `search.chunk_splade_ms`, `search.chunk_retry`

### A5: Evaluate chunk branch soft timeout (G6 — deferred)

Deferred — initial measurements from A2 show chunk merge total is 9–11ms with BM25 at 4–6ms and branch fusion overhead at 3–7ms. These are well within the 5s gRPC deadline. A soft timeout is not needed at current scale. Revisit if the corpus grows significantly or if the retry path becomes common.

## Verification (2026-03-14)

Verified against live queries on a ~600-doc BEIR courtlistener corpus with chunk embeddings. Temporarily set `SearchOrchestrator` to DEBUG in the Worker logback, ran two queries via MCP, confirmed output in `worker.log`:

```
17:10:39.950 Chunk branch: bm25=4ms/32 hits, knn=0ms/0 hits, splade=0ms/0 hits, fused=32, collapsed=13, budget=200
17:10:39.954 Chunk merge completed in 11ms, 16 results after collapse

17:11:20.894 Chunk branch: bm25=6ms/200 hits, knn=0ms/0 hits, splade=0ms/0 hits, fused=200, collapsed=40, budget=200
17:11:20.895 Chunk merge completed in 9ms, 20 results after collapse
```

Key findings:
- BM25 chunk leg: **4–6ms** (dominant cost)
- KNN/SPLADE: 0ms (not active in this corpus — no embeddings/SPLADE on chunk queries without GPU)
- Branch fusion (Stage 3b) overhead: **3–7ms** — not sub-millisecond as initially assumed, but small. Dominated by parent-doc collapse lookups rather than the CC/RRF fusion math.
- Total chunk merge: **9–11ms** — well within the 5s gRPC deadline.

## Files Changed

| File | Change |
|------|--------|
| `modules/indexer-worker/.../SearchOrchestrator.java` | A1: 4 log.debug calls. A2: per-leg timing, expanded records, summary log, hitCount helper, accumulated retry timing. A3+branch fusion timing: proto builder wiring. A4: 7 span attributes. |
| `modules/ipc-common/src/main/proto/indexing.proto` | A3: 5 new fields in `ComponentTiming` (field numbers 4–8) |
| `modules/app-api/.../KnowledgeSearchResponse.java` | A3: 5 new fields in `PipelineExecution` record |
| `modules/app-services/.../KnowledgeHttpApiAdapter.java` | A3: read new proto fields, pass to `PipelineExecution` |
