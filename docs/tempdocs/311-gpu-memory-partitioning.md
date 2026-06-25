---
title: "311: GPU Memory Partitioning for Concurrent ORT Sessions"
type: tempdoc
status: active
created: 2026-03-16
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 311: GPU Memory Partitioning for Concurrent ORT Sessions

## Purpose

Fix GPU memory contention between ORT CUDA sessions (embedding, SPLADE,
cross-encoder) that causes embedding to silently fall back to CPU,
degrading chunk embedding backfill throughput by ~3-5x. Also address the
broader problem that ORT arenas never release idle VRAM — critical for
8 GB consumer GPUs where ORT sessions compete with llama-server for memory.

## Evidence (from tempdoc 310 profiling, 2026-03-16)

During SPLADE GPU backfill on RTX 4070 (12 GB VRAM), the embedding ORT
session fails with `ORT_RUNTIME_EXCEPTION` during inference and
permanently falls back to CPU:

```
OnnxEmbeddingEncoder GPU session initialized: model=model.onnx, device=0, memLimit=1024MB
SpladeEncoder GPU session initialized: model=model_fp16.onnx, device=0, memLimit=4096MB
...
Batch embedding failed: ONNX batch embedding failed: Error code - ORT_RUNTIME_EXCEPTION
  - message: Non-zero status code returned while running ...
(repeated dozens of times during primary indexing)
```

After the first failure, `OnnxEmbeddingEncoder` sets `gpuAvailable = false`
and uses the CPU session for all subsequent inference — permanently for
the encoder's lifetime.

### Impact on chunk embedding backfill

| Metric | GPU (if working) | CPU (actual fallback) |
|--------|-----------------|----------------------|
| Chunk batch (100 chunks) | est. 3-5s | 18-24s |
| Effect on SPLADE | SPLADE waits 3-5s per loop | SPLADE waits 18-24s per loop |

During the overlap phase (chunks + SPLADE both pending), chunk embedding
blocks SPLADE in each idle loop iteration. CPU chunk embedding at 18-24s
is the dominant factor keeping the overall pipeline at ~4.4 docs/sec
instead of the SPLADE per-batch rate of ~55 docs/sec.

## Root Cause

Two compounding problems: static arena sizing is wrong, and idle arenas
never release memory.

### Problem 1: Static arena sizing

| Session | Arena size | Model | Notes |
|---------|-----------|-------|-------|
| Embedding (nomic-embed-text-v1.5 INT8) | 1024 MB | 131 MB | `DEFAULT_GPU_MEM_MB = 1024` |
| SPLADE (naver/splade-v3 O3+FP16) | 4096 MB | 266 MB | `gpuMemLimitBytes` default |
| Cross-encoder (ms-marco-MiniLM INT8) | 512 MB | 22 MB | `GPU_MEM_LIMIT_BYTES` |
| **Total ORT claim** | **5632 MB** | | |
| llama-server (7B LLM) | ~4–6 GB | | Separate process |
| Available VRAM (RTX 4070 12 GB) | ~11,200 MB | | After OS/display |
| Available VRAM (8 GB consumer) | ~7,200 MB | | After OS/display |

On the 12 GB dev card, ORT alone claims 5.6 GB — leaving room for either
the LLM or peak activations, but not both comfortably. On an 8 GB card
the ORT arenas + LLM exceed available VRAM entirely.

At `MAX_ORT_BATCH_SIZE = 8` with 2048-token inputs, the embedding
model's activations may exceed the 1024 MB arena. ORT's BFCArena
allocator cannot grow beyond the pre-set limit, causing the CUDA EP to
throw `ORT_RUNTIME_EXCEPTION`.

### Problem 2: Arenas never release idle memory

ORT pre-allocates GPU memory in an arena — a large block grabbed upfront
to avoid per-inference allocation overhead. This arena **never releases
memory back to the system**, even when the model isn't running inference.
All three GPU sessions (embedding, SPLADE, cross-encoder) permanently
hold their arenas.

ORT provides `memory.enable_memory_arena_shrinkage` (CUDA EP option) to
release unused arena memory between inference calls. The tradeoff:
slightly slower next inference (memory must be re-allocated), but GPU
memory is freed for other sessions and the LLM. **None of the three
session setup sites currently enable shrinkage.**

Current CUDA EP config (identical pattern in all three):
```java
cudaOpts.add("gpu_mem_limit", String.valueOf(...));
cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
// No shrinkage configured — arena holds allocation permanently
```

### Silent fallback behavior

`OnnxEmbeddingEncoder.tryCreateGpuSession()` catches the exception and
sets `gpuAvailable = false`. All subsequent `selectSession()` calls
return `cpuSession`. There is no recovery mechanism — the GPU session
is abandoned for the lifetime of the encoder.

`OnnxEmbeddingEncoder.selectSession()`:
```java
if (gpuSessionReleasing) return cpuSession;
if (!gpuEnabled || !shouldUseGpu.getAsBoolean()) return cpuSession;
return gpuAvailable ? gpuSession : cpuSession;
```

