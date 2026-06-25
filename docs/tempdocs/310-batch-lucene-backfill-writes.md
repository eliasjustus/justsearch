---
title: "310: Batch Lucene Backfill Writes"
type: tempdoc
status: done
created: 2026-03-16
---

> NOTE: Noncanonical doc (architecture + implementation). May drift.

# 310: Batch Lucene Backfill Writes

## Purpose

Reduce the per-doc cost of `LuceneIndexRuntime.updateDocument()` during
SPLADE backfill. Profiling (tempdoc 278, 2026-03-16) shows this single
operation consumes 74% of the SPLADE backfill pipeline time at 88ms/doc.
Inference (tokenize + ORT + postProcess) is only 28ms/doc (24%).

This is the highest-impact optimization remaining for SPLADE backfill
throughput. Model optimization (O3+INT8, O3+FP16) has reached diminishing
returns — further gains require reducing the Lucene write cost.

## Evidence (from tempdoc 278 profiling)

GPU SPLADE backfill, O3+FP16, batch=50, 1000-doc SciFact subset:

| Phase | Avg/doc | % of total |
|-------|---------|------------|
| Query (find pending IDs) | 0ms | 0% |
| Content fetch | 0ms | 0% |
| Encode (tokenize+ORT+postProcess) | 28ms | 24% |
| **Lucene updateDocument()** | **88ms** | **74%** |
| Commit | 3ms | 2% |
| **Total** | **119ms/doc** | |

Current SPLADE backfill rate: 4.17 docs/sec (GPU FP32 avg on full SciFact).
With 50% write cost reduction: ~16 docs/sec. With zero write cost: ~36.

## Root Cause Analysis

### The full per-doc call chain

`SpladeBackfillOps` calls `indexRuntime.updateDocument(docId, updates)` once
per document in a loop of 50 docs. Each call executes:

1. **`searcherManager.maybeRefreshBlocking()`** — opens new NRT segment
   readers to see the PREVIOUS doc's write. Called unconditionally per doc
   (line 2066 of `LuceneIndexRuntime.java`). This is the dominant cost.
