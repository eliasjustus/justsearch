---
title: "348: Head-Side Reranker BFCArena OOM (20 GB)"
status: done
created: 2026-03-26
updated: 2026-03-26
---

# 348: Head-Side Reranker BFCArena OOM (20 GB)

## Problem

The Head JVM (not the Worker) grows to 20+ GB when a search query
triggers the `CrossEncoderReranker` on long documents. The reranker
runs in the Head process on CPU with no BFCArena memory limit. Long
query+document pairs produce quadratic attention matrices that cause
unbounded native memory allocation.

## Evidence

Stack trace from `app.log` (Head process):

```
OrtException: BFCArena::AllocateRawInternal
  Failed to allocate memory for requested buffer of size 64424509440
  at FusedMatMul node '/model/layers.0/attn/MatMul/MatMulScaleFusion/'
  at CrossEncoderReranker.rerank(CrossEncoderReranker.java:401)
  at KnowledgeHttpApiAdapter.doSearch(KnowledgeHttpApiAdapter.java:653)
```

**64 GB allocation attempt** for a single attention matrix. The
ModernBERT reranker (8192 max seq len) computes `[batch, heads, seqlen,
seqlen]` attention scores. At `seqlen=8192` with 12 heads:

```
8192 × 8192 × 12 × 4 bytes = 3.0 GB per batch element
```

With fused operators and intermediate buffers, the total reaches 60+ GB.

## Process memory breakdown (jcmd + OS)

```
JVM heap:     reserved 8 GB, committed 112 MB, used 81 MB
OS working set: 20.64 GB
OS private mem: 38.87 GB
OS virtual mem: 104.6 GB
```

The 20 GB is entirely ORT BFCArena native memory, not JVM heap.

## Why the reranker runs in the Head

The `CrossEncoderReranker` is a **search-time** component, not an
indexing-time component. It reranks the top-K BM25/dense results
returned by the Worker before presenting them to the user. The
search flow is:

```
User query → Head (LocalApiServer)
  → gRPC to Worker (BM25 + dense retrieval, returns top-50)
  → Head reranks top-50 with CrossEncoderReranker (ORT session)
  → Head returns reranked top-10 to user
```

The reranker sits in `app-services` (Head-side) because:
1. It operates on search results, not on the index
2. It needs the query text (available in Head, not in Worker)
3. It's a post-retrieval filter — the Worker returns candidates,
   the Head refines them
4. Keeping it Head-side avoids a second gRPC round-trip

This is architecturally correct. The issue isn't that the reranker
is in the Head — it's that the CPU ORT session has no memory guard.

## Root cause

`CrossEncoderReranker` creates its CPU `OrtSession` via
`OnnxSessionCache` (graph-optimized, CPU-only). Unlike the GPU
sessions created by `OrtSessionFactory` (which set
`arena_extend_strategy=kSameAsRequested` and bounded `gpu_mem_limit`),
the CPU session uses ORT's default BFCArena settings:

- No `gpu_mem_limit` (CPU arena has no equivalent cap)
- Default `arena_extend_strategy` (doubles on each allocation)
- No per-run arena shrinkage (no `RunOptions` with shrinkage config)

When the reranker tokenizes a long query+document pair (e.g., 6000+
tokens from a MultiHop-RAG article), the attention `FusedMatMul` node
requests a contiguous buffer for the `seqlen²` attention matrix. The
BFCArena allocates it, OOM-fails, but doesn't release previously
allocated memory. The Head process retains 20+ GB of dead arena
allocations.

## Previous misdiagnosis

This issue was initially attributed to:
- Worker-side embedding CPU memory (tempdoc 347) — WRONG process
- ORT native memory during embedding backfill — WRONG phase

The 20 GB occurs in the **Head** during **search reranking**, not in
the Worker during embedding. The confusion arose because:
1. `tasklist` shows both Java processes without distinguishing Head vs Worker
2. JVM heap metrics (0.1 GB) looked fine — the bloat is native
3. The timing coincided with embedding completion (when verification
   searches were first attempted)

## Potential fixes

### F1: Truncate reranker input to 2048 tokens (safest)

The reranker's `maxSequenceLength=8192` allows inputs that cause
quadratic memory explosion. Truncating to 2048 tokens:
- Attention matrix: `2048² × 12 × 4 = 192 MB` (vs 3 GB at 8192)
- Matches the cross-encoder quality sweet spot (most relevance signal
  is in the first 2K tokens)
- The reranker still sees enough context for accurate scoring

### F2: Set CPU BFCArena memory limit

ORT's `SessionOptions.addConfigEntry("arena.max_mem", ...)` can cap
the CPU arena. This prevents unbounded growth but causes OOM errors
instead — the reranker would fail rather than allocate 20 GB.

### F3: Use `OrtSessionFactory` for the CPU session

`OrtSessionFactory` already sets `kSameAsRequested` arena strategy
(exact allocation, no doubling). Applying this to the CPU reranker
session would make allocations tighter but doesn't prevent the
fundamental quadratic cost.

### F4: Move reranker to Worker (architectural change)

The Worker already has ORT infrastructure, GPU sessions, and bounded
arenas. Moving the reranker there would share the GPU session pool.
However, this requires an additional gRPC call and changes the
search pipeline architecture.

### Recommendation

F1 (truncate to 2048) is the right fix. The 8192 max was adopted
from the model's capability (tempdoc 309 §41), not from a quality
requirement. Cross-encoder rerankers produce diminishing returns
beyond ~1K tokens. 2048 provides a 16× memory reduction with
negligible quality impact.

## Additional finding: Worker embedding backfill memory

Separate from the Head-side reranker issue, the Worker process
also grows to 20+ GB during active embedding backfill on large
corpora. When embeddings are already computed (fingerprint match,
100% coverage), Worker uses 2.75 GB. The 20 GB only occurs during
active computation and is NOT released after backfill completes.

This is ONNX Runtime BFCArena native memory (off-heap, not shown
in JVM heap metrics). jseval progress reports 0.1 GB heap because
it only measures JVM heap, not native allocations.

Verified: re-ingesting the same corpus (no new embeddings needed)
shows 2.75 GB Worker process. Clean index + fresh embedding
backfill shows 20 GB. The memory scales with embedding batch
processing, not with index size.

## Related

- **309** §41: GTE-ModernBERT adopted with 8192 max seq len
- **347**: GPU auto-detection (fixes Worker embedding, not this)
- **349**: OrtSessionFactory (GPU sessions only, CPU reranker excluded)
- **346**: Agent retrieval eval (triggered the symptom via search)

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Head-side reranker BFCArena OOM investigation. Investigation reached its findings; related tempdocs (309, 346, 347, 349) consumed the cross-cutting recommendations.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