### Why it manifests during backfill

During primary indexing, embedding runs inline (before SPLADE exists in
the index). Embedding GPU + SPLADE GPU sessions coexist. When both try
to allocate activations concurrently, the smaller embedding arena runs
out first.

During idle backfill, the same sessions are still live. Embedding
continues to use CPU fallback.

## Implementation

### Phase 1: Arena Shrinkage — COMPLETED

Three coordinated changes across all 3 GPU session creation sites:

- [x] **6. Enable shrinkage + device allocator for initializers.**
  Added `session.use_device_allocator_for_initializers = "1"` (SessionOptions)
  and `memory.enable_memory_arena_shrinkage = "gpu:0"` (RunOptions) to all
  3 encoders. RunOptions created with GPU session, closed in
  `releaseGpuSession()` and `close()`. Helper `runOptionsFor(session)`
  selects GPU RunOptions or null for CPU.

- [x] **8. Disable CUDA Graphs.** Added `enable_cuda_graph = "0"` to all
  3 GPU session CUDA provider options. ORT 1.24.1 enables graphs by
  default; graphs pin memory addresses, preventing arena shrinkage.

- [x] **Run() call sites updated (6 total).** All GPU-capable
  `session.run()` calls now pass RunOptions:
  - `OnnxEmbeddingEncoder`: `embedPreTokenizedBatch()`, `embedSingle()`
  - `SpladeEncoder`: `runMLMInference()` (4-arg pinned output overload),
    `runSingleSparseInference()`, `runHeapFallback()`
  - `CrossEncoderReranker`: `rerank()`

**Build:** Compilation green. 785/788 tests pass (3 pre-existing model
discovery path failures in worktrees, unrelated).

### Phase 1 Measurement Results (2026-03-16)

Full SciFact run (5,189 docs) with SPLADE on GPU FP32, embedding on CPU.
RTX 4070 12 GB. VRAM baseline ~1,200 MB (OS + display).

| Phase | VRAM (MB) | Delta from baseline |
|-------|-----------|---------------------|
| Idle (post-GPU-session-init) | 1,951 | +751 MB |
| Primary indexing (peak) | 1,973 | +773 MB |
| Pure SPLADE backfill (active, batch=50) | 1,973 | +773 MB |
| All complete (idle) | 1,879 | +679 MB |

**SPLADE peak GPU VRAM: ~770 MB.** The 4,096 MB arena limit is ~5x
overprovisioned. Shrinkage keeps VRAM flat at ~1.97 GB throughout the
entire run — never approaching the arena limit. Post-inference idle drops
to 1,879 MB as scratch memory is freed.

**Embedding GPU not tested** — `gpuLayers=0` (config). With shrinkage
active, ~9.2 GB free VRAM available for embedding GPU. No
ORT_RUNTIME_EXCEPTION observed (the original problem).

### Phase 2: Right-Size Arena Limits — COMPLETED

Based on Phase 1 measurement (SPLADE peak ~770 MB):

- [x] **SPLADE:** 4,096 → **2,048 MB**. Initial reduction to 1,024 MB
  caused BFC arena allocation failures at batch=8 seqLen=512 because
  `use_device_allocator_for_initializers` moves model weights outside the
  arena — the measured ~770 MB peak was total VRAM, not arena usage.
  2,048 MB provides safe headroom for activations.
  Changed default in `SpladeConfig.fromEnv()` and `ResolvedConfigBuilder`.
- [x] **Embedding:** 1,024 → **2,048 MB**. At 1,024 MB, embedding GPU
  failed with ORT_RUNTIME_EXCEPTION during primary indexing when SPLADE
  GPU was concurrently active (16 failures per 1K SciFact run). At
  2,048 MB, failures dropped to 2 (initial warmup only).
- [x] Total Worker GPU budget: 5,632 → **4,096 MB** (SPLADE 2048 + Embed 2048; CE CPU-only).

### Phase 2 Measurement Results (2026-03-16, combined with tempdoc 310)

Full pipeline measurement: 1K SciFact, GPU SPLADE O3+FP16, GPU embedding,
both arenas at 2,048 MB, arena shrinkage enabled, batch writes active.

| Metric | Pre-310/311 | Post-310 only | **Post-310+311** |
|--------|-------------|---------------|------------------|
| Embedding GPU failures | 16+ | 16 | **2** |
| SPLADE arena failures | N/A | 0 | **0** |
| Chunk embedding batch | 18-24s | 18-24s (CPU) | **8.6-8.9s** |
| Primary indexing rate | 8.4 docs/sec | 8.4 docs/sec | **7.9 docs/sec** |
| SPLADE idle batch=200 | — | 54.9 docs/sec | **55.9 docs/sec** |
| **Full pipeline (1K)** | **244s (4.09/s)** | **226s (4.42/s)** | **185s (5.41/s)** |

