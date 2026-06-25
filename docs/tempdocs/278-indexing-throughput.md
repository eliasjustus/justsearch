---
title: "278: Indexing Throughput"
type: tempdoc
status: done
created: 2026-03-12
updated: 2026-03-15
---

> NOTE: Noncanonical doc (architecture + implementation). May drift.

# 278: Indexing Throughput

## Purpose

Improve indexing throughput from the current ~4 docs/sec to a level that
makes initial indexing of a 10K-file Documents folder tolerable (~42 min
today). The bottleneck chain is well-understood from prior tempdocs; this
doc consolidates the throughput-specific work items and sequences them.

This tempdoc does NOT propose changing the responsiveness-first default.
It proposes **making indexing faster when the user is not actively using
the system**, and **reducing per-document overhead**.

---

## Measured Throughput

### Pre-implementation baselines

| Scenario | docs/sec | Source |
|----------|----------|--------|
| Full pipeline (Tika + embed + SPLADE) | ~4 | tempdoc 142 |
| SciFact ONNX INT8 CPU embed | ~0.86 | tempdoc 268 |
| SciFact GGUF Q8 CPU embed | ~0.58 | tempdoc 268 |
| GPU SPLADE-only, batch=1, seqLen=256 | ~6.7 | tempdoc 273 |
| Engine-only (no extraction/embedding) | 2,800-3,600 | tempdoc 142 |

### Post-implementation results

| Scenario | docs/sec | Notes | Source |
|----------|----------|-------|--------|
| **Primary indexing (complete SciFact)** | **5.3–5.8** | 5,184 docs / 14.9–16.2 min; SPLADE deferred; Tika+write is bottleneck | 278 complete runs |
| **GPU SPLADE backfill (FP32, idle, batch=50)** | **4.17 avg** | 4,378 docs / 17.5 min; 1.83 early, 4.33 mid, 7.02 late | 278 GPU run |
| **CPU SPLADE backfill (idle, batch=50)** | **2.97 avg** | 4,333 docs / 24.3 min; 1.56 early, 4.17 late | 278 CPU run |
| SPLADE interleave (during primary) | 0.88 | ~811–856 docs; batch=10 every 5s | 278 complete runs |
| **Full pipeline (GPU SPLADE)** | **2.67** | 5,189 docs / 32.4 min | 278 GPU run |
| Full pipeline (CPU SPLADE) | 2.13 | 5,189 docs / 40.6 min | 278 CPU run |
| **Historical** | | | |
| Full pipeline SciFact (partial, 2026-03-13) | 4.4–5.1 | 4,245/5,184 docs; interrupted | 278 partial run |
| CPU SPLADE backfill (pre-postProcess fix) | ~1.63 | 615ms/doc due to cache-thrashing postProcess loop | 278 Phase 4 |
| GPU SPLADE backfill (FP16, batch=8) | ~14.9 | FP16 model since deleted; listed for comparison | 278 Entry 27 |

### Bottleneck analysis (SciFact complete runs, 2026-03-15)

Two complete SciFact runs (5,189 docs each) with all 278 optimizations:
- **Embedding**: 100% coverage reached during primary indexing in both runs.
  Embedding is never the bottleneck.
- **SPLADE interleaving**: ~811–856 docs completed during primary indexing
  (batch=10 every 5s, ~0.88 docs/sec). Backlog accumulates as designed;
  bulk processing in dedicated backfill phase after primary completes.
- **Primary indexing**: 5.3–5.8 docs/sec steady-state. Up from ~4 docs/sec
  pre-278 baseline (which included inline SPLADE). Improvement from
  deferring SPLADE (2a) and batch embedding (1c).
- **SPLADE backfill**: GPU FP32 averages 4.17 docs/sec, CPU averages 2.97.
  Rate varies 3-4x within a run due to token-budget sort (long docs first).
- **Conclusion**: Primary indexing is bottlenecked by Tika extraction +
  Lucene write, not inference. SPLADE backfill is bounded by per-batch
  ONNX inference + Lucene read-modify-write overhead per document.

---

## Constraints

1. **Responsiveness-first remains the default.** All throughput
   improvements must respect user-active pausing and GPU arbitration.
