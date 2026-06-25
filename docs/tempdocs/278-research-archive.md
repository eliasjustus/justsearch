---
title: "278: Research Archive"
type: tempdoc-archive
parent: 278-indexing-throughput.md
created: 2026-03-12
archived: 2026-03-13
---

# 278: Indexing Throughput — Research Archive

Archived research, tradeoff analysis, gap analysis, investigation plans, and
experiment designs. These informed the implementation but are no longer
actionable. Kept for historical reference.

---

## Improvement Areas

> **Authoritative work items:** See [Revised Work Items (Post-Gap-Analysis)](#revised-work-items-post-gap-analysis--cross-tempdoc-review)
> below. This section provides detailed rationale and tradeoff analysis;
> the revised work items are the implementation plan.

### Area 1: Reduce Per-Document Overhead (no concurrency changes)

These improvements make each document faster without changing the
single-threaded architecture.

#### 1a. Batch SQLite operations

**Current:** `pollPending(1)` + `markDone()` per doc = 2 SQLite round-trips
per document, each acquiring the ReentrantLock.

**Proposed:** Increase `POLL_BATCH_SIZE` to 10-50. Claim a batch of jobs,
process them, `markDone` in batch. SQLite `executeBatch()` already exists
on the enqueue side.

**Risk:** Low. If the loop crashes mid-batch, uncompleted jobs stay
`IN_PROGRESS` and will be reclaimed after timeout. Existing retry logic
handles this.

**Expected gain:** Eliminates ~80-95% of SQLite overhead per document.
SQLite overhead is small vs embedding time, so this is a minor gain in
absolute terms but removes a serialization point.

#### 1b. Reuse embedding sessions across documents

**Current:** `EmbeddingService.embedDocument()` creates a new
`backend.createSession()` per call and closes it after.

**Proposed:** Keep a single session alive across the batch. Close only on
GPU state transitions or loop shutdown.

**Risk:** Medium. Session lifecycle may have implicit state (ONNX RT
sessions are thread-safe and stateless per `run()`, so this should be
safe for the ONNX backend).

**Expected gain:** Eliminates per-document session creation overhead.
May be significant for ONNX Runtime if session creation involves graph
optimization or memory allocation.

#### 1c. Batch embedding inference across documents

**Current:** One `embedDocument()` call per document. Even the ONNX
backend processes one document's chunks at a time.

**Proposed:** Collect text from N documents (e.g., 8-16), tokenize and
embed in a single `OrtSession.run()` call with a batched input tensor.
ONNX Runtime is optimized for batched inference.

**Risk:** Medium. Requires changes to `EmbeddingService` API to accept
multiple documents. Must handle variable-length inputs (padding/truncation).
`OnnxEmbeddingEncoder` already supports this conceptually but needs a
multi-document batch API.

**Expected gain:** 2-4x embedding throughput on CPU, 5-10x on GPU
(tempdoc 268 projections). This is the **single biggest lever** for
documents that require embedding.

#### 1d. Batch SPLADE inference across documents

**Current:** `SpladeEncoder.encode()` called per document. `encodeBatch()`
exists but is unused in the indexing path.

**Proposed:** Use `encodeBatch()` for inline SPLADE during indexing. For
backfill, `SpladeBackfillOps` already processes batches of 20 but encodes
one-at-a-time within the batch.

**Risk:** Low. `encodeBatch()` already exists and is tested.

**Expected gain:** GPU utilization improvement. Less impactful than
embedding batching because SPLADE encoding is faster per-document.

#### 1e. Reduce idle sleep when work is available

**Current:** `IDLE_SLEEP_MS = 1000ms` when queue is empty. If the file
watcher is slightly behind, the loop idles for a full second.

**Proposed:** Adaptive sleep: 100ms when recently active (processed docs
in last 5s), 1000ms when truly idle (no docs for >5s).

**Risk:** Negligible. Slightly higher CPU when transitioning from
active to idle.

**Expected gain:** Eliminates up to 1s latency between queue refills.
Marginal for bulk indexing but noticeable for incremental updates.

### Area 2: Pipeline Stage Parallelism (architecture change)

Decouple extraction, embedding, and writing into concurrent stages.

#### 2a. Extract-ahead pipeline

**Current:** Extract → embed → SPLADE → write, all on one thread.

**Proposed:** Two-stage pipeline:
- **Stage 1 (extractor thread):** Tika extraction + text processing.
  Produces extracted text into a bounded queue.
- **Stage 2 (writer thread):** Consumes extracted text, runs embedding +
  SPLADE + Lucene write.

**Benefit:** Tika extraction (I/O-bound, reads files from disk) overlaps
with embedding inference (compute-bound). When one doc is being embedded,
the next doc is already being extracted.

**Risk:** Medium. Requires thread coordination, bounded queue, and error
propagation. Must respect user-active pausing for both threads.

**Expected gain:** Up to 2x for I/O-heavy workloads (large PDFs, Office
docs). Less benefit for small text files where extraction is fast.

#### 2b. Deferred SPLADE (already partially implemented)

**Current:** SPLADE can run inline during indexing OR as backfill.
Backfill runs only when the queue is empty and all embeddings are done.

**Proposed:** Always defer SPLADE to backfill. Remove inline SPLADE from
`IndexingDocumentOps.buildDocument()`. This makes the primary indexing
loop faster (skip SPLADE per-doc) and SPLADE backfill can batch more
efficiently with `encodeBatch()`.

**Risk:** Low. Documents are searchable via BM25 and dense vectors
immediately. SPLADE features appear after backfill completes. The search
pipeline already handles missing SPLADE gracefully.

**Expected gain:** Removes SPLADE latency from the critical indexing
path. Combined with batched backfill (1d), SPLADE throughput improves
without slowing primary indexing.

### Area 3: Progressive Indexing (tempdoc 142 scope)

Two-phase indexing: metadata-first, then content. This is a larger
architectural change tracked in tempdoc 142. Including here for
completeness — it addresses time-to-first-result, not steady-state
throughput.

### Area 4: GPU Acceleration for Embedding

#### 4a. Default to GPU embedding when available

**Current:** `JUSTSEARCH_EMBED_GPU_LAYERS = 0` (CPU-only by default).
GPU requires explicit opt-in.

**Proposed:** Auto-detect GPU availability and default to GPU embedding
when VRAM budget allows. The ONNX backend already has dual CPU+GPU
session support (following `CrossEncoderReranker` pattern). VRAM
arbitration via `signalBus.isMainGpuActive()` prevents conflicts with
chat LLM.

**Risk:** Medium. Must ensure GPU embedding doesn't starve chat LLM
VRAM. The existing `shouldUseGpu` arbitration handles this but needs
validation under concurrent indexing + chat workload.

**Expected gain:** 5-10x embedding throughput per tempdoc 268 projections.
Combined with batching (1c), this is the highest-impact lever.

#### 4b. GPU SPLADE during backfill

**Current:** `SpladeEncoder` supports GPU but SPLADE backfill checks
`isMainGpuActive()` and skips if GPU is busy.

**Proposed:** When GPU is available and chat LLM is not active, use GPU
for SPLADE backfill with batched `encodeBatch()`.

**Risk:** Low. Already implemented, just needs proper VRAM arbitration.

---

## Tradeoff Analysis (2026-03-12)

Detailed tradeoffs for each improvement item, informed by codebase
analysis and external research.

### Item 1a: Batch SQLite Operations (POLL_BATCH_SIZE 1 → 10-50)

**Gains:**
- Eliminates per-document SQLite overhead (lock acquire + SQL round-trip)
- SQLite WAL can handle 72K inserts/sec — not a bottleneck at any scale

**Tradeoffs:**
- **Responsiveness delay.** The main indexing loop has **no user-activity
  check inside its job-processing for-loop** (`IndexingLoop.java:477-481`).
  With `POLL_BATCH_SIZE=1`, user-activity is checked after every document.
  With `POLL_BATCH_SIZE=50`, up to 50 documents process before checking.
  At ~4 docs/sec, that's ~12s of uninterruptible indexing. Fix: add a
  `signalBus.isUserActive()` check inside the for-loop (like
  `SpladeBackfillOps` already does at lines 67-81).
- **Crash recovery granularity.** If the worker crashes mid-batch, all
  `PROCESSING` jobs are recovered on restart via `recoverStuckJobs()`
  (`SqliteJobQueue.java:437-461`). No attempts consumed. Already safe.
- **Memory.** Holding 50 file paths in memory is negligible.

**Verdict:** Do it, but add per-document user-activity check inside the
for-loop first. The gain is small (<5%) but the change is clean.

---

### Item 1b: Session Reuse in EmbeddingService — ALREADY DONE

**Codebase finding:** The `OrtSession` is **already long-lived** in
`OnnxEmbeddingEncoder` (CPU session created eagerly in constructor,
GPU session created lazily, both held for encoder lifetime). The
per-call `AiBackend.Session` created in `EmbeddingService.embedWithChunks()`
(`line 377`) is a **stateless thin wrapper** — `OnnxEmbeddingSession.close()`
is a no-op. No ORT resources are allocated or freed per document.

**This item can be removed from the work plan.** The ONNX migration
(tempdoc 268) already solved it. The old llama.cpp path had real
per-call overhead; the ONNX path does not.

---

### Item 1c: Batch Embedding Inference Across Documents

**Gains (the dominant lever):**
- 10-30x on CPU, 50-100x on GPU (see Finding 1)
- FastEmbed achieves 2,500 emb/sec at batch=32-64 on CPU INT8

**Tradeoffs:**

1. **Padding waste.** Real-world documents have highly variable lengths.
   Naive batching pads all sequences to the longest in the batch.
   Published data: **50-89% of tokens are padding** without mitigation
   (Graphcore PackedBERT analysis). A batch where one doc is 512 tokens
   and the rest are 30 tokens wastes ~94% of compute.

   *Mitigation:* Sort documents by token count before batching. Reduces
   padding waste to ~10-20%. sentence-transformers does this by default.
   More advanced: token-count-based batching (MongoDB reports 8x
   improvement) — batch by total tokens, not document count.

2. **Error propagation.** One bad input fails the **entire batch**.
   ONNX Runtime treats a batch as a single tensor operation — no
   per-document error isolation (`microsoft/onnxruntime#4423`). NaN
   from one document can silently corrupt the entire batch's results.

   *Mitigation:* Pre-validate all documents before batching (truncate
   to max_seq_len, reject malformed tokens). On batch failure, retry
   with bisection (split batch in half, retry each half) to isolate
   the bad document. JustSearch already truncates to 2048 tokens in
   `EmbeddingService`, which helps, but edge cases (empty strings,
   extremely long single tokens) need validation.

3. **Memory scaling.** Peak memory scales as `batch_size * seq_len^2`
   (attention matrix). On GPU with batch=64, seq_len=512: ~6-8 GB
   activation memory on top of model weights. On an 8GB GPU, batch=32
   is the safe ceiling. On CPU (16GB system), batch=256 at seq_len=128
   stays under 4GB.

   *Mitigation:* Cap batch size at 32 for GPU, 64 for CPU. Use
   token-count batching to avoid worst-case memory from one long doc.

4. **GPU JIT/shape recompilation.** ONNX Runtime CUDA EP with CUDA
   graphs, and TensorRT EP, optimize per tensor shape. Changing batch
   size triggers re-optimization (seconds for TensorRT). Varying batch
   sizes continuously causes repeated recompilation.

   *Mitigation:* Use 2-3 fixed batch sizes (e.g., 1, 16, 32) and
   pre-warm at startup. Pad the last partial batch to the nearest
   fixed size. On CPU EP this is not a concern.

5. **Latency granularity.** A batch of 32 documents must all complete
   before any single result is available. If one document in the batch
   is very long (512 tokens, multi-chunk), the entire batch waits.

   *Mitigation:* Sort by length (shortest first) so most documents
   complete quickly. Process long-document batches separately.

6. **Thread contention with Tika extraction.** ORT defaults to
   `intra_op_num_threads = physical_cores`. If extraction threads also
   run concurrently, you get 2x oversubscription on CPU.

   *Mitigation:* Leave `intra_op_num_threads` at 0 (ORT auto-detects
   P-cores on Windows — see Gap 8). Disable ORT spin-waiting
   (`allow_spinning = false`) — Inworld AI measured 47% → 0.5% idle
   CPU from this alone.

**Verdict:** Highest-impact item. Implement with: (a) sort-by-length,
(b) pre-validation, (c) fixed batch sizes, (d) ORT thread tuning.
Expected real-world gain after mitigations: **5-15x on CPU, 20-50x
on GPU** (discounted from theoretical ceiling by padding/overhead).

---

### Item 1d: Batch SPLADE Inference in Backfill

**Gains:**
- 2-3x SPLADE throughput (ELSER v2 quantized: 1.7-2.2x over v1)
- `SpladeEncoder.encodeBatch()` already exists and is tested

**Tradeoffs:**

1. **Mid-batch interrupt granularity.** `SpladeBackfillOps` currently
   checks `isUserActive()` and `isMainGpuActive()` **before each
   document** (lines 67-81). Switching to `encodeBatch()` means the
   entire batch runs as one ORT call — no per-document interrupt point.
   A batch of 20 documents at ~40ms/doc = ~800ms of uninterruptible
   GPU/CPU use.

   *Mitigation:* Use smaller SPLADE batches (4-8) to keep interrupt
   latency under 200ms. Check interrupt conditions between batches.

2. **Same error propagation risk as 1c.** One malformed document
   fails the batch. Pre-validation needed.

3. **SPLADE output is vocab-sized (30,522 dims).** This makes SPLADE
   more memory-intensive per batch element than dense embedding (768
   dims). Practical limit: batch=8-16 on CPU, batch=32-64 on GPU.

**Verdict:** Low-risk, moderate gain. Already has infrastructure.
Use batch=4-8 with inter-batch interrupt checks.

---

### Item 1e: Adaptive Idle Sleep

**Gains:**
- Eliminates up to 1s latency between queue refills
- Noticeable for incremental updates (single file changed)

**Tradeoffs:**
- **Slightly higher idle CPU.** 100ms polling = 10 wakeups/sec vs
  1/sec. Each wakeup is cheap (SQLite `pollPending` returning empty)
  but non-zero.
- **Interaction with breath-holding.** Must not conflict with the
  `BREATH_HOLD_MS = 500ms` pause. If user is active, breath-hold
  takes priority regardless of adaptive sleep.

**Verdict:** Trivial to implement, negligible risk. Worth doing for
UX improvement on incremental updates.

---

### Item 2a: Extract-Ahead Pipeline (Separate Extractor Thread)

**Gains:**
- Up to 2x for I/O-heavy workloads by overlapping Tika (I/O-bound)
  with embedding (compute-bound)
- Lucene's own benchmarks show 265% improvement from concurrent flush

**Tradeoffs:**

1. **Error propagation across threads.** Currently, if Tika throws,
   `processJob()` catches it and calls `markFailed()` on the same
   thread. With a producer-consumer pipeline, the extractor thread
   must communicate failures to the writer thread. Options: poison
   pill in the queue, `CompletableFuture` per document, or a shared
   error flag.

2. **Ordering and deduplication.** If the same file is modified twice
   while extraction is in-flight, two extraction results could be
   queued. The writer thread must handle this (Lucene's
   `DOC_ID = absolutePath` update-or-replace helps, but wastes work).

3. **Memory pressure from extraction queue.** Extracted text sits in
   an in-memory queue. A 10MB PDF might produce 500KB of text. With
   a queue depth of 50, that's up to 25MB of extracted text in memory.
   On a 16GB system, fine. Cap queue depth to bound memory.

4. **Breath-hold coordination.** Both threads must pause when user
   is active. The extractor thread must check `isUserActive()` before
   each extraction, AND the writer thread must check before each
   embed+write. If only one pauses, the queue fills/drains unevenly.

5. **GPU state transitions.** When GPU mode changes (ONLINE →
   INDEXING), `IndexingLoop` currently unloads/reloads embedding
   service (lines 880-963). With two threads, the writer thread
   must coordinate GPU transitions without racing the extractor.

6. **Benefit is workload-dependent.** For small text files (README,
   .java, .md), Tika extraction is <10ms — no overlap opportunity.
   The benefit only materializes for large PDFs, Office docs, and
   scanned images. JustSearch's target corpus (desktop Documents
   folder) likely has a mix.