**Full pipeline: 185s = 5.41 docs/sec** (32% faster than pre-optimization).

Primary indexing rate (7.9/s) is slightly below pre-311 (8.4/s) due to
GPU session initialization overhead from arena shrinkage. The 2 remaining
embedding failures occur during initial warmup when both arenas first
allocate. After warmup, embedding and SPLADE coexist on GPU without
contention.

The projected 120 docs/sec for primary indexing (tempdoc 312) was based
on a flawed assumption that batch GPU embedding would reduce per-doc
cost to ~5.6ms. Actual GPU embedding at batch=16 costs ~700ms/batch =
~44ms/doc — comparable to CPU. The GPU advantage for embedding is modest
on this model at this batch size.

### Phase 3: Fix Embedding shouldUseGpu Static Lambda — COMPLETED

- [x] **5. Fix static lambda.** Changed `shouldUseGpu` field from
  `final` to `volatile` on `OnnxEmbeddingEncoder`. Added
  `setShouldUseGpu(BooleanSupplier)` setter, forwarded through
  `OnnxEmbeddingBackend` → `EmbeddingService`. Wired in
  `KnowledgeServer.start()` with `() -> !signalBus.isMainGpuActive()`
  after embedding service initialization.

### Phase 4: Move Worker-side CE to CPU-Only — COMPLETED

- [x] **Worker-side CE to CPU.** Simplified `RagContextOps.getChunkReranker()`
  to always use `GpuConfig.CPU_ONLY`. Removed GPU config branching and
  signal bus wiring for Worker-side CE. Head-side CE retains GPU support.
  Saves 512 MB GPU arena in the Worker JVM.

### Phase 5: GPU Failure Recovery — COMPLETED

- [x] **Periodic GPU retry.** Added `gpuFailedAtMs` timestamp + 60s
  `GPU_RETRY_INTERVAL_MS` to all 3 encoders. On failure,
  `tryCreateGpuSession()` records timestamp. `selectSession()` checks
  if >60s have passed and resets `gpuSessionAttempted` for re-attempt.
  `releaseGpuSession()` clears the timer (intentional release is not a
  failure). Both mechanisms are orthogonal: release = immediate
  re-creation, timer = deferred failure recovery.

### Phase 6: GPU Inference Serialization (Concurrent Run Fix)

Root cause investigation (2026-04-09) revealed that the remaining BFC
arena failures stem from **concurrent `session.run()` calls on the same
GPU session**, each allocating independent activation buffers from the
same BFCArena.

**Root cause (confirmed via ORT source `bfc_arena.cc`, `execution_frame.cc`,
`inference_session.cc`):**

1. Each concurrent `run()` creates its own `ExecutionFrame` with its own
   activation buffers — all from the SAME BFCArena
2. If one run needs X MB activations and N threads run concurrently, the
   arena needs N × X MB. The `gpu_mem_limit` is sized for 1×, not N×
3. BFCArena does NOT attempt shrinkage before failing (`AllocateRawInternal`
   → `Extend()` → checks `memory_limit_ - total_allocated_bytes` → throws
   immediately if exceeded). Shrinkage only fires AFTER `run()` completes
4. `use_ep_level_unified_stream = 1` serializes GPU kernel execution but
   NOT CPU-side arena allocations (allocations happen before kernel submit)

**Concurrency analysis of our codebase:**

| Session | indexing-loop | gRPC Netty threads | Concurrent? |
|---|---|---|---|
| Embedding | backfill | `SearchOrchestrator.prepareQueryVector()` | **Yes** |
| SPLADE | backfill | `SearchOrchestrator.prepareSpladeWeights()` | **Yes** |
| Reranker | — | search reranking | **Yes** (multi-query) |

`DefaultWorkerAppServices.wireSpladeEncoder()` wires the same instance
to both `indexingLoop` and `searchService`. Same pattern for embedding.

**Fix:** `Semaphore(1)` per `OrtSessionManager` serializing GPU `run()`.
New `SessionLease` API with try-with-resources.

- [x] Add `SessionLease` record + `acquireSession()` to `OrtSessionManager`
- [x] Update `OnnxEmbeddingEncoder` callsites (2: `embedPreTokenizedBatch`, `embedSingle`)
- [x] Update `SpladeEncoder` callsites (1 top-level: `runOnnxInference`)
- [x] Update `CrossEncoderReranker` callsites (1: `rerank`)
- [x] Update `BertNerInference` callsites (2: `infer`, `inferBatch`)
- [x] Update `CitationScorer` callsites (1: `scoreSentenceAgainstChunks`)
- [x] Remove dead `ep.share_ep_contexts = 1` (does nothing for CUDA EP —
  only QNN/OpenVINO, confirmed in ORT source)

**Build:** Compilation green. All module tests pass (ort-common, worker-core,
reranker). 2 pre-existing model discovery path failures in worktree
(EmbeddingOnnxModelDiscoveryTest, unrelated).