2. **Head never touches Lucene.** All index IO stays in the Worker.
3. **Single-writer invariant.** Lucene IndexWriter is single-threaded.
   Pipeline parallelism must funnel writes through one writer.
4. **ONNX Runtime thread safety.** `OrtSession.run()` is thread-safe.
   Multiple threads can call `run()` concurrently on the same session.
5. **Windows memory pressure.** GPU + embedding + SPLADE must fit in
   VRAM budget without OOM on 8GB VRAM cards.

---

## Dependencies

- **268 (ONNX Migration):** Landed. Batch embedding (1c) builds on `OnnxEmbeddingEncoder`.
- **266 (SPLADE Throughput):** SPLADE batching architecture decisions.
- **142 (Progressive Indexing):** Orthogonal. If 142 proceeds, design around 278's batch architecture.
- **252 (Ingestion Quality):** Independent.
- **273 (SPLADE Follow-up):** SPLADE backfill now processes chunks too (not just parents).
  Affects workload estimates for items 2a, 2b. **Owns SPLADE quality eval
  (nDCG/MRR)** — 278 item 6b defers to 273 for golden vector verification.

---

## Non-Goals

- Changing the responsiveness-first default for desktop users
- Multi-writer Lucene (IndexWriter is inherently single-writer)
- External inference servers (Triton, TEI)
- Re-architecting the job queue away from SQLite
- Progressive indexing design (tempdoc 142)
- Upgrading to `ALL_OPT` (numerical output changes vs `EXTENDED_OPT`)

---

## Work Items

### Completed

> **Note (2026-03-15):** File paths below reference the pre-restructure
> `indexerworker/indexing/` package. These files now live at
> `indexerworker/loop/IndexingLoop.java` and `indexerworker/loop/ops/`
> (`LoopPacingPolicy.java`, `SpladeBackfillOps.java`,
> `IndexingDocumentOps.java`, `EmbeddingBackfillOps.java`).
> ONNX/SPLADE files are unchanged.

- [x] **0-pre. Commit watermark.** `markDone()` deferred to after `commit()`
  via `pendingMarkDone` list + `drainPendingMarkDone()`. Crash recovery via
  idempotent re-indexing.

- [x] **0a. ORT thread tuning.** `interOpNumThreads=1`, `allow_spinning=0`
  on embedding, SPLADE, and reranker. `intraOpNumThreads` left at 0 for
  auto P-core detection.

- [x] **0b. Adaptive idle sleep.** 100ms when recently active, 1000ms when
  truly idle.

- [x] **1b. POLL_BATCH_SIZE.** Increased from 1 to 16.

- [x] **1c. Chunk-aware batch embedding.** `embedBatchWithChunking()` with
  `MAX_ORT_BATCH_SIZE=8`, sub-batching, proper chunking for long docs.

- [x] **1d. Batch SPLADE in backfill.** `encodeBatch()` wired into
  `SpladeBackfillOps` with inter-batch interrupt checks.

- [x] **3b. GPU SPLADE backfill.** `encodeBatch()` with batch=8 on GPU.

- [x] **4a. SPLADE interleaving.** Time-gated at 5s intervals during primary
  indexing. Idle=50, interleave=10.

- [x] **4b. Remove `isMainGpuActive()` gate.** Encoder's `selectSession()`
  handles GPU/CPU fallback internally.

- [x] **4c. Increase SPLADE backfill batch size.** Idle=50, interleave=10.

- [x] **5b-1. ORT pinned outputs.** Zero-heap SPLADE inference via
  pre-allocated `FloatBuffer` and `OrtSession.run(inputs, emptySet, pinnedOutputs)`.

- [x] **5b-2. GPU arena default.** Increased to 4096 MB.

- [x] **5b-3. Token-budget batching.** `encodeBatchTokenBudget()` in
  `SpladeEncoder`: sort by token count, partition by budget, encode each
  partition with minimal padding, reassemble in original order.

- [x] **SeqLen bucketing** (from 273 merge). `SEQ_LEN_BUCKETS = {128, 256, 384, 512}`.
  Prevents BFCArena fragmentation from variable-size pinned output tensors.

- [x] **1a. Per-document `isUserActive()` check.** Inside the extraction
  for-loop.

- [x] **2a. Defer SPLADE to backfill by default.** Inline SPLADE removed
  from `IndexingDocumentOps.buildDocument()` — documents marked
  `SPLADE_STATUS_PENDING` unconditionally.