7. **Virtual threads vs platform threads.** Java virtual threads
   are ideal for I/O-bound Tika extraction but **10-100x slower for
   CPU-bound work** (Java virtual thread benchmarks). The hybrid
   pattern (virtual for extraction, platform for embed+write) is
   correct but adds complexity.

**Verdict:** Moderate gain, moderate complexity. Defer until batch
embedding (1c) is implemented and measured. If embedding throughput
is no longer the bottleneck, extraction overlap becomes the next
lever. If embedding is still dominant, extraction overlap helps less.

---

### Item 2b: Defer SPLADE to Backfill by Default

**Gains:**
- Removes SPLADE latency from the critical indexing path
- SPLADE backfill can batch efficiently with `encodeBatch()`
- Documents immediately searchable via BM25 + dense vectors

**Tradeoffs:**

1. **Temporary quality gap.** Documents without SPLADE features get
   zero contribution from the SPLADE retrieval leg. In HYBRID mode,
   this means newly-indexed documents rank lower until SPLADE backfill
   completes. The search pipeline handles missing SPLADE gracefully
   (returns zero for the sparse leg), but ranking quality is degraded.

2. **Backfill ordering dependency.** Currently, SPLADE backfill waits
   for ALL embedding backfill to complete (`IndexingLoop.java:438-451`).
   If embedding backfill is slow (large corpus, CPU-only), SPLADE
   doesn't start until the entire corpus is embedded. This could mean
   hours before SPLADE features appear.

   *Mitigation:* Decouple SPLADE backfill from embedding backfill.
   Run them in parallel or interleave batches.

3. **Backfill vs inline: different failure modes.** Inline SPLADE
   failure sets `SPLADE_STATUS=FAILED` per-document. Backfill failure
   (if SPLADE encoder crashes) affects an entire batch. The retry
   semantics differ: inline failures are per-document and permanent;
   backfill failures retry the whole batch.

4. **No production precedent.** No major search system (Elasticsearch,
   OpenSearch, Vespa) offers deferred sparse encoding as a built-in
   mode. They all encode inline during ingest. JustSearch would be
   inventing this pattern, which means less battle-tested.

**Verdict:** Good tradeoff. The quality gap is temporary, the search
pipeline already handles it, and the throughput gain on the primary
loop is meaningful. Implement, but decouple from embedding backfill
ordering.

---

### Item 3a: Auto-Detect GPU for Embedding

**Gains:**
- 5-10x embedding throughput over CPU INT8 at batch=32+
- Combined with batching: 20-50x over current single-doc CPU path

**Tradeoffs:**