2. **`searcher.search(TermQuery, 1)`** — find the doc by ID in the index.
3. **`searcher.storedFields().document()`** — load ALL stored fields from
   disk for the entire document (sequential read, can't skip large fields).
4. **Field merge** — `HashMap` accumulation of all fields, then
   `fields.putAll(updates)` to apply SPLADE data.
5. **`fieldMapper.toDocument(fields)`** — rebuild the entire Lucene Document
   from scratch, including re-creating all non-SPLADE fields.
6. **`IndexWriter.updateDocument(Term, Document)`** — delete old doc + add
   new doc to in-memory RAM buffer.

### Why the NRT refresh dominates

During a batch of 50 docs, each `updateDocument()` writes to the IndexWriter
RAM buffer (step 6). The next call's `maybeRefreshBlocking()` (step 1) must
then open new NRT segment readers to see that buffered write — even though
backfill doesn't need read-after-write consistency. This forces 50 NRT
refreshes when 0 are needed.

The NRT refresh cost scales with the number of pending in-memory segments.
After writing N docs without commit, there are N pending operations that each
refresh must incorporate. Profiling confirms: avg 85ms/doc refresh, with
spikes to 712ms during segment merges.

### Why `maybeRefreshBlocking()` exists here

The refresh was added for interactive read-after-write correctness: when
a document is indexed and immediately queried by the UI (e.g., VDU updates),
the searcher must reflect the latest state. This is appropriate for the
primary indexing path but unnecessary for batch backfill, where:

- The caller controls the full pipeline (no concurrent readers)
- All docs in the batch are independent (different docIds)
- No doc is read twice in the same batch
- A single pre-batch refresh is sufficient to find all pending docs

### StoredFields loading overhead

Lucene stored fields are compressed in blocks. Loading a document requires
reading the entire block sequentially — even fields not needed. The default
behavior loads ALL stored fields. For documents with large content fields,
this is expensive but unavoidable when the full document must be rewritten.

## Investigation Results

### 1. Profile updateDocument() internals — COMPLETED

**Finding**: The dominant cost is the per-doc `maybeRefreshBlocking()` call.
Everything else is sub-millisecond.

Measured data (1,273 `updateDocument` calls, 1,323 `readModifyWrite` calls,
1000-doc SciFact subset, CPU SPLADE O3+INT8):

| Step | Avg | Median | P95 | Max | % of total |
|------|-----|--------|-----|-----|------------|
| **NRT refresh** | **85.1ms** | **75ms** | **180ms** | **712ms** | **99.0%** |
| search (TermQuery) | 0ms | 0ms | 0ms | 0ms | ~0% |
| loadFields (StoredFields) | 0ms | 0ms | 0ms | 0ms | ~0% |
| toDocument (FieldMapper) | 0ms | 0ms | 0ms | 1ms | ~0% |
| iwUpdate (IndexWriter) | 0.07ms | 0ms | 1ms | 4ms | ~0.1% |
| **Total** | **85.9ms** | **76ms** | **181ms** | **713ms** | **100%** |

Key observations:
- **99.0% of updateDocument() time is NRT refresh.** The read-modify-write
  itself (search + load + rebuild + write) averages **0.07ms total**.
- 3.2% of calls had zero-refresh (0ms) — these are the first doc in each
  batch cycle where no prior write needs refreshing.
- Non-zero refreshes average 87.9ms, with occasional spikes to 712ms
  (likely during Lucene segment merges).
- StoredFields loading is sub-millisecond even for full documents — the
  previous estimate of "5-10ms" was wrong. MMapDirectory makes this fast.

**Implication**: Eliminating per-doc `maybeRefreshBlocking()` reduces write
cost from ~86ms/doc to **<1ms/doc**. This is a much larger win than
originally estimated.

### 2. Assess Lucene updateDocValues() — NOT VIABLE for SPLADE

**Finding**: DocValues cannot replace FeatureField for SPLADE search.

- FeatureField stores term-level weights in the **inverted index** (postings).
  This enables `FeatureField.newLinearQuery()` with WAND/Block-Max scoring
  acceleration — critical for search performance.
- DocValues is a **column-oriented** structure for per-document values.
  It cannot represent per-term weights in the inverted index.
- A hybrid approach (DocValues for storage, deferred FeatureField rebuild)
  adds complexity without eliminating the core problem: the document still
  needs to be rewritten to add FeatureField postings.

DocValues IS viable for metadata-only updates (splade_status,
splade_retry_count), but metadata updates are not the bottleneck.

**Verdict**: Ruled out. FeatureField must be written via document rewrite.

### 3. Batch IndexWriter operations — RECOMMENDED (highest impact)

**Finding**: Batching is viable and has the highest expected impact.

#### Approach A: Batch read-modify-write with single refresh

1. Call `maybeRefreshBlocking()` once (or skip entirely for backfill)
2. Acquire one IndexSearcher for the entire batch
3. Read all 50 documents via `storedFields().document()` in a single
   searcher session
4. Build all 50 updated Documents via `fieldMapper.toDocument()`
5. Write all 50 via individual `IndexWriter.updateDocument()` calls
   (each is a fast RAM buffer operation without inter-write refreshes)
6. Commit once at the end

This eliminates 49/50 NRT refreshes. Profiling confirms the remaining
per-doc cost (steps 3-5) is **<1ms/doc** — sub-millisecond for search,
StoredFields load, FieldMapper rebuild, and IndexWriter write combined.

**Expected throughput**: ~32 docs/sec (inference-limited at 28ms/doc).

#### Approach B: Batch delete + batch add (more aggressive)

1. Read all 50 docs (same as Approach A steps 1-4)
2. Call `deleteDocuments(Term[])` with all 50 doc ID terms
3. Call `addDocuments(Iterable<Document>)` with all 50 rebuilt docs
4. Commit once

Lucene's `deleteDocuments(Term...)` accepts an array — all deletes are
buffered atomically. `addDocuments(Iterable)` adds a block atomically.
This may have slightly less per-doc overhead than individual
`updateDocument()` calls, but the difference is small (both are RAM
buffer operations).

**Risk**: Approach B loses the atomicity of per-doc delete+add. If the
process crashes between delete and add, documents are lost. Approach A
preserves per-doc atomicity via `IndexWriter.updateDocument()`.

**Recommendation**: Start with Approach A (safer, simpler, captures
~90% of the gain). Measure. Consider Approach B only if Approach A's
per-doc IndexWriter overhead is still significant.

### 4. Reduce field copy overhead — NOT NEEDED

**Finding**: Profiling shows StoredFields loading is sub-millisecond (0ms
in all 1,323 samples). The previous estimate of "5-10ms/doc" was wrong —
MMapDirectory makes stored field access fast for SciFact-sized documents.
No optimization needed here. Options listed below for reference only:

#### Option 4a: Skip refresh entirely for backfill

Add a `skipRefresh` parameter or a new `batchUpdateDocuments()` API that
takes `List<String> docIds` + `List<Map<String, Object>> updatesList`.
The batch method does a single refresh (or none) before reading all docs.

#### Option 4b: Parallel/side-car index (ParallelReader)

Lucene's `ParallelReader` can combine fields from two separate indexes
with near-zero query overhead (one additional HashMap lookup per term).
SPLADE FeatureField could live in a dedicated parallel index, eliminating
the need to read+rewrite the base document during backfill.

**Challenge**: The parallel index must maintain segment structure alignment
with the main index across merges. Lucene's `ParallelIncrementalIndexing`
proposal addresses this with master-slave IndexWriter coordination, but
the implementation is experimental and adds significant complexity.

**Verdict**: Not viable for initial implementation. Revisit only if
StoredFields loading remains a bottleneck after Approach A.

#### Option 4c: Reduce stored field size

Fewer/smaller stored fields = faster loading. Requires architectural
analysis of which fields are stored unnecessarily. Out of scope for
this tempdoc.

## Implementation — COMPLETED

### Stage 1: Batch RMW with single refresh (Approach A)

1. [x] Added `LuceneRuntimeTypes.BatchUpdateResult` record
2. [x] Added `WritePathOps.readModifyWriteBatch()` — loops through entries
   calling existing `readModifyWrite()` per doc with shared searcher
3. [x] Added `LuceneIndexRuntime.updateDocumentsBatch()` — one
   `maybeRefreshBlocking()`, one `withSearcher()`, delegates to batch RMW
4. [x] Updated `SpladeBackfillOps.processSpladeBackfill()` Phase 3 — collects
   all updates into a list, single `updateDocumentsBatch()` call
5. [x] Added `LuceneIndexRuntimeBatchUpdateTest` (4 tests, all passing)
6. [x] Measured: write cost dropped from 88ms/doc to **0.3ms/doc** (300x)
7. [x] Applied batch write to `EmbeddingBackfillOps` — both doc and chunk
   embedding Phase 3 write-back loops now use `updateDocumentsBatch()`
8. [x] Applied batch write to `NerBackfillOps` — collects updates during
   per-doc NER inference loop, then batch-writes at the end
9. [x] Updated `NerBackfillOpsTest` to verify `updateDocumentsBatch()` calls

### Stage 2: Skip refresh — NOT NEEDED

The single `maybeRefreshBlocking()` per batch measures 0ms in both
interleave and idle backfill. The `ControlledRealTimeReopenThread`
(500ms max stale) keeps the searcher fresh enough that the explicit
refresh is a no-op. No skip-refresh overload needed.

## Measured Results (2026-03-16)

### Batch write timing (from `updateDocumentsBatch` instrumentation)

| Batch size | Refresh | Batch RMW | Per-doc RMW | Updated |
|-----------|---------|-----------|-------------|---------|
| 10 (interleave) | 0ms | 3-11ms | 0.3-1.1ms | 10/10 |
| **50 (idle)** | **0ms** | **13-15ms** | **0.3ms** | **50/50** |

### Pipeline profile comparison (idle backfill, batch=50)

| Metric | Before (per-doc refresh) | After — CPU O3+INT8 | After — GPU O3+FP16 |
|--------|-------------------------|---------------------|---------------------|
| Lucene write/batch | 4,400ms | **15ms** | **11ms** |
| Lucene write/doc | 88ms | **0.3ms** | **0.2ms** |
| Encode/batch | 4,000ms | 4,700ms | **1,077ms** |
| Commit/batch | 130ms | 150ms | 151ms |
| **Total/batch** | **8,600ms** | **4,900ms** | **1,246ms** |
| **Per-doc pipeline rate** | **5.8 docs/sec** | **10.2 docs/sec** | **40.1 docs/sec** |

GPU O3+FP16 achieves **40.1 docs/sec** per-batch rate — near the
theoretical ceiling of ~36 docs/sec projected when write cost was zero.
The pipeline is now fully **encode-limited**: write is 0.2ms/doc (0.9%
of pipeline), encode is 21.5ms/doc (86%), commit is 3ms/doc (12%).

### Chunk embedding batch write impact

The batch write fix applied to `EmbeddingBackfillOps` also improved
chunk embedding backfill:

| Metric | Before (per-chunk refresh) | After (batch RMW) |
|--------|---------------------------|-------------------|
| Chunk batch (100 chunks) | 18-25s | **7-13s** |
| Est. write overhead | ~8.5s | **<0.1s** |

The remaining 7-13s is ORT embedding inference (13 sub-batches of 8).

### Full-run wall-clock throughput (GPU O3+FP16, 1K SciFact)

| Metric | Value |
|--------|-------|
| Dataset | 1,000 SciFact files (998 SPLADE items) |
| Total time (ingest → all SPLADE complete) | ~243s (4.05 min) |
| **Overall rate** | **4.11 docs/sec** |
| Primary indexing phase | ~111s |
| Idle SPLADE-only phase | ~130s |
| SPLADE idle rate (per-batch) | 40.1 docs/sec |
| SPLADE effective rate (with chunk backfill gaps) | ~7.7 docs/sec |

The gap between per-batch rate (40.1/s) and effective rate (~7.7/s) is
chunk embedding backfill consuming 7-26s per loop iteration before
SPLADE runs. Once chunk embeddings complete, SPLADE runs back-to-back
at 50 docs/1.2s + 1s sleep = ~22.7 docs/sec effective.

### Idle backfill timeline (GPU, post-primary)

```
05:21:18  SPLADE 50 docs (2.1s) — no chunks pending yet
05:21:21  SPLADE 50 docs (1.3s)
05:21:23  SPLADE 50 docs (1.0s)
05:21:26  Chunk embedding 100 chunks (7.4s) — first chunk batch
05:21:33  SPLADE 50 docs (1.1s)
05:21:35  Chunk embedding 100 chunks (20.3s)
05:21:55  SPLADE 50 docs (1.1s)
05:21:57  Chunk embedding 100 chunks (19.8s)
05:22:17  SPLADE 50 docs (1.2s)
...pattern continues until chunks done...
05:23:01  Chunk embedding 73 chunks (13.1s) — final chunk batch
05:23:01  SPLADE runs back-to-back (~1.2s each)
```

### Effective throughput vs per-batch rate

Per-batch processing rate is 10.2 docs/sec, but monitoring shows only
~2 docs/sec effective throughput (50 docs every ~25 seconds). There is a
~20 second gap between consecutive SPLADE batches.

The idle loop runs backfill stages in priority order: embedding → chunks
→ NER → SPLADE → disambiguation. With 1,010 pending NER docs (100-doc
batches), NER backfill consumes most of the loop time between SPLADE
batches. The SPLADE stage only executes after NER completes its batch.

This means the effective SPLADE throughput is gated by the idle loop
scheduling, not by SPLADE processing speed. The batch write optimization
reduced SPLADE processing from ~9s to ~5s per batch, but the ~20s gap
is unchanged.

**Investigation result**: The gap is caused by **chunk embedding backfill**
running in the same loop iteration before SPLADE. See analysis below.

### Idle Loop Scheduling Analysis

Timeline from log (post-batch-fix run, 1K SciFact, CPU):

```
04:45:31  Chunk embedding backfill: 100 chunks
04:45:56  Chunk backfill complete (25.5s)
04:45:56  SPLADE backfill: 50 docs
04:46:06  SPLADE complete (9.1s)
04:46:08  SPLADE: 10 docs (interleave? 2.7s gap = sleep + loop overhead)
04:46:10  SPLADE complete (1.6s)
04:46:10  Chunk embedding backfill: 80 chunks
04:46:28  Chunk backfill complete (18.2s)
04:46:28  SPLADE backfill: 50 docs
04:46:37  SPLADE complete (8.8s)
04:46:37  SPLADE backfill: 50 docs (0.1s gap!)
04:46:47  SPLADE complete (9.8s)
04:46:48  SPLADE backfill: 50 docs (1.0s gap = idle sleep)
...continues at ~9s per batch, ~1s gaps...
```

Key observations:

1. **Chunk embedding backfill is the blocker** — takes 18-25s per batch of
   80-100 chunks. Runs before SPLADE in the loop. Once chunks are done,
   SPLADE runs back-to-back with only 1s idle sleep between batches.

2. **NER is NOT a factor** — NER model not present in dev setup. The NER
   gate check (`nerService.isAvailable()`) fails fast.

3. **Post-chunk SPLADE rate** — once chunk embeddings complete, SPLADE runs
   at 50 docs/9s = 5.6 docs/sec (encode-limited). With 1s sleep between
   batches: 50/(9+1) = 5.0 docs/sec effective.

4. **One SPLADE failure** at 04:46:57 triggered a 2s backoff. This is the
   exponential backoff: `1000ms * 2^1 = 2000ms`. The failure was likely a
   transient issue (next batch succeeded).

### Effective throughput structure

| Phase | Duration | SPLADE effective rate |
|-------|----------|---------------------|
| Primary indexing (queue > 0) | ~3 min | ~1 doc/sec (interleave batch=10 every 5s) |
| Chunk embedding backfill | ~45s | 0 (SPLADE blocked) |
| **Pure SPLADE backfill** | **variable** | **5.0 docs/sec (CPU O3+INT8)** |

For mldr-en (1.4M items):
- At 5.0 docs/sec: 77.8 hours
- At 10 docs/sec (GPU, est.): 38.9 hours
- Target 8 hours needs 48.6 docs/sec — requires faster encode or parallelism

### Remaining throughput limiters (uninvestigated)

With write cost eliminated, the next-largest overhead sources are loop
scheduling and amortization, not inference:

1. ~~1s idle sleep between loop iterations~~ — **Fixed.** The loop now uses
   100ms sleep when backfill is active, 1000ms when truly idle. Measured
   SPLADE gap between batches dropped from 1.0s to 0.1-0.4s. Pure SPLADE
   phase: **~35.7 docs/sec effective** (up from 22.7). Overall pipeline
   rate unchanged (4.1 docs/sec) because chunk embedding still dominates.

2. ~~SPLADE batch size (50)~~ — **Increased to 200.** Larger batches
   amortize commit overhead and improve GPU sub-batch packing. Measured:
   per-batch rate **54.9 docs/sec** (was 40.1 at batch=50, +37%).
   Encode/doc dropped from 21.5ms to 16.4ms (less padding waste).
   Pure SPLADE effective rate: ~52 docs/sec (was ~35.7, +46%).

3. **Commit frequency** — Investigated. At batch=200 (GPU), commit is
   304ms per batch = 8.3% of pipeline time. Tested deferred time-based
   commits (every 10s instead of per-batch): adds complexity (state
   tracking, drain commits) for ~5% theoretical gain. Run was confounded
   by unrelated ORT CUDA error causing an 80s stall. **Reverted** — the
   complexity/risk ratio is unfavorable for a marginal gain. Per-batch
   commit is simpler and safer for crash recovery.

4. ~~GPU utilization~~ — **Confirmed 85% avg** (up from 15% pre-fix).
   Write overhead between GPU calls dropped from 88ms to 0.2ms, keeping
   the GPU fed between inference calls.

5. **Chunk embedding GPU failure** — Investigated. The embedding GPU
   session (1024MB) IS initialized alongside SPLADE GPU (4096MB), but
   **fails with ORT_RUNTIME_EXCEPTION during inference** when both run
   concurrently. The OnnxEmbeddingEncoder falls back to CPU permanently
   after the first GPU failure (`gpuAvailable = false`). This is why
   chunk embedding takes 18-24s (CPU) despite GPU being configured.

   Root cause: GPU memory contention — both sessions allocate from the
   same 12GB RTX 4070. The 1024MB embedding arena may be too small for
   batch=8 with long inputs when SPLADE holds 4096MB.

   Options (not implemented — separate tempdoc recommended):
   - Reduce SPLADE GPU arena (4096→2048MB) to leave room for embedding
   - Increase embedding GPU arena (1024→2048MB)
   - Sequence GPU usage: run embedding GPU first, release, then SPLADE GPU
   - Accept CPU embedding for chunk backfill (current behavior, 18-24s)

6. **Primary indexing throughput** — Investigated. ~9 docs/sec for 1K
   SciFact. The pipeline is single-threaded with no parallelism.
   Bottleneck structure (per-doc costs for 200-byte .txt files):

   | Cost | Where | Est. per doc |
   |------|-------|-------------|
   | Tika extraction + thread-hop | `TimeboxedContentExtractor` single-thread executor | 2-10ms |
   | Lucene updateDocument + analysis | `WritePathOps.indexDocument()` | 5-20ms |
   | `isUnmodified()` Lucene query | `DocumentQueryOps` — NRT refresh + TermQuery per doc | 1-5ms |
   | `queueDepth()` SQLite COUNT | `SqliteJobQueue.queueDepth()` per written doc | 1-3ms |
   | Filesystem stats | `Files.size()` + `getLastModifiedTime()` per doc | 0.5-2ms |

   Two significant inefficiencies found:
   - **`queueDepth()` per doc**: Full `SELECT COUNT(*) FROM jobs` with
     ReentrantLock after every single document write. 1000+ SQLite
     queries per SciFact run, purely for a metrics gauge.
   - **`drainPendingMarkDone()` after commit**: N individual SQLite
     `UPDATE` calls (not batched). Up to 1000 sequential single-row
     updates with lock acquisition per row at each commit boundary.

   These are out of scope for tempdoc 310 (backfill writes) but
   documented here for future reference.

### Per-doc `updateDocument()` overhead in other stages

All backfill stages except SPLADE Phase 3 still use per-doc
`updateDocument()` with the 85ms/doc NRT refresh overhead:

| Stage | Per-doc calls | Batch size | Est. refresh overhead/batch |
|-------|--------------|------------|----------------------------|
| **Chunk embedding** | 1-2 per chunk | 100 | **~8.5s** |
| NER | 1-2 per doc | 100 | ~8.5s (when NER enabled) |
| Embedding | 1-2 per doc | 100 | ~8.5s |
| Disambiguation | per doc | 500 | ~42.5s |

The chunk embedding backfill's 25s batch time likely includes ~8.5s of
unnecessary NRT refresh overhead. Applying the batch write pattern to
chunk embedding would reduce its batch time by ~35%, freeing up loop
time for SPLADE to run sooner.

## Code Locations

| File | Relevance |
|------|-----------|
| `adapters-lucene/.../LuceneIndexRuntime.java:2055` | `updateDocument()` facade — per-doc refresh |
| `adapters-lucene/.../WritePathOps.java:250` | `readModifyWrite()` — the RMW implementation |
| `adapters-lucene/.../FieldMapper.java:249` | SPLADE FeatureField creation (`case "splade"`) |
| `adapters-lucene/.../ComponentsFactory.java:163` | IndexWriter config (64MB RAM, TieredMergePolicy) |
| `indexer-worker/.../SpladeBackfillOps.java:162` | Phase 3 write loop (the call site to optimize) |

## Technical Notes

- **Lucene version**: 10.4.0 (latest, supports all discussed features)
- **IndexWriter config**: 64MB RAM buffer, TieredMergePolicy (15 segs/tier),
  MMapDirectory, `JustSearchCodec`, NRT reopen 500ms max stale
- **FeatureField**: Not stored, no DocValues. One FeatureField per SPLADE
  token (field="splade", feature=token, value=weight capped at 64.0f).
  Queried via `FeatureField.newLinearQuery()` (linear scoring, SHOULD clauses)
- **SPLADE field config** (from `SSOT/catalogs/fields.v1.json`):
  `{"id": "splade", "type": "splade", "stored": false, "docValues": false}`
- **Existing batch API**: `WritePathOps.applyBatch()` exists for primary
  indexing but does NOT support read-modify-write. Only full-document writes.

## Constraints

1. **Single-writer invariant.** Lucene IndexWriter is single-threaded.
2. **Search correctness.** SPLADE FeatureField must remain queryable via
   `FeatureField.newLinearQuery()` for the Score-max search path.
3. **Crash recovery.** The current commit-after-batch pattern must be
   preserved or replaced with an equally safe alternative.
4. **Atomicity.** Per-doc `updateDocument()` ensures atomic delete+add.
   Any batch approach must not lose documents on crash between operations.

## Dependencies

- **278 (Indexing Throughput):** Provides the profiling data and model
  optimization baseline. Paused pending this work.
- **273 (SPLADE Follow-up):** mldr-en overnight run becomes feasible
  if SPLADE backfill reaches ~50 docs/sec (8-hour target for 1.4M items).

## Non-Goals

- Changing the SPLADE encoding architecture (batching, GPU utilization)
- Multi-writer Lucene
- Changing the primary indexing path (this is backfill-specific)
- ParallelReader/side-car index (too complex for the expected gain)

## Research Sources

- [Lucene IndexWriter API (batch operations)](https://lucene.apache.org/core/7_0_1/core/org/apache/lucene/index/IndexWriter.html)
- [NRT readers and SearcherManager](https://blog.mikemccandless.com/2011/11/near-real-time-readers-with-lucenes.html)
- [Lucene ImproveIndexingSpeed wiki](https://cwiki.apache.org/confluence/display/lucene/ImproveIndexingSpeed)
- [Lucene #11799: Indexing method for learned sparse retrieval](https://github.com/apache/lucene/issues/11799)
- [Lucene ParallelIncrementalIndexing](https://cwiki.apache.org/confluence/display/lucene/ParallelIncrementalIndexing)
- [OpenSearch sparse retrieval deep dive](https://opensearch.org/blog/A-deep-dive-into-faster-semantic-sparse-retrieval-in-OS-2.12/)
- [Lucene IndexWriter internals (Alibaba)](https://www.alibabacloud.com/blog/lucene-indexwriter-an-in-depth-introduction_594673)
- [StoredFields performance analysis](https://bookstack.kb.ucla.edu/books/programming-and-web-development/page/why-are-lucenes-stored-fields-so-slow-to-access)
- [Updatable DocValues internals](http://shaierera.blogspot.com/2014/04/updatable-docvalues-under-hood.html)

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Batch backfill architecture (528 lines). Lucene-internals reference doc. Architecture proposal phase concluded; implementations of batched writes have happened in subsequent enrichment-batch work.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