- [x] **2b. Decouple SPLADE backfill from embedding backfill ordering.**
  SPLADE backfill runs independently; no gate on embedding completion.

- [x] **3-pre-a. Update ORT natives** to 1.24.3 with CUDA 12 support.

- [x] **3-pre-b. FP16 model auto-discovery.** `tryCreateGpuSession()` looks
  for `model_fp16.onnx` first, falls back to `model.onnx` if FP16 fails or
  is absent. (Post-278: FP16 SPLADE model deleted in `27c86c7b` due to ORT
  CUDA EP incompatibility. Embedding FP16 model unaffected. FP32 fallback
  added to both `OnnxEmbeddingEncoder` and `SpladeEncoder`.)

- [x] **3a. GPU embedding with CUDA EP.** `tryCreateGpuSession()` with
  lazy initialization and `shouldUseGpu` callback.

### Deferred (data-informed)

- [~] **2c. Extract-ahead pipeline.** Deferred — SciFact data shows
  embedding has zero queue depth throughout. The bottleneck is Tika +
  Lucene write, not embedding compute. Extract-ahead only helps if Tika
  dominates wall time (PDF/Office-heavy corpora). Low value for text-
  heavy workloads. Revisit if a PDF-heavy benchmark shows Tika as the
  limiter.

- [x] **5b-4. ORT transformer optimizer.** Applied O3 optimization to
  SPLADE model: 12 Attention, 24 SkipLayerNorm, 13 BiasGelu fusions.
  Measured 1.26x standalone, 1.55x combined with INT8 quantization
  (EXP-8). Model files generated but not shipped — quality validation
  via tempdoc 273 required before replacing the FP32 default.

### Measurement (completed 2026-03-15)

- [x] **6a. Complete SciFact run with SPLADE backfill timing.** Two full
  uninterrupted SciFact runs (5,189 docs each): CPU-only and GPU FP32.
  - **CPU run:** Primary 5.32 docs/sec. CPU SPLADE backfill 2.97 avg
    (1.56 early / 4.17 late). Total pipeline: 40.6 min (2.13 docs/sec).
  - **GPU run (FP32):** Primary 5.80 docs/sec. GPU SPLADE backfill 4.17
    avg (1.83 early / 4.33 mid / 7.02 late). Total: 32.4 min (2.67 docs/sec).
  - GPU SPLADE is 1.4x faster than CPU overall. FP16 model failed to
    load (ORT CUDA EP incompatibility, as expected from `27c86c7b`);
    FP32 fallback worked correctly.
  - Zero failures across both runs. Embedding reached 100% before SPLADE.

- [x] **6b. SPLADE quality verification.** Deferred to tempdoc 273.

- [x] **6c. Update reference doc.** `docs/reference/performance/indexing-throughput.md`
  updated with CPU and GPU SPLADE backfill rates, interleave rates,
  full pipeline end-to-end figures, and GPU vs CPU comparison.

---

## Agent Workflow Lessons (2026-03-15 measurement session)

Lessons from the measurement-only session that completed items 6a–6c.

1. **Verify GPU session before ingesting data.** After starting the dev
   stack, check the worker log for `SPLADE first encode: using {GPU/CPU}`
   before sending documents. Sub-batch size in the pinned-output log also
   confirms: batch=8 means GPU, batch=4 means CPU.

2. **Search for existing artifacts before accepting a blocker.** CUDA DLLs
   were declared "unavailable" and an entire CPU-only run was executed
   (~40 min) before discovering the DLLs already existed at
   `tmp/ort-variant-test/cuda-12.4-v1.24.3/`. The decision log Entry 27
   explicitly mentioned copying DLLs from a temp directory — that was a
   direct hint. A 2-minute `find` would have saved the detour.

3. **Grep the codebase for field names, don't explore endpoints.** Finding
   `spladePendingCount` took ~10 min of trial-and-error API calls. One
   `grep -r spladePendingCount modules/` would have shown it's served by
   `/api/status`, not `/api/knowledge/status` or `/api/debug/state`.

4. **Fall back from broken MCP tools immediately.** The `fetch_api_json`
   MCP tool failed with `mainRepoRoot is not defined` three times before
   switching to direct `curl`. Fall back on first failure.