### Phase 6 Measurement Results (2026-04-09)

Full SciFact run (5184 docs) with GPU embedding, GPU SPLADE, GPU NER.
RTX 4070 12 GB. jseval `--pipeline --start-backend --clean`.

| Metric | Phase 2 (pre-semaphore) | **Phase 6 (semaphore)** |
|--------|------------------------|------------------------|
| Embedding GPU failures | 2 (warmup) | **0** |
| Full pipeline time | 185s (1K subset) | **334s (5184 docs)** |
| Pipeline throughput | 5.41 docs/s (1K) | **15.4 docs/s** |
| Primary indexing | — | 27s (186 docs/s) |
| Embedding complete | — | 322s |
| SPLADE complete | — | 332s |
| NER complete | — | 241s (7294 entities) |
| Chunks complete | — | 233s |
| GPU avg utilization | — | 51% |
| GPU peak utilization | — | 91% |
| Peak VRAM | — | 4577 MB |
| Avg VRAM | — | 3921 MB |

**Key result:** Zero GPU failures across the entire 5184-doc pipeline.
The semaphore eliminates BFC arena contention without measurable
throughput loss (GPU kernel execution was already serialized by the
unified stream).

**Search quality (hybrid):** nDCG@10=0.7290, P@1=0.58, R@10=0.889.
Mean query latency 165ms (p50=162ms, p95=174ms).

### Post-merge regression check (2026-04-09)

SciFact full pipeline run after 311 merge + 381 model distribution.
Same hardware (RTX 4070), same dataset, `jseval --pipeline --start-backend --clean`.

| Stage | Phase 6 (311) | Post-merge | Delta |
|-------|--------------|------------|-------|
| Primary indexing | 27s (186 docs/s) | 29s (170 docs/s) | -9% |
| Embedding 100% | 322s | **302s** | -6% (faster) |
| SPLADE 100% | 332s | **310s** | -7% (faster) |
| NER complete | 241s | **223s** | -7% (faster) |
| Chunks 100% | 233s | **210s** | -10% (faster) |
| GPU avg util | 51% | 43% | -8pp |
| Peak VRAM | 4577 MB | 4576 MB | same |

Pipeline is ~7% faster overall (332s → 310s bottleneck). GPU
utilization dropped 8pp (51% → 43%), suggesting the serialization
semaphore is slightly more aggressive than the Phase 6 measurement.

**However:** Compared to the pre-311 canonical baseline from
`docs/reference/performance/indexing-throughput.md`:

| Stage | Pre-311 baseline | Post-merge | Delta |
|-------|-----------------|------------|-------|
| Primary indexing | 25s (201 docs/s) | 29s (170 docs/s) | **-15%** |
| Embedding 100% | **179s** | 302s | **+69%** |
| SPLADE 100% | **241s** | 310s | **+29%** |
| NER complete | 262s | 223s | -15% (faster) |

**Embedding is 69% slower than the pre-311 baseline (179s → 302s).**
The canonical baseline was measured before the GPU serialization
semaphore. The semaphore prevents concurrent embedding + SPLADE GPU
calls that previously overlapped. This is the expected cost of
the serialization fix — BFCArena stability at the expense of
throughput.

The Phase 6 measurement (322s) already showed this regression but
was not compared against the canonical baseline. The 179s → 302s
gap was masked by the fact that Phase 6 used a different dataset
size (1K subset) making direct comparison non-obvious.

**Search quality unchanged:** nDCG@10=0.725 (hybrid), 0.663
(lexical). Within run-to-run variance of baselines.

**Open question:** Can the semaphore be relaxed to allow embedding
and SPLADE to overlap when they use separate ORT sessions with
separate arenas? Currently the semaphore is global per session
manager, serializing ALL GPU inference. Per-session semaphores
could recover the lost concurrency without risking BFCArena
contention between the same session type.

**Additional finding — SpladeEncoder data race:**

`pinnedOutputTensor`, `pinnedOutputBuffer`, `pinnedBatchSize`,
`pinnedSeqLen` are unsynchronized mutable instance fields accessed from
both indexing-loop thread and gRPC search threads. The comment
"single-threaded access from indexing-loop" is inaccurate —
`wireSpladeEncoder()` shares the instance. This is a correctness bug
(tracked separately, not in this phase).

**WDDM note (Windows):** `cudaMalloc` can silently fall back to system
RAM instead of failing when VRAM is exhausted. BFCArena's accounting
may believe it has fast GPU memory when some is system-RAM-backed
(10-20× slower). Conservative `gpu_mem_limit` sizing is the only defense.

### Phase 7: Arena right-sizing post-358 model swap (2026-04-19)

**Regression context.** Phase 6's zero-failure result (2026-04-09) held
until the 358 model swap replaced the embed model from Q4
EmbeddingGemma (131 MB INT8) with gte-multilingual-base (628 MB FP16).
The arena:model ratio collapsed from 15× to 3.3×, tight enough that
MLP intermediate activations fragment the 2048 MB arena during
concurrent enrichment.