1. **VRAM contention with chat LLM.** Embedding (~150MB) + SPLADE
   (~110MB) + chat LLM (~4.5GB Q4) = ~4.8GB. On an 8GB GPU, that
   leaves ~3.2GB for KV cache and OS. Tight but feasible if the LLM
   isn't generating.

   JustSearch's existing `shouldUseGpu` arbitration via MMF
   (`isMainGpuActive()`) prevents concurrent GPU use. But the
   transition latency matters: when user starts a chat, GPU must be
   released from embedding → signaled via MMF → embedding falls back
   to CPU → LLM loads onto GPU. This transition takes 2-3 seconds
   (model load time), during which chat feels unresponsive.

   *Mitigation:* Pin embedding + SPLADE in VRAM permanently (~260MB).
   Only the LLM swaps on/off. PCIe 4.0 transfers 5GB in <100ms; the
   JIT/init overhead dominates at 2-3s. Or: use CPU INT8 for embedding
   by default and only use GPU when explicitly in bulk-indexing mode.

2. **INT8 on CUDA EP is slower than FP32.** Published data shows
   CUDA EP INT8 is **4-5x slower** than FP32 due to missing native
   INT8 tensor core paths. Only TensorRT EP benefits from INT8 on
   GPU. **Use FP16 on GPU, INT8 on CPU.**