5. **Set `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` for GPU SPLADE.** ORT 1.24.3
   does not bundle CUDA DLLs. Point this env var at the DLL directory
   (e.g., `tmp/ort-variant-test/cuda-12.4-v1.24.3`). Also requires
   `JUSTSEARCH_SPLADE_GPU_ENABLED=true`. The MCP server's process does not
   inherit shell env vars — start the dev stack via `dev-runner.cjs`
   directly with inline env vars.

---

## Further Throughput Improvements (research, 2026-03-15)

Model-specific analysis of remaining optimization opportunities, based on
the exact models JustSearch uses and public benchmarks for comparable systems.

### Model optimization gap

| Model | Current precision | Opportunity |
|-------|------------------|-------------|
| nomic-embed-text-v1.5 (embedding) | **INT8** (131 MB) | Already quantized |
| naver/splade-v3 (SPLADE) | **FP32** (508 MB) | INT8 would be ~134 MB |
| ms-marco-MiniLM-L-6-v2 (reranker) | **INT8** (22 MB) | Already quantized |

**The SPLADE model is the only FP32 model in the pipeline and is the slowest
backfill stage.** Every other model is already INT8 quantized.

### Ranked improvement opportunities

**Shipped (quality validated via EXP-9, deployed 2026-03-16):**

1. **O3+INT8 SPLADE model** — now the default `model.onnx` (134 MB).
   1.55x CPU throughput, nDCG@10 delta -0.004. Originals preserved as
   `model_fp32_original.onnx` and `model_fp16_naive.onnx`.

2. **O3+FP16 for GPU** (measured 1.07x early phase; loads on CUDA EP).
   `model_o3_fp16.onnx` (266 MB). Drop-in replacement for
   `model_fp16.onnx`. Prior naive FP16 failed; O3-fused graph works.

**Requires architecture work:**

3. **Batch Lucene `updateDocument()`** — the dominant bottleneck.
   Profiling (2026-03-16) shows per-doc breakdown for GPU SPLADE backfill:

   | Phase | Avg/doc | % of total |
   |-------|---------|------------|
   | query (find pending) | 0ms | 0% |
   | contentFetch | 0ms | 0% |
   | encode (tokenize+ORT+postProcess) | 28ms | 24% |
   | **luceneWrite (updateDocument)** | **88ms** | **74%** |
   | commit | 3ms | 2% |

   Lucene `updateDocument()` at 88ms/doc is 74% of pipeline time. This
   is the per-doc read-modify-write that fetches the existing document,
   adds the SPLADE field, and writes it back. Batching these writes
   (e.g., collecting all updates then writing in one pass) would be the
   single highest-impact change. With zero write cost, max rate would be
   ~36 docs/sec (from 28ms encode); with 50% reduction, ~16 docs/sec.

4. **Pipeline parallelism / double-buffering.** Less impactful than
   previously estimated now that profiling shows encode is only 24% of
   time. Overlapping encode with Lucene writes would help but the write
   cost still dominates. Est. 1.3x.

**Lower priority:**

5. **Matryoshka dimension reduction** (est. 1.5–2x embedding speed, high
   effort). nomic-embed-text-v1.5 supports 64–768 dims via Matryoshka.
   Reducing to 256 retains 95.8% quality per Nomic's benchmarks. Requires
   re-embedding all documents.

### EXP-8: INT8 SPLADE throughput comparison (completed 2026-03-15)

**Method:** Generated `model_int8.onnx` (134 MB) via ORT dynamic
quantization (`quantize_dynamic`, `QInt8`). Ran 1,000-doc SciFact subset
with FP32 then INT8, both CPU-only. 3-minute measurement windows on
SPLADE idle backfill (batch=50), early phase (long docs).

**Results (1,000-doc SciFact subset, 3-min early-phase windows):**

| Model | Size | Device | Rate | vs FP32 CPU | Failures |
|-------|------|--------|------|-------------|----------|
| FP32 (baseline) | 532 MB | CPU | 1.10 docs/sec | 1.00x | 0 |
| O3 FP32 (fused) | 532 MB | CPU | 1.39 docs/sec | 1.26x | 0 |
| Raw INT8 | 134 MB | CPU | 1.47 docs/sec | 1.34x | 0 |
| **O3+INT8** | **134 MB** | **CPU** | **1.71 docs/sec** | **1.55x** | **0** |
| FP32 (baseline) | 532 MB | GPU | ~1.83 docs/sec | 1.66x | 0 |
| **O3+FP16** | **266 MB** | **GPU** | **1.96 docs/sec** | **1.78x** | **0** |