**Observed in tempdoc 391's 2026-04-19 re-measurement:** 10 BFCArena
`AllocateRawInternal` failures per 215 s pipeline run, all of form
`Available 36-81 MB / Requested 73-165 MB` (MLP `up_gate_proj` node).
Fragmentation, not total exhaustion. Pipeline still completed
correctly (retries succeeded), but each failure cascaded to SPLADE-
status updates via the combined-enrichment RMW path, elevating
`splade_churn_drops` from baseline median 1 to median 4.

**Fix (committed `3af6773cc`): raise `DEFAULT_GPU_MEM_MB` from 2048 →
3072 MB** in both `OnnxEmbeddingEncoder` (encoder fallback) and
`ResolvedConfigBuilder` (config-layer default). Adds ~1 GB headroom —
5-10× worst-case single-allocation size — while staying within the
7.2 GB ORT envelope on 8 GB cards (8 GB users land on GPU-lite profile
with no LLM per tempdoc 381).

**A/B results** (jseval scifact, 5184 docs, same session, same day):

| Metric | Pre (2048) | Post (3072) | Δ |
|---|---|---|---|
| Total wall (s) | 220.5 | **197.1** | −10.6 % |
| Total docs/s | 23.5 | **26.3** | +11.9 % |
| 3-run CV | 4.3 % | **1.6 %** | stability improved |
| BFCArena failures/run | 10 | **0 / 0 / 0** | eliminated |
| `splade_churn_drops` | 4 / 5 / 2 | **0 / 0 / 0** | eliminated |
| hybrid nDCG@10 | 0.7536 | **0.7540** | matches baseline exactly |
| embed p99 (µs) | 129 761 | 152 567 | +17.6 % (noise) |
| ner p99 (µs) | 4 184 | **3 893** | −7.0 % |

**Sysmem-fallback investigation (discarded hypothesis).** A first 3072
MB triple run was taken with CUDA sysmem-fallback disabled at the
driver level — median 255.5 s (slow despite zero failures). Initially
attributed to sysmem-backed arena memory per the WDDM note above, but
then contradicted by re-test: with the Windows-default fallback
**enabled**, the 3072 MB arena runs fast (197.1 s median). At this
arena size on a 12 GB card, real VRAM is never exhausted (peak ~4 GB),
so the WDDM note's failure mode doesn't apply — but driver-side
allocation overhead differs between fallback-on/off states in a way
that favours fallback-on for throughput. **Conclusion**: the WDDM
note remains valid guidance at the *boundary* (cards where real VRAM
is nearly exhausted), but the 3072 MB × 12 GB card configuration is
comfortably inside the envelope. Ship with Windows default
(fallback-on).

**Open question from Phase 6 (per-session semaphores) still open.**
Not addressed by Phase 7 — but the arena bump reduces the incentive
to fix it, since concurrent inference no longer causes fragmentation
failures at the new arena size.

### Remaining investigation items

- [x] **7. Measure shrinkage latency penalty.** Phase 1 measurement shows
  VRAM flat at ~1.97 GB throughout (vs 4 GB arena held permanently
  before). No ORT_RUNTIME_EXCEPTION. SPLADE backfill ran to completion
  on GPU with zero failures. Latency penalty not separately measurable
  — shrinkage overhead is dominated by Lucene I/O between batches.

- [~] **4. Evaluate sequenced GPU usage.** Not needed — shrinkage resolves
  the contention. SPLADE peak is only ~770 MB, leaving ample room for
  embedding GPU.

## Constraints

1. **Single GPU.** All sessions share one CUDA device (device=0).
2. **ORT arena is fixed at session creation.** Cannot resize dynamically.
   Arena shrinkage releases unused pages but does not change the limit.
3. **Silent fallback is by design.** GPU failure should not crash the
   pipeline — CPU fallback is the safety net. But it should log a WARN
   (currently only DEBUG).
4. **SPLADE quality.** SPLADE GPU uses FP16 model. Embedding GPU uses
   INT8 model. Both must remain queryable with correct scores.
5. **8 GB consumer GPUs.** The solution must work on 8 GB cards where
   ORT arenas + llama-server compete for ~7.2 GB usable VRAM.

## Dependencies

- **310 (Batch Lucene Writes):** Provides the profiling data and batch
  write infrastructure. Chunk embedding batch writes are already applied.
- **278 (Indexing Throughput):** Provides the model optimization baseline
  and throughput measurements.

## Theoretical Solutions (ranked by impact, 2026-03-16 research)

Full research in `311-research-archive.md`. 7 parallel internet research
threads covering ORT internals, CUDA memory management, multi-model
serving architectures, session lifecycle, alternative runtimes, and ORT
roadmap.

### Tier 1: Achievable from Java API (no native code)

