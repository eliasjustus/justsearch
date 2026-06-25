---
title: "334: Single-Pass Enrichment (Eliminate Backfill RMW Churn)"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-04-09
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 334: Single-Pass Enrichment

## Purpose

Eliminate the structural RMW churn that causes cross-stage data loss
during backfill. Currently, each backfill stage (embedding, SPLADE, NER)
independently does read-modify-write on every document. Non-stored fields
(SPLADE FeatureField, embedding KnnFloatVectorField) are dropped during
reconstruction, forcing other stages to re-process. Measured: 54-249%
SPLADE churn depending on concurrency (tempdoc 312 items 36-39).

## Context (from tempdoc 312)

Tempdoc 312 identified and fixed two immediate bugs:
- **BUG-1:** RMW drops non-stored fields; fixed by resetting
  `splade_status=PENDING` so SPLADE re-processes (conservative fix,
  causes churn but no data loss)
- **BUG-2:** Disambiguation loop blocked SPLADE; fixed by adding a
  completion flag

These fixes made the pipeline functionally correct (all stages reach
100%) but SPLADE still does 54% redundant work due to NER RMW churn.

**Current pipeline (tempdoc 312 Phase 8, Q4+GPU, SciFact 5184):**

| Stage | Duration | Rate | Notes |
|-------|----------|------|-------|
| Primary indexing | 21s | 247/sec | Deferred embedding |
| Embedding backfill (Q4 GPU) | 115s | 45.1/sec | RMW per doc |
| NER backfill | ~690s | 6.5/sec | RMW per doc, drops SPLADE |
| SPLADE backfill | 837s | 6.4/sec | 54% churn from NER RMW |
| **Total** | **837s** | | Critical path: SPLADE (churn) |

## Root Cause

Lucene segments are immutable. Updating any field requires deleting
and re-adding the entire document. `readModifyWrite()` reads stored
fields, merges the update, and calls `FieldMapper.toDocument()` to
reconstruct. Non-stored fields (FeatureField, KnnFloatVectorField,
doc-values-only status fields) are invisible to the stored-fields
reader and get dropped.

Each backfill stage runs its own RMW pass independently:
1. Embedding RMW: writes vector, drops SPLADE → SPLADE re-queued
2. NER RMW: writes entities, drops SPLADE → SPLADE re-queued again
3. SPLADE RMW: writes FeatureFields, but may be dropped by next stage

The blue-green migration (tempdoc 312 item 20) solves this for
embedding model changes by rebuilding the index from scratch. The
same principle applies here.

## Approaches (ordered by complexity)

### A. Combined single-pass backfill (low complexity)

Modify the backfill pipeline to run all enrichments in a single
combined RMW pass per document:

```
for each pending doc:
  read stored fields
  embed (if pending)
  SPLADE encode (if pending)
  NER extract (if pending)
  write once with all enrichments
```

One RMW per doc instead of three. No cross-stage data loss.

**Pros:** No new infrastructure. Reuses existing backfill ops.
No trigger/cutover complexity. Incremental (can still serve search
during backfill).

**Cons:** Still uses RMW (re-analyzes text fields). Rate limited
by slowest enrichment (NER at 6.5/s). Requires composing three
separate backfill ops into one pass.

**Estimated rate:** ~6.5 docs/sec (NER-limited), 5184 docs in
~800s. Same wall time as current but zero churn = zero wasted GPU.

### B. Blue-green rebuild after primary indexing (medium complexity)

After primary indexing completes, trigger a blue-green migration
that re-ingests all documents with inline embedding + SPLADE + NER.
Blue serves BM25 search. Green gets all enrichments in one write.

**Pros:** Zero RMW. Each doc written once. Uses proven migration
infrastructure (tempdoc 312 item 20). Blue provides BM25 during
rebuild.

**Cons:** 2x storage during rebuild. Requires wiring inline SPLADE
and NER into `processBatch()` (currently only embedding is inline).
Trigger logic needed ("primary ingest done" detection). GPU
contention between embedding + SPLADE during inline processing.

**Estimated rate:** ~6.5 docs/sec (NER-limited inline), ~800s.
Same as approach A but architecturally cleaner (no RMW at all).

### C. Parallel enrichment threads (high complexity)

Run embedding, SPLADE, and NER as parallel threads/executors
instead of sequential loop stages. Each writes its own fields
without RMW (requires Lucene architectural changes or a sidecar
store for non-text fields).

**Pros:** Maximum throughput (each enrichment at its own rate).
**Cons:** Requires fundamental Lucene write-path changes or
external vector store. Out of scope for this tempdoc.

## Recommendation

**Start with Approach A** (combined single-pass backfill). It
delivers the churn elimination with the lowest risk:
- No new trigger/cutover logic
- No blue-green complexity
- Reuses existing enrichment code
- Can be tested incrementally

If A proves insufficient (e.g., NER inline is too slow for large
datasets), escalate to B (blue-green rebuild) using the existing
migration infrastructure.

## Existing Infrastructure

| Component | File | Status |
|-----------|------|--------|
| `EmbeddingBackfillOps` | `worker-services/.../ops/EmbeddingBackfillOps.java` | Working, batch=100 |
| `SpladeBackfillOps` | `worker-services/.../ops/SpladeBackfillOps.java` | Working, batch=200 |
| `NerBackfillOps` | `worker-services/.../ops/NerBackfillOps.java` | Working, batch=100 |
| `WritePathOps.readModifyWrite()` | `adapters-lucene/.../WritePathOps.java` | Has BUG-1 fix (SPLADE reset) |
| `IndexGenerationManager` | `worker-core/.../IndexGenerationManager.java` | 806 lines, E2E tested |
| `KnowledgeServerMigrationOps` | `indexer-worker/.../KnowledgeServerMigrationOps.java` | 903 lines, E2E tested |
| `migrationActiveSupplier` | `IndexingLoop.java:131` | Enables inline embedding during migration |
| `embeddingReadyLatch` | `KnowledgeServer.java:165` | Gates enumerator on embedding readiness |

## Pre-Implementation Investigation (completed 2026-03-21)

### Codebase investigation

I-1. [x] **Backfill ops data flow comparison.**

    All three follow the same pattern: query pending → fetch content →
    encode → build update map → batch RMW via `updateDocumentsBatch()`.

    | Aspect | Embedding | SPLADE | NER |
    |--------|-----------|--------|-----|
    | Query | `EMBEDDING_STATUS=PENDING` | `SPLADE_STATUS=PENDING` | `NER_STATUS=PENDING` |
    | Batch | 100 | 200 | 100 |
    | Content | `getDocumentContentBatch()` | `getDocumentField(CHUNK_CONTENT)` fallback `getDocumentContent()` | `getDocumentContent()` |
    | Encoder | `embedDocumentBatch()` → `List<float[]>` | `encodeBatch()` → `List<Map<String,Float>>` | `extractEntities()` → `NerResult` (per-doc only) |
    | Update fields | `vector`, `embedding_status`, `embedding_retry_count` | `splade`, `splade_status`, `splade_retry_count` | `ner_status`, `ner_retry_count`, 6 entity fields |
    | Failure | retry ≤3 → PENDING, ≥3 → FAILED | same + returns boolean (systemic) | same |

    **Key:** NER has NO batch encode — strictly per-doc. Embedding and
    SPLADE have batch. All three use `updateDocumentsBatch()` for write.

I-2. [x] **RMW update map merge: YES, clean merge.**
    All field names non-overlapping. `FieldMapper.toDocument()` processes
    each field independently via switch on `def.type` — no ordering
    dependencies. Multi-valued fields (entity lists) handled correctly.
    A single merged `Map<String, Object>` works with one RMW call.

I-3. [x] **Content: all read `CONTENT`, shareable.**
    All three read `SchemaFields.CONTENT` for parent docs. SPLADE also
    tries `CHUNK_CONTENT` first (chunk docs). Preprocessing differs:
    embedding applies prefix internally, SPLADE/NER use raw text.
    A single `getDocumentContentBatch()` serves all three for parent
    docs. SPLADE chunk-content needs separate handling.

I-4. [x] **Batch size: batch=100, sub-batching is internal.**
    Each encoder handles sub-batching internally (transparent). Combined
    pass can use batch=100. NER's per-doc nature dominates batch time
    (100 × ~150ms = ~15s per outer batch).

I-5. [x] **GPU: arenas are additive, no cross-encoder mutex.**
    Both GPU sessions are init-time, held for lifetime. Peak VRAM is
    sum of both arenas (both live simultaneously). No shrink between
    `session.run()` calls. Embedding Q4 (2048MB) + SPLADE FP16 (4096MB)
    = 6144MB — crashed Worker in tempdoc 312 item 29.
    **Decision:** Run SPLADE CPU in combined pass (20ms/doc, NER-
    dominated anyway). Or run SPLADE GPU only when embedding is CPU.

I-6. [x] **Pending query: union of three separate queries.**
    Query each status separately, union doc IDs. Per-doc status check
    determines which enrichments to run. No new field needed.

I-7. [x] **Partial failure: write successful results, retry failed.**
    Existing ops write partial results (increment retry count on
    failure, status stays PENDING). Combined pass: if embedding
    succeeds but SPLADE fails, write embedding result + leave
    `splade_status=PENDING` + increment `splade_retry_count`.

### Internet research

R-1. [x] **Lucene partial field updates: still no solution.**
    LUCENE-3837 Won't Fix (2020). LUCENE-4258 stalled (2022). No
    KnnFloatVectorField or FeatureField in-place update API exists
    in Lucene 10.x. Full-document RMW is still the only option.