**Key findings:**
- O3 fusion + INT8 quantization are **additive** (1.26x + 1.34x -> 1.55x)
- **O3+FP16 loads on CUDA EP** where the prior naive FP16 failed (the ORT
  optimizer produces a properly fused graph compatible with the CUDA provider)
- GPU O3+FP16 shows only 1.07x over GPU FP32 in the early phase — expected
  because pipeline overhead (Lucene I/O) dominates for long documents. The
  FP16 advantage would be larger in mid/late phases where shorter docs make
  inference the bottleneck.
- All variants produce zero failures

**Extrapolated full-run impact:**
- CPU O3+INT8: 2.97 avg -> ~4.6 docs/sec (pipeline: 40.6 -> ~33 min)
- GPU O3+FP16: 4.17 avg -> ~5–7 docs/sec (pipeline: 32.4 -> ~26–28 min)
- Combined best (GPU O3+FP16 with INT8 CPU fallback): not yet tested

**Generated model files** (in `models/splade/naver-splade-v3/`, gitignored):
- `model_o3.onnx` — O3 fused FP32 (532 MB)
- `model_o3_int8.onnx` — O3 fused + INT8 quantized (134 MB, CPU drop-in)
- `model_o3_fp16.onnx` — O3 fused + FP16 (266 MB, GPU `model_fp16.onnx` drop-in)
- `model_int8.onnx` — Raw INT8 without fusion (134 MB, superseded by O3+INT8)

**Shipping path:** Rename `model_o3_int8.onnx` to `model.onnx` (CPU) and
`model_o3_fp16.onnx` to `model_fp16.onnx` (GPU). Quality validation below.

### EXP-9: O3+INT8 quality validation (SciFact nDCG/MRR)

**Method:** Index SciFact with FP32 model, run 300 queries (lexical +
splade modes), record nDCG@10 and MRR@10. Then re-index with O3+INT8
model, run the same 300 queries, compare. Uses the eval config from
273 (`tmp/273-experiment/splade-quality-eval-config.json`).

**Pass:** O3+INT8 nDCG@10 within 0.02 of FP32 nDCG@10. Zero encode failures.

**Results (SciFact, 300 queries, 2026-03-16):**

| Mode | Metric | FP32 | O3+INT8 | Delta |
|------|--------|------|---------|-------|
| SPLADE | nDCG@10 | 0.590 | 0.586 | -0.004 |
| SPLADE | MRR@10 | 0.558 | 0.554 | -0.005 |
| SPLADE | Recall@10 | 0.711 | 0.714 | +0.003 |
| Lexical | nDCG@10 | 0.642 | 0.646 | +0.004 |
| Lexical | MRR@10 | 0.615 | 0.621 | +0.005 |

**Assessment: PASS.** SPLADE nDCG@10 delta is -0.004, well within the
0.02 threshold. All deltas are within measurement noise. Zero query
errors in both runs. The O3+INT8 model is quality-equivalent to FP32.

**Unblocked:** O3+INT8 model (`model_o3_int8.onnx`, 134 MB) is validated
for production use as the default CPU SPLADE model. 1.55x throughput
improvement with no quality regression.

---

## Archived Sections

Detailed research, tradeoff analysis, gap analysis, investigation plans,
and experiment designs have been archived to `278-research-archive.md`.
These informed the implementation decisions above but are no longer
actionable.

Archived sections: Improvement Areas, Tradeoff Analysis, Cross-Agent Notes,
External Research Findings (1-8), Revised Expected Impact, Investigation
Plan (INV-1 through INV-5), Experiments (EXP-1 through EXP-7), Critical
Gap Analysis (Gaps 1-11), Cross-Tempdoc Review Notes, Experiment
Prerequisites.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Indexing-throughput investigation. Body explicitly states "These informed the implementation decisions above but are no longer actionable" with an Archived sections list. Self-declared terminal.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