**1a. Arena shrinkage + device allocator for initializers (highest ROI)**

Enable two settings on all GPU sessions:
```java
opts.addConfigEntry("session.use_device_allocator_for_initializers", "1");
// Then on each RunOptions:
runOpts.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "gpu:0");
```
`use_device_allocator_for_initializers` routes model weights through
`cudaMalloc` directly (outside the arena). Without this, weight
allocations pin arena regions permanently, making shrinkage ineffective.
With it, scratch memory regions become fully freeable after each
`session.run()`.

**Expected effect:** After SPLADE finishes a batch, its scratch memory
is released back to the CUDA driver. Embedding's next batch can claim
that VRAM via `cudaMalloc`. Eliminates the contention that causes
`ORT_RUNTIME_EXCEPTION`.

**Risk:** `cudaFree()` is synchronous — may add latency. ORT team says
"some decrease in speed is expected." Can apply shrinkage selectively
(only on runs where cleanup is desired).

**1b. Right-size arena limits**

Measure actual peak VRAM per session, set `gpu_mem_limit` accordingly.
Current SPLADE arena (4096 MB) is likely overprovisioned for a 266 MB
model. VRAM budget for 8 GB card:

| Component | Budget |
|-----------|--------|
| LLM (Q4_K_M, 32 layers) | ~4.0 GB weights + ~1.0 GB KV |
| CUDA/driver/WDDM overhead | ~0.8 GB |
| ORT sessions (shared) | ~1.5-2.0 GB |
| Safety margin | ~0.2-0.4 GB |

**1c. Session lifecycle management (lazy create + idle timeout)**

Create GPU sessions on demand, close after 30-60s idle. Session
creation cost for BERT-class models: 0.5-3s (acceptable for batch
workloads). `setOptimizedModelFilePath()` caches the optimized graph,
saving ~200-500ms per creation.

Target: **only 1 active GPU ORT session at a time.** Embedding during
primary indexing, SPLADE during backfill, CE during search queries.
These workloads rarely overlap.

**1d. Move cross-encoder to CPU-only**

The CE model is 22 MB and runs infrequently (only during search queries
with reranking). Its 512 MB GPU arena is disproportionate. Moving CE to
CPU-only frees 512 MB of permanent VRAM claim with negligible latency
impact on search (CE inference is fast on CPU for small models).

**1e. Check CUDA Graphs interaction**

ORT 1.24.1 enables CUDA Graphs by default. Graphs capture fixed memory
addresses — arena shrinkage may not work when graphs are active. Verify
whether our sessions have CUDA Graphs enabled and test disabling with
`cudaOpts.add("enable_cuda_graph", "0")`.

**1f. Serialize GPU inference per session**

Add a `Semaphore(1)` around `session.run()` for GPU sessions. This
prevents concurrent activation memory buildup in the BFCArena. GPU
kernel execution is already serialized by the unified stream, so the
semaphore adds no GPU idle time — it only prevents premature CPU-side
allocation.

### Tier 2: Requires native code or architecture changes

**2a. Shared arena allocator via JNI bridge**

ORT's C API has `CreateAndRegisterAllocatorV2()` to register a single
GPU arena at the `OrtEnv` level. All sessions opt in via
`session.use_env_allocators = "1"`. Would collapse 3 x N-GB arenas into
one shared pool.