3. **Windows DirectML is slower than CPU INT8.** For 137M-param
   models, DirectML adds ~2x overhead vs CUDA. May be **slower than
   CPU INT8** (ORT issue #20983). Not a viable fallback for non-NVIDIA
   GPUs at this model size.

4. **First-inference warmup.** TensorRT RTX EP requires JIT
   compilation on first inference per tensor shape. Adds seconds of
   startup. Engine caching mitigates subsequent starts.

5. **ORT CUDA `gpu_mem_limit` is not reliably enforced**
   (triton-inference-server/server#5844). Cannot rely on ORT to stay
   within a VRAM budget — must right-size batch sizes manually.

**Verdict:** High gain but requires careful VRAM management. Start
with CUDA EP + FP16, fixed batch sizes, and existing `shouldUseGpu`
arbitration. TensorRT RTX is a future optimization. Default to CPU
INT8 unless user explicitly enables GPU indexing or system detects
idle GPU with sufficient VRAM.

---

### Item 3b: GPU SPLADE Backfill with Batched Encoding

**Gains:**
- 2-3x SPLADE throughput on GPU vs CPU
- GPU is especially helpful for SPLADE's vocab-sized output (30,522)

**Tradeoffs:**

1. **Same VRAM contention as 3a.** SPLADE on GPU (~256MB arena) +
   embedding on GPU (~150MB) = ~400MB minimum. Must coordinate with
   LLM via `isMainGpuActive()`.

2. **SPLADE GPU backfill is already gated.** `SpladeBackfillOps`
   checks `isMainGpuActive()` at line 39 and skips the entire batch
   if GPU is busy. This is correct but conservative — it means SPLADE
   backfill only runs on GPU when the chat LLM is completely offline.

3. **Batch size limits.** SPLADE output is 30,522 dims per token per
   sequence. At batch=64, seq_len=512, the output tensor is ~4GB FP32.
   Practical GPU batch limit: 8-16.

**Verdict:** Worthwhile but lower priority than embedding batching.
The existing gating is correct. Main improvement: use `encodeBatch()`
with batch=8 instead of per-document `encode()`.

---

### ORT Thread Tuning (New Item from Research)

**Gains:**
- Idle CPU from 47% → 0.5% (Inworld AI case study)
- No throughput regression

**Tradeoffs:**
- **Potential latency increase under load.** Without spin-waiting,
  threads sleep between work items and must be woken by the OS
  scheduler. This adds ~1-10 microseconds of latency per inference
  call. For embedding (5-50ms per call), this is negligible.
- **Must coordinate with extraction threads.** Leave
  `intra_op_num_threads` at 0 — ORT auto-detects P-cores on Windows
  via `HardwareCoreEnumerator` (see Gap 8). Do NOT use
  `availableProcessors() - 2` which returns 22 on a 24-LP hybrid
  system instead of the correct 8 P-cores.

**Verdict:** Zero-risk, high-value. Apply immediately:
```java
// Do NOT set intraOpNumThreads — ORT auto-detects P-cores on Windows
// via HardwareCoreEnumerator (see Gap 8 analysis below)
sessionOptions.setInterOpNumThreads(1);
sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0");
sessionOptions.addConfigEntry("session.inter_op.allow_spinning", "0");
sessionOptions.addConfigEntry("session.force_spinning_stop", "1");
// Keep EXTENDED_OPT — ALL_OPT changes numerical output (see Gap 3)
```


---

## Cross-Agent Notes (from tempdoc 273 SPLADE experiment)

Integrated 2026-03-12 from the SPLADE quality agent's mldr-en GPU
experiment:

1. **ORT native DLL version mismatch is a Phase 3 blocker.**
   `cuda-12.4-pinned/` has ORT 1.19.2 natives; Gradle deps pull
   `onnxruntime_gpu-1.24.3.jar`. Causes `ExceptionInInitializerError`.
   Working set built at `tmp/ort-variant-test/cuda-12.4-v1.24.3/`.
   `DEFAULT_SPLADE_GPU_ENV` in `scripts/search/lib/mixed-corpus-config.mjs`
   still points to stale path. **Must fix before any Phase 3 work.**

2. **SPLADE maxSeqLen 256 → 512 (tempdoc 273).** Longer sequences
   mean slower per-doc inference (roughly 2x due to quadratic
   attention). Strengthens the case for batching (1d) and deferring
   SPLADE to backfill (2a). GPU SPLADE baseline at maxSeqLen=256
   was ~6.7 docs/sec; expect ~3-4 docs/sec at maxSeqLen=512.

3. **`SpladeEncoder.encodeBatch()` is a trivial wiring change.**
   `SpladeBackfillOps` already processes batches of 20 docs but calls
   `encode()` one-at-a-time inside the loop. Item 1d is just swapping
   to `encodeBatch()`.

4. **GPU SPLADE lazy init is verified end-to-end.** `selectSession()`
   lazily creates a CUDA session when `shouldUseGpu` returns true.
   Item 3b is "already implemented, just needs batching."

5. **nomic-embed is INT8-quantized.** Item 3a must specify FP16 or
   FP32 model for GPU. Cannot reuse the CPU INT8 ONNX model on CUDA
   EP — need a separate FP16 model file (~274 MB vs 131 MB INT8).

---

---

## External Research Findings (2026-03-12)

Research across official sources, published benchmarks, and production
system architectures. All findings sourced from 2024-2026 publications.

### Finding 1: Batch Embedding Is the Dominant Lever

**The single biggest throughput gain comes from batching embedding
inference.** JustSearch currently embeds one document at a time; production
systems batch 32-128 documents per `OrtSession.run()` call.

Published throughput numbers for BERT-class encoders (~137M params):

| Configuration | Throughput | Source |
|---------------|-----------|--------|
| PyTorch CPU, batch=1 | ~120 emb/sec | FastEmbed benchmarks |
| ONNX CPU FP32, batch=1 | ~220 emb/sec | sentence-transformers docs |
| ONNX CPU INT8, batch=32-64 | **~2,500 emb/sec** | FastEmbed (Qdrant), 2025 |
| ONNX CUDA EP, batch=64 | **~3,000-8,000 emb/sec** | Microsoft ORT blog, extrapolated |
| ONNX TensorRT RTX, batch=64-128 | **~5,000-15,000 emb/sec** | FastEmbed GPU benchmarks |
| Production RAG (ORT optimized) | 800ms → 60ms per doc (13x) | Microsoft ORT deployment blog |

**Key insight:** JustSearch's current ~0.86 docs/sec (ONNX INT8 CPU,
batch=1) is ~2,900x below the CPU INT8 ceiling at optimal batch size.
The gap is almost entirely due to (a) batch size 1, (b) per-document
session overhead, and (c) sequential pipeline blocking.

Even accounting for real-world overhead (Tika extraction, chunking,
Lucene writes), moving from batch=1 to batch=32 should yield **10-30x
embedding throughput improvement** on CPU alone.

**Sources:**
- [FastEmbed ONNX benchmarks 2025](https://johal.in/fastembed-onnx-lightweight-embedding-inference-2025/)
- [Microsoft ONNX RT transformer optimizations](https://opensource.microsoft.com/blog/2020/01/21/microsoft-onnx-open-source-optimizations-transformer-inference-gpu-cpu)
- [sentence-transformers efficiency docs](https://sbert.net/docs/sentence_transformer/usage/efficiency.html)
- [Nixiesearch: 3x faster with ONNX quantization](https://medium.com/nixiesearch/how-to-compute-llm-embeddings-3x-faster-with-model-quantization-25523d9b4ce5)

### Finding 2: ORT Graph Optimizations Give 2-7x on Top of Batching

ONNX Runtime's transformer-specific graph optimizations fuse multiple
operations into single kernels:

| Fusion | What it does | Impact |
|--------|-------------|--------|
| Multi-Head Attention | Fuses Q/K/V projections + softmax + output | **Biggest single gain** — eliminates dozens of kernel launches |
| EmbedLayerNormalization | Fuses token + position + segment embed + LayerNorm | Significant for first layer |
| SkipLayerNormalization | Fuses residual add + LayerNorm | Per-layer savings |
| Gelu/FastGelu | Fuses GELU approximation pattern | Minor per-layer |

Measured compound speedups:
- **17x CPU latency reduction** (Microsoft BERT optimization blog)
- **>3x GPU latency reduction** at batch=64 vs PyTorch (Microsoft)
- **2-7x overall** depending on hardware and batch size

JustSearch should ensure `OnnxEmbeddingEncoder` uses graph optimizations.
**Caveat:** Stay on `EXTENDED_OPT`, not `ALL_OPT` — see Gap 3 below.
`ALL_OPT` activates `SkipLayerNormFusion` and GELU approximations that
change numerical output (ORT issues #17689, #18959).

**Sources:**
- [ONNX RT Transformers Optimizer docs](https://onnxruntime.ai/docs/performance/transformers-optimization.html)
- [Microsoft: Optimizing BERT for Intel CPU](https://opensource.microsoft.com/blog/2021/03/01/optimizing-bert-model-for-intel-cpu-cores-using-onnx-runtime-default-execution-provider)

### Finding 3: GPU Is 5-10x Over CPU, but Only Above Batch=8-16

The CPU-GPU crossover for small encoder models:

| Batch size | Winner | Why |
|-----------|--------|-----|
| 1-4 | **CPU INT8** | GPU kernel launch overhead dominates |
| 8-16 | Crossover | GPU starts amortizing launch cost |
| 32+ | **GPU CUDA** | GPU consistently 5-10x faster |
| 64-128 | **GPU optimal** | Sweet spot for RTX 4070-class GPUs |
| 256+ | Diminishing returns | Memory bandwidth bottleneck |

**TensorRT RTX EP** (new 2025, RTX 30xx+) adds another 1.5-2x over
CUDA EP. First-run JIT compilation adds seconds, but runtime caching
(`nv_runtime_cache_path`) eliminates this for subsequent runs.

**INT8 on GPU caveat:** INT8 quantization on the CUDA EP is actually
**4-5x slower** than FP32 due to missing native INT8 tensor core paths.
INT8 is only beneficial with TensorRT EP. **FP16 is the safe GPU
choice** for the CUDA EP.

**VRAM budget for concurrent models on 8-12GB GPU:**

| Model | VRAM (INT8/Q4) |
|-------|---------------|
| Embedding (nomic-embed, 137M) | ~150 MB |
| SPLADE (110M) | ~110 MB |
| Chat LLM 7B (Q4_K_M) | ~4.5 GB |
| **Total** | **~4.8 GB** |

On an 8GB GPU, this is tight but feasible. On a 12GB GPU (RTX 4070),
comfortable. **Strategy: pin embedding + SPLADE in VRAM (~260 MB),
swap LLM on demand.** PCIe 4.0 x16 transfers a 5GB model in <100ms.

**Sources:**
- [ONNX RT TensorRT RTX EP docs](https://onnxruntime.ai/docs/execution-providers/TensorRTRTX-ExecutionProvider.html)
- [NVIDIA: CUDA and TensorRT EPs](https://developer.nvidia.com/blog/end-to-end-ai-for-nvidia-based-pcs-cuda-and-tensorrt-execution-providers-in-onnx-runtime/)
- [HuggingFace: CPU Optimized Embeddings](https://huggingface.co/blog/intel-fast-embedding)
- [Vespa: Embedding Tradeoffs Quantified](https://blog.vespa.ai/embedding-tradeoffs-quantified/)

### Finding 4: ORT Thread Tuning Is Critical for Desktop Apps

**The spinning problem (Inworld AI case study):** With ONNX Runtime's
default spin-waiting enabled, idle inference threads consume **47% CPU**.
Disabling spinning reduced idle CPU to **0.5%** with negligible latency
impact. This is critical for a desktop app.

Recommended ORT session configuration:

| Setting | Value | Rationale |
|---------|-------|-----------|
| `intra_op_num_threads` | 0 (leave default — auto P-core detection on Windows) | ORT auto-detects P-cores via `HardwareCoreEnumerator` (see Gap 8) |
| `inter_op_num_threads` | 1 | Sequential mode; avoids contention |
| Execution mode | `ORT_SEQUENTIAL` | Single model, no parallel graph nodes |
| `allow_spinning` | **false** | Prevents 47% idle CPU drain |

**Sources:**
- [Inworld AI: Reducing CPU with ORT](https://inworld.ai/blog/reducing-cpu-usage-in-machine-learning-model-inference-with-onnx-runtime)
- [ONNX RT Thread Management](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)

### Finding 5: Pipeline Parallelism — Extract-Ahead Is the Right Pattern

How production systems parallelize indexing:

- **Elasticsearch:** Fixed-size write thread pool (= CPU count), bounded
  queue (up to 10K). Bulk API batches 1,000 docs / 5-15 MB per request.
- **Vespa:** Async document processing with `Progress.LATER` — processors
  can yield without blocking threads.
- **LlamaIndex:** `IngestionPipeline` with `num_workers` using
  `multiprocessing.Pool`. Claims 3-15x speedup from parallelization.
- **Lucene IndexWriter:** Supports concurrent `addDocument()` via DWPT
  (DocumentsWriterPerThread). Each thread flushes its own segment.
  Wikipedia 23.2 GB indexed in 5 min 10 sec with concurrent flush
  (265% improvement over single-threaded).

**Optimal pattern for JustSearch:**
```
[Extractor threads]  →  [bounded queue]  →  [Embed+Write thread]
  (virtual threads       (capacity ~50)      (platform thread,
   for I/O-bound                              ONNX batched inference,
   Tika extraction)                           Lucene single writer)
```

Java virtual threads are ideal for the I/O-bound extraction stage
(file reads, Tika parsing) but **10-100x slower than platform threads
for CPU-bound work** (sorting benchmarks). The hybrid pattern — virtual
threads for I/O, bounded platform thread pool for compute — is the
right architecture.

**Sources:**
- [Lucene 265% speedup with concurrent flushing](https://blog.mikemccandless.com/2011/05/265-indexing-speedup-with-lucenes.html)
- [Lucene ImproveIndexingSpeed wiki](https://cwiki.apache.org/confluence/display/lucene/ImproveIndexingSpeed)
- [LlamaIndex parallel ingestion](https://developers.llamaindex.ai/python/examples/ingestion/parallel_execution_ingestion_pipeline/)
- [Java virtual threads benchmarks](https://www.springjavalab.com/2025/12/java-25-virtual-threads-benchmarks-pitfalls.html)

### Finding 6: SQLite Is Not a Bottleneck

SQLite WAL mode handles **72,568 inserts/sec** (tuned) and **15,000
jobs/sec** in dedicated job queue libraries. At JustSearch's current
~4 docs/sec, SQLite is ~18,000x below its ceiling. Even at 1,000
docs/sec (our optimistic target), SQLite remains comfortable.

The `POLL_BATCH_SIZE = 1` overhead is real but marginal — the fix
(batch to 10-50) is worth doing for cleanliness, not for throughput.

**Sources:**
- [SQLite in Production Benchmark](https://shivekkhurana.com/blog/sqlite-in-production/)
- [SQLite WAL performance tuning](https://www.powersync.com/blog/sqlite-optimizations-for-ultra-high-performance)

### Finding 7: SPLADE Encoding Is Slower Than Dense Embedding

SPLADE/ELSER throughput on CPU is significantly lower than dense
embedding due to the vocab-sized output (30,522 dims vs 768 dims):

| Model | Hardware | Throughput | Source |
|-------|----------|-----------|--------|
| ELSER v1 | Xeon CPU, 1 thread | ~14 docs/sec | Elastic blog |
| ELSER v2 (quantized) | Xeon CPU, 1 thread | ~26 docs/sec | Elastic blog |
| OpenSearch v2-mini (67M) | CPU cluster | 4.18x faster than v1 | OpenSearch blog |
| SPLADE query (DistilBERT) | CPU, 1 thread | ~23/sec (43ms) | SIGIR 2022 |

**Deferring SPLADE to backfill is strongly validated by industry
practice.** No production system (Elasticsearch, OpenSearch, Vespa)
offers deferred sparse encoding as a built-in mode, but the pattern
is sound: index with BM25+dense immediately, add sparse features
asynchronously. JustSearch already has this infrastructure via
`SpladeBackfillOps`.

Batching SPLADE inference (batch=4-8 on CPU, 16-64 on GPU) should
yield 2-3x throughput improvement over per-document encoding.

**ONNX backend for SPLADE:** sentence-transformers v5.1 reports
**2-3x speedup** for `SparseEncoder` models with ONNX backend on CPU.

**Sources:**
- [Elastic ELSER v2 performance](https://www.elastic.co/search-labs/blog/articles/introducing-elser-v2-part-1)
- [OpenSearch neural sparse v2](https://opensearch.org/blog/neural-sparse-v2-models/)
- [SPLADE efficiency study (SIGIR 2022)](https://arxiv.org/abs/2207.03834)
- [sentence-transformers v5.1 ONNX sparse](https://sbert.net/docs/sentence_transformer/usage/efficiency.html)

### Finding 8: IO_BINDING Eliminates Memory Copy Overhead

ONNX Runtime's IO_BINDING pre-allocates tensors on the target device,
eliminating CPU↔GPU memory copies per inference call. Benefits:
- Pre-allocate output tensors on GPU, reuse across batches
- Enable CUDA graph capture (record and replay entire computation)
- Fully asynchronous execution via pinned memory

Most impactful for tight inference loops (embedding thousands of docs).
Available in the Java API (`OrtSession.IoBinding`). For CPU-only
inference, IO_BINDING's benefit is smaller since there's no device
transfer — but tensor reuse still helps.

**Sources:**
- [ONNX RT IO_BINDING docs](https://onnxruntime.ai/docs/performance/tune-performance/iobinding.html)


---

## Revised Expected Impact (with external data)

| Change | Estimated gain | Confidence | Key source |
|--------|---------------|------------|------------|
| Batch SQLite ops (1a) | <5% | High | SQLite benchmarks (ceiling ~72K/s) |
| ~~Session reuse (1b)~~ | ~~5-15%~~ ALREADY DONE — 0% | N/A | Sessions already long-lived in ONNX backend |
| **Batch embedding (1c)** | **10-30x CPU; 50-100x GPU** | **High** | FastEmbed 2,500/s vs our 0.86/s |
| Batch SPLADE backfill (1d) | 2-3x for SPLADE phase | Medium | ELSER v2 + ST v5.1 benchmarks |
| Adaptive sleep (1e) | <5% | High | N/A |
| Extract-ahead pipeline (2a) | Up to 2x for I/O-heavy | Medium | Lucene concurrent flush data |
| Defer SPLADE (2b) | 10-30% primary loop speedup | Medium-High | Industry practice |
| **GPU embedding (3a)** | **5-10x over CPU INT8** | **High** | ORT CUDA benchmarks |
| GPU SPLADE backfill (3b) | 2-3x SPLADE throughput | Medium | OpenSearch GPU benchmarks |
| ORT thread tuning (new) | Idle CPU 47% → 0.5% | **High** | Inworld AI case study |
| ORT graph optimizations (new) | 2-7x on top of batching | Medium-High | Microsoft BERT optimization |

**Revised compound estimate:**
- **Phase 1 (batch embedding alone):** 10-30x on CPU → from ~0.86
  docs/sec to ~10-25 docs/sec (embedding-dominated workloads)
- **Phase 1 + Phase 3 (+ GPU):** 50-100x → from ~0.86 to ~50-80
  docs/sec (at optimal batch size on RTX 4070)
- **Full pipeline (all phases):** 10K-file Documents folder from
  ~42 min to **~2-5 min** (CPU) or **~1-2 min** (GPU)

The single most important change is **batching embedding inference
(item 1c)**. Everything else is secondary.

---

---

## Investigation Plan

### Pre-Implementation Investigations

Before writing any code, the following codebase investigations answer
open questions that affect implementation decisions.

#### INV-1: ORT Optimization Level Audit — MOSTLY RESOLVED

**Status:** Gap 3 and Gap 8 research answered the core questions:
- `EXTENDED_OPT` is correct; `ALL_OPT` changes numerical output
- Thread config is defaulting everywhere (no explicit settings)
- ORT auto-detects P-cores on Windows when `intra_op_num_threads = 0`

**Remaining work (~15 min):** Verify GPU session opts in 3 files:
- `OnnxEmbeddingEncoder.java:345-400` (GPU session creation)
- `SpladeEncoder.java:377-410` (GPU session creation)
- `CrossEncoderReranker.java:222-280` (GPU session creation)

Confirm each uses `OnnxSessionCache` or equivalent `EXTENDED_OPT`.

---

#### INV-2: Embedding Batch API Feasibility — ARCHITECTURE RESOLVED

**Status:** Gap 2 research answered the core architecture question:
batch at chunk level, not document level. Token-budget batching, smart
sorting, ORT requires padded rectangular tensors.

**Remaining work (~30 min):**
1. Confirm dynamic batch axes via `session.getInputInfo()` on the
   nomic-embed model
2. Design the `embedBatch(List<String>)` method signature using
   `SpladeEncoder.encodeBatchInternal()` (line 208) as blueprint
3. Verify mean pooling + L2 norm can be vectorized across batch dim

**Files to read:**
- `OnnxEmbeddingEncoder.java:150-172` (embed + chunking logic)
- `OnnxEmbeddingEncoder.java:189-240` (embedSingle)
- `SpladeEncoder.java:208-266` (encodeBatchInternal — blueprint)

---

#### INV-3: IndexingLoop Batch Processing Path

**Question:** What changes are needed to process a batch of documents
through the indexing loop?

**What to check:**
- `IndexingLoop.java:477-481` — the job for-loop. Need to understand
  exactly what state is accumulated between documents (commit counter,
  metrics, etc.) and whether batching breaks any invariants.
- `IndexingDocumentOps.buildDocument()` — currently takes a single
  document's content. For batch embedding, we need to collect text
  from N documents, embed them all at once, then write each to Lucene
  individually. This inverts the current per-doc flow.
- `LoopPacingPolicy.shouldCommit()` — currently called after each
  poll batch. With larger batches, confirm the commit interval (10s /
  1000 docs) still makes sense.
- `OperationalMetrics.documentsIndexed` — incremented per-doc. Confirm
  throughput monitor sampling still works with batched processing.

**Exit criteria:** Identify all code paths that assume per-doc
processing and need modification for batch flow.

**Files to read:**
- `IndexingLoop.java:460-520` (batch processing + commit logic)
- `IndexingLoop.java:567-683` (processJob — per-doc flow)
- `IndexingDocumentOps.java:37-138` (buildDocument)
- `OperationalMetrics.java:359-399` (ThroughputMonitor)

---

#### INV-4: SPLADE Backfill → encodeBatch() Wiring

**Question:** What exactly needs to change in `SpladeBackfillOps` to
use `encodeBatch()` instead of per-doc `encode()`?

**What to check:**
- `SpladeBackfillOps.java:50-120` — current per-doc loop. It reads
  content from Lucene per-doc, encodes, and writes back per-doc.
  For `encodeBatch()`, we need to: (a) collect all content strings,
  (b) encode batch, (c) write results back individually.
- The mid-batch interrupt check (lines 67-81) currently runs before
  each `encode()`. With `encodeBatch()`, the entire batch is one ORT
  call — no interrupt point inside. **Decide**: check before the
  batch call only? Or split into sub-batches (4-8) with checks
  between sub-batches?
- Error handling: if `encodeBatch()` throws for the whole batch,
  what status do the individual docs get? Currently per-doc failures
  set `SPLADE_STATUS=FAILED`. Batch failure would need to fall back
  to per-doc retry to isolate the bad document.
- **(Cross-tempdoc addition):** Count the chunk-to-parent ratio on
  SciFact to calibrate batch sizes. `SpladeBackfillOps.java:44-50`
  queries `SPLADE_STATUS=PENDING` with no `IS_CHUNK` filter — returns
  parents and chunks alike. `SpladeBackfillOps.java:84-88` has
  dual-path content reading (tries `CHUNK_CONTENT` first, falls back
  to parent content). A batch of 20 pending items may be a mix of
  parents and chunks. Inline SPLADE only runs on parents; chunks
  always enter backfill. Actual backfill workload per document:
  1 parent (if deferred) + N chunks.

**Exit criteria:** Write out the modified loop structure. Decide
sub-batch size and interrupt strategy. Measure chunk-to-parent ratio
on SciFact (expected: low, ~0-1 chunks/doc) and estimate ratio for
production workloads (PDFs: ~5-20 chunks/doc).

**Files to read:**
- `SpladeBackfillOps.java` (full file)
- `SpladeEncoder.java:204-266` (encodeBatch + runOnnxInference)
- `ChunkDocumentWriter.java:154` (chunk SPLADE_STATUS=PENDING)

---

#### INV-5: ORT Native DLL Version Status — NON-BLOCKING FOR PHASE 0-1

**Question:** What is the current state of ORT native DLLs, and what
needs to be fixed before GPU work?

**Status:** Still needed for Phase 3 (GPU acceleration). Not blocking
Phase 0 (thread tuning) or Phase 1 (batch embedding on CPU).

**What to check:**
- `gradle/libs.versions.toml` — what version of `onnxruntime_gpu` is
  declared?
- `modules/ui/native-bin/` — what ORT native DLLs exist? What version?
- `tmp/ort-variant-test/cuda-12.4-v1.24.3/` — does the tempdoc 273
  agent's working set exist? What DLLs are there?
- `OrtCudaHelper.java` — how does it resolve native paths? Does it
  handle version mismatches?
- `scripts/search/lib/mixed-corpus-config.mjs` — confirm
  `DEFAULT_SPLADE_GPU_ENV` points to stale path.

**Exit criteria:** Document the version mismatch, the working fix,
and the path to a permanent fix (update pinned natives or change
Gradle deps).

---

### Default Parameter Values

Implementation defaults. Use these unless an experiment justifies a
change. Document any changes in the decision log
(`278-decision-log.md`).

| Parameter | Default | Change condition |
|-----------|---------|-----------------|
| `POLL_BATCH_SIZE` | 16 | Only if EXP-7 shows <2x gain |
| Token budget per mini-batch | 4096 tokens | Adjust if EXP-4 shows memory pressure |
| Smart batching poll timeout | 50ms | Only if incremental latency is noticeable |
| SPLADE sub-batch size | 8 | Increase if chunk-to-parent ratio >10x |
| CPU embedding batch size | 16 | Use EXP-4 winner |
| GPU embedding batch size | 32 | Use EXP-4 winner |
| Commit interval | 10,000ms (existing) | Do not change |
| `IDLE_SLEEP_MS` (recently active) | 100ms | Do not change |
| `IDLE_SLEEP_MS` (truly idle, >5s) | 1000ms (existing) | Do not change |

---

### Experiments

Experiments to validate assumptions and measure baselines before
committing to implementation. Each experiment is designed to be
run independently. **Decision rules for each experiment are in the
decision log (`278-decision-log.md`).**

#### EXP-1: Baseline Throughput Profile (Phase 4a prerequisite)

**Goal:** Establish a reproducible baseline with per-stage timing.

**Method:**
1. Index SciFact corpus (5184 docs) on a clean data directory.
2. Instrument `IndexingLoop.processJob()` with per-stage timing:
   - Tika extraction time
   - Embedding time (including chunking)
   - SPLADE encoding time
   - Lucene write time (index + chunks)
   - SQLite markDone time
   - Total per-doc time
3. Record: total wall time, docs/sec, per-stage breakdown (mean,
   p50, p95, p99), idle time (sleep + breath-hold).
4. Run 3 times, take median.

**Instrumentation approach:** Add `System.nanoTime()` brackets around
each stage in `processJob()`. Write results to a CSV file. Do NOT
commit the instrumentation — use it for measurement only, remove after.

Alternatively: the existing `pipeline.stage_ms` telemetry histogram
(via `Telemetry.histogram`) may already capture per-stage timing.
Check if it does and whether data can be extracted without adding
new instrumentation.

**Exit criteria:** A CSV with per-stage timing for all 5184 docs.
Identify which stage dominates wall-clock time (expected: embedding).

**Decision rule:** If embedding is NOT the dominant stage, document the
actual bottleneck and re-evaluate Phase 1 priority. Proceed anyway —
the batch changes are still architecturally correct.

---

#### EXP-2: ORT Thread Tuning Impact (Phase 0a)

**Goal:** Measure idle CPU before/after thread tuning.

**Method:**
1. Start the backend with default ORT settings. Let it idle (no
   indexing). Measure CPU usage over 60 seconds via
   `powershell Get-Counter '\Process(*java*)\% Processor Time'`.
2. Apply thread tuning to `OnnxEmbeddingEncoder`:
   ```java
   // Leave intraOpNumThreads at 0 (auto P-core detection on Windows)
   sessionOptions.setInterOpNumThreads(1);
   sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0");
   sessionOptions.addConfigEntry("session.inter_op.allow_spinning", "0");
   sessionOptions.addConfigEntry("session.force_spinning_stop", "1");
   ```
   Note: `addConfigEntry` is a direct pass-through to C++ config map.
   All keys from `onnxruntime_session_options_config_keys.h` work.
3. Restart backend. Measure idle CPU again.
4. Run SciFact indexing with both configs. Compare throughput.

**Exit criteria:** Quantify idle CPU reduction. Confirm no throughput
regression. If `allow_spinning` is not available via Java API, document
the gap.

**Decision rule:** Pass if idle CPU drops >20% AND throughput regression
<5%. If throughput regresses >5%, revert `force_spinning_stop` first,
re-measure. If still regressed, revert all thread tuning. Proceed to
Phase 1 regardless.

---

#### EXP-3: Optimization Level Impact (Phase 0a) — DEPRIORITIZED

**Goal:** Measure whether `ALL_OPT` improves throughput over
`EXTENDED_OPT` for the embedding and SPLADE models.

**Status:** Research (Gap 3) found that `ALL_OPT` activates
`SkipLayerNormFusion` and GELU approximations that **change numerical
output** (ORT issues #17689, #18959). For search quality stability,
we should stay on `EXTENDED_OPT`. This experiment is now informational
only — run it to quantify the throughput delta, but do not deploy
`ALL_OPT` unless the gain is >20% AND the cosine similarity check
passes at ≥ 0.99999.

**Method:**
1. Modify `OnnxSessionCache.java` to use `OptLevel.ALL_OPT` instead
   of `EXTENDED_OPT`.
2. Delete the `.optimized` cache files so the models are re-optimized.
3. Index SciFact. Compare docs/sec to EXP-1 baseline.
4. Check for correctness: compare embedding vectors for 20 sample
   documents against baseline (cosine similarity ≥ 0.99999 on CPU).

**Exit criteria:** Throughput delta and correctness check. Decision:
deploy only if gain >20% with cosine ≥ 0.99999.

---

#### EXP-4: Embedding Batch Size Sweep (Phase 1c validation)

**Goal:** Find optimal batch size for `OnnxEmbeddingEncoder` on this
machine (CPU and GPU).

**Method:**
1. Write a standalone Java benchmark (not integrated into the indexing
   loop) that:
   - Loads `OnnxEmbeddingEncoder` once
   - Pre-tokenizes 100 SciFact documents
   - Runs batched inference at batch sizes: 1, 2, 4, 8, 16, 32, 64
   - Measures wall time per batch, computes embeddings/sec
   - Reports peak memory usage (via `Runtime.totalMemory()`)
2. For GPU: repeat with `shouldUseGpu = () -> true`.
3. Sort documents by token count before batching to minimize padding.
   Also measure unsorted for comparison.

**Implementation note:** This requires adding a `embedBatch(List<String>)`
method to `OnnxEmbeddingEncoder`. This is the core of item 1c. The
benchmark is both validation AND the first implementation step.

**Exit criteria:** Throughput curve (emb/sec vs batch size), optimal
batch size for CPU and GPU, padding overhead (sorted vs unsorted).

**Decision rules:**
- **Optimal batch selection:** Pick the batch size where throughput
  plateaus (≤10% gain from doubling). If no clear plateau by 64, use 32.
- **Golden vector check:** Assert cosine ≥ 0.99999 between batch=1 and
  winning batch size for 20 fixed documents. If this fails at ANY batch
  size, that size is rejected. Fall back to next smaller passing size.
- **If no batch gives ≥2x over batch=1:** Investigate padding waste or
  tokenization overhead. Research internet for ORT Java batch embedding
  examples. Proceed with best available batch size.

---

#### EXP-5: SPLADE encodeBatch() Throughput (Phase 1d validation)

**Goal:** Measure `encodeBatch()` throughput vs per-doc `encode()`.

**Method:**
1. Collect 100 SciFact document texts.
2. Encode all 100 via `encode()` one at a time. Measure total time.
3. Encode in batches via `encodeBatch()` at sizes: 4, 8, 16, 20.
   Measure total time.
4. Compare: speedup ratio, peak memory.
5. Test on both CPU and GPU (if GPU available after INV-5 fix).
6. **(Cross-tempdoc addition):** Benchmark on actual parent+chunk
   mix, not parents alone. After indexing SciFact, count total
   SPLADE_STATUS=PENDING items (parents + chunks) to get the real
   backfill workload. Repeat on a longer-document corpus if available
   (see Open Question Q1).

**Exit criteria:** Speedup ratio for each batch size. Identify the
batch size where throughput plateaus. Report chunk-to-parent ratio.

**Decision rule:** Pass if encodeBatch(8) gives ≥1.5x over single
encode(). If it doesn't, keep per-doc encoding and skip item 1d wiring.

---

#### EXP-6: Deferred SPLADE Quality Impact (Phase 2a validation)

**Goal:** Verify that deferring SPLADE to backfill produces identical
search quality to inline SPLADE.

**Method:**
1. Index SciFact with inline SPLADE (current default). Run eval.
   Record hybrid nDCG@10.
2. Index SciFact with SPLADE disabled (`JUSTSEARCH_SPLADE_ENABLED=false`).
   Run eval with hybrid mode — should fall back to BM25+dense only.
   Record hybrid nDCG@10 (this is the "during backfill" quality).
3. Run SPLADE backfill to completion. Re-run eval.
   Record hybrid nDCG@10 (this should match step 1).
4. Compare steps 1 and 3 — must be identical (within HNSW variance).
   The delta between step 1 and step 2 is the temporary quality gap
   during backfill.

**Exit criteria:** Confirm step 1 ≈ step 3. Quantify step 2 gap.

**Decision rule:** Pass if step 1 and step 3 nDCG@10 are within ±0.02.
If they differ more, there's a bug in backfill — investigate before
shipping item 2a.

---

#### EXP-7: Full Pipeline Batch Throughput + Extract-Ahead Analysis (Integration)

**Goal:** Measure end-to-end throughput with batch embedding integrated
into the indexing loop, and quantify extract-ahead overlap opportunity.

**Method:** After implementing items 1a-1d:
1. Index SciFact on a clean data directory.
2. Compare to EXP-1 baseline.
3. Record per-stage breakdown (Tika extraction now the likely
   bottleneck after embedding is batched).
4. **Extract-ahead sub-analysis** (formerly EXP-8): Using per-stage
   data, calculate `overlap_opportunity = min(extract_time, embed_time)`
   per document. Sum across all docs for the theoretical max gain from
   pipelining. Categorize: what % of docs are extraction-bottlenecked
   vs embedding-bottlenecked?

**Exit criteria:** Docs/sec improvement ratio over baseline. Identify
the new bottleneck. If extract-ahead overlap is <10% of wall time,
deprioritize item 2c.

**Decision rules:**
- **≥3x over baseline:** Phase 1 is successful. Proceed to Phase 2.
- **1.5x-3x:** Phase 1 is acceptable. Document what limited the gain.
  Proceed to Phase 2.
- **<1.5x:** Something is wrong. Investigate: is padding waste high?
  Is the batch path actually being used? Research internet for similar
  issues. Document findings before proceeding.

---

### Investigation and Experiment Sequencing

```
INV-1 (ORT audit)  ──→  EXP-2 (thread tuning)  ──→  EXP-3 (opt level)
                                                          │
INV-2 (batch API)  ──→  EXP-4 (batch size sweep)         │
                              │                           │
INV-3 (loop batch) ──────────→│                           │
                              ↓                           │
                    Implement Phase 0 + Phase 1    ←──────┘
                              │
INV-4 (SPLADE)     ──→  EXP-5 (SPLADE batch)     ──→  Phase 1d
                                                          │
INV-5 (ORT DLLs)  ──────────────────────────────→  Phase 3 prereq
                                                          │
EXP-1 (baseline)   ──→  EXP-7 (integration + extract-ahead analysis)
                              │
EXP-6 (deferred quality) ──→ Phase 2a
```

**Critical path:** INV-1 → INV-2 → EXP-4 → Implement 1c → EXP-7.
Everything else can run in parallel.

**Estimated effort:**
- Investigations: ~2 hours (mostly reading code, no changes)
- EXP-1 (baseline): ~1 hour (instrumentation + 3 runs)
- EXP-2 + EXP-3 (ORT tuning): ~1 hour (config changes + measurement)
- EXP-4 (batch sweep): ~2 hours (new batch API + benchmark harness)
- EXP-5 (SPLADE batch): ~30 min (trivial wiring)
- EXP-6 (quality): ~2 hours (3 full eval runs)
- EXP-7 (integration + extract-ahead analysis): ~1 hour (after Phase 1 implemented)


---

## Critical Gap Analysis (2026-03-12)

Pre-implementation review identified 11 gaps in the original plan. Internet
research (7 parallel investigations, ~37 queries across current sources)
validated and refined each one. Findings below.

### Gap 1: `markDone()` Before `commit()` — Data Loss Risk (CRITICAL)

**Problem:** `jobQueue.markDone(filePath)` is called at `IndexingLoop.java:652`
immediately after `indexRuntime.index()`, but Lucene commits happen on a
10-second timer. If the JVM crashes between `markDone()` and the next
`commit()`, documents are **permanently lost** — marked done in SQLite but
never committed to the Lucene index. Flushed-but-uncommitted segments do NOT
survive a crash (confirmed by Mike McCandless, Lucene core committer).

With batch processing (Phase 1), the window widens: 50 documents could be
marked done but uncommitted.

**Research findings:**
- Elasticsearch and Solr both solve this with a WAL above Lucene. JustSearch
  does not need a full WAL — the SQLite queue IS the WAL equivalent.
- **The canonical Lucene pattern is the "commit watermark"**: store the
  highest processed job ID/sequence in `IndexWriter.setLiveCommitData()`.
  On crash recovery, read the watermark from the commit, re-enqueue jobs
  above the watermark. Re-indexing is idempotent (same doc ID overwrites).
- Lucene's `TwoPhaseCommit` API (`prepareCommit()` / `commit()`) exists
  for coordinating with external resources but is overkill here.
- Per-document commit caps at ~1,000/sec on SATA SSD (fsync cost). Batch
  commit every N seconds with watermark is the correct approach.

**Resolution:** Move `markDone()` to **after** `commit()`. Store the
highest committed job sequence in Lucene's `commitUserData`. On startup,
reconcile SQLite queue state against the committed watermark. Add as
**Phase 0 blocker** — implement before increasing batch sizes.

**Sources:**
- [Mike McCandless — Testing Lucene's index durability (2014)](https://blog.mikemccandless.com/2014/04/testing-lucenes-index-durability-after.html)
- [Mike McCandless — Transactional Lucene (2012)](https://blog.mikemccandless.com/2012/03/transactional-lucene.html)
- [Elasticsearch translog settings](https://www.elastic.co/docs/reference/elasticsearch/index-settings/translog)
- [Solr — Commits and Transaction Logs](https://solr.apache.org/guide/solr/latest/configuration-guide/commits-transaction-logs.html)

---

### Gap 2: Chunks ≠ Documents for Batching

**Problem:** The tempdoc treats batch embedding as "collect N documents,
embed in one ORT call." But chunks are the unit of embedding, not documents.
Document A might produce 1 chunk, Document B might produce 5 chunks. The
batch API is more complex than `List<String> → List<float[]>`.

**Research findings:**
- **ORT does NOT support ragged tensors.** All sequences in a batch must be
  padded to the same length. Attention mask zeros out padding positions.
- **sentence-transformers, FastEmbed, and TEI all batch at the chunk level.**
  The universal pattern: flatten all chunks from all documents into one list,
  maintain a `chunkIdx → documentId` mapping, batch-embed chunks, then
  reassemble per-document vectors via mean pooling.
- **Smart batching (sort by token count)** reduces padding waste from
  30-50% to <5%. This is the single highest-leverage optimization.
- **Token-count batching** (cap total tokens per batch, not doc count) is
  the production pattern. MongoDB reported 8x improvement over fixed-count
  batching. Algorithm: accumulate chunks until total tokens would exceed
  budget (e.g., 4096), then flush that mini-batch.
- **PackedBERT / sequence packing** eliminates padding entirely (~2x on top
  of smart batching) but requires custom model support. Not practical for
  standard ONNX exports.

**Resolution:** Revise INV-2 and item 1c to design chunk-level batching:
1. Extract text from N documents, chunk each
2. Flatten all chunks into one list with doc-ID mapping
3. Sort by token count (smart batching)
4. Fill mini-batches by token budget (e.g., 4096 total tokens)
5. Pad each mini-batch to its internal max length
6. Run ORT, collect chunk embeddings
7. Reorder, mean-pool per-document

**Sources:**
- [ORT Java API — OnnxTensor (no ragged support)](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html)
- [Smart Batching Tutorial — Chris McCormick](https://mccormickml.com/2020/07/29/smart-batching-tutorial/)
- [MongoDB — Token-Count-Based Batching (8x improvement)](https://www.mongodb.com/company/blog/engineering/token-count-based-batching-faster-cheaper-embedding-inference-for-queries)
- [sentence-transformers encode() — chunk-level batching](https://sbert.net/docs/package_reference/sentence_transformer/SentenceTransformer.html)

---

### Gap 3: No Regression Testing Strategy

**Problem:** Batch inference with padding can produce different vectors than
single-inference. ORT graph optimizations (`ALL_OPT`) also change output.
No plan for detecting silent quality regression.

**Research findings:**
- **CPU EP: batch=1 vs batch=N produces identical results** within machine
  epsilon for FP32. This is confirmed by ORT issue #4611 and
  sentence-transformers issues #2312, #3197.
- **GPU EP: NOT identical.** Different CUDA kernels dispatched for different
  tensor shapes, causing structured (not random) FP noise.
- **ORT `EXTENDED_OPT` → `ALL_OPT` changes numerical output.** The
  `SkipLayerNormFusion` (enabled at EXTENDED) and additional GELU
  approximations (enabled at ALL) alter results. Issue #17689, #18959.
- **`use_deterministic_compute`** exists in Java API but only affects GPU
  kernel selection. CPU is deterministic by default.
- **Golden-vector testing is production practice.** Shaped.ai, pytest-
  regressions v3.0+, and multiple production teams use capture → compare
  workflows in CI.

**Thresholds:**
| Context | Cosine similarity threshold |
|---------|-----------------------------|
| CPU EP, same ORT version | ≥ 0.99999 |
| GPU EP | ≥ 0.999 |
| Cross-optimization-level | ≥ 0.999 |
| Semantic equivalence (wrong metric for regression) | ≥ 0.99 |

**Resolution:** Add golden-vector test to EXP-4 exit criteria:
1. Capture vectors for 20 fixed documents at batch=1 (current path)
2. Re-embed at batch=16 and batch=32, assert cosine ≥ 0.99999 on CPU
3. Store golden vectors as test fixtures for CI regression detection
4. Do NOT upgrade to `ALL_OPT` — stick with `EXTENDED_OPT`

**Sources:**
- [ORT Issue #4611 — GPU not deterministic, CPU is](https://github.com/microsoft/onnxruntime/issues/4611)
- [ORT Issue #17689 — SkipLayerNormFusion output difference](https://github.com/microsoft/onnxruntime/issues/17689)
- [sentence-transformers #2312 — batch size affects embeddings](https://github.com/UKPLab/sentence-transformers/issues/2312)
- [Shaped.ai — Golden Tests in AI (2024)](https://www.shaped.ai/blog/golden-tests-in-ai)
- [arxiv 2511.00025 — FP noise in batch GPU matmul](https://arxiv.org/abs/2511.00025)

---

### Gap 4: Existing Telemetry Unused

**Problem:** EXP-1 proposes adding new `System.nanoTime()` instrumentation.
The codebase already has per-stage timing via `pipeline.stage_ms` histograms.

**Codebase finding:** `recordStageMs()` at `IndexingLoop.java:213-238`
already instruments stages `extract`, `write`, `post_commit`, and `analyze`
(= embedding). SPLADE timing is the only gap.

**Resolution:** Audit existing telemetry before designing new instrumentation.
EXP-1 likely only needs a SPLADE stage addition, not a from-scratch approach.

---

### Gap 5: No Warmup / Steady-State Distinction

**Problem:** ORT has significant first-inference warmup (memory pool
allocation, CPU cache warming, thread pool init). Averaging across all docs
masks this.

**Resolution:** Amend EXP-1 to report first 50 docs separately from the
remaining ~5000. Report both warmup and steady-state throughput.

---

### Gap 6: Tika Thread Safety for Extract-Ahead

**Problem:** Item 2a proposes running Tika on a separate thread. Is it safe?

**Research findings:**
- **`AutoDetectParser` is thread-safe** on Tika 3.2.0+ — share one instance.
  Confirmed by Tika project lead: "Detect and parse are both thread-safe."
- **`RecursiveParserWrapper` is NOT thread-safe** — must create per-thread.
- **PDFBox is NOT thread-safe at the document level** (PDFBOX-6031, closed
  Won't Fix), but Tika creates a fresh `PDDocument` per `parse()` call, so
  concurrent parse calls on different documents are safe.
- **Residual risk:** PDFBox's shared static font caches (`PDType1Font`)
  have had thread-safety issues (PDFBOX-4219, PDFBOX-3641). Risk is low in
  practice but exists for complex PDFs with embedded fonts.
- **Single-producer extract-ahead is safe.** One virtual thread doing Tika
  extraction concurrent with one platform thread doing embedding operates on
  completely separate data — no shared mutable state.

**Resolution:** Extract-ahead (item 2a) is feasible. Use a single extractor
thread (not multiple concurrent extractors) to avoid the PDFBox font cache
risk. Create `RecursiveParserWrapper` per-call, share `AutoDetectParser`.

**Sources:**
- [TIKA-4393 — Thread-safety fix in Tika 3.2.0](https://issues.apache.org/jira/browse/TIKA-4393)
- [PDFBOX-6031 — Won't Fix: processPage not thread-safe](https://www.mail-archive.com/dev@pdfbox.apache.org/msg93944.html)
- [Tika mailing list — "Detect and parse are both thread-safe"](https://www.mail-archive.com/user@tika.apache.org/msg03473.html)

---

### Gap 7: Windows-Specific ORT Behavior

**Problem:** All cited benchmarks are Linux-based. Windows ORT differs.

**Research findings:**
- **BERT inference is reportedly faster on Windows than Linux** (ORT #4862).
- **Linux ORT holds ~4x more unmanaged memory** vs Windows (ORT #24296).
- **Windows 11 Thread Director** natively understands P-core vs E-core
  priority; Windows 10 hybrid scheduling is degraded.
- **Windows ML (inbox ORT) disables spinning by default** for battery life;
  the standalone Maven artifact does NOT.

**Resolution:** Flag in EXP-2 that results may differ from Linux benchmarks.
Windows-specific behavior is generally favorable (faster inference, better
hybrid scheduling), but idle CPU from spinning is worse (not disabled by
default in the Maven artifact).

**Sources:**
- [ORT Issue #4862 — BERT faster on Windows](https://github.com/microsoft/onnxruntime/issues/4862)
- [Alois Kraus — Hybrid CPU Performance on Windows 10 and 11](https://aloiskraus.wordpress.com/2024/02/08/hybrid-cpu-performance-on-windows-10-and-11/)

---

### Gap 8: Heterogeneous Core Awareness (RESOLVED)

**Problem:** Phase 0a uses `Runtime.getRuntime().availableProcessors() - 2`
which returns 22 on a 24-logical-processor hybrid system — massively wrong.

**Research findings (critical):**
- **ORT already auto-detects P-cores vs E-cores on Windows.** The
  `HardwareCoreEnumerator::DefaultIntraOpNumThreads()` function in
  `onnxruntime/core/platform/windows/hardware_core_enumerator.cc`
  enumerates processor topology, detects Intel hybrid CPUs via CPUID,
  and returns **P-core count only** (e.g., 8 on an 8P+8E system).
- **`intra_op_num_threads = 0` (the ORT default) triggers this auto-
  detection.** Setting it to `availableProcessors() - 2` **overrides**
  the smart default with a wrong value.
- **Java's `availableProcessors()` returns ALL logical processors** (24
  on 8P+8E) — it has no P/E-core awareness.
- **Explicit affinity pinning is available** via
  `addConfigEntry("session.intra_op_thread_affinities", ...)` but Intel
  recommends against it — the Windows 11 Thread Director handles this.
- **ORT maintainer formula when benchmarking is impractical:**
  `min(16, number_of_physical_P_cores)`.

**Resolution:** **Do NOT set `intra_op_num_threads` explicitly.** Leave it
at 0 (default) to let ORT's `HardwareCoreEnumerator` pick the right value.
Only set `inter_op_num_threads = 1` and disable spinning. Remove the
`availableProcessors() - 2` formula from Phase 0a.

**Complete Phase 0a Java config:**
```java
// Do NOT set intraOpNumThreads — ORT auto-detects P-cores on Windows
opts.setInterOpNumThreads(1);
opts.addConfigEntry("session.intra_op.allow_spinning", "0");
opts.addConfigEntry("session.inter_op.allow_spinning", "0");
opts.addConfigEntry("session.force_spinning_stop", "1");
```

**All available threading config keys (from onnxruntime_session_options_config_keys.h):**

| Key | Values | Effect |
|-----|--------|--------|
| `session.intra_op.allow_spinning` | `"0"/"1"` | Worker spin-wait vs OS-block. Default: `"1"`. |
| `session.inter_op.allow_spinning` | `"0"/"1"` | Same for inter-op pool. Default: `"1"`. |
| `session.force_spinning_stop` | `"0"/"1"` | Stop all spinning after last `Run()` returns. |
| `session.intra_op_thread_affinities` | `"id,id;id,id"` | Pin threads to specific logical processors. |
| `session.dynamic_block_base` | positive int | Dynamic block-sizing for thread pool work splitting. |

**Sources:**
- [ORT hardware_core_enumerator.cc (GitHub)](https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/core/platform/windows/hardware_core_enumerator.h)
- [ORT onnxruntime_session_options_config_keys.h](https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h)
- [ORT Thread Management docs](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)
- [Mozilla — Best hardware concurrency for CPU inference](https://blog.mozilla.org/en/firefox/firefox-ai/what-is-the-best-hardware-concurrency-for-running-inference-on-cpu/)

---

### Gap 9: No Rollback Strategy

**Problem:** No plan for what happens if a phase makes things worse.

**Resolution:** Each phase should have:
- **Rollback criteria:** throughput regresses >10%, or embedding cosine
  similarity drops below threshold
- **Mechanism:** All batch sizes and thread config are constants in
  `LoopPacingPolicy.java` — reverting is a single-line change. No feature
  flags needed for Phase 0-1; consider a config flag for Phase 2 (deferred
  SPLADE) since it affects search quality during backfill.

---

### Gap 10: Theoretical Impact Numbers

**Problem:** The "10-30x from batch embedding" claim extrapolates from
FastEmbed (Python, Linux, embedding-only) to JustSearch (Java, Windows,
full pipeline including Tika + Lucene).

**Resolution:** Flag all impact estimates as "theoretical, pending EXP-1
baseline." The 0.86 docs/sec number includes Tika + Lucene, not just
embedding. True embedding-only baseline is unknown until EXP-1 runs.
Do not commit to expected outcomes.

---

### Gap 11: Incremental Indexing UX Degradation

**Problem:** Batch-oriented changes can degrade single-file update latency.
With batch collection, a single file change could wait for the batch to fill.

**Research findings:**
- **Smart batching is the correct pattern**: `BlockingQueue.poll(timeout)`
  + `drainTo(maxBatch - 1)`. Under load, batches fill naturally. Under
  idle (single file changed), the item processes immediately with zero
  added latency.
- **Java implementation:** `Guava Queues.drain()` or manual
  `poll(T, MILLISECONDS)` + `drainTo(N-1)`.
- **Typical values:** N = 16-64 CPU, up to 128 GPU. T = 10-50ms for
  near-real-time, or T = 0 for smart batching (best dual-mode behavior).
- **User-activity pausing is orthogonal** — gate the consumer thread,
  don't reset queue timeouts.

**Resolution:** Use smart batching in Phase 1: the consumer calls
`queue.poll(50, MILLISECONDS)` then `queue.drainTo(batch, maxBatch - 1)`.
Single-file updates see ≤50ms added latency. Bulk ingestion naturally
fills batches for throughput. Document this as a design constraint.

**Sources:**
- [Baeldung — Smart Batching in Java](https://www.baeldung.com/java-smart-batching)
- [NVIDIA Triton — Dynamic Batcher](https://docs.nvidia.com/deeplearning/triton-inference-server/user-guide/docs/user_guide/batcher.html)
- [CocoIndex — Adaptive Batching (5x throughput)](https://cocoindex.io/blogs/batching)
- [Guava Queues.drain() API](https://guava.dev/releases/33.4.7-jre/api/docs/com/google/common/collect/Queues.html)


---

### Experiment Prerequisites (NEW — from cross-tempdoc review)

Before running EXP-1 through EXP-8:

1. **Use a fresh data directory per experiment run.** Set
   `JUSTSEARCH_DATA_DIR` env var. An existing data directory with
   documents but no embedding model fingerprint in commit metadata
   triggers `BLOCKED_LEGACY` in `EmbeddingCompatibilityController`
   (line 100-117), which silently blocks vector/hybrid search.
   Experiments would measure text-only performance without realizing it.

2. **Set `JUSTSEARCH_API_PORT` via env var, not Gradle `-D`.** The
   `build.gradle.kts` injects `-D` flags into the Gradle JVM, not the
   spawned HeadlessApp process.

3. **Benchmark on both SciFact AND a longer-document corpus.** SciFact
   abstracts are short (most produce 0-1 chunks), which makes SPLADE
   chunk volume, extract-ahead overlap, and padding waste invisible.
   A supplementary corpus with PDFs/Office docs (5-20 chunks each) is
   needed to validate items 1c, 1d, 2a, 2c, and EXP-8. **See Open
   Question Q1.**


---

## Cross-Tempdoc Review Notes (2026-03-12)

Review of tempdocs 142, 252, 266, 268, 273 against this plan.

### Verified Non-Issues

- **Embedding tokenizer truncation:** `models/onnx/embedding/tokenizer.json`
  has `"truncation": null`. Not the same bug as 273's SPLADE tokenizer.
  Minor hardening opportunity but not a 278 concern.
- **Tempdoc 142 conflict:** Real overlap in `processJob()` (567-683) and
  the backfill block (406-460), but 142 is not started and has 5 open
  design questions. If tempdoc 142 proceeds, it should design around
  278's batch architecture rather than the current single-doc flow.

### Open Questions (RESOLVED)

**Q1: Is SciFact sufficient as the sole benchmark corpus?**
**Decision: No. Add a mixed-doc corpus.**

SciFact documents are short scientific abstracts. Most produce 0-1 chunks.
SPLADE chunk volume (item 1d note above), extract-ahead overlap (EXP-8),
and batch padding waste (item 1c) won't manifest in SciFact benchmarks.
**Action:** Create a reproducible throughput corpus from an existing
public dataset with longer documents — e.g., a subset of LoCoV1
courtlistener (already referenced in tempdoc 252) or GovInfo PDFs.
Avoid hand-curated folders that can't be reproduced. SciFact remains
the quality baseline; the mixed corpus is for throughput profiling only.

**Q2: GPU session switching cost for item 2b.**
**Decision: Option (b) — co-resident sessions.**

~260MB combined VRAM fits the budget (see VRAM table in Finding 3).
Session creation at 2-3s makes switching (option a) impractical for
interleaved batches. Both embedding and SPLADE sessions stay loaded
simultaneously. Phase 2b item updated accordingly.

**Q3: FP16 ONNX model availability for nomic-embed-text-v1.5.**
**Decision: Defer to Phase 3 execution.**

A 5-minute HuggingFace search during item 3-pre-b will determine
availability. If unavailable, convert via `optimum-cli export onnx`.
This is not blocking for Phase 0-1 work.