R-2. [x] **ORT multi-model GPU: arenas are additive.**
    Two sessions stack arenas. No Java BFCArena shrink API. CUDA
    Mempool Arena (ORT PR #26535, Nov 2025) adds shrink but not in
    stable Java bindings. Serialize GPU usage or run SPLADE CPU.

R-3. [x] **Industry pattern: pipeline-in-memory, write-once.**
    ES ingest pipelines, Solr URPs, Vespa document processors, and
    OpenSearch all use sequential enrichment chain → single write.
    Separate write passes per enrichment is an anti-pattern.
    **Strongly validates Approach A.**

### Investigation summary

All uncertainties resolved. Approach A is feasible:
- Update maps merge cleanly (I-2)
- Content is shareable (I-3)
- Sub-batching is internal (I-4)
- GPU contention solved by SPLADE CPU in combined pass (I-5)
- Partial failure semantics are straightforward (I-7)
- Industry pattern confirms the design (R-3)

**One design constraint discovered:** NER has no batch encode path.
The combined pass will be NER-limited at ~6.5 docs/sec regardless
of how fast embedding (45/s) and SPLADE (49/s) are. This is
acceptable — same wall time as current but zero wasted GPU work.

## Work Items

### Approach A: Combined single-pass backfill

1. [x] **Investigate composability of backfill ops.** (see I-1 through I-7)

2. [x] **Design combined backfill pass.** Defined in implementation plan:
   which docs to query (union of all pending statuses), how to
   batch efficiently (NER batch=100, SPLADE batch=200, embed
   batch=100 — need a common batch size), how to handle partial
   failures (one enrichment fails but others succeed).

3. [x] **Implement `CombinedEnrichmentBackfillOps`.** Created at
   `worker-services/.../ops/CombinedEnrichmentBackfillOps.java`.
   Single class: queries union of pending IDs, batch content fetch,
   batch embed → batch SPLADE → per-doc NER, merged update map,
   single `updateDocumentsBatch()` write.

4. [x] **Wire into `IndexingLoop` idle path.** Added
   `processCombinedBackfillIfApplicable()`. When ≥2 enrichment
   providers are available, runs combined pass. Falls back to
   individual stages otherwise. Individual ops preserved as fallback.

5. [x] **Verify zero churn.** Live profiling (SciFact 5184, Q4+GPU):
   - Combined pass SPLADE encodes: **5185** (target: 5184) ✓
   - Previous: 18,111 (249% churn) → **eliminated**
   - SPLADE coverage drops: **2%** (vs 54% without combined pass)

6. [x] **Measure and compare.**

   | Metric | Q4+gate39 (prev) | Combined pass | Change |
   |--------|------------------|---------------|--------|
   | Primary indexing | 21s (247/s) | 21s (247/s) | same |
   | Embedding 100% | 115s | 563s | slower (combined batch) |
   | SPLADE 100% | 837s | 782s | **6.6% faster** |
   | SPLADE churn | 54% (NER RMW) | **2%** | **eliminated** |
   | SPLADE encodes | 18,111 | **5,185** | **3.5x less GPU** |
   | Total pipeline | 837s (14.0m) | **782s (13.0m)** | **6.6% faster** |

   Embedding shows 563s because it now runs inside the combined batch
   alongside NER (rate-limited by NER at ~6.5 docs/sec). But total
   pipeline is faster because SPLADE doesn't waste 13K GPU encodes.

   Per-batch profile: docs=100, embed=1.4s, splade=2.0s, ner=10.5s,
   write=30ms, total=14.1s. NER dominates at 74% of batch time.

### Approach B: Blue-green rebuild with full inline enrichment

The migration infrastructure (`IndexGenerationManager`,
`KnowledgeServerMigrationOps`) is already built and working.
Migration currently inlines embedding only. These items extend it
to inline all enrichments, eliminating RMW entirely.

7. [ ] **Auto-trigger migration after primary ingest.** Detect
   "primary ingest done" (enumerator finished + queue drained) and
   trigger a blue-green migration automatically. Blue serves BM25
   search during rebuild.
8. [ ] **Inline SPLADE during migration `processBatch()`.** Wire
   `SpladeEncoder` into the migration's document-building path
   alongside the existing inline embedding. SPLADE encodes each
   doc's content during initial write, not as a separate backfill.
9. [ ] **Inline NER during migration `processBatch()`.** Wire
   `NerService` into the migration's document-building path. NER
   extracts entities during initial write.
10. [ ] **Combined enrichment in `buildDocument()`.** All three
    enrichments (embed + SPLADE + NER) run in sequence per document
    during migration, producing a single merged field map written
    once. Zero RMW, zero churn.
11. [ ] **Skip backfill after cutover.** After migration cutover,
    all docs are fully enriched. Backfill stages should detect this
    (all statuses = COMPLETED) and skip immediately.
12. [ ] **Verify and measure.** Compare with Approach A (163s
    enrichment, ~2% SPLADE churn, RMW overhead ~30ms/batch). Expected
    benefit: zero churn, zero RMW re-analysis overhead, cleaner
    architecture. GPU contention between embed + SPLADE needs
    investigation (I-5: arenas are additive, may need serialization).

### Phase 2: NER bottleneck optimization

NER is 74% of every combined batch (10.5s/100 docs = 105ms/doc).
Embedding (14ms) and SPLADE (20ms) are 10x faster. NER is the
single remaining bottleneck for the full pipeline.

13. [x] **NER INT8 quantization.** Downloaded INT8 `bert-base-NER`
    from `onnx-community/bert-base-NER-ONNX` (108MB vs 431MB FP32).
    Same model weights, just quantized — zero accuracy risk.
    Result: **57.3ms/doc (was 105ms) — 1.8x speedup.**
    Total pipeline: **577s (was 782s) — 26% faster.**

14. [x] **NER batch encoding — dead end (CPU and GPU).**
    Implemented `inferBatch()` with padding-to-max-len and
    `MAX_NER_BATCH_SIZE=8`. Tested both CPU and GPU batch:
    - CPU batch INT8: 137.6ms/doc (2.4x SLOWER than per-doc)
    - GPU batch INT8: 138.4ms/doc (2.4x SLOWER than per-doc)
    **Root cause:** Padding overhead. SciFact docs have variable-
    length chunks (50-400 tokens). Padding 7 short chunks to the
    longest chunk's 400 tokens wastes 8x compute. For BERT-base
    INT8, the per-call overhead is small enough that batching
    can't amortize the padding cost. GPU doesn't help because
    the wasted attention computation on padding tokens is the
    bottleneck, not raw inference speed. Reverted.

    **Why embedding/SPLADE batching works but NER doesn't:**
    - Embedding (300M params): high per-call overhead makes
      amortization worthwhile despite padding
    - SPLADE: uses fixed sequence-length buckets (128/256/384/512)
      and pinned output tensors — no variable-length padding waste
    - NER (110M INT8): fast per-call (~57ms), low overhead to
      amortize, and no length bucketing

15. [x] **NER model swap to `dslim/distilbert-NER` INT8.** 66M params,
    F1 92.17% (slightly better than bert-base-NER's 91.44%).
    Pre-built INT8 ONNX from `onnx-community/distilbert-NER-ONNX`
    (65.8MB vs 108.9MB bert-base INT8).

    **Implementation changes:**
    - `BioTagDecoder`: refactored from hardcoded label indices to
      `LabelMapping` loaded from config.json `id2label`. Supports
      both bert-base-NER (1=B-MISC) and distilbert-NER (1=B-PER)
      label orderings. Backward-compatible default mapping.
    - `BertNerInference`: auto-detects `token_type_ids` via
      `session.getInputNames().contains("token_type_ids")`.
      DistilBERT doesn't use token_type_ids (6-layer model,
      no segment embeddings). BERT does (12-layer, type_vocab_size=2).
    - `NerService`: loads label mapping from config.json at init time.
    - Tests: added `DistilBertMapping` test class verifying that
      the same logits decode differently with each mapping (index 1
      = B-MISC in bert-base, B-PER in distilbert).

    **Also fixed:** `EMBED_GPU_ENABLED` not auto-detected in
    `applyHeadlessEvalContract()`. Added auto-enable alongside
    SPLADE GPU when CUDA DLLs detected. Without this, Q4 embedding
    runs CPU-only at 3,078ms/doc (30x slower than GPU).

    **Pipeline results (SciFact 5184, Q4+GPU, DistilBERT-NER INT8):**

    | Metric | BERT-base INT8 | DistilBERT INT8 | Change |
    |--------|---------------|-----------------|--------|
    | Total pipeline | 674s (11.2m) | **483s (8.1m)** | **-28%** |
    | NER total time | 312s | 157s | **-50%** |
    | NER per-batch | 2,456ms | 1,200ms | **-51%** |
    | Embedding 100% | 416s | 307s | -26% |
    | SPLADE 100% | 603s | 421s | -30% |
    | SPLADE churn | 15 drops | 10 drops | minimal |
    | VRAM usage | 2.7GB | 1.8GB | -33% |
    | Model size | 108.9MB | 65.8MB | -40% |

    **Phase 7 baseline: ~163s enrichment, SciFact 5184 docs, GPU.**

    After Phase 7 (FP16 baked-PRESPARSE + batch=16 + 4096MB arena).
    Phase 8 (tight loop + chunk tight loop) further reduced to
    **213s total wall time (168s enrichment + 40s chunks + 25s primary).**

    Note: Phase 5 baseline was ~214s enrichment with FP32 batch=8.
    Phase 7 reduced it by 24% via FP16 + doubled batch size.

### Phase 3: Infrastructure fixes

16. [x] **Q4 embedding CPU fallback.** Q4 model runs at 3,078ms/doc
    on CPU (30x slower than GPU) because 4-bit dequantization is
    not hardware-accelerated on x86 CPUs. INT8 runs at ~150ms/doc
    CPU. Fix: `OnnxEmbeddingEncoder` now checks for `model_int8.onnx`
    when GPU is not configured and uses it as the CPU session model.
    Q4 remains as `model.onnx` (GPU default). Renamed INT8 backup
    from `model.onnx.int8-backup` to `model_int8.onnx`.

17. [x] **jseval httpx noise.** Removed the `if verbose:` guard
    around httpx/httpcore log suppression in `jseval/cli.py`. Now
    always suppresses httpx INFO-level request logging, eliminating
    the 14:1 noise ratio between HTTP polls and progress updates.

### Phase 4: NER GPU acceleration

18. [x] **NER GPU support.** `BertNerInference` was CPU-only — no
    `SessionOptions` with CUDA provider, no dual-session pattern,
    no GPU config in `NerConfig`. Added full dual-session GPU
    support following the `OnnxEmbeddingEncoder`/`SpladeEncoder`
    pattern. Auto-enabled in `applyHeadlessEvalContract()` when
    CUDA DLLs detected.

    **Critical finding: INT8 on CUDA EP is 3.75x SLOWER than CPU.**
    The CUDA Execution Provider dequantizes INT8 to FP32 per
    operation — it doesn't use native INT8 tensor cores for token
    classification models. Fix: use FP16 model for GPU sessions
    (same pattern as embedding and SPLADE). Downloaded
    `model_fp16.onnx` (131MB) from onnx-community/distilbert-NER-ONNX.

    **Verification results (SciFact 5184, Q4+GPU):**

    | NER config | NER/100 docs | Per doc | Total pipeline |
    |------------|-------------|---------|----------------|
    | BERT-base INT8 CPU | 2,456ms | 24.6ms | 674s |
    | DistilBERT INT8 CPU | 1,200ms | 12ms | 483s |
    | DistilBERT INT8 GPU | 4,500ms | 45ms | 546s (**worse**) |
    | **DistilBERT FP16 GPU** | **208ms** | **2.1ms** | **345s** |

    Pipeline: **345s (5.8 min) — 7.7x vs pre-optimization baseline.**
    NER dropped from 64% to 8% of combined batch time. SPLADE is
    now the bottleneck at 71% (878ms/batch).

    VRAM: peaked at 2.7GB (three sessions fit comfortably on 12GB).
    SPLADE churn: 8 drops (negligible).

    **Changes made:**
    - `EnvRegistry`: added `NER_GPU_ENABLED`, `NER_GPU_DEVICE_ID`,
      `NER_GPU_MEM_MB`
    - `ResolvedConfig.Ner`: added `gpuEnabled`, `gpuDeviceId`,
      `gpuMemMb` fields
    - `ResolvedConfigBuilder.buildNer()`: resolves GPU fields
    - `NerConfig`: added GPU fields, updated `fromEnv()`
    - `BertNerInference`: dual-session (CPU always-on + lazy GPU),
      `selectSession()` + `tryCreateGpuSession()` following
      `OnnxEmbeddingEncoder` pattern. FP16 model preferred for GPU
      (`model_fp16.onnx`), INT8 for CPU (`model.onnx`).
    - `NerService`: accepts `BooleanSupplier shouldUseGpu`
    - `KnowledgeServer`: wires `() -> !signalBus.isMainGpuActive()`
    - `build.gradle.kts`: `NER_GPU_ENABLED` auto-enabled when CUDA
      DLLs detected, added to `HEADLESS_GPU_ENV_VARS`
    - Downloaded `model_fp16.onnx` (131MB) for GPU path

### Phase 5: SPLADE optimization

19. [x] **SPLADE post-processing bulk read.** Profiling revealed
    post-processing (log1p + max pooling over 30K-dim MLM output)
    was 44% of SPLADE time (65.9s of 150.4s total). Root cause:
    `FloatBuffer.get()` called per-element (30K × seqLen × batch
    = ~94M calls per ORT invocation) — each is a JNI call on
    DirectByteBuffer. Fix: bulk-read entire vocab row into heap
    array via `FloatBuffer.get(float[])` (single native memcpy),
    then process on heap where JIT can auto-vectorize.

    | Metric | Before | After | Change |
    |--------|--------|-------|--------|
    | Avg post-process/call | 60ms | 37ms | **-38%** |
    | Total post-process | 65.9s | 40.6s | **-38%** |
    | Combined SPLADE/batch | 878ms | 654ms | **-25%** |
    | **Total pipeline** | **345s** | **316s** | **-8%** |

20. [x] **SPLADE GPU batch=16 — OOM crash, reverted to batch=8.**
    Pinned output at batch=16/seq=512/vocab=30522 = 952MB. Combined
    with attention buffers, exceeds 2048MB arena. Worker crashes with
    `EXCEPTION_ACCESS_VIOLATION` in `onnxruntime.dll`. batch=8
    (476MB pinned output) is the safe maximum for 2048MB arena.

21. [x] **SPLADE v2→v3 model upgrade.** Replaced `splade-cocondenser-
    selfdistil` (v2, May 2022) with `naver/splade-v3` (March 2024).
    Same architecture (BERT-base, 110M params), improved training
    recipe (8 negatives, ensemble distillation, combined loss).
    Downloaded pre-built ONNX from `castorini/splade-v3-onnx`
    (508MB FP32 + 254MB FP16 converted locally). V2 backed up to
    `v2-backup/`.

    SPLADE-v3 is 27% faster per batch (476ms vs 654ms) despite
    identical architecture — the v3 model produces sparser output
    (FLOPS 1.2 vs v2's 1.4), reducing post-processing work.

    | Metric | V2 | V3 | Change |
    |--------|-----|-----|--------|
    | SPLADE per batch | 654ms | 476ms | **-27%** |
    | Total pipeline | 316s | **274s** | **-13%** |
    | BEIR-13 nDCG@10 | 50.7 | 51.7 | +1.0 (quality) |
    | MS MARCO MRR@10 | 37.6 | 40.2 | +2.6 (quality) |

## Open Questions

- ~~VRAM budget~~ **Resolved:** Three GPU sessions peaked at 3.8GB
  (embed Q4 2048MB + SPLADE FP16 4096MB + NER FP16 ~512MB).
- ~~OpenSearch doc-v3-distill~~ **Adopted** in Phase 5 (item 21)
  with baked-PRESPARSE + FP16 in Phase 7 (items 23-25).
- ~~SPLADE FP16 on CUDA EP~~ **Resolved** in Phase 7 (item 23).
  Fix: optimize+convert entire graph including PRESPARSE ops.

## Dependencies

- **312 (Primary Indexing Throughput):** Parent tempdoc. BUG-1 fix,
  item 39, Q4 model, profiling infrastructure.
- **310 (Batch Lucene Writes):** `updateDocumentsBatch()` API.
- **338 (Config-Driven Model Decoding):** Generalizes the NER label
  mapping work done in item 15 (`BioTagDecoder` → `LabelMapping`).
- **339 (Inference Phase Timing):** Generalizes the SPLADE/NER
  per-batch timing instrumentation from items 19 and 22.
- **340 (Model File Manifest):** Generalizes the CPU/GPU model
  selection pattern from item 16 (Q4 GPU vs INT8 CPU fallback).

## Remaining optimization analysis

### Current baseline: 213s wall time (168s enrichment), SciFact 5184

After Phase 8 (tight loop + chunk tight loop + disambiguation gating).
Measured with jseval `--pipeline --timeline` (2026-03-24).

| Stage | Time |
|-------|------|
| Primary indexing | 25s (201 docs/s) |
| Embed+SPLADE+NER 100% | 168s |
| Chunk vectors 100% | 208s |
| **Total wall time** | **213s (3.5 min)** |

**Per-batch inference (53 combined batches):**

| Phase | Total ms | Per batch | % of inference |
|-------|----------|-----------|----------------|
| **Embedding (Q4 GPU)** | 60,305 | 1,138ms | **49.7%** |
| **NER (DistilBERT FP16 GPU)** | 31,375 | 592ms | **25.8%** |
| **SPLADE (FP16 baked-PRESPARSE GPU)** | 29,716 | 540ms | **24.5%** |
| Fetch + Write overhead | 3,166 | 23ms | — |
| **Total measured batch time** | **121,396** | **2,270ms** | **100%** |

**Key findings:**

1. **Fetch and write are negligible** — 23ms/batch (1%). Approach B
   (eliminating RMW) would save ~10ms/batch — not worth the complexity.

2. **Between-batch overhead reduced from 91s to ~30s** by tight loop
   (skip sleep, job poll, disambiguation between batches). Remaining
   30s is per-batch Lucene commits + pending-ID queries.

3. **Embedding dominates** at 50% of inference time — more than SPLADE
   + NER combined. Primary remaining bottleneck.

4. **Chunk tail reduced from 63s to 40s** by chunk embedding tight loop.

**Resource utilization (jseval gpu summary):**
- GPU avg: 46%, peak 95%, idle polls 18%
- VRAM avg: 2.0GB, peak 2.4GB (three sessions active)
- Heap: 0.1GB

### Between-batch overhead investigation

The 91s between batches was investigated by tracing the `IndexingLoop`
main loop. Each iteration does the following when combined backfill
is active:

```
handleGpuStateTransition()                    // MMF check
signalBus.isUserActive()                      // breath-holding
jobQueue.pollPending(16)                      // SQLite query
  → jobs empty → idle branch
commitOps.commitAndTrack() if needed          // Lucene commit
LoopPacingPolicy.shouldRunBackfill()          // GPU state check
processCombinedBackfillIfApplicable():
  3× queryDocIdsByField (embed/splade/ner)    // 3 Lucene searcher queries
  processCombinedBackfill()                   // *** THE ACTUAL WORK (1,577ms) ***
    → commits internally (commitAndTrack)     // another Lucene commit
disambiguation checks:
  2× countByField (NER pending + completed)   // 2 Lucene searcher queries
maybeFinalizeEmbeddingRebuildIfNeeded()
Thread.sleep(100ms)                           // active-idle sleep
```

**Breakdown of the 91s (97 iterations):**

| Component | Estimated total | How |
|-----------|----------------|-----|
| `Thread.sleep(100ms)` | **~10s** | 97 × 100ms. Constant cost. |
| Lucene commits | **~20-40s** | 97 per-batch commits (segment flush + fsync + merge eval). Plus potential double-commit from idle branch. |
| Lucene queries | **~20-30s** | 97 × 5 queries (3 pending-ID + 2 disambiguation). Each opens IndexSearcher (may refresh segments after commit). |
| Job queue poll + misc | **~5s** | SQLite poll, GPU checks, provider availability. |

### Remaining optimization opportunities

Research (March 2026) into Lucene commit cost, ORT CUDA stream
pipelining, search system enrichment patterns, embedding batch size
scaling, and Java GC impact on GPU workloads. Key external findings:

- **Lucene commit vs NRT refresh are independent.** Search visibility
  (NRT reader) doesn't require fsync. ES sets `translog.durability:
  async` during bulk indexing to eliminate per-doc fsync.
  **Codebase verification:** `SearcherBridge.withSearcher()` uses an
  NRT reader (`DirectoryReader.open(IndexWriter, ...)`) with
  `ControlledRealTimeReopenThread` (50-500ms refresh). Uncommitted
  writes are visible to the next `withSearcher()` call without commit.
  Per-batch commits are unnecessary for pipeline correctness —
  they only affect crash durability.
- **Lucene IndexWriter is fully thread-safe** for concurrent reads +
  writes. `SearcherManager.acquire()` returns immutable segment
  snapshots. Writer and reader threads don't interfere. This confirms
  producer-consumer stage pipelining (Tier 3 item 7) is feasible
  from Lucene's perspective — the complexity is in coordination, not
  concurrency.
- **ORT CUDA stream overlap is possible** but requires C++ API
  (`disable_synchronize_execution_providers`). Java `RunOptions
  .addRunConfigEntry(key, value)` is a thin JNI passthrough with no
  key validation — the string key *might* be accepted by the native
  layer. Untested. Would allow embedding batch N+1 to start while
  SPLADE processes batch N.
- **GC is NOT the bottleneck.** JNI threads inside `session.run()` are
  not stopped at safepoints — GPU kernels continue. Java 25 ZGC keeps
  pauses under 0.5ms. The 120ms/batch within-batch gap is CPU work
  (map assembly, status checks), not GC pauses.
- **No search system pipelines stages within a document.** ES, Solr,
  Vespa all run enrichment stages sequentially per doc. Throughput
  scaling is via concurrent documents, not stage overlap.
- **Embedding batch plateau around batch=16-32** for 300M-param models
  on RTX 4070 class GPUs (sentence-transformers benchmarks).

### Phase 8: Between-batch overhead reduction

28. [x] **Tight backfill loop.** When combined backfill returns true
    (wrote docs), immediately call `processCombinedBackfillIfApplicable()`
    again — skip sleep, job poll, disambiguation checks. Only fall back
    to full `IndexingLoop` iteration when combined pass returns false.
    Periodic checks for shutdown, breath-holding, and GPU state
    transitions within the tight loop. Implemented as `while(useCombined)`
    loop in `IndexingLoop.java`.

    **Final results (jseval --pipeline --timeline, includes chunk tight loop):**
    - Primary indexing: 25s (201 docs/s)
    - Embed+SPLADE+NER 100%: **168s** (was 244s pre-tight-loop)
    - Chunk vectors 100%: **208s** (was 276s, chunk tight loop saves 23s)
    - **Total wall time: 213s (3.5 min)** — was 276s (4.6 min), **23% faster**
    - Between-batch overhead: ~30s (was 91s, 67% reduction)
    - SPLADE churn: 3 drops (all during primary indexing, none in combined pass)

    **Per-batch (from jseval inference section, combined pass batches):**

    | Stage | Batches | Total ms | Avg/batch |
    |-------|---------|----------|-----------|
    | Embedding (Q4 GPU) | 53 | 60,305 | 1,138ms |
    | SPLADE (FP16 GPU) | 55 | 29,716 | 540ms |
    | NER (FP16 GPU) | 53 | 31,375 | 592ms |

    **Resource utilization (jseval gpu summary):**
    - GPU avg: 46%, peak 95%, idle polls 18%
    - VRAM avg: 2.0GB, peak 2.4GB (arenas are ceilings, not pre-alloc)
    - Heap: 0.1GB (Worker at 512MB max, minimal usage)

29. [x] **Skip disambiguation queries during enrichment.** Gate the 2
    `countByField` queries on `!backfillDidWork` — disambiguation only
    runs when the combined pass has no more work. Previously ran
    unconditionally every iteration, adding ~5-10s of Lucene queries.

30. [x] **Deferred Lucene commits — investigated, not viable.** Tested
    three approaches, all regressed:
    - **(a) Time-based 5s:** 24GB native memory (mmap'd uncommitted
      segments from `MMapDirectory`). `ControlledRealTimeReopenThread`
      refreshing every 50-500ms creates NRT readers that mmap all
      uncommitted segment files. Without commits to checkpoint merges,
      old segments can't be unmapped.
    - **(b) Every-5-batches:** Same OOM — 5 uncommitted batches still
      produce enough segments for CRTRT to mmap.
    - **(c) Every-5 + NRT suspended:** 304s (was 244s). Suspending CRTRT
      prevents mmap growth but degrades everything else: writes 14x
      slower (164ms vs 12ms), inference 50-80% slower from memory
      pressure. NRT refresh isn't just for search visibility — it
      drives segment cleanup that keeps `IndexWriter` flushes fast.
    Per-batch commits remain. The `suspendNrtRefresh()`/`resumeNrtRefresh()`
    methods remain in `CommitOps` for future experiments but are unused.
    Root cause documented in code comments on `CombinedEnrichmentBackfillOps`.

**Item 2 investigation: deferred commits cause unbounded mmap growth.**

Root cause confirmed: **not JVM heap (capped at 512MB), but native
memory from memory-mapped segment files.** The chain:

1. `IndexWriter` RAM buffer is 64MB (`ComponentsFactory` line 209).
   When it fills, Lucene auto-flushes a new segment to disk.
2. With the tight loop writing 100 docs/1.5s, the buffer fills
   every few batches, producing many small segment files.
3. `ControlledRealTimeReopenThread` runs every 50-500ms (configured
   at `ComponentsFactory` line 298: `targetMin=0.05, targetMax=0.5`).
   Each refresh opens a new `DirectoryReader` that mmaps all current
   segment files.
4. Old readers are released promptly (try-finally in
   `SearcherBridge.withSearcher()`), but the mmap'd files can't be
   unmapped until no reader references them AND the segment is merged.
5. Without commits, merges can't checkpoint — old segments and new
   merged segments coexist in memory.
6. `MMapDirectory` (default) maps `.vec`, `.knn`, `.tim`, `.pos` files
   into native address space. Cumulative mmap footprint reaches tens
   of GBs as unmergeable segments multiply.

**Per-batch commits break this cycle** by checkpointing merges,
allowing old segments to be unmapped. The ~20-40s commit overhead
is the cost of keeping native memory bounded.

**Possible mitigation (not yet attempted):**

- **Pause `ControlledRealTimeReopenThread` during tight backfill.**
  The NRT refresh is unnecessary during enrichment — no user searches
  happen during backfill. Pausing it would prevent new reader snapshots
  from holding segment references. The thread supports `close()` but
  not pause/resume. Would need to stop and restart it, or set
  `targetMaxStaleSec` to a very high value during backfill.
- **Increase `RAMBufferSizeMB` during backfill.** Fewer auto-flushes
  → fewer small segments → less mmap accumulation. But this increases
  JVM heap pressure (already at 512MB).
- **Use `NIOFSDirectory` instead of `MMapDirectory`.** File reads go
  through JVM heap, not native mmap. Avoids unbounded native memory
  but significantly slower for search. Not viable for production.
- **Commit every N batches** (e.g., 5) instead of every batch or
  every 5s. Reduces commits from 97 to ~20 while still periodically
  checkpointing merges. Middle ground between per-batch (97 commits)
  and time-based (which caused the RAM issue).

**Tier 2: Moderate fixes (~10-20s additional savings)**

**Tier 2: Moderate fixes (~10-20s additional savings)**

4. **Combined pass returns result summary.** Return a record with
   `{embedRemaining, spladeRemaining, nerRemaining}` instead of
   `boolean`. Outer loop uses this for disambiguation gating and
   completion detection without re-querying Lucene. Eliminates
   redundant pending-ID queries entirely.

5. **Embedding batch=16.** Experiment with larger embedding batches.
   Pinned output is tiny (768-dim vs SPLADE 30K-dim). Activation
   memory is the constraint — needs profiling at batch=16 to see
   if it fits in 2048MB arena. Sentence-transformers benchmarks
   show throughput plateau at batch=16-32 for 300M models. Even
   a 20% embedding speedup saves ~14s.

**Tier 3: Complex fixes (high theoretical ceiling, significant risk)**

6. **CUDA stream pipelining across ORT sessions.** Three sessions
   on three separate CUDA streams with async execution. Embedding
   batch N+1 starts while SPLADE processes batch N. Theoretical
   ~30-40% throughput gain by filling GPU idle gaps. **Blocker:**
   Java ORT API doesn't expose `disable_synchronize_execution_providers`.
   Requires JNI bridging to the C API — ORT C++ example at
   `ort_tutorial/30_syncstreams-cuda` demonstrates the pattern.
   GPU VRAM contention between concurrent sessions needs investigation.

7. **Producer-consumer stage pipelining.** Model each stage (embed,
   SPLADE, NER) as an async worker with bounded queues between them.
   Different documents at different stages simultaneously — keeps all
   three GPU sessions busy. No search system does this natively (ES,
   Solr, Vespa all sequential per-doc). Closest precedent: Vespa's
   continuous batching for LLM enrichment (2025). Would require
   fundamental restructuring of `CombinedEnrichmentBackfillOps` from
   single-pass to multi-stage pipeline.

**Not worth pursuing:**
- **Approach B (blue-green rebuild):** 12ms/batch write (0.8%).
- **Fetch optimization:** 15ms/batch (0.9%).
- **NER/SPLADE model optimization:** Both already FP16 on GPU.
- **GC tuning:** Confirmed not the bottleneck (Java 25 ZGC, JNI
  threads not stopped at safepoints, GPU kernels continue during GC).

**Breakthrough 1: bake SPLADE pooling into ONNX graph.**

Research revealed that the post-processing bottleneck (ReLU + log1p +
max-pool over 30K-dim tensor) can be expressed as standard ONNX ops
(Relu, Add, Log, ReduceMax, TopK) and baked into the graph. All ops
run on GPU via CUDA EP — the CPU only receives the sparse ~256-entry
TopK result instead of the full 30K-dim tensor.

This technique converts ANY MLM_LOGITS model to PRESPARSE format
without requiring a sentence-transformers export. Tested on OpenSearch
doc-v3-distill (6 layers, 67M) with double-log activation baked in:

| Config | SPLADE/batch | Total |
|--------|-------------|-------|
| SPLADE-v3 PRESPARSE (castorini) FP32 GPU | 615ms | ~330s |
| **Distill baked-PRESPARSE FP32 GPU** | **533ms** | **~339s** |
| Distill MLM_LOGITS FP16 GPU | 869ms | 397s |
| V3 MLM_LOGITS FP16 GPU | 963ms | 397s |

Adopted distilled baked-PRESPARSE as default SPLADE model.
OpenSearch doc-v3-distill with double-log activation baked into the
ONNX graph. Apache 2.0 license. BEIR 51.7 (same as SPLADE-v3).

**Breakthrough 2: FP16 baked-PRESPARSE on CUDA EP.**

Previous FP16 attempts failed with Cast node type mismatches when
converting a model that already had PRESPARSE ops appended. The fix:
**append PRESPARSE ops to the FP32 model first, then optimize+convert
the entire graph to FP16** using ORT's transformer optimizer.

Script: `scripts/models/bake_presparse_fp16.py`
1. Load base DistilBERT MLM model (FP32)
2. Append PRESPARSE ops (Relu, Add, Log, ReduceMax, TopK) in FP32
3. Run ORT transformer optimizer (`model_type="bert"`, fuse attention)
4. Convert entire graph to FP16 (`keep_io_types=False`)
5. Append Cast FP16→FP32 on `output_weights` for Java ORT compat
   (Java `OnnxTensor.getFloatBuffer()` requires FP32; only the
   256-element sparse output is cast — negligible overhead)

The key insight: the optimizer must see the PRESPARSE ops as part of
the graph *before* FP16 conversion, so they get converted uniformly.
Appending FP16 ops *after* conversion creates type boundary mismatches.

**FP16 also unblocked batch=16.** At FP16, pinned output for batch=16
is ~476MB (vs 952MB FP32), fitting in 4096MB arena with room for
attention buffers. Previous batch=16 OOM at 2048MB arena is resolved.

**Experiment results (SciFact 5184, RTX 4070 12GB, all enrichments
GPU):**

| Config | Arena | Batch | Enrichment | vs FP32 b=8 |
|--------|-------|-------|------------|-------------|
| Distill baked-PRESPARSE FP32 GPU | 2048MB | 8 | **214s** | baseline |
| Distill baked-PRESPARSE **FP16** GPU | 2048MB | 8 | **194s** | **-9%** |
| Distill baked-PRESPARSE **FP16** GPU | 4096MB | **16** | **163s** | **-24%** |

VRAM peak: 3.8GB across all three GPU sessions (embed Q4 2048MB +
SPLADE FP16 4096MB + NER FP16 ~512MB). Fits comfortably on 12GB GPU.
0 SPLADE failures at batch=16. GPU utilization avg ~50-60%.

**Adopted: FP16 baked-PRESPARSE + batch=16 + arena=4096MB.**

Changes:
- `model_fp16.onnx`: ORT-optimized FP16 baked-PRESPARSE (173MB,
  was 345MB FP32). GPU model via `model_manifest.json`.
- `SpladeEncoder.MAX_SPLADE_BATCH_SIZE_GPU`: 8→16
- `SpladeConfig` + `ResolvedConfigBuilder`: default arena 2048→4096MB
- `build.gradle.kts`: GPU env var → sysprop forwarding fix (GPU env
  vars were silently ignored because `ResolvedConfigBuilder` reads
  system properties, not env vars, but `applyHeadlessEvalContract()`
  only forwarded env vars to the child process)

**Investigated — not viable (for inference optimization):**

- **SPLADE MLM_LOGITS FP16 GPU:** 963ms/batch — slower than PRESPARSE
  615ms. CPU-side post-processing dominates.
- **NER batching** (item 22): per-call overhead only 467us. Padding
  waste exceeds savings.
- **NER GPU/CPU split**: CPU NER 6x slower than GPU.
- **IOBinding**: not in Java ORT API (v1.24.4, March 2026).
- **Double-buffered pipeline**: CPU work only 92ms/batch (8%) — but
  this was measured when SPLADE dominated. With the current profile
  (44% overhead), the cost of GPU idle gaps may be worth revisiting
  after overhead profiling identifies the actual bottleneck.

### Phase 6: Batched NER GPU inference

22. [x] **NER GPU overhead profiling + batching experiments.**

    **Per-call overhead profile (12,000 calls, GPU FP16):**

    | Phase | Per call | % |
    |-------|---------|---|
    | ORT session.run() | 1,520us | 77% |
    | Tokenize | 420us | 21% |
    | Tensor creation | 34us | 2% |
    | Output extraction | 12us | <1% |
    | **Total** | **1,987us** | |

    **Conclusion: batching is not viable for DistilBERT-NER (66M)
    on GPU.** Per-call overhead is only 467us (23%) — batching can't
    amortize it. Pad-to-max: 19% slower. Bucketed padding: 24%
    slower. Reverted to per-doc NER path.

### Phase 7: SPLADE FP16 + batch=16

23. [x] **FP16 baked-PRESPARSE model conversion.**
    Previous FP16 attempts failed with Cast node type mismatches.
    Root cause: appending PRESPARSE ops to FP32 model, then naively
    converting to FP16 creates type boundaries the ONNX checker
    rejects. Fix: append PRESPARSE ops first (FP32), then
    optimize+convert the entire graph with ORT transformer optimizer.
    FP16→FP32 Cast appended on `output_weights` for Java ORT compat.
    Script: `scripts/models/bake_presparse_fp16.py`.
    Result: model loads and runs on CUDA EP. 173MB (was 345MB FP32).

24. [x] **FP16 throughput verification.**
    Enrichment: **194s** (was 214s FP32) — **9% faster** at same
    batch=8 and 2048MB arena. Memory bandwidth reduction from FP16
    weights and activations.

25. [x] **Batch=16 at 4096MB arena.**
    FP16 halved pinned output memory: 476MB at batch=16 (was 952MB
    FP32). 4096MB arena accommodates batch=16 with room for attention
    buffers. Enrichment: **163s** (was 214s) — **24% faster** than
    FP32 batch=8 baseline. VRAM peak: 3.8GB. 0 SPLADE failures.

26. [x] **GPU env var → sysprop forwarding fix.**
    `applyHeadlessEvalContract()` forwarded GPU env vars to the child
    process but `ResolvedConfigBuilder` reads system properties.
    GPU config was silently ignored in eval runs. Fixed by adding
    env→sysprop mapping in `build.gradle.kts` for GPU_ENABLED,
    SPLADE_GPU_ENABLED, EMBED_GPU_ENABLED, NER_GPU_ENABLED, and
    arena size overrides.

27. [x] **Default arena size 2048→4096MB.**
    Updated `SpladeConfig`, `ResolvedConfigBuilder`, and Javadoc.
    Three GPU sessions at new defaults: embed Q4 2048MB + SPLADE
    FP16 4096MB + NER FP16 ~512MB = ~6.6GB total arenas. Fits
    comfortably on 12GB GPU (3.8GB observed peak VRAM).

### Phase 9: Optimization experiments

31. [x] **Outer batch=200 — REGRESSION (+44s).** Increased combined pass
    batch from 100 to 200. VRAM peaked at 3.4GB (was 2.4GB), embedding
    per-ORT-call slowed 33% (91→121ms). Reverted.
    **Critical analysis:** GPU thermal throttling was a confound — this
    was the 2nd pipeline run on a warm GPU. The ORT sub-batching (batch=8)
    means each ORT call is identical regardless of outer batch size.
    The measured regression may be partly or wholly thermal, not VRAM.
    **Status:** Needs cold-GPU retest for accurate measurement.

32. [x] **Concurrent embed+SPLADE+NER — REGRESSION (+41s).** Ran all
    three enrichments via `CompletableFuture.supplyAsync()`. Each stage
    slowed 2-4x. RTX 4070 can't run 3 ORT sessions concurrently.
    **Critical analysis:** 3-way concurrency was the wrong approach, but
    it was used to dismiss ALL concurrency. **NER pipelining** (overlap
    NER for batch N with embed+SPLADE for batch N+1) is a distinct,
    untested approach. NER is tiny (67M, 512MB) vs embed (300M, 2048MB)
    — contention would be far less than the 3-way test showed.
    **Status:** NER pipelining untested and potentially viable (~31s).

33. [x] **Skip per-doc status checks — REGRESSION (+107s).** Removed
    300 individual TermQuery lookups, causing 1.5x redundant GPU work.
    **Critical analysis:** The implementation was destructive — removed
    all checks instead of optimizing them. Better approaches not tried:
    (a) batch multi-field stored-field read (100 reads instead of 300
    queries), (b) cache completed doc IDs across iterations.
    **Status:** Optimization target valid, implementation was bad.

34. [x] **Chunk interleaving during combined pass.** Every 3 combined
    batches, run one `processChunkEmbeddingBackfill()` batch. Chunks
    are independent of parent enrichment (own CHUNK_CONTENT from primary
    indexing). Net ~12s improvement on warm GPU. Kept.

35. [x] **Embed arena=8GB experiments.** Two tests isolating arena vs batch:
    - **arena=8GB, batch=8:** 260s (≈ baseline 276s). Embed 1630ms/batch,
      VRAM peak 3.3GB (was 2.5GB at 2048MB). Arena headroom had zero
      effect on inference speed — 2048MB was not constraining batch=8.
    - **arena=8GB, batch=16:** 280s. Embed 1863ms/batch. Per-doc 10%
      slower (16.6ms vs 15.1ms) — attention compute scales with batch.
      No crash (unlike prior 4096MB test with 12GB total arena
      fragmentation). VRAM peak 3.8GB.
    **Conclusion:** arena was never the constraint at batch=8. batch=16
    is inherently slower per-doc due to attention memory scaling. batch=8
    at 2048MB arena is optimal for EmbeddingGemma Q4.

**GPU thermal throttling finding:** RTX 4070 boosts to ~2500 MHz cold
but throttles to ~1900 MHz sustained (~24% clock reduction). The 213s
measurement was cold-GPU; warm-GPU steady state is ~260-270s. All
Phase 8 timing comparisons assumed cold GPU and are optimistic by
~20-30%. The Phase 8 improvements (tight loop, etc.) are real but
measured against a cold baseline.

**Theoretical floor analysis (corrected):** With all 3 models on GPU
sequentially: 121s (parents) + 23s (chunks) + 25s primary + 10s commits
= 179s minimum. **But with NER on CPU (pipelined):** GPU critical path
drops to embed+SPLADE = 90s. 90 + 23 (chunks) + 25 (primary) + 10
(commits) = 148s. 150s is achievable with NER-CPU pipeline + overhead
reduction.

### Phase 10: Implementation plan

**Goal:** ≤150s total pipeline (SciFact 5184, cold GPU).

**Measurement protocol:**
- Establish noise floor: 3 identical runs before any changes. Record variance.
- Wait ≥3 min between jseval runs (GPU thermal cooldown).
- Every experiment gets its own cold-GPU baseline (A/B comparison).
- One variable per experiment. No stacking untested changes.
- If >40 min on a single failed experiment without progress, stop and
  reassess approach.
- Keep a running log: run number, code state, wall time, per-batch
  inference, GPU utilization.

**Experiment sequence (cheapest/safest first):**

**Exp 1: Establish reproducible baseline** (3 runs, no changes)
- Run jseval 3× on current code. Record min/max/median.
- If spread >15%, diagnose variance before proceeding.
- This is the control for all subsequent experiments.

**Exp 2: ORT CUDA provider tuning — DONE, 263s→202s (-23%).**
- Added `tunable_op_enable=1`, `tunable_op_tuning_enable=1`,
  `cudnn_conv_use_max_workspace=1`, `use_ep_level_unified_stream=1`,
  `session.force_spinning_stop=1`, `ep.share_ep_contexts=1`.
- Hypothesis was 2-5% — actual was **33% embed speedup** from GEMM
  kernel auto-tuning. First run 219s (tuning warmup), second run 202s.
- Embed 1631→1100ms/batch. NER 705→578ms. SPLADE 539→519ms.
- Config-only change in `OrtSessionFactory.java`. Committed.

**Exp 3: Batched status reads — DONE, committed (architectural improvement).**
- Added `DocumentFieldOps.getDocumentFieldsBatch()` — 1 searcher
  acquisition + 100 TermQuery resolutions + batch DocValues reads.
- Replaces 300-400 individual getDocumentField() calls. Also batches
  CHUNK_CONTENT fetch for SPLADE.
- Measured improvement within noise (~5s) — overhead was smaller than
  estimated. Kept for architectural cleanliness.

**Exp 3 (original):** Batched status reads
- Add `getDocumentFieldsBatch(List<String> docIds, Set<String> fields)`
  to `DocumentFieldOps.java`. Same 3-phase pattern as
  `getDocumentContentBatch()`: 1 searcher acquisition, N TermQuery
  resolutions sorted by doc number, batch DocValues reads.
- Also batch the per-doc CHUNK_CONTENT fetch for SPLADE.
- Update call site in `CombinedEnrichmentBackfillOps.java` (Phase 1-2).
- Hypothesis: eliminates 300-400 SearcherManager acquire/release cycles
  per batch. ~8-10s total across ~53 batches.
- **Files:** `DocumentFieldOps.java` (new method),
  `CombinedEnrichmentBackfillOps.java` (call site)

**Exp 4: Pending-ID caching** (refactor, low risk)
- Query all pending IDs upfront (no batchSize limit), cache in a list.
- Pop batchSize per iteration. Re-query only when cache exhausted.
- `queryDocIdsByField` already omits `maybeRefreshBlocking` — per-doc
  status re-check (which does refresh) provides idempotency guarantee.
- Hypothesis: eliminates 3 × `queryDocIdsByField` per iteration except
  the first. ~3-5s total.
- **File:** `CombinedEnrichmentBackfillOps.java` (refactor Phase 0)

**Exp 5: NER-CPU pipelining — FAILED, reverted.**
- Implemented within-batch pipeline: NER-CPU (CompletableFuture.supplyAsync)
  concurrent with embed+SPLADE (GPU, main thread). Added `infer(text, forceCpu)`
  to BertNerInference, `extractEntitiesOnCpu()` to NerService.
- **Default intra-op threads:** NER-CPU uses all cores → CPU contention
  slows SPLADE by 40-50% and embed by 13%. NER is hidden (127ms join)
  but net savings only 0-15s (inconsistent across runs).
- **2 intra-op threads:** NER becomes 5x slower (3685ms/batch) →
  bottlenecks entire pipeline. 425s total (was 258s).
- **Root cause:** No sweet spot. NER DistilBERT INT8 on CPU needs many
  cores for competitive throughput (default threads: ~12ms/doc). With
  limited threads: ~37ms/doc (3x slower). The CPU contention from
  using many threads eats more time from GPU stages than NER saves.
- Cross-batch pipelining (NER for batch N concurrent with embed+SPLADE
  for batch N+1) is also not viable: requires two writes per doc,
  which reintroduces RMW churn on non-stored SPLADE FeatureField.
- **Conclusion:** NER pipelining is not viable on this hardware. NER
  must stay on GPU in the sequential combined pass.

**Exp 5 (original):** NER-CPU pipelining
- Run NER on background thread using CPU session (INT8, ~1,200ms/100
  docs) while main thread runs embed+SPLADE on GPU (1,678ms/batch).
- NER fits within embed+SPLADE with 478ms headroom. Zero GPU contention.
- `NerService` confirmed thread-safe (javadoc + implementation).
- `BertNerInference.infer()` safe for concurrent ORT calls. Only benign
  data race on profiling `long` counters (cosmetic).
- Pipeline structure:
  ```
  batch N:   embed+SPLADE(N) → start NER-CPU(N) async
  batch N+1: embed+SPLADE(N+1) → join NER(N) → write(N) → commit(N)
             → start NER-CPU(N+1) async
  ```
- Implementation: add `extractEntitiesOnCpu(String text)` to NerService
  (or pass a `forceCpu` flag). Restructure Phase 3 of
  `processCombinedBackfill()` to submit NER to a single-thread executor
  and join at the start of the next iteration.
- Hypothesis: removes NER (~592ms/batch) from GPU critical path entirely.
  ~31s total savings. This is the single change that makes 150s reachable.
- **Files:** `CombinedEnrichmentBackfillOps.java` (restructure Phase 3),
  `NerService.java` or `BertNerInference.java` (CPU-only path)

**Exp 6: Re-test deferred commits on Lucene 10** (medium risk)
- Lucene 10.x fixed MMapDirectory `.si` arena leak (issue #15068):
  confined arenas closed immediately.
- Re-test every-5-batches commit. **Monitor native memory** via
  `Runtime.getRuntime().maxMemory()` and OS-level memory checks.
- Use `suspendNrtRefresh()` (already implemented) during enrichment.
- If native memory stays bounded: keep (saves ~42 fsyncs × 100-200ms).
- If OOM recurs within 2 minutes: revert immediately.
- Also test: `setNoCFSRatio(0.0)` during enrichment (disable compound
  files, saves 7-33% per-flush overhead).
- Hypothesis: Lucene 10 fix eliminates the mmap accumulation. ~4-8s.
- **Files:** `CombinedEnrichmentBackfillOps.java` (commit frequency),
  `ComponentsFactory.java` or `CommitOps.java` (CFS ratio, NRT suspend)

**Exp 7: Cold-GPU retest of batch=200** (retest, low risk)
- Original test was confounded by thermal throttling.
- Hypothesis: batch=200 is neutral on cold GPU (the 33% ORT slowdown
  was thermal, not VRAM). ORT sub-batch size is fixed at 8 regardless
  of outer batch size.
- If neutral: fewer batches = fewer commits = ~10s savings.
- If still regresses: confirms VRAM pressure is real. Record and move on.

**Results so far:**

| Exp | Change | Result |
|-----|--------|--------|
| 1 | Baseline (3 runs) | 263s ±2s (noise floor <5%) |
| 2 | ORT tuning | Committed. R3/R4 showed 202-219s but not reproducible. Config kept (zero downside). |
| 3 | Batched status reads | Committed. ~5s improvement, within noise. Architectural improvement. |
| 5 | NER-CPU pipeline | Failed. CPU contention eats gains. Reverted. |
| — | Chunk folding | Committed. Chunks complete during enrichment (181s vs 259s). GPU idle 27%→6%. Net 252s. |

**Remaining experiments:**

| # | Experiment | Expected | Complexity |
|---|-----------|----------|------------|
| 4 | Pending-ID caching | 3-5s | Low |
| 6 | Deferred commits on Lucene 10 | 4-8s | Medium (needs memory monitoring) |
| 7 | batch=200 cold retest | 0-10s | Trivial (one constant) |
| A | Reduce NRT refresh to 5s during enrichment | 2-5s | Low |
| B | Pre-tokenize SPLADE during embed GPU time | 5-15s | Medium |
| C | Tune chunk slots (30→50+) | Needs testing | Trivial |
| D | Disable compound files during enrichment | 1-3s | Low |

**Current baseline: 179s** (from 252s before Phase 10 experiments).
Improvements: ORT tuning, batched status reads, pending-ID caching,
chunk folding (50 slots/batch), deferred commits on Lucene 10.

| Run | Change | Total | Embed/batch |
|-----|--------|-------|-------------|
| R8 | Chunk folding (30 slots) | 252s | 1615ms |
| R9 | + Pending-ID caching | 207s | 1099ms |
| R10 | + Deferred commits (every-5) | 206s | 1110ms |
| R11 | + Chunk slots 30→50 | **179s** | 1499ms |
| R12 | Chunk slots 50→100 (regressed) | 190s | 1562ms |

Chunk slots=50 is the sweet spot. 100 causes embedding regression
(200 docs/batch saturates GPU, same issue as batch=200 experiment).

### Phase 11-12: Profiling & verification → tempdoc 356

Observability infrastructure, roofline analysis, and performance
verification moved to **tempdoc 356 (Inference Observability)**.

**Key findings (settled):**
- GPU thermal throttling disproved (2600-2800 MHz, 63C, 168W)
- ORT tunable op tuning hurts by ~11s (disabled)
- Reproducible baseline: **204s ±2s**
- 179s R11 was a warmup anomaly
- Models run strictly sequentially — no GPU contention

**Key findings (updated by tempdoc 356 observability — Step 1 + Nsight):**

*Encoder-level (Step 1):*
- GPU (session.run()) is 82-92% of each encoder's time. CPU pipelining
  of tokenization would save <6% for embed/SPLADE.
- **SPLADE uses PRESPARSE per-doc calls:** 5,300 ORT calls (one per doc),
  not batched. This is the #1 batching opportunity.
- **NER chunks per doc: 2.37 average**, producing 12,300 ORT calls for
  5,184 docs. Chunk size reduction is a potential optimization.
- **NER efficiency corrected: 55%** (was 18% estimate). p50=2.8ms/call.
  Per-call overhead is ~1ms — not enough to amortize padding waste.
  Consistent with Phase 6 finding. NER batching deprioritized.
- First-batch warmup: embed 1,237ms (15x avg), SPLADE 152ms (28x avg).
  Total ~1.4s — real but small.

*GPU hardware-level (Nsight Systems + Nsight Compute):*
- **98.4% compute-bound, 1.6% memory transfers.** H2D 77ms total, D2H
  1.19s total across entire pipeline. PCIe is not a bottleneck for ANY
  model including NER at batch=1. NER per-call overhead is kernel launch
  + GPU scheduling, not data transfer.
- **Q4 embed dequantize→GEMM is 32% of all GPU time.** Largest kernel
  (`cutlass_80_tensorop_s1688gemm_128x128`) = 28% (26.6s). Dequantize
  kernel = 4% (3.5s). Combined 30.1s. This is a hard constraint of the
  Q4 model — dequantize happens per GEMM, unavoidable without changing
  quantization format. Any per-doc speedup on embed has outsized impact.
- **Attention softmax is only 5% (4.5s).** Flash attention not viable as
  an optimization path. FFN matmuls dominate, not attention.
- **LayerNorm: 4% (3.8s) across 138K invocations.** 27μs/call. Op
  fusion ceiling is 3.8s — not worth pursuing.
- **3.1 million CUDA kernel launches → ~15.5s overhead.** At ~5μs/launch,
  this is a hard floor set by the CUDA driver. The only way to reduce it
  is fewer ORT calls (SPLADE batching, NER chunk reduction). ORT config
  cannot help.
- **Distributions are unimodal and tight.** No bimodal patterns, no GC
  spikes, no interference. Variance is data-driven (sequence length).
  Nothing fixable hiding in the tail.
- **Embed p99 = 237.5ms (2.9× p50 81.5ms).** ~1% of embed calls hit
  max-length docs (seqLen near 2048). Sorting documents by length before
  sub-batching (as SPLADE already does via token-budget batching) could
  reduce this tail by ~2-4s.
- **Per-batch composition stable:** 100 parents + 50 chunks per batch,
  consistent across all batches. Chunk folding works correctly.

### Phase 13: Observability-informed optimization opportunities

**Complete time budget (204s baseline, Nsight-informed):**

| Component | Time | Source |
|-----------|------|--------|
| Primary indexing | 25s | jseval |
| GPU compute (all models) | 93.5s | Nsight Systems |
| CUDA kernel launch overhead | ~15.5s | 3.1M launches × ~5μs |
| Java-side overhead in batches | ~11s | 120s batch - 93.5s GPU - 15.5s launches |
| Between-batch overhead | 59s | 204 - 120 - 25 |

The between-batch 59s (~1.1s/batch) is NOT in the measured {embed,
splade, ner, fetch, write} phases. It includes: commits (every 5
batches, ~10 commits at 2-3s each = 20-30s), map construction, status
filtering, Lucene reader refresh, and tight loop control flow.

The GPU compute breakdown (93.5s):
- Q4 embed GEMM: 26.6s (28%) — single largest kernel
- Q4 embed dequantize: 3.5s (4%) — inherent to Q4 format
- Attention softmax (all models): 4.5s (5%)
- LayerNorm (all models): 3.8s (4%)
- Other GEMM/FFN: remainder (~55s)

**Hard floors (reducible only by model changes or fewer ORT calls):**
- CUDA kernel launch overhead: ~15.5s — reducible by fewer ORT calls
- Q4 embed dequantize: 3.5s — inherent to Q4 format. Eliminable by
  switching to FP16 embed (model switching is allowed)
- Q4 embed GEMM: 26.6s — reducible by smaller model or different
  quantization. Model switching opens this up.
- GPU compute at current efficiency: ~78s (93.5 - 15.5) — models are
  at 42-55% efficiency (typical for ORT on GPU). Efficiency ceiling
  would improve with fewer, larger kernels (batching).

**Prioritized improvements (Nsight-informed, model switching allowed):**

| # | Improvement | Est. savings | Confidence | Mechanism |
|---|------------|-------------|------------|-----------|
| A★ | **EmbeddingGemma Q4 → FP16** | 13-17s | High | Q4 dequant+GEMM = 30.1s (32% of GPU). Q4 dequantizes to FP32 → runs FP32 GEMM at 14.6 TF (TF32 tensor cores). FP16 native → 29.15 TF FP16 tensor cores (2× compute). Eliminates 3.5s dequant + halves 26.6s GEMM → ~16.8s savings. |
| B | **SPLADE PRESPARSE batching** | 8-12s | High | Reduce 5,300→~330 ORT calls. Saves per-call ORT overhead + kernel launch overhead (~3s). |
| C | **NER chunk size increase** | 10-18s | Medium | Reduce 12,300→~6,000 ORT calls (2× chunk size). p50 2.8ms/call × 6,300 saved calls = 17.6s theoretical. Conservative: 10s. |
| D | **Between-batch overhead** | 10-30s | Medium | 59s unaccounted. Requires decomposition first. |
| E | **Embed length sorting** | 2-4s | High | Reduce embed p99 tail (237ms→~150ms). Sort docs by tokenized length before sub-batching. |
| F | **Further commit deferral** | 5-15s | Low-medium | Blocked on item D measurement. |
| G | NER FP16 GPU batching | Likely 0 | Low | Phase 6 regression. Nsight confirms overhead is kernel launch, not transfer. |
| H | I/O-compute overlap | ~1s | High (won't help) | Fetch+write only 23ms/batch. |

**Revised path to 150s:**
- A★: EmbeddingGemma FP16 → -16s → 188s
- B: SPLADE batching → -10s → 178s
- C: NER chunk doubling → -13s → 165s
- D: Between-batch optimization → -10s (conservative) → 155s
- E: Embed length sorting → -3s → 152s

Total estimated: ~52s savings → **152s**. Items A★+B+C alone could
reach 165s. Item D provides the cushion to reach 150s.

**Why EmbeddingGemma FP16 is the highest-impact item:**

Nsight shows Q4 embed consumes 32% of total GPU time (30.1s).
This is outsized — SPLADE (67M FP16) and NER (67M FP16) combined
are less. The root cause is the Q4 execution path:

1. **Dequantization overhead:** Every GEMM is preceded by
   `Dequantize4BitsKernel` converting Q4→FP32. Cost: 3.5s total.
2. **FP32 tensor core throughput:** After dequant, GEMMs run on TF32
   tensor cores at 14.6 TF. FP16 tensor cores run at 29.15 TF (2×).
3. **Same model, same weights:** FP16 is just higher-precision storage
   of the same parameters. No quality regression possible — in fact,
   FP16 should produce slightly more accurate embeddings than Q4.

EmbeddingGemma FP16 would:
- Eliminate 3.5s dequant overhead entirely
- Run GEMMs at 2× throughput (~26.6s → ~13.3s)
- Increase VRAM from 2048MB to ~4096MB arena (still fits on 12GB)
- Total: ~16.8s savings from a config-level change

**Nsight-confirmed non-viable items:**
- NER batching: Per-call overhead is kernel launch, not PCIe transfer.
  Padding waste exceeds savings. Phase 6 regression stands.
- Flash attention: Softmax is 5% of GPU time. Max 4.5s — not worth it.
- I/O-compute overlap: H2D is 77ms total. Negligible.
- GPU utilization: 98.4% compute. Nothing to fix.

### Implementation & verification approach per item

#### Item A★: EmbeddingGemma Q4 → FP16

**Why this is the top priority:** Nsight shows Q4 embed is 32% of all
GPU time (30.1s). The root cause is the Q4 execution path:

```
Q4:  Load Q4 weights → Dequantize4BitsKernel (FP32) → FP32 GEMM (TF32 tensor cores, 14.6 TF)
FP16: Load FP16 weights → FP16 GEMM (FP16 tensor cores, 29.15 TF — 2× faster)
```

FP16 eliminates the dequant step (3.5s) AND doubles GEMM throughput
(26.6s → ~13.3s). Same model, same architecture, same weights (just
higher precision) — no search quality change. This is a format swap.

**Pre-implementation research results (2026-03-25):**

1. **FP16 tensor core usage CONFIRMED by Nsight.** SPLADE and NER (both
   FP16) use `ampere_fp16_s16816gemm` kernels — true FP16 tensor cores
   at 29.15 TF. Q4 embed uses `cutlass_80_tensorop_s1688gemm` — TF32
   at 14.6 TF. ORT correctly dispatches FP16 models to FP16 tensor
   cores. The 2× claim is proven, not speculative.

2. **No FP16 model exists.** `models/onnx/embeddinggemma-300m/` contains
   only `model.onnx` (GPU, Q4 weights) and `model_int8.onnx` (CPU).
   Need to convert from source or download FP16 from HuggingFace.

3. **Model loading is manifest-driven.** `model_manifest.json` has
   `{"gpu": "model.onnx"}`. Changing to `{"gpu": "model_fp16.onnx"}`
   is sufficient — `OnnxEmbeddingEncoder` resolves via
   `ModelManifest.resolveModelPath(modelDir, true)`.

4. **Arena default is 2048MB** (`EmbeddingConfig.gpuMemMb`). FP16
   model needs larger arena. Pass through `ResolvedConfigBuilder`.

5. **Model specs:** hidden=768, 24 layers, max_seq=2048, 3 attention
   heads (head_dim=256), GQA with 1 KV head, mean pooling.

**VRAM analysis:**
- Q4 model: ~150MB weights. Arena 2048MB.
- FP16 model: ~600MB weights. Peak activations at batch=8, seq=2048:
  8 × 2048 × 768 × 24 × 2 bytes ≈ 600MB. Total: ~1.2GB model+activations.
  Arena 2048MB should suffice (current Q4 uses 2048MB with room to spare).
  If not, 3072MB is conservative. 4096MB gives maximum headroom.
- Current total VRAM peak: 3.8GB. With FP16 embed at 3072MB arena:
  ~3GB + 4GB (SPLADE) + 0.5GB (NER) = 7.5GB. Fits on 12GB.

**Implementation:**
- Convert EmbeddingGemma FP32 source → FP16 ONNX (ORT transformer
  optimizer, following the SPLADE Phase 7 conversion pattern)
- Place as `model_fp16.onnx` in model directory
- Update `model_manifest.json`: `{"gpu": "model_fp16.onnx"}`
- Increase arena default if needed (try 2048 first, then 3072/4096)

**Verification protocol:**
- **Numerical correctness:** Embed test docs with both Q4 and FP16.
  Cosine similarity between corresponding vectors should be >0.99.
- **Pipeline timing:** jseval `--pipeline --json` (cold GPU, A/B).
- **Search quality:** jseval with queries. nDCG@10 should be identical
  or better (higher precision → more accurate embeddings).

**Expected outcome:** ~16s savings. Mechanistically justified by Nsight
kernel data (2× FP16 tensor core throughput vs TF32).

**Risk:** Low. Same model weights at higher precision. VRAM is the only
concern — mitigated by arena sizing. If batch=8 doesn't fit, batch=4
at FP16 (2× compute) is still faster than batch=8 at Q4.

**Implementation status (2026-03-25):**

1. Downloaded `model_fp16.onnx` from `onnx-community/embeddinggemma-300m-
   ONNX`. Merged external data into single 589MB file. Placed at
   `models/onnx/embeddinggemma-300m/model_fp16.onnx`.

2. Updated `model_manifest.json`: `{"gpu": "model_fp16.onnx"}`.

3. Updated `build.json` with FP16 variant provenance and SHA256.

4. **VRAM verified:** FP16 at 2048MB arena, batch=8, seq=512 works
   (tested via Python ORT). No arena increase needed.

5. **Numerical verification against FP32 ground truth:**
   - FP16 vs FP32: cosine **0.9999** — virtually identical
   - Q4 vs FP32: cosine **0.71-0.83** — severe quantization degradation
   FP16 is dramatically more faithful than Q4. Switching improves
   embedding quality alongside the 2× speed gain.

6. **Compilation + unit tests pass.**

**Pipeline verification FAILED — FP16 produces NaN on some inputs.**

The combined enrichment pass crashes with:
```
java.lang.IllegalArgumentException: non-finite value at vector[0]=NaN
    at KnnFloatVectorField.<init>
```

Root cause: FP16 range max is ~65504. EmbeddingGemma has vocab_size
262144 — the large embedding table can produce intermediate values
that overflow FP16 range, yielding NaN after normalization. Q4 avoids
this because it dequantizes to FP32 (range ~3.4e38).

The per-ORT-call speed was promising (p50=52ms vs Q4 81ms = 36%
faster), but NaN makes the FP16 model unusable without mitigation.

**Mitigation options:**
1. **Mixed precision conversion:** Keep IO types as FP32, only convert
   internal ops to FP16 (onnxconverter `keep_io_types=True`). This
   may prevent NaN in embedding lookup while keeping FP16 GEMMs.
2. **NaN guard + CPU fallback:** Check embedding output for NaN,
   re-encode affected docs on CPU (INT8). Adds complexity.
3. **FP32 model on GPU:** No NaN risk but no FP16 tensor core benefit.
   Only saves the 3.5s dequant overhead vs Q4.

**Status:** Reverted to Q4. Item A★ is DEAD — Gemma3 architecture is
fundamentally incompatible with FP16 (confirmed by HuggingFace
maintainers, HF #39972). Hidden states reach ~264K in FP32, far
beyond FP16 max 65504. Designed for BFloat16 (same exponent range as
FP32). Mixed-precision with `op_block_list` was tried — blocking
`SimplifiedLayerNormalization` + `Add` + overflow ops fixed NaN but
made model 3.2× SLOWER (431s vs 233s Q4 baseline). The fused
`MultiHeadAttention` kernel embeds softmax and Q*K^T computation
internally — can't selectively keep parts in FP32.

BFloat16 would work (RTX 4070 has BF16 tensor cores) but ORT's BF16
CUDA support is still incomplete (ORT #25740). Not viable yet.

**Note:** Q4 → FP16 also changes the embedding space. Re-indexing
required for production. For jseval, this is transparent.

#### Item B: SPLADE PRESPARSE batching

**Pre-implementation verification — ALL PASSED (2026-03-25):**

1. **ONNX graph supports batching.** Input: `input_ids [B, seq_len]`
   with dynamic batch dim. Output: `output_idx [B, 256]` INT64,
   `output_weights [B, 256]` FP32. TopK preserves batch dimension.

2. **Code insertion point is clean.** `encodeBatchTokenBudget()` already
   groups docs by token budget into sub-batches (GPU max=16). The
   PRESPARSE path then loops per-doc (lines 616-637):
   ```java
   for (int i = 0; i < batch; i++) {
       results.add(runSingleSparseInference(session, allInputIds[i], ...));
   }
   ```
   This loop becomes a single batched ORT call.

3. **`runSingleSparseInference()` hardcodes shape={1, seqLen}.** Output
   parsed as flat buffers (`getLongBuffer()`, `getFloatBuffer()`).
   Batched version: shape={batchSize, maxSeqLen}, parse output as
   `[batchSize, 256]` by striding the buffer.

4. **Padding infrastructure exists** in `runOnnxInference()` (MLM_LOGITS
   path). Reusable for PRESPARSE batching — same input padding logic,
   just different output handling.

**Implementation plan (only after all 4 checks pass):**
- Add `runBatchSparseInference(List<Encoding> encodings)` alongside
  `runSingleSparseInference`. Reuse padding logic from `runOnnxInference`.
- Post-process: iterate over batch dimension, extract per-doc sparse
  weights from `[batch, 256]` output.
- Wire into `encodeBatchTokenBudget()` where it currently loops over
  individual docs calling `runSingleSparseInference`.

**Verification protocol:**
- **Correctness first:** Batch=2 with known doc pair. Compare output
  sparse weights against per-doc results. Must match within FP16
  tolerance (1e-3 relative).
- **Then performance:** Batch=8 timing comparison (cold GPU, 3-min gap).
- **Then quality:** Full jseval with queries to verify search results.

**Key risk:** The baked PRESPARSE TopK op may not batch correctly. If
TopK flattens the batch dimension, output extraction produces garbage.
The correctness check (batch=2 comparison) catches this immediately.

**Nsight-informed savings mechanism:** SPLADE's 5,300 ORT calls generate
a proportional share of the 3.1M total kernel launches. Batching at 16
reduces calls to ~330, cutting SPLADE kernel launches by ~94%. This
saves both CUDA driver scheduling overhead (~5μs/launch) and ORT
per-call setup (session.run() → execution plan traversal → OrtValue
allocation/deallocation). The compute itself is unchanged — same total
FLOPs, just in fewer, larger kernel invocations.

#### Item C: NER chunk size increase

**Pre-implementation verification results (2026-03-25):**

1. **ChunkSplitter is token-based (heuristic), not char-based.**
   `NER_CHUNK_TOKENS = 400` in NerService (hardcoded constant).
   ChunkSplitter converts via ~3.85 chars/token → ~1,540 chars/chunk.
   Overlap: 50 tokens (~193 chars). Splitting uses paragraph > sentence
   > word boundary preference.

2. **BertNerInference max_seq_len = 512 tokens. Truncation is SILENT.**
   No warning logged when input exceeds 512 BERT tokens. The 400-token
   heuristic → ~342 actual BERT tokens (at ~4.5 chars/token), leaving
   ~170 BERT tokens headroom.

3. **Safe increase ceiling is ~550 heuristic tokens, not 800.**
   550 × 3.85 = 2,118 chars → ~470 BERT tokens. Below 512 with ~42
   token headroom. 600 tokens → ~513 BERT tokens — right at the limit,
   risky. 800 → ~684 BERT tokens — would truncate.

4. **Revised savings: ~10s** (not 17s). 400→550 tokens reduces
   chunks/doc from ~2.37 to ~1.7. Saves ~3,500 × 2.8ms = 9.8s.

5. **Quality impact is limited.** Entity boost is disabled
   (`ENTITY_BOOST = 0.0f` in TextQueryOps). Entities used for facet
   filters (active) and metadata display, not search ranking.
   Quality regression from larger chunks only affects entity filters.

**Remaining unknown:** Actual BERT tokenized length distribution at
current 400-token setting. Need one instrumented run to confirm the
~342 BERT token average and verify headroom before increasing.

**Key risk:** Silent truncation. Must log actual BERT token counts
per chunk for one run before choosing the target chunk size.

#### Item C: Between-batch overhead (the 59s)

**This is MEASUREMENT ONLY — no optimization until decomposition is
complete.**

**What to instrument (Gap C1 from tempdoc 356):**
Inside the tight loop in `IndexingLoop.java` and
`CombinedEnrichmentBackfillOps.processCombinedBackfill()`:

1. Time from tight-loop iteration start to `processCombinedBackfill()`
   entry (loop control: shutdown/breath-hold/GPU checks).
2. Time in ID cache pops and status map construction (Phase 0-1 of
   combined backfill, excluding the already-timed enrichment phases).
3. Time in each commit operation (already deferred to every 5 batches).
4. Time from `processCombinedBackfill()` return to next iteration start.
5. Time in the periodic tight-loop checks (every N batches: shutdown,
   GPU state, breath-hold).

**Expected decomposition:**
- Commits: 20-30s (10 commits × 2-3s) — largest contributor
- ID/status overhead: 5-10s (map construction, cache management)
- Loop control: 5-10s (Lucene searcher refresh between batches)
- Unaccounted: should be <5s if instrumentation is complete

**Verification:** Sum of measured sub-components must account for >90%
of the 59s gap. If <70%, there's a hidden contributor (likely Lucene
segment merges running on background threads and blocking IndexWriter).

**Only after decomposition:** Choose the largest contributor and
optimize it. If commits dominate → test further deferral (item D).
If Lucene refresh dominates → investigate NRT thread interaction. If
map construction dominates → look at data structure efficiency.

#### Item D: Further commit deferral

**Blocked on Item C** — must confirm commits are actually the largest
overhead contributor before attempting.

**If commits are confirmed as dominant (>20s of the 59s):**
- Test every-10-batches (5 commits instead of 10).
- **Must monitor native memory:** Run with
  `jcmd <pid> VM.native_memory summary` before/after enrichment phase.
  If native memory grows >8GB, the Lucene 10.4 mmap fix is insufficient
  and further deferral is unsafe.
- Also test: increase `RAMBufferSizeMB` from 64→128 during enrichment
  to reduce auto-flush frequency (fewer segments = less mmap pressure).

**Verification:** A/B timing comparison with memory monitoring. Both
timing improvement AND memory stability required to keep the change.

#### Item D-new: Embed length sorting

**Rationale (from Nsight):** Embed p99=237.5ms (2.9× p50=81.5ms). The
tail is from sub-batches containing max-length docs (seqLen near 2048),
where 7 shorter docs are padded to 2048 tokens. SPLADE avoids this
via token-budget batching (group docs by similar token count).

**Implementation:** Sort the 150-doc outer batch by tokenized length
before feeding to `embedBatchWithChunking()`. Short-doc sub-batches
compute faster (less padding waste). Total embed FLOPs may decrease.

**Verification:** Compare embed total time before/after. Expect 2-4s
savings (embed p95 drops from 152.7ms toward p50 81.5ms for most
sub-batches). Also verify correctness: embedding results must be
identical regardless of doc ordering within a batch.

**Risk:** Low. This is a sort operation on the doc list before batching.
No model or ORT changes. The only subtlety is maintaining doc→result
mapping after reordering.

#### Item E: NER batching — skip

Phase 6 (item 22) measured 19-24% regression from padding waste.
Nsight confirms NER per-call overhead is kernel launch + GPU scheduling
(not PCIe transfer as originally hypothesized). Batching doesn't reduce
kernel count per document — it only amortizes per-call setup, which is
~1ms. Padding waste (variable chunk lengths padded to max) adds more
compute than batching saves in overhead. No further experiments planned.

#### Execution order

```
Step 1: Research (zero risk, no code changes to pipeline)
  1a. Check if FP16 EmbeddingGemma ONNX exists or needs conversion (A★)
  1b. Check OnnxEmbeddingEncoder model loading + arena config path (A★)
  1c. Check SPLADE ONNX graph input/output shapes (item B)
  1d. Read ChunkSplitter config + NER model max_seq_len (item C)
  1e. Read runSingleSparseInference() + runOnnxInference() (item B)

Step 2: Measurement (low risk, instrumentation only)
  2a. Implement Gap C1 instrumentation (item D)
  2b. Run jseval with instrumentation to decompose 59s overhead
  2c. Optionally: log NER tokenized length distribution (item C)

Step 3: Implementation (informed by Steps 1-2)
  3a. Embed model switch — highest impact, simplest change (A★)
  3b. SPLADE batching — if ONNX graph supports it (item B)
  3c. NER chunk size increase — if quality checks pass (item C)
  3d. Embed length sorting — low risk (item E)
  3e. Largest overhead fix from C1 analysis (item D/F)

Step 4: Validation
  4a. Full jseval pipeline timing (cold GPU, A/B comparison)
  4b. Full jseval with queries (search quality verification)
  4c. Verify ≤150s on two consecutive runs (reproducibility)
```

Step 1 items are all independent (parallelizable). Step 3a is the
highest-priority implementation item and can proceed as soon as 1a+1b
complete. Items 3b, 3c, 3d are independent of 3a and each other.
Item 3e depends on Step 2 findings.

**Critical gate:** Item 3a requires jseval search quality verification
before committing any model change.

### Phase 14: Embedding model research (2026-03-25)

**Context:** Item A★ (EmbeddingGemma Q4→FP16) failed — Gemma3
architecture is FP16-incompatible (head_dim=256, NaN from overflow).
Research into alternative models that CAN run FP16 on CUDA EP.

**FP16 safety rule:** head_dim ≤ 64 is safe. head_dim=128 is risky
(Qwen2/Qwen3 family has documented FP16 overflow). head_dim ≥ 256 is
broken (Gemma3 confirmed).

#### Latest models (2025-2026 releases)

| Model | Params | Layers | head_dim | MTEB Retrieval | Dim | FP16 safe? | ONNX? | Release | Notes |
|-------|--------|--------|----------|---------------|-----|------------|-------|---------|-------|
| **Qwen3-Embedding-0.6B** | 620M | 28 | **128** | Top multilingual | 1024 | **Risky** | Yes | Jun 2025 | Qwen3 decoder, BF16 native |
| **pplx-embed-v1-0.6b** | 620M | 28 | **128** | ~69.7 multilingual | 1024 | **Risky** | No | 2025 | Qwen3-based |
| **nomic-embed-text-v2-moe** | 475M (305M active) | 12 | **64** | 52.9 (BEIR) | 768 | **Safe** | No | Feb 2025 | MoE (8 experts, top-2), multilingual |
| **modernbert-embed-large** | 400M | 28 | **64** | 54.4 | 1024 | **Safe** | No | Early 2025 | ModernBERT backbone, 8K context |
| **modernbert-embed-base** | 150M | 22 | **64** | 52.9 | 768 | **Safe** | No | Early 2025 | ModernBERT backbone, 8K context |
| EmbeddingGemma-300M | 300M | 24 | **256** | Top <500M MTEB | 768 | **Broken** | Yes | Sep 2025 | Current model, FP16 NaN confirmed |

The newest top-scoring models (Qwen3-Embedding, pplx-embed) are Qwen3-
based with head_dim=128 — FP16 risky. However, newer FP16-safe models
also emerged in 2025:
- **nomic-embed-text-v2-moe** (Feb 2025): First MoE embedding model.
  475M total but only 305M active. head_dim=64 (safe). BEIR 52.9.
  Multilingual. No ONNX yet but NomicBERT arch is exportable.
- **modernbert-embed-large** (Early 2025): ModernBERT backbone, 28
  layers, head_dim=64 (safe). Retrieval 54.4. No pre-built ONNX but
  ONNX export is possible (with workarounds for unpadding, see HF
  optimum #2177). 8K context.
- **modernbert-embed-base** (Early 2025): Same architecture, 22 layers.
  Retrieval 52.9. Smaller and faster.

#### Proven FP16-safe models (head_dim=64, sorted by retrieval quality)

| Model | Params | Layers | MTEB Retrieval | Dim | Max Seq | FP16 ONNX | Release |
|-------|--------|--------|---------------|-----|---------|-----------|---------|
| **gte-large-en-v1.5** | 434M | 24 | 57.9 | 1024 | 8192 | Yes (FP16) | Early 2024 |
| **snowflake-arctic-embed-l** | 335M | 24 | 56.0 | 1024 | 512 | Yes | Apr 2024 |
| **snowflake-arctic-embed-l-v2.0** | 568M | 24 | 55.6 | 1024 | 8192 | Yes | Dec 2024 |
| **snowflake-arctic-embed-m-v2.0** | 305M | 12 | 55.4 | 768 | 8192 | Yes | Dec 2024 |
| **snowflake-arctic-embed-m** | 110M | 12 | 54.9 | 768 | 512 | Yes | Apr 2024 |
| **modernbert-embed-large** | 400M | 28 | 54.4 | 1024 | 8192 | Exportable | Early 2025 |
| **gte-base-en-v1.5** | 137M | 12 | 54.1 | 768 | 8192 | Yes (FP16) | Early 2024 |
| bge-large-en-v1.5 | 335M | 24 | 54.3 | 1024 | 512 | Yes | Sep 2023 |
| nomic-embed-text-v1.5 | 137M | 12 | ~53 | 768 | 8192 | Yes (FP16) | Early 2024 |
| **nomic-embed-text-v2-moe** | 305M active | 12 | 52.9 (BEIR) | 768 | 512 | Exportable | Feb 2025 |
| modernbert-embed-base | 150M | 22 | 52.9 | 768 | 8192 | Exportable | Early 2025 |
| bge-base-en-v1.5 | 110M | 12 | 53.3 | 768 | 512 | Yes | Sep 2023 |
| all-MiniLM-L6-v2 | 22M | 6 | 42.0 | 384 | 512 | Yes | 2022 |

**Note on 2025 models:** modernbert-embed and nomic-embed-text-v2-moe
are newer but don't have pre-built FP16 ONNX on HuggingFace. ONNX
export requires workarounds (ModernBERT unpadding issue HF #35545,
MoE routing for nomic-v2). The 2024 models (arctic-embed, gte) have
ready-to-use FP16 ONNX files — lower integration risk.

#### Speed comparison (estimated FP16 batch=8)

Current EmbeddingGemma Q4: 81ms/call on TF32 (14.6 TF), 24 layers.

| Model | Layers | head_dim | Tensor cores | Est. ms/call | vs Gemma Q4 |
|-------|--------|----------|-------------|-------------|-------------|
| EmbeddingGemma Q4 (current) | 24 | 256 | TF32 14.6TF | 81ms | baseline |
| snowflake-arctic-embed-m (FP16) | 12 | 64 | FP16 29.15TF | ~15ms | **5.4× faster** |
| gte-base-en-v1.5 (FP16) | 12 | 64 | FP16 29.15TF | ~19ms | **4.3× faster** |
| arctic-embed-m-v2.0 (FP16) | 12 | 64 | FP16 29.15TF | ~20ms | **4× faster** |
| snowflake-arctic-embed-l (FP16) | 24 | 64 | FP16 29.15TF | ~40ms | **2× faster** |
| gte-large-en-v1.5 (FP16) | 24 | 64 | FP16 29.15TF | ~50ms | **1.6× faster** |

Speed benefit comes from TWO factors:
1. FP16 tensor cores (2× throughput vs TF32)
2. Fewer layers (12 vs 24 → 2× fewer GEMMs)
Combined: 4-5× faster for 12-layer models.

#### Recommended shortlist

**Tier 1 — best speed/quality (768-dim, drop-in compatible):**
1. **snowflake-arctic-embed-m** (110M, 12L) — fastest, 54.9 retrieval
2. **gte-base-en-v1.5** (137M, 12L) — 54.1 retrieval, 8K context
3. **snowflake-arctic-embed-m-v2.0** (305M, 12L) — 55.4 retrieval, 8K

**Tier 2 — higher quality (1024-dim, requires index rebuild):**
4. **gte-large-en-v1.5** (434M, 24L) — 57.9 retrieval, best quality
5. **snowflake-arctic-embed-l** (335M, 24L) — 56.0 retrieval

**Tier 3 — 2025 models (higher integration effort, no pre-built ONNX):**
6. **modernbert-embed-large** (400M, 28L) — 54.4 retrieval, 8K context,
   head_dim=64. Needs ONNX export workaround (HF optimum #2177). 28
   layers is same depth as Gemma — speed gain only from FP16 (2×), not
   from fewer layers. Estimated ~40ms/call.
7. **nomic-embed-text-v2-moe** (305M active, 12L) — 52.9 BEIR, MoE
   architecture. First MoE embedding model. MoE routing may not export
   cleanly to ONNX. Multilingual. Lower retrieval than Tier 1.

**Not recommended:**
- Qwen3-Embedding-0.6B: head_dim=128 (FP16 risky), 28 layers (slow),
  620M params. BF16 native — ORT BF16 CUDA incomplete.
- pplx-embed-v1-0.6b: Same Qwen3 architecture, same FP16 risk.
- stella_en_400M_v5: Best quality (~58-59) but no ONNX export.
- modernbert-embed-base: 52.9 retrieval (lower than Tier 1) despite
  being newer. 22 layers — slower than 12-layer models.
- all-MiniLM-L6-v2: Too low quality (42.0 retrieval).

#### Model testing results

**Q4 EmbeddingGemma baseline** (established 2026-03-25):
- SciFact 5184 docs, 300 queries, modes: lexical, bm25_splade, full
- Pipeline: embed 213.8s, SPLADE 218.1s, total 220.1s
- Quality: lexical=0.6610, bm25_splade=0.6680, **full=0.7128**
- Model fingerprint: d9cf2cab...

**gte-base-en-v1.5 FP16** (tested 2026-03-25):
- Config: FP16 ONNX (265 MB), CLS pooling, no prefix, 768-dim, 12 layers
- FP16 CUDA validation: PASSED (no NaN/Inf on 5 real SciFact sentences)
- Pipeline: embed 196s, SPLADE 198s, NER 196s, chunks 176s, **total 204.0s**
- Quality: lexical=0.6610, bm25_splade=0.6680, **full=0.7226**
- **Quality ABOVE baseline: +0.0098 nDCG@10 (+1.4%) in full mode**
- **Pipeline 16s faster (204s vs 220s)**
- Per-doc embed time: ~8.8ms (vs ~40ms for Q4 Gemma — **4.5× faster**)
- VRAM: 2.4-3.3 GB (similar to baseline)
- Observation: embed finishes within 2s of SPLADE — SPLADE is now the
  bottleneck. Full embed speed benefit will only appear after SPLADE
  batching (Item B) decouples them.

**Bug fix discovered during testing:** `EmbeddingFingerprint.discoverModelPath()`
hardcoded `"model.onnx"` as the model filename. Models with non-standard
names (e.g., `model_fp16.onnx` only) caused fingerprint=UNAVAILABLE,
blocking all embedding writes. Fixed by using `ModelManifest.loadOrDefault()`
to resolve the correct filename from the manifest.

**snowflake-arctic-embed-m-v2.0 FP16** (tested 2026-03-25):
- Config: FP16 ONNX (585 MB), CLS pooling, query prefix `"query: "`, 768-dim, 12 layers
- FP16 CUDA validation: PASSED
- Pipeline: embed 226s, SPLADE 230s, NER 175s, chunks 166s, **total 236.9s**
- Quality: lexical=0.6623, bm25_splade=0.6679, **full=0.7047**
- **Quality BELOW baseline: -0.0081 nDCG@10 (-1.1%) in full mode**
- **Pipeline 33s SLOWER than gte-base (237s vs 204s)**
- Despite higher MTEB retrieval score (55.4 vs 54.1), the 305M model
  scored lower than the 137M gte-base on SciFact. MTEB aggregate scores
  don't predict per-dataset performance — direct evaluation is essential.

#### Additional candidates discovered (2025-2026 releases)

Fresh survey of models released Oct 2025 – Mar 2026 found:

| Model | Released | Params | Dim | head_dim | FP16 | BEIR | License |
|-------|----------|--------|-----|----------|------|------|---------|
| **gte-modernbert-base** | Jan 2025 (upd Feb 2026) | 149M | 768 | 64 | Safe | **55.33** | Apache 2.0 |
| nomic/modernbert-embed-base | Dec 2024 | 149M | 768 | 64 | Safe | ~similar | Apache 2.0 |
| pplx-embed-v1-0.6B | Feb 2026 | 600M | 1024 | 128 | Borderline | Strong | MIT |
| lightonai/modernbert-embed-large | Jan 2025 | 395M | 1024 | 64 | Safe | Higher | Apache 2.0 |

**gte-modernbert-base** (Alibaba-NLP) is the key find — same GTE team,
same 768-dim/CLS/no-prefix integration profile as gte-base-en-v1.5, but:
- ModernBERT backbone (FlashAttention, unpadding — architecturally faster)
- BEIR 55.33 vs gte-base-en-v1.5's 54.09 (+1.2 pts)
- Updated Feb 2026 (most recently maintained of the candidates)
- ONNX available on HuggingFace

Must test before finalizing model selection.

**gte-modernbert-base FP16** (tested 2026-03-25):
- Config: FP16 ONNX (285 MB), CLS pooling, no prefix, 768-dim, ModernBERT
- FP16 CUDA validation: PASSED
- Pipeline: embed 182s, SPLADE 186s, NER 153s, chunks 139s, **total 191.6s**
- Quality: lexical=0.6595, bm25_splade=0.6668, **full=0.7199**
- **Pipeline 28s faster than baseline (192s vs 220s)**
- **Pipeline 12s faster than gte-base (192s vs 204s)**
- **VRAM: 1.6-1.9 GB** — significantly lower than gte-base (2.4-3.3 GB)
- Quality 0.7199 vs gte-base 0.7226: -0.4% (within noise)

#### Model selection decision (2026-03-25)

| Model | full nDCG@10 | Pipeline | VRAM | Size | License |
|-------|-------------|----------|------|------|---------|
| Q4 EmbeddingGemma (current) | 0.7128 | 220s | 3.0 GB | 350 MB | Gemma Terms |
| gte-base-en-v1.5 FP16 | **0.7226** | 204s | 3.3 GB | 265 MB | Apache 2.0 |
| arctic-embed-m-v2.0 FP16 | 0.7047 | 237s | 3.3 GB | 585 MB | Apache 2.0 |
| **gte-modernbert-base FP16** | 0.7199 | **192s** | **1.9 GB** | 285 MB | Apache 2.0 |

**Winner: gte-modernbert-base.** Rationale:
- Fastest pipeline (192s) — closest to 150s target
- Lowest VRAM (1.9 GB) — most headroom for SPLADE/NER
- Quality within 0.4% of gte-base (0.7199 vs 0.7226) — noise level
- Most recently maintained (updated Feb 2026)
- ModernBERT architecture: FlashAttention, unpadding (future perf gains)
- Apache 2.0 license (vs EmbeddingGemma's restrictive Gemma Terms)
- Same integration profile as gte-base (CLS, no prefix, 768-dim)

### Phase 15: Throughput baseline and fixes (2026-04-09)

**Baseline** (jseval, SciFact 5184, git 3af30db9e, gte-multilingual-base FP16):

| Metric | Value |
|--------|-------|
| Total pipeline | **296.3s** |
| Full pipeline rate | **17.3 docs/s** |
| Embedding 100% | 286.0s |
| SPLADE 100% | 294.3s |
| Chunks 100% | 201.1s |
| NER complete | 209.4s |
| Primary indexing | 26.8s / 187.5 docs/s |
| SPLADE churn drops | 35 |
| GPU avg | 48.3%, peak 92% |
| VRAM avg | 3,410 MB, peak 4,009 MB |

Per-batch timing (combined backfill):

| Phase | Batches | Avg ms/batch | Total ms |
|-------|---------|-------------|----------|
| Embedding | 49 | 1,113 | 54,535 |
| SPLADE | 58 | 1,464 | 84,925 |
| NER | 53 | 639 | 33,873 |
| Write | 84 | 148 | 12,450 |
| Total | 84 | 2,324 | 195,201 |

**Fixes applied in this phase (commit 3af30db9e):**

36. [x] **Fix SPLADE churn from non-combined RMW paths.**
    `WritePathOps.readModifyWrite` SPLADE guard (line 287-291) was
    resetting `SPLADE_STATUS=PENDING` on all RMW writes without SPLADE
    fields — including embedding/NER success writes and VDU lifecycle
    writes. Added `boolean preserveSplade` parameter; 16 non-content
    callers pass `true`. Content-changing paths keep `false`.

37. [x] **Relax SPLADE embed-completion gate.**
    Fallback path Gate B at `IndexingLoop.java` line 633 required
    `pendingEmbedForSplade == 0`. Relaxed to
    `< EMBEDDING_BACKFILL_BATCH_SIZE`. Allows SPLADE to start ~8s
    before embedding finishes.

**Known remaining issues:**

38. [ ] **Periodic 6.2s GPU stalls (~19s total).**
    Main process periodically claims GPU via MMF signal. Worker's
    `handleGpuStateTransition()` synchronously destroys and recreates the
    ONNX CUDA session on the loop thread. The 6.2s is model load time.
    Async reload was attempted and **failed** — SPLADE hung at 92.3% for
    250s+ due to hidden interactions between the reload flag and the
    combined/fallback path selection, `parentIdCache` repopulation, and
    `shouldRunBackfill()` gate. Needs a deeper redesign of the GPU
    transition lifecycle, not just making the reload async.

39. [ ] **Fix jseval NER false completion detection.** `_first_at_threshold`
    in `timeline.py` triggers on first poll where coverage >= 100%, even
    when only 5 docs exist. Fix: require `min_docs` floor (e.g., 50) or
    require primary indexing to have completed before checking NER.

40. [ ] **Canonical doc sync (from tempdoc 312 item 41).** Update
    `docs/reference/performance/indexing-throughput.md` with current
    measured rates and the gte-multilingual-base model.

### Optimization opportunities

| # | Improvement | Est. savings | Complexity | Risk |
|---|------------|-------------|------------|------|
| P4 | SPLADE PRESPARSE batching (Phase 13 Item B) | 8-12s | Medium | Medium |
| P5 | NER chunk size 400→550 (Phase 13 Item C) | ~10s | Low | Low-medium |
| P6 | Embed length sorting (Phase 13 Item E) | 2-4s | Low | Low |
| P7 | Between-batch overhead decomposition (Phase 13 Item D) | 10-30s | Medium | Medium |

P4-P7 are Phase 13 items identified but never implemented.
Target: ~250-270s.

**Measurement protocol (same as Phase 10):**
- Wait ≥3 min between jseval runs (GPU thermal cooldown)
- A/B comparison: one variable per experiment
- `jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean --timeline tmp/timeline.tsv --json`

## Non-Goals

- Multi-writer Lucene
- External vector store
- Distributed enrichment

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 38 days at audit time.