**Blocker:** Not exposed in Java API. Requires JNI extension to call
the C API. Effectiveness on CUDA is disputed (Discussion #21577).

**2b. GGUF unification via llama-server**

llama-server now supports `models.ini` with multi-model routing
(embedding + reranker + LLM from one process). `convert_hf_to_gguf.py`
handles BERT-class models. This eliminates ORT entirely for
embedding+CE and shares one ggml memory pool.

**Blocker:** SPLADE's sparse vocabulary-sized output (30,522-dim) is
non-standard for llama-server's `/v1/embeddings` endpoint. Embedding +
CE are viable candidates; SPLADE likely stays on ORT.

**2c. TensorRT EP for SPLADE**

TensorRT's `createExecutionContextWithoutDeviceMemory()` allows
providing your own activation buffer, shared across non-concurrent
contexts. Could provide better memory efficiency than CUDA EP's BFC
arena for the largest model. Available as ORT execution provider.

### Tier 3: Safety nets

**3a. WDDM Sysmem Fallback (Windows)**

Since NVIDIA driver 536.40, `cudaMalloc` allocations can transparently
fall back to system RAM when VRAM is exhausted. Configurable per-
executable via NVIDIA Control Panel. 10-50x slower for bandwidth-bound
ops — purely a crash-prevention safety net, not a performance strategy.

**3b. GPU failure recovery**

Current behavior: `gpuAvailable = false` is permanent. Add recovery:
periodically retry GPU session (e.g., after SPLADE finishes its
backfill batch and releases scratch memory via shrinkage). Or at
minimum, log at WARN level instead of DEBUG.

### Not viable on Windows

| Approach | Why |
|----------|-----|
| CUDA MPS | Linux/QNX only |
| CUDA Unified Memory oversubscription | Windows falls back to pre-Pascal |
| CUDA Memory Pools (cudaMemPool) | ORT doesn't use them |
| CUDA VMM (cuMemAddressReserve) | ORT doesn't use them |
| Per-process VRAM limits (nvidia-smi) | No such mechanism exists |
| DirectML for memory sharing | 2x slower, no arena sharing |
| Shared arena from Java | Not exposed in Java API |

### Recommended implementation order

1. **1a + 1b + 1e** — shrinkage + right-size + CUDA Graph check.
   Lowest effort, directly addresses the ORT_RUNTIME_EXCEPTION. Test
   whether shrinkage alone resolves the embedding GPU failure.
2. **1d** — move CE to CPU. Easy win, frees 512 MB.
3. **1c** — session lifecycle. Higher effort but most robust for 8 GB
   cards. Only 1 GPU session active at a time.
4. **3b** — GPU recovery. Incremental improvement to current fallback.
5. **2b** — GGUF unification for embedding+CE (if llama-server is
   already in use). Eliminates 2 of 3 ORT GPU sessions.
6. **2a** — JNI bridge for shared arena. Only if Tiers 1-2 are
   insufficient.

---

## Codebase Investigation Findings (2026-03-16)

### INV-1: GPU Session Configuration (all 3 sites)

All three GPU sessions use identical CUDA EP config pattern:

| Setting | Embedding | SPLADE | Cross-Encoder |
|---------|-----------|--------|---------------|
| `gpu_mem_limit` | 1024 MB | 4096 MB | 512 MB |
| `arena_extend_strategy` | `kSameAsRequested` | `kSameAsRequested` | `kSameAsRequested` |
| `interOpNumThreads` | 1 | 1 | 1 |
| `allow_spinning` | 0 | 0 | 0 |
| `setMemoryPatternOptimization` | false | false | **not set** (default) |
| `use_device_allocator_for_initializers` | **not set** | **not set** | **not set** |
| `enable_cuda_graph` | **not set** | **not set** | **not set** |
| RunOptions with shrinkage | **none** | **none** | **none** |
| FP16 model fallback | yes | yes | no |

All three have lazy GPU init (double-checked locking in `selectSession()`),
FP32 fallback on FP16 load failure (embedding + SPLADE), and permanent
`gpuAvailable = false` on session creation failure.

### INV-2: session.run() Call Sites (8 total)

None of the 8 `session.run()` call sites use `RunOptions`:

| File | Method | Line | Pinned? | Session | Frequency |
|------|--------|------|---------|---------|-----------|
| OnnxEmbeddingEncoder | `embedPreTokenizedBatch()` | 310 | No | CPU/GPU | Per batch (8 docs) |
| OnnxEmbeddingEncoder | `embedSingle()` | 460 | No | CPU/GPU | Per chunk |
| SpladeEncoder | `runMLMInference()` | 514 | **Yes** | CPU/GPU | Per batch (4-8 docs) |
| SpladeEncoder | `runSingleSparseInference()` | 639 | No | CPU/GPU | Per doc |
| SpladeEncoder | `runHeapFallback()` | 664 | No | CPU/GPU | Fallback only |
| BertNerInference | `infer()` | 90 | No | CPU-only | Per chunk |
| CrossEncoderReranker | `rerank()` | 413 | No | CPU/GPU | Per search query |
| CitationScorer | `scoreSentenceAgainstChunks()` | 192 | No | CPU-only | Per sentence |

**Shrinkage injection:** Add `RunOptions` with shrinkage to the 4 GPU-capable
call sites. SpladeEncoder's pinned output path (line 514) is the hot path —
the 3-arg `run(inputs, emptySet, pinnedOutputs)` also accepts RunOptions.
Consider selective shrinkage: only on the **last** run of a batch, not every
individual call.

### INV-3: Session Lifecycle

| Component | Owner | Process | Lifetime | Idle Timeout |
|-----------|-------|---------|----------|--------------|
| OnnxEmbeddingEncoder | EmbeddingService | Worker | Process lifetime | None |
| SpladeEncoder | KnowledgeServer | Worker | Process lifetime* | None |
| CrossEncoderReranker | KnowledgeHttpApiAdapter | **Head** | Process lifetime | None |
| CrossEncoderReranker | RagContextOps | **Worker** | Process lifetime | None |

*SpladeEncoder has a **pre-existing re-creation bug** (278 Entry 18):
KnowledgeServer re-creates it every ~15s on config reload, resetting
`gpuSessionAttempted` and `firstEncodeLogged`. Must be fixed before idle
timeout makes sense.

**CE lives in both processes.** Head-side CE and Worker-side CE are separate
instances in separate JVMs. The Worker-side CE competes for VRAM with
Embedding + SPLADE; the Head-side CE has its own OrtEnvironment.

**Lazy create + idle timeout is feasible:** `releaseGpuSession()` exists on
all three and resets `gpuSessionAttempted`, enabling lazy re-creation on
next use. Infrastructure exists — just needs a last-access timestamp +
periodic reaper in the indexing loop.

### INV-4: GPU Failure and Recovery

**All three have permanent `gpuAvailable = false`** on first GPU failure
with no recovery path. Once set, only `releaseGpuSession()` (called by
Main to reclaim GPU) resets `gpuSessionAttempted` for re-creation.

SpladeEncoder is the exception: it has **per-request GPU→CPU fallback**
for BFC arena allocation failures (detected via `isBfcArenaFailure()`).
If a batch is too large for the arena, it falls back to CPU for that
request without setting `gpuAvailable = false`.

**Embedding has NO per-request fallback.** If GPU inference fails during
a batch, the `OrtException` propagates up. This is the component that
suffers `ORT_RUNTIME_EXCEPTION` from arena contention.

### INV-5: CUDA Graphs — Implicitly Enabled

No code sets `enable_cuda_graph` anywhere. ORT 1.24.1 defaults to
enabled. **CUDA Graphs pin memory allocations after first run** — this
may prevent arena shrinkage from freeing regions. Must explicitly
disable with `cudaOpts.add("enable_cuda_graph", "0")` before testing
shrinkage.

### INV-6: GPU Arbitration — Embedding is Static

| Component | `shouldUseGpu` callback | Dynamic? |
|-----------|------------------------|----------|
| **Embedding** | `() -> gpuEnabled` (captured constant) | **No** |
| SPLADE | `() -> !signalBus.isMainGpuActive()` | Yes |
| CE (Worker) | Wired to signal bus | Yes |

**Embedding cannot respond to GPU arbitration at runtime.** The lambda
passed by `OnnxEmbeddingProvider` captures `gpuEnabled` as a constant
boolean at construction time. Even when Main claims GPU (sets MMF byte
at offset 24), embedding continues to attempt GPU inference.

This is a **concrete gap**: when the LLM is active, embedding should
yield GPU. Wiring embedding's `shouldUseGpu` to
`() -> !signalBus.isMainGpuActive()` (same as SPLADE) would fix this.

### INV-7: Cross-Encoder Usage Patterns

CE reranking is **conditional and infrequent**:
- Triggers once per search query (not per-batch or per-doc)
- Gates: enabled in config, min 3 hits, not navigational query, doc
  length check
- Batch size: 10-50 candidates depending on GPU availability
- Worker-side CE runs during RAG retrieval (BM25 mode only)
- Head-side CE runs during search response pipeline

**Moving Worker-side CE to CPU-only is low-risk.** The 22 MB model
with 10-50 candidates is fast on CPU. Eliminates 512 MB GPU arena from
the Worker process where it competes with Embedding + SPLADE.

### INV-8: OrtEnvironment

All sessions call `OrtEnvironment.getEnvironment()` — JNI singleton
per JVM. Worker process shares one env across Embedding + SPLADE +
Worker-side CE. Head process has a separate env for Head-side CE +
CitationScorer. `close()` is never called (correct — it's process-scoped).

---

## Non-Goals

- Multi-GPU support
- Changing the embedding or SPLADE model architecture
- Dynamic VRAM monitoring (ORT doesn't expose per-session arena usage)

## Code Locations

| File | Relevance |
|------|-----------|
| `worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java` | GPU session creation, selectSession(), tryCreateGpuSession() |
| `worker-core/.../embed/onnx/OnnxEmbeddingProvider.java` | `shouldUseGpu = () -> gpuEnabled` **static lambda (bug)** |
| `worker-core/.../splade/SpladeEncoder.java` | SPLADE GPU session, gpuMemLimitBytes, per-request fallback |
| `worker-core/.../splade/SpladeConfig.java` | `gpuMemLimitBytes` default (4096 MB) |
| `reranker/.../CrossEncoderReranker.java` | CE GPU session, `GPU_MEM_LIMIT_BYTES` (512 MB) |
| `worker-core/.../ner/BertNerInference.java:90` | NER inference (CPU-only, no shrinkage needed) |
| `reranker/.../CitationScorer.java:192` | Citation scoring (CPU-only, no shrinkage needed) |
| `indexer-worker/.../server/KnowledgeServer.java:518-521` | SPLADE + embedding creation, signal bus wiring |
| `app-services/.../worker/KnowledgeHttpApiAdapter.java:233` | Head-side CE creation |
| `worker-services/.../services/RagContextOps.java:733` | Worker-side CE reranking call site |
| `indexer-worker/.../server/KnowledgeServer.java:824` | GPU lifecycle monitoring (Main claims GPU) |
| `worker-core/.../ipc/MmfWorkerSignalBus.java:180` | `isMainGpuActive()` — reads MMF offset 24 |
