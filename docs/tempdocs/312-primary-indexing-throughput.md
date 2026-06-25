---
title: "312: Primary Indexing Throughput"
type: tempdoc
status: done
created: 2026-03-16
updated: 2026-04-09
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 312: Primary Indexing Throughput

## Purpose

Identify and address bottlenecks in the primary indexing pipeline (ingest
to indexed, before backfill stages). Current rate is ~9 docs/sec for 1K
SciFact (.txt files, ~200 bytes each). The engine-only ceiling is
2,800-3,600 docs/sec (tempdoc 278 Claim A), so the primary pipeline
captures only ~0.3% of Lucene's write capacity.

## Evidence (from tempdoc 310 profiling, 2026-03-16)

1K SciFact ingest on dev hardware (20 logical cores, RTX 4070):

| Metric | Value |
|--------|-------|
| Dataset | 1,000 SciFact .txt files (~200 bytes each) |
| Primary indexing time | ~111s |
| Rate | ~9 docs/sec |
| Engine-only ceiling | 2,800-3,600 docs/sec |
| Pipeline efficiency | ~0.3% of Lucene capacity |

## Pipeline Architecture

The primary indexing pipeline is **entirely single-threaded**. No
parallelism in extraction, embedding, or writing.

```
for each batch of 16 jobs (from SQLite queue):
  Phase 1: Extract all (sequential, per doc)
    - shouldSkip() check
    - isUnmodified() Lucene TermQuery + NRT refresh
    - Tika extraction via single-thread executor + Future.get()
  Phase 2: Batch embed (one call for all 16 docs, if service available)
    - embeddingService.embedDocumentBatch(texts)
    - SPLADE always deferred (PENDING status)
  Phase 3: Write all (sequential, per doc)
    - deriveParentMetadata() — language detect, classify, normalize
    - IndexingDocumentOps.buildDocument() — build field map
    - indexRuntime.index(doc) — Lucene updateDocument + analyzer
    - indexChunks() — chunk creation if content > 4096 chars
    - queueDepth() — SQLite COUNT query (metrics gauge)
  Post-batch:
    - Time/count-based Lucene commit (every 10s or 1000 docs)
    - drainPendingMarkDone() — N individual SQLite UPDATEs
```

## Bottleneck Analysis

### Per-document costs (200-byte .txt file)

| Cost | Location | Est. per doc | Notes |
|------|----------|-------------|-------|
| **Tika extract + thread-hop** | `TimeboxedContentExtractor` | 2-10ms | Single-thread executor, Future.get() per doc |
| **Lucene updateDocument + analysis** | `WritePathOps.indexDocument()` | 5-20ms | Full analyzer chain on content field |
| **`isUnmodified()` Lucene query** | `DocumentQueryOps` | 1-5ms | NRT refresh + TermQuery per doc, even for fresh datasets |
| **`queueDepth()` SQLite COUNT** | `SqliteJobQueue.queueDepth()` | 1-3ms | `SELECT COUNT(*)` with ReentrantLock per written doc |
| **Filesystem stats** | `Files.size()` + `getLastModifiedTime()` | 0.5-2ms | Two Windows NTFS stat calls per doc |
| **Language detection** | `deriveParentMetadata()` | 0.5-1ms | Char-by-char Unicode block scan, up to 4096 chars |

At ~111ms/doc (9 docs/sec), the dominant cost is NOT extraction or Lucene
writes — it is **per-doc inline embedding fallback** inside
`buildDocument()` when batch GPU embedding fails. See profiling below.

### Burst costs at commit boundaries

| Cost | Location | Notes |
|------|----------|-------|
| Lucene commit (fsync) | `indexRuntime.commit()` | 50-500ms per commit, amortized over 1000 docs |
| **`drainPendingMarkDone()`** | `IndexingLoop` | N individual SQLite UPDATEs with lock per row |

`drainPendingMarkDone()` calls `jobQueue.markDone(path)` individually for
each deferred path — up to 1000 sequential single-row SQLite updates at
each commit boundary. No batch UPDATE is used. The `JobQueue` interface
only has `markDone(Path path)` (singular).

## Profiling Results (2026-03-16)

### Phase-level breakdown (63 batches of 16 docs, GPU)

| Phase | Avg/batch | Avg/doc | % of total |
|-------|-----------|---------|------------|
| Phase 1: Extract (isUnmod + Tika) | 27ms | **1.7ms** | **1.6%** |
| Phase 2: Batch embed (ORT) | 726ms | **45.8ms** | **44%** |
| Phase 3: Write (build + Lucene) | 899ms | **56.6ms** | **54%** |
| **Total** | **1,653ms** | **104ms/doc** | **9.6 docs/sec** |

### The real bottleneck: GPU embedding failure → per-doc fallback

Sub-instrumentation of Phase 3 revealed the dominant cost:

| Docs with precomputed embedding (256/1000) | Docs without (744/1000) |
|-------------------------------------------|------------------------|
| `deriveMetadata`: 0.4ms | `deriveMetadata`: 0.7ms |
| **`buildDocument`: 0.0ms** | **`buildDocument`: 86.2ms** |
| `index` (Lucene): 0.5ms | `index` (Lucene): 0.3ms |
| **Total: 1.0ms/doc** | **Total: 87.2ms/doc** |

**74% of docs (744/1000) fell through to per-doc inline embedding**
inside `buildDocument()` because batch GPU embedding failed with
`ORT_RUNTIME_EXCEPTION`. The embedding GPU session (1024MB) fails
intermittently when the SPLADE GPU session (4096MB) is also active —
the same GPU memory contention documented in tempdoc 311.

When batch embedding fails, `processBatch()` catches the exception and
sets `embeddings = null`. All 16 docs in that batch then call
`embeddingService.embedDocument(content)` individually inside
`buildDocument()`, falling back to the CPU session at ~86ms/doc.

### Post-311 measurement (embedding arena 2048MB)

With tempdoc 311 fixes (arena shrinkage, both arenas at 2048MB),
embedding GPU failures dropped from 16 to 2 (initial warmup only).
But primary indexing rate barely changed:

| Metric | Pre-311 | Post-311 |
|--------|---------|----------|
| Embedding GPU failures | 16 | **2** |
| Primary indexing rate | 8.4 docs/sec | **7.9 docs/sec** |

The projected 120 docs/sec was based on a flawed assumption: that GPU
batch embedding would cost ~5.6ms/doc (700ms / 16 docs, amortized).
In reality, GPU embedding with nomic-embed-text-v1.5 at batch=16 costs
~700ms/batch = **44ms/doc** — comparable to CPU. The GPU provides no
significant speedup for this embedding model at this batch size.

### Detailed profiling (post-311, embed arena 2048MB)

Instrumented `processBatch()` to trace `canBatchEmbed` decisions and
batch embedding outcomes (63 batches of 16 docs):

| Metric | Value |
|--------|-------|
| canBatchEmbed=true | 63/63 (100%) |
| Batch embed OK | 61/63 (97%) |
| Batch embed null (BFC failure) | 2/63 (3%) |
| Batch embed time (warm) | avg 1,544ms / 16 docs = **97ms/doc** |
| First batch (cold start) | 11,574ms (GPU session init) |

The 2 failures are `BFCArena::AllocateRawIntern` errors on
`FusedMatMul` and `BiasSoftmax` nodes — transient GPU memory contention
when SPLADE interleave runs a GPU batch immediately before embedding.
Arena shrinkage doesn't free memory instantly; the CUDA driver needs
time to reclaim. These are recovered by the 60s GPU retry (311 Phase 5).

**Embedding at 97ms/doc GPU is the genuine inference cost** for
nomic-embed-text-v1.5 INT8 at batch=16. GPU provides no meaningful
speedup over CPU inline embedding (~86ms/doc). The model is too small
to benefit from GPU parallelism at this batch size.

Actual per-batch cost: extract 27ms + embed 1,544ms + write ~160ms =
1,731ms / 16 docs = **~108ms/doc = ~9.3 docs/sec**. This matches the
measured 7.9/s (slightly lower due to cold start + 2 failed batches +
SPLADE interleave overhead).

**Primary indexing is genuinely bottlenecked by embedding inference
speed** — not GPU contention, not Tika, not Lucene writes, not SQLite.

### Embedding Model Alternatives Research (2026-03-16)

Current model: **nomic-embed-text-v1.5** (137M params, 768 dims, INT8,
131 MB). Measured: 97ms/doc GPU, 86ms/doc CPU. GPU provides no speedup
at batch=16 — model is too small for GPU parallelism at this batch size.

| Model | Params | Dims | Quality (MTEB) | Speed (CPU) | Speed (GPU) | Notes |
|-------|--------|------|----------------|-------------|-------------|-------|
| **all-MiniLM-L6-v2** | 22M | 384 | ~78% top-5 | **15ms/1K tok** | — | 6x faster, 5-8% quality loss |
| **snowflake-arctic-embed-s** | 22M | 384 | ~MiniLM-class | **sub-10ms query** | 100+ docs/s (A10) | MiniLM-based, ONNX INT8 available |
| **E5-small-v2** | 33M | 384 | ~80% | **2.5ms query (INT8)** | — | Fastest with INT8 on CPU |
| **E5-base-v2** | 110M | 768 | 83.5% | 20ms/1K tok | — | Good quality, moderate speed |
| **BGE-base-v1.5** | 110M | 768 | 84.7% | 22ms/1K tok | — | Slightly better quality |
| **nomic-embed-text-v1.5** | 137M | 768 | 86.2% top-5 | **42ms/1K tok** | ~97ms/doc batch=16 | Current model |
| **gte-modernbert-base** | ~150M | 768 | ~68.5% (NanoBEIR) | INT8: 2.7-3.4x faster | FP16: 2x faster | Best quantization robustness |
| **nomic-embed-text-v2 (MoE)** | 475M (305M active) | 256-768 | — | — | — | MoE: only 305M/475M active |
| **EmbeddingGemma-300M** | 300M | 128-768 | — | **<22ms (EdgeTPU)** | — | Edge/mobile focus |

**Key findings:**

1. **5-6x speedup possible with quality tradeoff**: Switching to
   all-MiniLM-L6-v2 or snowflake-arctic-embed-s (22M params) would
   reduce embedding from ~97ms to ~15ms/doc. Quality drops ~5-8%.
   Primary indexing would improve from 8/s to ~40-50/s.

2. **ModernBERT architecture is better for quantization**: GTE-ModernBERT
   retains 98% quality with binary quantization (vs 87-92% for E5/BGE).
   INT8 on CPU runs 2.7-3.4x faster than FP32. If we switch to a
   modernBERT-based model, INT8 quantization would be more effective.

3. **GPU is not the answer for small models**: Models under 150M params
   don't benefit from GPU at small batch sizes. The overhead of
   CUDA memory transfer exceeds the compute savings. CPU with INT8
   quantization is the optimal path for these models.

4. **Matryoshka (dimension reduction) is free speed**: nomic-embed-text-v1.5
   supports 256-dim Matryoshka embeddings (3x smaller) with ~95.8%
   quality retention. This doesn't speed up inference but reduces index
   size and search cost.

5. **Recommended paths**:
   - **Fastest**: all-MiniLM-L6-v2 INT8 (~15ms/doc, ~50 docs/sec primary)
   - **Best quality/speed**: E5-base-v2 INT8 (~20ms/doc, ~35 docs/sec)
   - ~~gte-modernbert-base INT8~~: **blocked** by ORT compatibility (see below)
   - **Current model, smaller dims**: nomic v1.5 at 256-dim (no speed gain, smaller index)

### gte-modernbert-base Test Results (2026-03-16)

**Code changes implemented** (pooling + prefix configurable):
- Added `PoolingStrategy` enum (MEAN_POOL, CLS) with auto-detection from
  `pooling_config.json` in model directory. CLS pooling: `hidden[0]` + L2 norm.
- Made task prefixes configurable via `prefix_config.json` in model directory.
  Empty prefixes for models that don't need them (gte-modernbert).

**Model acquisition**:
- Exported to ONNX via `optimum` (`feature-extraction`, opset 17)
- FP32 raw: 597 MB. O3 fused: 597 MB.
- **INT8 quantization blocked**: `ORT_MODEL_REQUIRES_COMPILATION` on session
  creation. Both O3+INT8 and O3-only models fail. Raw FP32 loads successfully
  but only on one of two parallel initialization attempts (race condition in
  OnnxSessionCache). ModernBERT ONNX export is still immature — the ops graph
  contains nodes that ORT 1.24 can't handle without compilation.

**Performance (raw FP32, no quantization)**:

| Metric | nomic v1.5 INT8 | gte-modernbert FP32 |
|--------|----------------|---------------------|
| Model size | 131 MB | 597 MB |
| Full pipeline | 185s (5.41/s) | **~400s (~2.5/s)** |
| Primary indexing | ~135s (7.9/s) | **~230s (~4.3/s)** |
| Embedding failures | 2 | 0 |

**Verdict**: Without INT8 quantization, gte-modernbert is **2x slower** than
nomic INT8. The model architecture should be faster when quantized (research
shows 2.7-3.4x INT8 speedup), but the ONNX export compatibility blocks this.
Revisit when ORT adds native ModernBERT support or when optimum stabilizes
the export path.

**Alternative**: Try all-MiniLM-L6-v2 INT8 (22M params, well-supported ONNX
export, proven INT8 quantization) for a 5-6x inference speedup with 5-8%
quality tradeoff.

Sources:
- [Embedding tradeoffs quantified (Vespa)](https://blog.vespa.ai/embedding-tradeoffs-quantified/)
- [Best open-source embedding models benchmarked](https://supermemory.ai/blog/best-open-source-embedding-models-benchmarked-and-ranked/)
- [Best open-source embedding models 2026 (BentoML)](https://www.bentoml.com/blog/a-guide-to-open-source-embedding-models)
- [FastEmbed ONNX lightweight inference](https://johal.in/fastembed-onnx-lightweight-embedding-inference-2025/)
- [Snowflake Arctic Embed](https://github.com/Snowflake-Labs/arctic-embed)
- [Nomic modernbert-embed-base](https://huggingface.co/nomic-ai/modernbert-embed-base)
- [Speeding up Sentence Transformers inference](https://sbert.net/docs/sentence_transformer/usage/efficiency.html)

## Key Inefficiencies

### 1. `queueDepth()` SQLite COUNT per document

**Location**: `IndexingLoop.writeExtractedJob()` calls
`metrics.setQueueDepth(jobQueue.queueDepth())` after every document write.

`SqliteJobQueue.queueDepth()` acquires a `ReentrantLock` and runs:
```sql
SELECT COUNT(*) FROM jobs WHERE state IN ('PENDING', 'PROCESSING')
```

For 1,000 SciFact docs, this executes 1,000 times during primary
indexing — purely for a metrics gauge that could be updated less
frequently (e.g., once per batch or once per commit).

**Fix**: Move `queueDepth()` to batch level or commit level instead of
per-doc. Saves ~1-3ms × N docs of serialized SQLite overhead.

### 2. `drainPendingMarkDone()` — N individual SQLite UPDATEs

**Location**: `IndexingLoop.drainPendingMarkDone()` after each Lucene
commit.

Calls `jobQueue.markDone(path)` in a loop for up to 1000 paths. Each
call acquires the SQLite `ReentrantLock` and runs a single-row UPDATE.

**Fix**: Add a batch `markDone(List<Path>)` method to `JobQueue` that
runs a single `UPDATE ... WHERE path IN (?, ?, ...)` statement. Reduces
1000 lock acquisitions + 1000 prepared statement executions to 1.

### 3. `isUnmodified()` per-document Lucene query

**Location**: `DocumentFieldOps.isUnmodified()` called in
`IndexingLoop.extractJob()` for every document.

Runs a TermQuery per doc against the NRT searcher. Does NOT force a
blocking NRT refresh (deliberately removed — relies on CRTRT 500ms
background refresh to bound staleness). On fresh datasets, every doc
returns "not found" but the query still runs.

**Fix**: For fresh/empty indexes, skip the `isUnmodified()` check
entirely (check if index has 0 docs first). For non-empty indexes,
batch the check: query all 16 doc IDs in one pass instead of 16
individual TermQueries.

### 4. Single-threaded Tika extraction

**Location**: `TimeboxedContentExtractor` uses a single-thread
`ExecutorService` ("ContentExtractor-Timebox").

Each doc is submitted to the executor and blocked on via `Future.get()`.
There is never any extraction parallelism. For small .txt files, the
thread-hop overhead (context switch + future resolution) may exceed the
actual parse time.

**Fix**: Use a multi-thread executor (2-4 threads) for extraction
parallelism. The single-thread design was likely for safety (Tika
parsers may not be thread-safe), but `Tika.parseToString()` creates
fresh parser instances per call.

### 5. No pipeline parallelism between phases

Phases 1 (extract), 2 (embed), and 3 (write) run strictly sequentially.
Extraction of batch N+1 cannot overlap with writing of batch N.

**Fix**: Producer-consumer pattern — extract in one thread, write in
another. But adds complexity and may not help much if the per-doc costs
within each phase dominate.

## Throughput Research & Goal Setting

### Comparable systems

| System | Stack | Rate | Notes |
|--------|-------|------|-------|
| **DocFetcher** | Java, Lucene, Tika | ~3.3 files/sec | Desktop search; closest comparable |
| Elasticsearch (single-thread) | Lucene, no extraction | 30K docs/sec | 600-char pre-parsed docs |
| Elasticsearch (Rally, multi-thread) | Lucene, no extraction | 116K docs/sec | Optimal config, 64GB server |
| Elasticsearch (single shard) | Lucene | 22K events/sec | Log-style documents |
| Lucene raw (McCandless) | Lucene only, 6 threads | 95.8 GB/hour | Wikipedia, no extraction |
| **JustSearch (current)** | **Tika + Lucene + embed + SQLite** | **9 docs/sec** | **Single-threaded, 200-byte .txt** |
| JustSearch engine-only | Lucene only | 2,800-3,600 docs/sec | tempdoc 278 Claim A |

Our 9 docs/sec is **2.7x faster than DocFetcher** (the closest comparable
Java/Lucene/Tika desktop search app) and captures **~0.3% of our Lucene
engine-only ceiling** (2,800-3,600 docs/sec).

The gap is expected: we do much more per doc than raw Lucene (Tika
extraction, language detection, embedding, SQLite queue management,
NER/SPLADE status tracking, chunk generation). The single-threaded design
is the primary architectural limiter.

### Realistic targets

| Target | Rate | How | Complexity |
|--------|------|-----|------------|
| Current baseline | 9 docs/sec | — | — |
| **Low-hanging fruit** | **12-15 docs/sec** | Batch SQLite ops, skip isUnmodified on empty | Low |
| **Parallel extraction** | **25-35 docs/sec** | 2-4 Tika threads, batched SQLite | Medium |
| **Full pipeline parallelism** | **40-60 docs/sec** | Extract ‖ embed ‖ write, producer-consumer | High |
| Theoretical ceiling | 2,800+ docs/sec | Lucene write only, no extraction | N/A |

**Recommended goal: 25-35 docs/sec** (3-4x current). This is achievable
with moderate effort (parallel Tika + batch SQLite) and would reduce
mldr-en primary indexing from ~6.2 hours to ~1.6-2.2 hours.

### What this means for large datasets

| Dataset | Docs | At 9/s | At 30/s | At 50/s |
|---------|------|--------|---------|---------|
| SciFact | 5.2K | 9.6 min | 2.9 min | 1.7 min |
| mldr-en | 200K | 6.2 hr | 1.9 hr | 1.1 hr |
| 1M docs | 1M | 30.9 hr | 9.3 hr | 5.6 hr |

Sources:
- [Lucene nightly benchmarks](https://benchmarks.mikemccandless.com/indexing.html)
- [Lucene indexing speed (McCandless)](https://blog.mikemccandless.com/2010/09/lucenes-indexing-is-fast.html)
- [Elasticsearch single-node rates](https://discuss.elastic.co/t/max-indexing-rate-per-single-elasticsearch-server/91340)
- [DocFetcher](https://docfetcher.sourceforge.io/)
- [Elasticsearch tune for indexing speed](https://www.elastic.co/docs/deploy-manage/production-guidance/optimize-performance/indexing-speed)

## Estimated Impact per Fix

| Fix | Est. improvement | Complexity |
|-----|-----------------|------------|
| Batch `markDone()` | ~5-10% (burst at commit) | Low |
| Move `queueDepth()` to batch level | ~3-5% | Low |
| Skip `isUnmodified()` on empty index | ~5-10% (first run only) | Low |
| Parallel Tika extraction (2-4 threads) | ~100-200% | Medium |
| Pipeline parallelism (extract + write) | ~50-100% (on top of above) | High |

With all low-complexity fixes: ~15-25% improvement (~11-12 docs/sec).
With parallel extraction: ~2-3x improvement (~20-30 docs/sec).
With full pipeline parallelism: ~3-5x (~30-50 docs/sec).

Note: these estimates are speculative — proper profiling with
`System.nanoTime()` instrumentation (as done for SPLADE in tempdoc 310)
would identify the actual dominant cost.

## Work Items

### Phase 0: Persistent pipeline profiling via OTel spans

Previous profiling was done with ephemeral instrumentation that was
discarded after the session. This phase adds permanent, switchable
profiling using the OpenTelemetry tracing infrastructure
(`TracingBootstrap`, `NdjsonSpanExporter`, OTLP fan-out to Jaeger).

**Why OTel spans over more histograms:**
- Hierarchical parent-child timing (batch → doc → sub-phase)
- Per-document attributes (path, content length, embedding decision)
- Visual flamechart exploration via Jaeger/Tempo (OTLP already wired)
- Existing `recordStageMs()` histograms are kept for aggregate metrics

**Switch design:**
```
-Djustsearch.index.tracing_level=none      # default: no spans, no TracingBootstrap
-Djustsearch.index.tracing_level=sample    # 1% ratio sampling
-Djustsearch.index.tracing_level=detailed  # 100% — all batches, all docs
```
Config key: `JUSTSEARCH_INDEX_TRACING_LEVEL` / `justsearch.index.tracing_level`.
Read from `EnvRegistry` directly at Worker startup (before ConfigStore init).
NOT runtime-swappable — requires Worker restart (TracingBootstrap is one-time).

**Span tree:**
```
indexing.batch                              [1,653ms]
├── indexing.extract  {doc=report.pdf}      [8ms]
│   ├── indexing.isUnmodified               [2ms]
│   └── indexing.tika                       [5ms]
├── indexing.embed_batch {size=16, gpu=true} [726ms]
├── indexing.write {doc=report.pdf}         [87ms]
│   ├── indexing.deriveMetadata             [0.4ms]
│   ├── indexing.buildDocument              [86ms]
│   └── indexing.luceneIndex                [0.5ms]
├── indexing.commit                         [50ms]
└── indexing.markDone {count=16}            [12ms]
```

#### Investigation results (2026-03-20)

Codebase investigation and OTel SDK research resolved all uncertainties
about this approach. Key findings:

**1. Worker has no TracingBootstrap — requires adding one.**
Neither `IndexerWorker.main()` nor `KnowledgeServer.start()` creates
a `TracingBootstrap`. Only the AI Worker (`AiWorkerServer`) does.
`GlobalOpenTelemetry.set()` is only called inside the `TracingBootstrap`
constructor, so the Worker's `GlobalOpenTelemetry` is the no-op instance.
`SearchOrchestrator`'s `static final Tracer` (line 67) resolves to no-op
today — all its search spans are silently dropped.

*Fix:* Create `TracingBootstrap` in `KnowledgeServer.start()` after
`LocalTelemetry` init (line ~203) and before service construction
(line ~388, `new DefaultWorkerAppServices`). This ensures
`SearchOrchestrator`'s static Tracer resolves to a real tracer when
the class loads at service construction time. Side benefit: search
spans that were silently dropped today will start working.

**2. OTel Sampler is not zero-cost — use application-level gating.**
Per the OTel spec, a `DROP` sampling decision still generates a span
ID and calls `SpanProcessor#onStart` (but NOT `onEnd`, so
`BatchSpanProcessor` never queues the span). This is lightweight
(~nanoseconds) but not literally zero allocation.

*Fix:* Application-level boolean gate (`if (!detailedTracing) return
Span.getInvalid()`) provides true zero-cost when off — `Span.getInvalid()`
is a singleton no-op, no allocation at all. The OTel sampler acts as a
safety net, not the primary gate.

**3. NdjsonSpanExporter has volume limits — `detailed` is for investigation only.**
Default: 10 MB rotation, 7-day retention. `BatchSpanProcessor` defaults:
2048 queue, 512 batch, 5s interval. Queue overflow silently drops spans.
1M docs × 5 spans/doc ≈ 2 GB → 200 file rotations. The exporter always
returns success (no backpressure). Missing health state updates for
rotation and disk pressure (pre-existing gap in span exporter vs metric
exporter).

*Conclusion:* `detailed` mode is for investigation sessions (1K-5K docs).
`sample` mode (1%) is safe for bulk indexing. Document this clearly.

**4. `GlobalOpenTelemetry.set()` is a process-global singleton.**
Throws `IllegalStateException` if called twice. In normal operation,
`KnowledgeServer.start()` runs once per process lifetime — safe.
Hot-reload (`DevReloadManager`) reconstructs services but preserves
telemetry infrastructure — safe. Guard with try-catch for defensive safety.

**5. TracingBootstrap constructor hardcodes `Sampler.alwaysOn()`.**
Need to add a constructor overload or modify existing to accept a
`Sampler` parameter. "sample" → `Sampler.traceIdRatioBased(0.01)`,
"detailed" → `Sampler.alwaysOn()`.

**6. Config propagation is automatic.**
Any key in `EnvRegistry` is automatically included in
`toWorkerSnapshot()`. However, TracingBootstrap init happens before
ConfigStore is ready (ConfigStore accessed at line ~248, after proposed
init location at line ~203). Read config directly from
`EnvRegistry.INDEX_TRACING_LEVEL.getString("none")` at startup.

#### Work items

1. [x] **Add indexing tracing config key.** `INDEX_TRACING_LEVEL` in
   `EnvRegistry`, wired into `ResolvedConfig.Index.tracingLevel` and
   `ResolvedConfigBuilder.buildIndex()`. `TracingBootstrap.forIndexing()`
   factory method added accepting tracing level string.

2. [x] **Init TracingBootstrap in Worker.** `KnowledgeServer.start()`
   step 0b after `LocalTelemetry` init: reads level from `EnvRegistry`,
   creates `TracingBootstrap` with appropriate sampler, guarded by
   try-catch for `IllegalStateException`. Cleanup in `close()`.

3. [x] **Instrument `processBatch()`.** Parent span `indexing.batch`
   with `batch.polled` and `batch.extracted` attributes. Child span
   `indexing.embed_batch` with `embed.batch_size`, `embed.gpu`,
   `embed.success`. Application-level gate via `maybeSpan()` returning
   `Span.getInvalid()` when `detailedTracing=false`.

4. [x] **Instrument per-doc extraction.** Child span `indexing.extract`
   per doc with `doc.path` attribute.

5. [x] **Instrument per-doc write.** Child span `indexing.write` per
   doc with `doc.path` and `embedding.source` attributes.

6. [x] **Instrument commit + markDone.** Span `indexing.markDone` with
   `paths.count` attribute.

7. [x] **Validate zero overhead when off.** Added
   `IndexingTracingOverheadTest` in worker-services. Three tests:
   (a) `Span.getInvalid()` is a singleton — no allocation per call.
   (b) All operations on invalid span (setAttribute, makeCurrent, end)
   are no-ops with no exceptions or side effects.
   (c) Timing: per-batch overhead is sub-10µs (100K iterations after
   JIT warmup). At 7ms/batch (deferred embedding) this is <0.15%.
   At 1600ms/batch (inline embedding) this is <0.001%.

### Phase 1: Low-hanging throughput fixes

These fixes address known inefficiencies from the profiling above.
Each should be measured before/after using the Phase 0 instrumentation.

8. [x] **Batch `markDone()`.** Added `markDoneBatch(Collection<Path>)`
   to `JobQueue` interface with default fallback. Implemented in
   `SqliteJobQueue` as a single `UPDATE ... WHERE path IN (?, ...)`
   with chunking at 499 params (SQLite limit). `drainPendingMarkDone()`
   now calls the batch variant with per-path fallback on failure.

9. [x] **Move `queueDepth()` to batch level.** Removed per-doc
   `metrics.setQueueDepth()` from `writeExtractedJob()`. Added once
   after the Phase 3 loop in `processBatchInner()`.

10. [x] **Skip `isUnmodified()` on empty index.** Added
    `indexEmptyForBatch` flag set via `indexCountOps.docCount() == 0`
    once per batch in `processBatchInner()`. When true, skips the
    `isUnmodified()` Lucene query in `extractJob()`. Force-reindex
    paths are still consumed from `forcedPaths` regardless.

### Deferred embedding (312 continuation, 2026-03-20)

19. [x] **Defer embedding to backfill during primary indexing.**
    Skipped Phase 2 (batch embed) in `processBatchInner()` and passed
    `allowEmbeddingWrites=false` to `buildDocument()`. All docs get
    `EMBEDDING_STATUS=PENDING` during primary indexing. Existing
    `EmbeddingBackfillOps` processes them after the job queue drains.

    | Run | Config | Rate | Elapsed |
    |-----|--------|------|---------|
    | Baseline (main) | nomic inline | 5.6 docs/sec | 179.4s |
    | Phase 0+1 | nomic inline | 8.6 docs/sec | 116.0s |
    | **Deferred embedding** | **nomic backfill** | **136.4 docs/sec** | **7.3s** |
    | **Improvement vs baseline** | | **24.4x** | |

    Embedding backfill rate: ~10 docs/sec (300/30s observed), completing
    full coverage ~100s after primary indexing. Users get BM25 search
    immediately; vector search improves progressively.

    **Updated measurement (2026-03-21, 5184 SciFact, clean index):**
    Primary indexing with deferred embedding: 5184 docs in 22s =
    **235 docs/sec**. Higher than previous 136/s due to Phase 0+1
    fixes (batch markDone, batch queueDepth, skip isUnmodified) and
    larger dataset amortizing cold-start overhead.

### Phase 2: Parallel extraction (deferred — negligible impact)

11. [deferred] **Multi-thread Tika extraction.** OTel profiling (item 18)
    showed extract is 2-4% of batch time (1.7ms/doc). With deferred
    embedding, the entire batch takes ~7ms. Parallelizing extraction
    would save ~1-2ms/batch — negligible.

### Phase 3: Pipeline parallelism (deferred — negligible impact)

12. [deferred] **Double-buffered batch processing.** Same reasoning:
    extract+write overlap would save ~2ms on a 3.5ms/doc pipeline.
    Not worth the complexity with deferred embedding active.

### Phase 4: Backfill RMW bottleneck

Embedding backfill runs at ~7-10 docs/sec regardless of model speed
(EmbeddingGemma at 2.9ms/doc still backfills at 7 docs/sec). The
bottleneck is `readModifyWrite()` in `WritePathOps` — re-runs the
full analyzer chain on CONTENT for every document, even when only
the embedding vector changed. See "Backfill bottleneck investigation"
section for detailed analysis.

#### Research findings (2026-03-20)

Two rounds of research: first validating specific Lucene APIs, then
investigating the problem itself across the ecosystem. 30 web searches
across Lucene JIRAs, mailing lists, Elasticsearch, Solr, Vespa, Qdrant,
Weaviate, Milvus, OpenSearch, and production case studies (Notion 10B+
vectors, Flax Redis codec experiment).

**The fundamental constraint.** Lucene segments are immutable. Any
field update = delete + re-add entire document. The Lucene community
tried to solve this at the core level twice (LUCENE-3837 "updateable
fields", LUCENE-4258 "stacked segments") — both abandoned due to
immense complexity. LUCENE-4258 spawned the simpler LUCENE-5189
(DocValues-only updates) as a scoped subset. A Flax experiment with
a Redis-backed codec documented 4 failure modes and was dropped.

**No system built on Lucene has solved this.** Elasticsearch `_update`
always does full Lucene delete+add. Solr "atomic updates" re-analyze
everything (confirmed by Solr ref guide + yonik.com). No in-place
`KnnFloatVectorField` update API exists or has been proposed. HNSW
graphs are immutable per segment by design.

**Systems that solved it separated vector from text storage.** Vespa
(in-memory attribute arrays), Qdrant/Weaviate/Milvus (separate vector
and text engines). This requires fundamental architecture changes
incompatible with Lucene's unified document model.

**What production systems actually do:**

| Approach | Who | Trade-off |
|----------|-----|-----------|
| Accept RMW cost, batch aggressively | Most ES/Lucene users | Simple, slow |
| Blue-green rebuild | Notion, large ES | 2x storage, zero RMW |
| Disable HNSW during bulk, build after | OpenSearch k-NN | Brute-force kNN during window |
| BinaryDocValues for vectors + script | Pre-KnnVectorsFormat OpenSearch | No HNSW, brute-force only |
| Separate vector store + app-join | Some hybrid archs | Query complexity |
| ParallelLeafReader (two synced indexes) | Rare, documented on Lucene wiki | Fragile docID sync |

**Approaches investigated and assessed:**

1. **Pre-tokenized field bypass** (`Field(name, TokenStream, type)` +
   `CachingTokenFilter`). Lucene's canonical pattern (Solr's
   `PreAnalyzedField`). Viable API but **does not help our backfill
   case**: the cached token stream from initial indexing doesn't
   survive JVM restart, so content must be re-analyzed once anyway
   to populate the cache — same cost as today since each doc is
   backfilled only once.

2. **`updateBinaryDocValuesField`** as embedding carrier. Serialize
   `float[]` to `BytesRef`, update without text re-analysis. But
   does NOT update HNSW graph — only brute-force kNN possible.
   Interesting as a two-phase strategy: write embeddings cheaply
   first, then rebuild with HNSW later.

3. **Block-join (parent text / child vector)**. `DiversifyingChildren-
   FloatKnnVectorQuery` in Lucene 10.3+ supports this. But updating
   a child still requires rewriting the entire block (parent +
   children). No independent child update. Not viable.

4. **`ParallelLeafReader`** (two synchronized indexes). Only Lucene-
   native path to truly independent field-set updates. Requires
   `LogDocMergePolicy` + lockstep flush/merge. Extremely fragile —
   any divergence in segment structure breaks docID alignment
   permanently. Not practical for JustSearch.

5. **StoredFieldVisitor + ascending doc-ID order**. Selective field
   loading (return `STOP` after CONTENT) + LZ4 block cache locality.
   10-30% gain. Low effort, compatible with existing architecture.

6. **OpenSearch `approximate_threshold=-1` trick**. Disable HNSW
   graph construction during bulk backfill, re-enable and trigger
   single graph build over full dataset. Much more efficient than
   incremental HNSW. Equivalent for us: suppress per-batch NRT
   refresh during backfill, `forceMerge` at end.

7. **Blue-green rebuild**. After all embeddings are computed, rebuild
   entire index from source with vectors included from the start.
   Text analyzed once alongside vectors — no RMW. Requires source
   data (we have it in the job queue / filesystem). This is what
   Notion does at scale.

#### Existing infrastructure (codebase exploration, 2026-03-20)

Explored the codebase to assess what already exists for each
optimization. Key discoveries:

**Blue-green migration system — EXISTS but not ready for embedding
migration.** `IndexGenerationManager` (806 lines) +
`KnowledgeServerMigrationOps` (903 lines) implement generation-based
index rebuild with atomic swap. E2E tested (migration, rollback,
pause/resume — last maintained 2026-03-14). Not dead code — wired at
`KnowledgeServer.start()` behind `blue_green_migrate` config flag
(default is `REBUILD_BACKUP_FIRST`). 6 gRPC RPCs for operator control.

**Critical gaps for embedding model migration:**

1. **Trigger mismatch (small effort).** Currently triggered by Lucene
   field schema mismatch only. Embedding model change doesn't change
   field types, so it would never fire. Need ~50 lines of new trigger
   code in `KnowledgeServer.start()`.

2. **Inline embedding during rebuild (the killer gap, 2-3 days).**
   `canBatchEmbed = false` is hardcoded in `IndexingLoop` (line 736).
   During blue-green rebuild, vectors must be generated inline (not
   deferred to backfill). Without this, the rebuild creates text-only
   index → same RMW backfill bottleneck. Fix requires conditional
   behavior in the indexing loop + GPU scheduling coordination.

3. **`EmbeddingCompatibilityController` is completely separate.**
   ZERO references to `IndexGenerationManager`. REBUILDING state
   refers to per-doc RMW backfill, not blue-green. These two systems
   would need to be connected.

**`StoredFieldVisitor` — already used in content fetch.**
`DocumentFieldOps.getDocumentContent()` already loads only CONTENT
via `SearchResultFormatter.extractFromStoredFields(..., Set.of(
SchemaFields.CONTENT))`. Item 21's "use StoredFieldVisitor" is
already done for the content-fetch phase. The gap: per-doc searcher
acquisition (N separate `withSearcher()` calls instead of one shared
searcher for the batch).

**`StoredFieldVisitor` NOT used in RMW.** `WritePathOps.readModifyWrite()`
calls `storedFields().document(docNum)` which loads ALL stored fields
(including large CONTENT). Could use a visitor to load only the
fields being retained.

**NRT refresh already batched.** `updateDocumentsBatch()` calls
`maybeRefreshBlocking()` once per 100-doc batch. No mechanism to
suppress it entirely. No `forceMerge` in production code.

**No bulk embedding status reset.** No `resetAllEmbeddingToPending()`
operation exists. Would be needed for triggering re-embedding of all
docs after model change.

**No content hash per document.** Only model-level SHA-256 via
`EmbeddingFingerprint`. Notion's content-hash optimization (skip
re-embedding unchanged docs) not implementable without adding a
per-doc hash field.

#### Work items (ordered by implementation priority)

21. [x] **Batch content fetch — share searcher across batch.**
    Added `getDocumentContentBatch(List<String> docIds)` to
    `DocumentFieldOps`. Acquires ONE searcher, resolves all doc IDs
    to Lucene doc numbers, sorts ascending (LZ4 locality), batch-
    extracts content via existing `StoredFieldVisitor` pattern.
    Updated `EmbeddingBackfillOps` Phase 1 to use batch method.

26. [deferred] **Reduce commit frequency during backfill.**
    Original plan was to suppress NRT refresh during backfill.
    **Pre-implementation investigation invalidated this:** NRT
    refresh does NOT create segments — it only reopens the
    `IndexReader` to make already-flushed segments visible.
    Segments are created by RAM buffer flushes (at 64 MB, ~16K
    docs — well above a 100-doc batch) and explicit `commit()`
    calls (1 per batch). Suppressing refresh would also break
    RMW correctness (the searcher needs to see old docs to read
    stored fields for merge).

    The actual lever is commit frequency: currently 1 `commit()`
    per 100-doc batch = 10 commits for 1K docs. Reducing to 1
    commit per N batches would reduce segment creation. But: fewer
    commits means more data at risk if the process crashes during
    backfill. Trade-off is marginal gain vs. crash safety.
    Deprioritized — focus on item 20 (blue-green) which eliminates
    the RMW bottleneck entirely.

20. [x] **Blue-green rebuild for embedding model changes.**

    ##### How the existing migration works

    1. `KnowledgeServer.start()` catches `SCHEMA_MISMATCH` from
       `createIndexRuntime()` when config is `blue_green_migrate`
    2. Opens Blue (old index, read-only, serves search)
    3. `genManager.startMigration("schema_mismatch")` creates Green
    4. Opens Green (new generation, writable)
    5. Daemon thread `"migration-enumerator"` walks filesystem,
       calls `jobQueue.enqueue(batch)` — same queue as normal ingest
    6. **IndexingLoop processes migration jobs identically to normal
       ingest** — no migration awareness, same `processBatch()` path
    7. Cutover monitor polls until queue drains, then promotes Green
       to active and restarts worker

    Key insight: migration documents flow through the same
    `IndexingLoop.processBatch()`, which has `canBatchEmbed = false`
    hardcoded. So migration produces text-only docs with
    `EMBEDDING_STATUS=PENDING`. After cutover, the slow RMW backfill
    would need to re-embed everything — defeating the purpose.

    ##### Pre-implementation verification (2026-03-20)

    Three pre-implementation investigations resolved key uncertainties:

    **1. `canBatchEmbed = true` path is NOT bitrotted.**
    All dependencies intact: `EmbeddingProvider.embedDocumentBatch()`
    exists, `EmbeddingService` implements it, `buildDocument()`
    11-param overload accepts precomputed embeddings, `NoOp` fallback
    is safe. No compilation issues if re-enabled.

    **2. `precomputedEmbedding` takes priority over `allowEmbeddingWrites`.**
    `IndexingDocumentOps.buildDocument()` line 164: if
    `precomputedEmbedding != null && length > 0`, it writes the
    vector directly and sets `EMBEDDING_STATUS=COMPLETED` — regardless
    of `allowEmbeddingWrites`. The flag only gates the fallback
    inline embedding path (line 172). This means: **the batch embed
    path works with the current `allowEmbeddingWrites=false` setting.**
    No additional changes needed to `buildDocument()`.

    **3. Commit metadata fingerprint stamping is automatic.**
    Green starts empty → ECC enters COMPATIBLE (NEW_INDEX_NO_FINGERPRINT)
    → `fingerprintToStamp()` returns the current model hash → every
    commit to Green stamps `embedding_model_sha256`. After cutover +
    restart, ECC reads Green's metadata, sees matching fingerprint →
    COMPATIBLE. No REBUILDING flow needed.
    Edge case: if embedding model is unavailable during migration,
    no fingerprint is stamped → restart enters BLOCKED_LEGACY. Same
    behavior as normal startup without model.
    Timing: `embeddingFingerprintSupplier` is late-bound via
    `AtomicReference` — commits before `initDeferredModels()` have no
    fingerprint, but model loading (seconds) finishes long before
    migration population (minutes/hours). Safe in practice.

    ##### Strategy options

    **Option A: Inline embedding during migration (recommended).**
    Replace `canBatchEmbed = false` with a runtime condition. When
    migrating, enable batch embedding so docs get vectors inline.
    - Pro: vectors ready at cutover, ~8.6 docs/sec end-to-end
    - Pro: 8.6/s inline > 7/s RMW backfill — actually faster total
    - Pro: `canBatchEmbed` path verified functional, no bitrot
    - Pro: `precomputedEmbedding` bypasses `allowEmbeddingWrites`
    - Pro: commit metadata stamping is automatic via ECC COMPATIBLE
    - Con: slower primary indexing during migration (8.6 vs 136/s)
    - Con: Green not serving search, so "fast BM25" has no benefit
    - **This is the right strategy for migration** — the deferred
      approach optimizes for "BM25 immediately" which only matters
      when the index IS serving search (normal ingest). During
      migration, Blue serves search; Green should optimize for
      total time including vectors.

    **Option B: Keep deferred, accept slow backfill.**
    Just trigger migration, let normal paths run. Primary ingest at
    136/s, then RMW backfill at 7-10/s after cutover.
    - Pro: simplest, no IndexingLoop changes
    - Con: degraded vector search for hours after cutover
    - Con: total time dominated by backfill (same as today)
    - Good as **step 1** to validate trigger/cutover/ECC flow before
      adding inline embedding (Option A) in step 2.

    **Recommendation: Implement Option B first** (validate the flow),
    **then upgrade to Option A** (inline embedding).

    ##### Implementation plan

    **Phase 1: Option B — trigger only (DONE)**

    Added embedding fingerprint mismatch detection to
    `KnowledgeServer.start()` step 4. After the active runtime
    opens successfully, reads stored `embedding_model_sha256` from
    `openTimeCommitUserData()` and compares with
    `EmbeddingFingerprint.get()` (standalone filesystem SHA-256).
    When mismatch detected AND policy is `blue_green_migrate`:
    - Opens Blue (current index, read-only for search)
    - Closes old writable runtime
    - Creates Green via `genManager.startMigration("embedding_model_change")`
    - Opens Green (writable) and starts migration enumerator

    Key design decision: check runs synchronously in `start()`
    (not in async `initDeferredModels()`) — avoids runtime-swap
    timing issues. `EmbeddingFingerprint.get()` only needs filesystem
    I/O, no ORT sessions.

    Conditions: (a) `blue_green_migrate` policy (opt-in, not default),
    (b) stored fingerprint exists + non-blank, (c) current fingerprint
    exists, (d) fingerprints differ.

    **Phase 2: Option A — inline embedding (DONE)**

    Added `BooleanSupplier migrationActiveSupplier` to `IndexingLoop`
    (default `() -> false`). Wired from `KnowledgeServer` after
    `DefaultWorkerAppServices` construction: checks
    `buildingIndexPath != null && searchLifecycle != ingestLifecycle`.

    `canBatchEmbed` in `processBatchInner()` changed from hardcoded
    `false` to:
    ```
    migrationActiveSupplier.getAsBoolean()
        && embeddingProvider != null
        && embeddingProvider.isAvailable()
    ```

    Added `wireMigrationActiveSupplier()` to `WorkerAppServices`
    interface (default no-op) and `DefaultWorkerAppServices`
    implementation.

    **Phase 3: End-to-end validation plan**

    Initial live test (2026-03-20) was blocked by pre-existing entity
    field schema mismatch on the dev index. NOT a blocker for clean-
    index test — plan uses empty `JUSTSEARCH_DATA_DIR`.

    **Verification script (5 steps):**

    Step 1 — Ingest with model A (nomic):
    ```
    rm -rf tmp/embed-migration-test
    JUSTSEARCH_DATA_DIR=tmp/embed-migration-test runHeadlessEval
    jseval run --dataset scifact --max-queries 0 --embedding
    Poll /api/status: embeddingCompatState=COMPATIBLE,
      embeddingFingerprintStored non-empty
    Kill backend
    ```

    Step 2 — Restart with model B + blue_green_migrate:
    ```
    JUSTSEARCH_EMBED_ONNX_MODEL_PATH=models/onnx/embeddinggemma-300m
    JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY=blue_green_migrate
    JUSTSEARCH_DATA_DIR=tmp/embed-migration-test  (same dir)
    runHeadlessEval
    ```

    Step 3 — Verify migration triggers:
    ```
    Poll /api/status:
      migrationState == MIGRATING
      buildingGenerationId non-empty
      migrationEnumerator.running == true
    ```

    Step 4 — Wait for cutover + restart:
    ```
    Poll until migrationState returns to IDLE
    Verify: embeddingCompatState=COMPATIBLE
            embeddingFingerprintCurrent == embeddingFingerprintStored
    ```

    Step 5 — Verify inline embedding (Phase 2):
    ```
    embeddingCoveragePercent == 100
    embeddingPendingCount == 0
    (vectors written inline, no backfill needed)
    ```

    jseval handles step 1 (ingest + readiness). Steps 2-5 need a
    wrapper (~50 lines) that restarts the backend with different
    env vars and polls migration-specific `/api/status` fields.

    **Live verification PASSED (2026-03-21):**

    End-to-end flow verified:
    1. Ingest 10 docs with nomic → fingerprint `b4342336` committed
    2. Restart with `JUSTSEARCH_EMBED_ONNX_MODEL_PATH=embeddinggemma-300m`
       + `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY=blue_green_migrate`
    3. Trigger fired: `storedFp=b4342336, currentFp=989f119d, match=false`
    4. Migration created new generation, enumerator re-ingested 10 files
    5. After cutover + restart: `COMPATIBLE FINGERPRINT_MATCH` with
       `989f119d` (EmbeddingGemma fingerprint)

    Bugs found and fixed during verification:
    - `openTimeCommitUserData()` doesn't include fingerprint (captured
      before fingerprint commit). Fix: use `latestCommitUserDataBestEffort()`
    - `WorkerSpawner` didn't forward `embed.onnx.model_path` or
      `schema_mismatch.policy` as `-D` sysprops. Fix: added
      `forwardPropIfSet` for both + Gradle task sysprop forwarding.

    **Inline embedding (Phase 2) VERIFIED (2026-03-21):**

    Initial attempt failed: `EmbeddingService.createWithAutoDiscovery()`
    returned null for EmbeddingGemma because `EmbeddingConfig.fromEnv()`
    disabled embedding when the model was found via explicit path
    (`autoDiscovered=false` → `enabled=false`). Fixed by enabling
    embedding when `EMBED_ONNX_MODEL_PATH` env var is present.

    After fix: `embeddingCompletedCount=10, pendingCount=0,
    coverage=100%`. All docs have vectors after migration — no RMW
    backfill needed. Inline embedding activated during migration and
    produced vectors in one pass.

    **Full end-to-end flow verified:**
    1. Ingest 10 docs with nomic → fp `b4342336` committed
    2. Restart with EmbeddingGemma + `blue_green_migrate`
    3. Trigger detected mismatch → new generation created
    4. Enumerator re-ingested 10 files with inline embedding
    5. After cutover: COMPATIBLE, fp `989f119d`, 100% coverage, 0 pending

    **Throughput measurement (1010 SciFact docs, 2026-03-21):**

    | Path | Docs | Embedded | Time | Rate |
    |------|------|----------|------|------|
    | RMW backfill (baseline) | 1010 | 1010 | ~105s | ~9.6/s |
    | Migration (inline) | 1010 | 354 (35%) | ~70s primary | ~14.4/s primary |

    **Partial success:** 354/1010 docs (35%) got vectors inline during
    migration at ~14.4 docs/sec primary. But 656 docs (65%) were
    ingested BEFORE the embedding provider was ready — async
    `initDeferredModels()` takes ~15-20s to load EmbeddingGemma
    while the IndexingLoop is already consuming migration jobs.

    After cutover: `BLOCKED_LEGACY` (not COMPATIBLE) because the
    fingerprint wasn't stamped during the brief active-embedding
    window. 656 docs stuck PENDING, backfill blocked.

    **Root cause:** The migration enumerator starts immediately and
    the IndexingLoop processes jobs before `initDeferredModels()`
    completes. The embedding provider isn't wired until model
    loading finishes.

    **Fix implemented: `embeddingReadyLatch` gates enumerator.**
    Added `CountDownLatch` to `KnowledgeServer`. The migration
    enumerator thread calls `embeddingReadyLatch.await(120s)` before
    `enqueueAllFilesUnderRoots()`. The latch is released by
    `initDeferredModels()` after embedding provider + ECC are wired
    (in `finally` block for safety). This ensures all migration jobs
    are enqueued AFTER the IndexingLoop has access to the embedding
    provider, so inline embedding activates for ~100% of docs.

    Also fixed: `applyHeadlessEvalContract()` in `build.gradle.kts`
    was not forwarding `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` and
    `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` as system properties
    to the Worker subprocess. Added both to the env var forwarding
    list and as explicit `systemProperty()` calls.

    **Live verification PASSED (2026-03-21, 5184 SciFact docs):**

    | Metric | Before latch fix | After latch fix |
    |--------|-----------------|-----------------|
    | Inline embedding coverage | 35% (354/1010) | **99.7% (5168/5184)** |
    | Docs needing RMW backfill | 65% (656/1010) | **0.3% (16/5184)** |
    | Post-cutover ECC state | BLOCKED_LEGACY | **COMPATIBLE / FINGERPRINT_MATCH** |
    | Migration total time | ~70s (1010 docs) | ~900s (5184 docs) |
    | Migration rate (nomic inline) | ~14.4 docs/sec | **~5.8 docs/sec** |

    Migration rate is lower than the previous partial measurement
    because: (1) full 5184-doc corpus vs 1010-doc subset, (2) nomic
    at 75ms/doc embedding dominates per-batch time. With EmbeddingGemma
    (2.9ms/doc), migration rate would be ~100+ docs/sec (extract+write
    bottleneck, not embedding).

    The key improvement is coverage: **from 35% to 99.7% inline**.
    After cutover, only 16 docs (first batch before embedding warmup)
    need RMW backfill instead of 656. The migration achieves its
    stated purpose: rebuild the index with the new model's embeddings
    in one pass, avoiding the slow RMW bottleneck.

    For normal indexing, item 19 (deferred embedding, 235 docs/sec
    measured) remains the throughput optimization.

22. [deferred] **Lucene doc values for vectors.** Research confirmed
    `updateBinaryDocValuesField` does NOT update HNSW graph. Full
    `updateDocument()` required regardless. Could serve as phase 1
    of a two-phase strategy (write BDV cheaply, rebuild with HNSW
    later) but the blue-green rebuild (item 20) solves this more
    cleanly. Deprioritized.

**Research sources:**
- [LUCENE-3837: A modest proposal for updateable fields](https://issues.apache.org/jira/browse/LUCENE-3837)
- [LUCENE-4258: Incremental Field Updates through Stacked Segments](https://issues.apache.org/jira/browse/LUCENE-4258)
- [LUCENE-5189: Numeric DocValues Updates](https://issues.apache.org/jira/browse/LUCENE-5189)
- [Updatable DocValues Under the Hood](http://shaierera.blogspot.com/2014/04/updatable-docvalues-under-hood.html)
- [Updating individual fields in Lucene — Flax Redis codec](https://www.flax.co.uk/index.html@p=801.html)
- [ParallelIncrementalIndexing — Apache Lucene Wiki](https://cwiki.apache.org/confluence/display/lucene/ParallelIncrementalIndexing)
- [Solr Atomic Updates (yonik.com)](https://yonik.com/solr/atomic-updates/)
- [Solr Partial Document Updates](https://solr.apache.org/guide/solr/latest/indexing-guide/partial-document-updates.html)
- [SOLR-17843: TextToVectorUpdateProcessor partial update bug](https://issues.apache.org/jira/browse/SOLR-17843)
- [DiversifyingChildrenFloatKnnVectorQuery (Lucene 10.3)](https://lucene.apache.org/core/10_3_0/join/org/apache/lucene/search/join/DiversifyingChildrenFloatKnnVectorQuery.html)
- [Adding passage vector search to Lucene — Elastic Labs](https://www.elastic.co/search-labs/blog/adding-passage-vector-search-to-lucene)
- [Two years of vector search at Notion](https://www.notion.com/blog/two-years-of-vector-search-at-notion)
- [Vespa Partial Updates](https://docs.vespa.ai/en/writing/partial-updates.html)
- [OpenSearch indexing performance tuning](https://docs.opensearch.org/latest/vector-search/performance-tuning-indexing/)
- [IndexWriter (Lucene 10.0.0)](https://lucene.apache.org/core/10_0_0/core/org/apache/lucene/index/IndexWriter.html)
- [StoredFieldVisitor (Lucene 9.11.0)](https://lucene.apache.org/core/9_11_0/core/org/apache/lucene/index/StoredFieldVisitor.html)

### Phase 5: Alternative embedding model

The default embedding model is nomic-embed-text-v1.5 (137M params,
768-dim, INT8). EmbeddingGemma-300M is available in `models/onnx/
embeddinggemma-300m/` but only usable via `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`
env var override. Auto-discovery hardcodes the `onnx/embedding/` directory.

23. [x] **EmbeddingGemma quality evaluation.** Quality confirmed
    higher than nomic by user assessment. Formal jseval run deferred.

24. [x] **Make EmbeddingGemma the default.** Updated
    `EmbeddingOnnxModelDiscovery` to try `embeddinggemma-300m/` first,
    falling back to `embedding/` (nomic). Explicit model path override
    still takes priority. Existing tests pass (they create `onnx/embedding/`
    temp dirs — discovery falls back correctly).

    **Post-default throughput measurement (2026-03-21, 5184 SciFact):**

    | Path | Model | Rate | Notes |
    |------|-------|------|-------|
    | Primary indexing (deferred) | EmbeddingGemma | 86 docs/sec | SPLADE interleave active |
    | Backfill RMW | EmbeddingGemma | ~10 docs/sec | **Unchanged from nomic** |
    | Primary indexing (deferred) | nomic (prev) | 235 docs/sec | No SPLADE interleave |

    Primary rate variance (86 vs 235) is due to SPLADE interleave
    timing, not the model change — deferred embedding doesn't run
    during primary indexing regardless of model.

    **Key finding:** Backfill RMW at ~10 docs/sec is identical for
    nomic (75ms/doc inference) and EmbeddingGemma (2.9ms/doc inference).
    This conclusively proves the RMW bottleneck is Lucene
    `FieldMapper.toDocument()` re-analysis, not embedding inference.

25. [x] **Re-embedding migration.** Solved by item 20: when the
    embedding model changes and `blue_green_migrate` policy is set,
    KnowledgeServer detects fingerprint mismatch at startup, creates
    a new generation, and re-ingests all files with the new model.
    After cutover, the new index has the new model's fingerprint.
    With Phase 2 (inline embedding), vectors are included during
    re-ingest — no slow RMW backfill needed.

### Phase 6: Post-optimization profiling

With deferred embedding (item 19), embedding is no longer in the primary
indexing path. The bottleneck profile has fundamentally shifted. Phase 0
tracing infrastructure exists but hasn't been used to profile the current
codebase.

27. [x] **Profile primary indexing bottleneck post-deferred-embedding.**
    100-doc SciFact ingest with `tracing=detailed`, EmbeddingGemma default.
    7 batches (6 warm), 210 spans.

    **Warm batch breakdown (16 docs/batch, deferred embedding):**

    | Phase | Avg/batch | Avg/doc | % of batch |
    |-------|----------|---------|------------|
    | Extract (Tika + isUnmod) | ~42ms | 2.6ms | ~47% |
    | Write (buildDoc + Lucene) | ~40ms | 2.5ms | ~45% |
    | Overhead (batch mgmt) | ~8ms | 0.5ms | ~8% |
    | **Total** | **~90ms** | **~5.6ms** | |

    Batch-level rate: ~178 docs/sec. System-level rate: 86-235 docs/sec
    (variance due to SPLADE interleave). **No single dominant bottleneck**
    — extract and write are nearly equal. The 93-95% embedding dominance
    from pre-optimization profiling is eliminated.

    **Conclusion:** With deferred embedding, primary indexing is balanced
    between Tika extraction and Lucene writes at ~2.5ms/doc each. Further
    optimization requires either parallel extraction (item 11, ~2x) or
    pipeline parallelism (item 12, extract+write overlap). Both were
    deferred as negligible when embedding dominated — they are now the
    only remaining levers for primary indexing speed.

28. [x] **Profile backfill RMW bottleneck at sub-operation level.**
    Added `System.nanoTime()` per-phase timing to
    `EmbeddingBackfillOps.processEmbeddingBackfill()`. Measured 100
    SciFact docs (2 batches: 64 + 36 docs).

    | Phase | Batch 1 (64 docs) | Batch 2 (36 docs) | Per-doc avg |
    |-------|------------------:|------------------:|------------:|
    | Content fetch | 10ms | 90ms | ~1ms |
    | **Embed** | **10,458ms** | **4,939ms** | **~154ms** |
    | Write (RMW) | 32ms | 13ms | ~0.5ms |
    | Total | 10,501ms | 5,043ms | ~155ms |

    **CORRECTION: The backfill bottleneck is embedding inference, NOT
    `FieldMapper.toDocument()` re-analysis.** RMW write is only 0.5ms/doc.
    The earlier Phase 4 research conclusion was wrong — it was based on
    the observation that switching models didn't change rates, but both
    models run at similar effective CPU speeds in the ORT batch path
    (~154ms/doc for EmbeddingGemma 300M on CPU).

    EmbeddingGemma at 154ms/doc in backfill vs 2.9ms/doc in standalone
    Python is a 53x gap. Root cause investigation (codebase exploration):

    | Factor | Detail | Impact |
    |--------|--------|--------|
    | **`allow_spinning=0`** | ORT threads sleep/wake on condvar instead of spin-waiting. 13 `session.run()` calls per 100-doc batch, each incurs ~10ms thread wake-up on Windows | ~130ms per batch in scheduler overhead alone |
    | **`MAX_ORT_BATCH_SIZE=8`** | Based on nomic EXP-4, not re-validated for EmbeddingGemma. 100 docs → 13 ORT calls instead of fewer larger batches | Compounds wake-up latency |
    | **Double float boxing** | `float[]` → `List<Double>` → `float[]` round-trip per vector. 768 dims × 100 docs = 76,800 boxed Double objects | GC pressure, memory bandwidth |
    | **`interOpNumThreads=1`** | Sequential operator execution across graph | Moderate for small models |

    `allow_spinning=0` was set to avoid CPU burn during user interaction.
    Correct for the indexing loop (latency-sensitive), wrong for backfill
    (background, throughput-oriented). The standalone Python test uses a
    single warm batch call with spinning enabled — no thread wake-up
    overhead.

    **This means:** The RMW overhead we attributed to `toDocument()` is
    negligible. Backfill speed is gated by ORT session overhead, not
    inference compute. Fixing the ORT session config for backfill could
    improve from ~10 docs/sec to 50-100+ docs/sec without changing the
    model.

29. [x] **Tune ORT session for backfill throughput.**

    **CPU tests (100 SciFact docs, EmbeddingGemma):**

    | Config | Per-doc embed | Change |
    |--------|-------------:|-------:|
    | Baseline (batch=8, spinning=0) | 154ms | — |
    | batch=16, spinning enabled | 185ms | **-20% (worse)** |
    | batch=8, spinning enabled | 150ms | **+3% (marginal)** |

    **(b) Spinning removal: marginal on CPU.** Kept the change (no
    downside). **(c) Float boxing deferred** — cross-module API change.

    **GPU batch sweep (100 SciFact docs, EmbeddingGemma, SPLADE GPU off):**

    | ORT batch | Embed/doc | Write/doc | Total/doc | Notes |
    |----------:|----------:|----------:|----------:|-------|
    | **8** | **97ms** | **0.3ms** | **98ms** | GPU success, optimal |
    | 16 | 263ms | 0.5ms | 263ms | GPU regression |
    | 32 | 11ms | 187ms | 200ms | **GPU OOM → CPU fallback** |
    | 64 | 6ms | 198ms | 204ms | **GPU OOM → CPU fallback** |

    **CORRECTION (E1 investigation):** batch=32/64 "fast embed + slow
    write" was a misdiagnosis. Per-doc write timing (E1) showed RMW
    at 0-5ms/doc even at batch=32. The 187ms "write" was actually
    **per-doc CPU fallback embedding** inside `embedAndUpdateSingle()`.

    Root cause: `BFCArena::AllocateRawInternal` — GPU OOM on
    `MultiHeadAttention` node (940MB requested > 853MB available).
    GPU batch fails silently, `embedDocumentBatch()` returns null,
    fallback embeds 100 docs sequentially on CPU at ~230ms/doc.
    The "embed" timer captured only the failed GPU attempt (fast
    return), and the "write" timer captured the fallback path.

    **E2 confirmed:** CPU-only batch=32 writes at 0.47ms/doc (normal).
    The GPU is not interfering with Lucene — it's just OOMing.

    batch=8 works because 8 × 300M-param attention = ~470MB, under
    the 853MB available. batch=16 = ~940MB, borderline (sometimes
    works, sometimes regresses). batch=32 = ~1.9GB, always OOMs.

    batch=8 at 98ms/doc GPU is the ceiling for EmbeddingGemma on
    an RTX 4070 (2048MB arena) with this BFC allocator config.

    **Validated by external research:** sentence-transformers benchmarks
    show gte-large (335M, comparable to EmbeddingGemma 300M) bottoms
    out at batch=3-5 on GPU. ORT GPU memory is 4-6x model file size
    (300MB model → 1.2-1.8GB). GPU provides 1.3-1.5x over CPU for
    embedding models under 300M — matching our 98ms vs 150ms (1.5x).
    Our batch=8 is slightly above the recommended range for this
    model size. The 2.9ms/doc Python measurement was likely shorter
    inputs (reducing quadratic attention cost).

    Sources:
    - https://medium.com/@vici0549/it-is-crucial-to-properly-set-the-batch-size-when-using-sentence-transformers-for-embedding-models-3d41a3f8b649
    - https://github.com/microsoft/onnxruntime/issues/9949
    - https://sbert.net/docs/sentence_transformer/usage/efficiency.html
    - https://onnxruntime.ai/docs/performance/tune-performance/memory.html

    **GPU measurement (2026-03-21, SPLADE GPU disabled to avoid VRAM
    contention):**

    | Batch | Docs | Embed (GPU) | Per-doc |
    |-------|-----:|------------:|--------:|
    | 1 (cold) | 22 | 9,248ms | 420ms (session warmup) |
    | 2 (warm) | 78 | 7,622ms | **98ms** |

    GPU warm at 98ms/doc vs CPU at 150ms/doc — 1.5x speedup. The
    2.9ms/doc standalone Python measurement used a different setup
    (likely smaller batch, shorter texts, or different quantization).
    98ms/doc GPU is the realistic production number.

    **VRAM note:** EmbeddingGemma GPU + SPLADE GPU crashes the Worker
    (silent exit code -1, no error logged). Running with SPLADE GPU
    disabled avoids contention. This is the same GPU memory pressure
    documented in tempdoc 311.

    Also fixed: `applyHeadlessEvalContract()` was not forwarding
    `EMBED_GPU_ENABLED`, `EMBED_GPU_MEM_MB` as sysprops. Added to
    `WorkerSpawner.forwardPropIfSet()` and the Gradle eval contract.

    **Conclusion (preliminary):** batch=8 is optimal end-to-end, but
    batch=32/64 show GPU CAN embed at 6-11ms/doc — the 580x write
    regression at those batch sizes is unexplained and blocks a
    potential 15-20x backfill improvement. See item 30.

30. [x] **Investigate write regression at large GPU batch sizes.**

    **Root cause found:** GPU OOM, not a write regression. At
    batch=32, `BFCArena` can't allocate 940MB for `MultiHeadAttention`
    (only 853MB available). `embedDocumentBatch()` returns null →
    fallback to per-doc CPU embedding at ~230ms/doc. The "fast embed
    + slow write" was a timing artifact: embed timer captured the
    quick GPU failure, write timer captured the CPU fallback.

    **Experiments run:**

    E1: Per-doc RMW timing — all docs 0-5ms each, total 45-75ms.
        The 23s "write" was NOT in readModifyWrite().
    E2: CPU-only batch=32 — writes at 0.47ms/doc (normal). GPU not
        interfering with Lucene.
    E1 refined: Added timing inside write phase — `listBuild` and
        `updateBatch` sub-timers. Discovered the batch embed returned
        null (GPU OOM) and the fallback per-doc path was the source.

    **BFC arena math:** EmbeddingGemma MultiHeadAttention at batch=8
    needs ~470MB (fits in 853MB). At batch=16 needs ~940MB
    (borderline). At batch=32 needs ~1.9GB (always OOMs).

    **Conclusion:** batch=8 at 98ms/doc GPU is the hardware ceiling
    for EmbeddingGemma on an RTX 4070 with 2048MB arena. To go
    faster: (a) increase GPU arena allocation, (b) use a smaller
    model, or (c) use a GPU with more VRAM.

### Phase 7: EmbeddingGemma inference optimization

Current state: EmbeddingGemma INT8, 768-dim, batch=8, ORT EXTENDED_OPT.
GPU: 98ms/doc, CPU: 150ms/doc. Four optimization paths identified.

31. [ ] **Matryoshka dimension reduction (768 → 256).**

    EmbeddingGemma supports 128/256/512/768 dims via Matryoshka
    Representation Learning. Using 256-dim instead of 768:
    - Reduces attention KV cache by ~3x → may allow batch=16+ on GPU
    - Reduces index size by ~3x (768 floats → 256 floats per doc)
    - Reduces vector search cost (fewer dimensions to compare)
    - Quality retention: ~95-97% per EmbeddingGemma paper

    **Implementation:**
    The model always produces 768-dim output. Matryoshka truncation
    happens post-inference: take the first N dimensions of the output
    vector and L2-normalize. Need to:
    (a) Add a `dimension` config to `EmbeddingConfig` (or read from
        `pooling_config.json`) that controls truncation.
    (b) In `OnnxEmbeddingEncoder`, after mean pooling + normalize,
        truncate to the configured dimension.
    (c) Update `EmbeddingService.DEFAULT_DIMENSION` to 256.
    (d) Index schema changes: `KnnFloatVectorField` dimension must
        match. Existing 768-dim indexes need migration (item 20).

    **Experiments:**
    EXP-31a: Measure GPU inference at 256-dim — does batch=16 fit?
             (attention memory scales with hidden_size, not output dim,
             so this may NOT reduce GPU VRAM — need to verify)
    EXP-31b: Measure CPU inference at 256-dim — truncation is post-
             inference so speed should be identical (verify).
    EXP-31c: Quality eval — SciFact nDCG@10 at 256-dim vs 768-dim.
             Run via jseval with both dimensions.

32. [x] **Quantization variant testing (FP16 blocked, Q4 viable).**

    FP16 is NOT supported for EmbeddingGemma — model card explicitly
    states "activations do not support fp16 or its derivatives."

    Downloaded and tested Q4 (4-bit quantization, 188MB vs INT8 298MB)
    from `onnx-community/embeddinggemma-300m-ONNX`. Required merging
    external data files (`model_q4.onnx` + `model_q4.onnx_data`) into
    a single `model.onnx` via `onnx.save()` — OnnxSessionCache doesn't
    support external data files.

    **GPU results (100 SciFact docs, SPLADE GPU off):**

    | Model | Size | GPU batch=8 per-doc | vs INT8 |
    |-------|-----:|--------------------:|--------:|
    | INT8 | 299MB | ~200ms | baseline |
    | **Q4** | **188MB** | **106ms** | **1.9x faster** |
    | Q4 batch=16 | 188MB | 112ms | 1.8x (slight regression) |

    Q4 at 106ms/doc is a significant improvement. Batch=8 remains
    optimal — batch=16 is slightly slower even with Q4's smaller
    footprint. The Q4 model is at `tmp/embeddinggemma-q4/` — needs
    to be moved to `models/onnx/embeddinggemma-300m/` and wired as
    the GPU model variant if adopted.

    **Open question:** Q4 quality vs INT8 — needs jseval SciFact eval
    to verify no retrieval quality degradation from 4-bit quantization.

33. [x] **CPU `intraOpNumThreads` tuning — no effect.**

    EXP-33: Tested default (all 20 cores), 10 (physical), 4.
    Results: 158ms, 161ms, 161ms per doc — within noise.
    ORT's default (all logical cores) is already near-optimal.
    For a 300M-param model with batch=8, the matrix multiply cost
    is large enough that ORT saturates available threads regardless
    of count. Hyperthreading overhead is negligible compared to
    compute cost. No code change kept.

34. [x] **Sequence length cap — no effect.**

    EXP-34a: Tested maxSeqLen 2048, 512, 128 on GPU batch=8.
    Results: 205ms, 227ms, 199ms per doc — within noise.
    Hypothesis was that `maxSeqLen=2048` causes GPU to pre-allocate
    attention buffers for 2048 tokens even for 50-token inputs.
    Wrong — ORT allocates dynamically based on actual input tensor
    dimensions, and batch padding already pads to max-in-batch (the
    longest doc in the batch, ~50 tokens), not to `maxSeqLen`. The
    config only affects tokenizer truncation, not GPU memory. GPU
    memory allocation is per-inference, not per-session-config.
    No code change needed.

### Infrastructure: GPU auto-detect for `runHeadless`

16. [x] **Auto-detect ORT CUDA DLLs in `runHeadless` Gradle task.**
    Added to BOTH `runHeadless` and `applyHeadlessEvalContract()` in
    `modules/ui/build.gradle.kts`:
    - Auto-detect `onnxruntime_providers_cuda.dll` at known path
      (`tmp/ort-variant-test/cuda-12.4-v1.24.3/`)
    - Resolves main repo root from `.git` worktree file for worktree builds
    - Auto-enables `JUSTSEARCH_SPLADE_GPU_ENABLED=true` when DLLs found
    - Also forwards `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` and
      `JUSTSEARCH_INDEX_TRACING_LEVEL` env vars

    **Root cause of earlier failure:** The auto-detect was added to
    `applyHeadlessEvalContract()` which is only used by `runHeadlessEval`,
    not `runHeadless`. The `runHeadless` task (line 1735) has its own
    simpler env var passthrough. Fixed by adding the auto-detect to both.

### Measurement

17. [x] **A/B measurement (main vs worktree).** 1K SciFact, GPU, clean
    index. Conditions: `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` set,
    `JUSTSEARCH_SPLADE_GPU_ENABLED=true`, clean `modules/ui-web/.dev-data/`
    and `modules/ui/build/applauncher-data/`.

    | Run | Branch | Rate | Elapsed |
    |-----|--------|------|---------|
    | A (baseline) | main | 5.6 docs/sec | 179.4s |
    | B (Phase 0+1) | worktree | 8.6 docs/sec | 116.0s |
    | **Improvement** | | **+54%** | **-35%** |

18. [x] **OTel profiling validated (tracing=detailed).** 50-doc ingest
    with `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed`. All 5 span types
    exported with attributes to `traces.ndjson`. Required adding indexing
    attribute keys to `NdjsonSpanExporter.ALLOWED_ATTRS`.

    Per-batch breakdown (50 docs, GPU, clean index, warm batches):

    | Phase | Avg/batch | % of batch |
    |-------|-----------|------------|
    | Extract (Tika) | 27ms (1.7ms/doc) | 2-4% |
    | **Embed batch** | **1200ms (75ms/doc)** | **93-95%** |
    | Write (Lucene) | 28ms (1.8ms/doc) | 2-3% |
    | markDone | 0.5ms | ~0% |

    **Conclusion:** Embedding inference is 93-98% of every batch.
    Extract and write are negligible. Items 11-12 (parallel Tika,
    double-buffer) would save ~2ms/doc on a 75ms/doc pipeline —
    negligible for text-heavy corpora. The only path to >15 docs/sec
    is faster embedding inference (smaller model or skip-to-backfill).

### Embedding speed research (2026-03-20)

Research into the theoretical upper limits of embedding inference speed,
to inform whether faster models or architectural changes are the right
path forward.

**Current state:** nomic-embed-text-v1.5 INT8, 137M params, 768-dim.
Measured: 75ms/doc (warm batch=16, CPU). Equivalent to ~13 docs/sec
embedding-only, but pipeline overhead reduces to 8.6 docs/sec.

**CPU throughput landscape (sentences/sec, NanoBEIR benchmark):**

| Model | Params | Dims | CPU sent/s | Quality (nDCG@10) | vs nomic |
|-------|--------|------|-----------|-------------------|----------|
| **nomic-embed-text-v1.5** | **137M** | **768** | **~200*** | **~0.56** | **baseline** |
| all-MiniLM-L6-v2 | 22M | 384 | 1,739 | 0.5623 | ~9x faster |
| bge-small-en-v1.5 | 45M | 384 | 888 | 0.6267 | ~4x faster, better quality |
| bge-base-en-v1.5 | 110M | 768 | 265 | 0.6376 | ~1.3x faster, much better quality |
| gte-tiny | 22M | 384 | 1,752 | 0.5692 | ~9x faster |
| static-retrieval-mrl-en-v1 | ~8M | 1024 | 107,420 | 0.5032 | ~537x faster, 10% quality loss |
| BM25 (no model) | — | — | 49,707 | 0.4518 | n/a |

*nomic estimated from our measured 75ms/doc at batch=16 ≈ 13/s for short docs.
Sent/s benchmarks use longer sequences; our short SciFact docs would be faster.

**Key findings:**

1. **all-MiniLM-L6-v2 is the sweet spot** for our use case: 9x faster
   than nomic with comparable quality (nDCG 0.56 vs 0.56). Would bring
   primary indexing from 8.6 to ~50-70 docs/sec (embedding drops from
   75ms to ~8ms/doc, making extract+write the new bottleneck).

2. **bge-small-en-v1.5 is the quality-first option**: 4x faster than
   nomic with BETTER retrieval quality (nDCG 0.63 vs 0.56). Would bring
   primary indexing to ~25-35 docs/sec.

3. **Static embedding models** (sentence-transformers v4+) reach
   107K sent/sec — effectively free. Quality is 87% of all-mpnet,
   adequate for many use cases. Would make embedding a non-factor
   in the pipeline. However, requires sentence-transformers integration
   (currently using raw ONNX Runtime).

4. **INT8 quantization** gives 2-4x speedup on CPU for transformer
   models. nomic is already INT8, so no further gain there. But
   all-MiniLM-L6-v2 INT8 would be even faster than FP32 numbers above.

5. **ONNX + O3 fusion + INT8** (our current stack for SPLADE) gives
   the best single-model throughput. Applying the same pipeline to
   all-MiniLM would yield ~2-3ms/doc on CPU.

6. **Deferred embedding** (mark PENDING during primary indexing, backfill
   later) would make primary indexing ~116 docs/sec (our 8.6 docs/sec
   pipeline minus the 93% embedding cost). This is the zero-model-change
   option — same approach used for SPLADE since tempdoc 278 item 2a.

**Recommended paths (ordered by effort):**

| Path | Est. rate | Effort | Trade-off |
|------|-----------|--------|-----------|
| **Defer embedding to backfill** | ~50-100 docs/sec | Low | Embedding not available until backfill completes |
| **Switch to all-MiniLM-L6-v2 INT8** | ~50-70 docs/sec | Medium | Requires ONNX export, quality eval, re-embedding |
| **Switch to bge-small-en-v1.5 INT8** | ~25-35 docs/sec | Medium | Better quality than nomic; same effort as MiniLM |
| **Static embedding model** | ~500+ docs/sec | High | New integration; 87% quality; no ONNX needed |

**EmbeddingGemma-300M (2025-09, Google):**
Released September 2025. 308M params, Gemma 3 architecture (T5Gemma init).
#1 open model under 500M on MTEB English/multilingual/code. 768-dim with
Matryoshka down to 128. Max 2048 tokens. ONNX available (FP32, Q8, Q4).
Does NOT support FP16. Uses task-specific prompt prefixes.

MTEB English: 69.67 mean (task). Beats models 2x its size. But at 308M
params it's 2.2x LARGER than nomic (137M) — likely slower on CPU, not
faster. It's a quality upgrade, not a throughput upgrade. For throughput,
smaller models (MiniLM 22M, bge-small 45M) or deferred embedding remain
the better paths.

**EXP-312-1: EmbeddingGemma-300M INT8 standalone eval (2026-03-20):**

Downloaded `onnx-community/embeddinggemma-300m-ONNX` (FP32), applied ORT
dynamic INT8 quantization. Model files at `models/onnx/embeddinggemma-300m/`.

| Metric | nomic v1.5 INT8 | EmbeddingGemma INT8 |
|--------|----------------|---------------------|
| Model size | 131 MB | 298 MB |
| Batch-16 CPU | 75ms/doc | **2.9ms/doc** (26x faster) |
| SciFact nDCG@10 (embedding only) | ~0.56 (NanoBEIR) | **0.7128** |
| SciFact encoding throughput | ~13 docs/sec | **39.7 docs/sec** (Python) |
| Params | 137M | 308M |
| Architecture | BERT (nomic) | Gemma 3 (T5Gemma init) |
| Max seq len | 8192 | 2048 |
| Dims | 768 | 768 (Matryoshka 128-768) |
| Released | mid-2024 | Sep 2025 |

ONNX compatibility: outputs both `last_hidden_state` (per-token) and
`sentence_embedding` (pre-pooled). Our `OnnxEmbeddingEncoder` reads
`last_hidden_state` and applies mean pooling — compatible. Prefix config
created (`prefix_config.json`) with EmbeddingGemma task prefixes.
`pooling_config.json` set to MEAN_POOL. Tokenizer uses SentencePiece
(`tokenizer.json` format, not BERT `vocab.txt`) — our HuggingFace DJL
tokenizer should handle this but untested in the Java pipeline.

**Conclusion:** EmbeddingGemma is both faster AND higher quality than
nomic. At 2.9ms/doc, embedding becomes a non-bottleneck (extract+write
at 3.5ms/doc would dominate). Primary indexing would be limited by
extract+write at ~100+ docs/sec.

**EXP-312-2: EmbeddingGemma Java pipeline integration (2026-03-20):**

Successfully loaded in the live pipeline via `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`.
- DJL HuggingFace tokenizer: handles SentencePiece `tokenizer.json` ✓
- `OnnxEmbeddingEncoder`: loads model, auto-detects no `token_type_ids` ✓
- Pooling: MEAN_POOL from `pooling_config.json` ✓
- Prefixes: loaded from `prefix_config.json` ✓
- Embedding backfill: ~7.1 docs/sec (1000 docs in ~140s), limited by
  Lucene read-modify-write per batch (same bottleneck as nomic backfill)
- Search pipeline functional: BM25+SPLADE retrieval works, embedding
  coverage reaches 100% after backfill.

No code changes required — the existing `OnnxEmbeddingEncoder` and
`EmbeddingService` handle EmbeddingGemma as a drop-in replacement via
env var. Quality eval through jseval remains TODO for a separate session
(requires running the full SciFact eval config with queries and qrels).

### Backfill bottleneck investigation (2026-03-20)

Embedding backfill at 7 docs/sec is NOT limited by inference (2.9ms/doc
with EmbeddingGemma). Tested batch=500: slower at 4 docs/sec (stall at
batch boundary). The bottleneck is the **read-modify-write (RMW) per doc**.

**Per-doc cost chain in `EmbeddingBackfillOps`:**

Phase 1 — content fetch (per doc, sequential):
1. `getDocumentContent(docId)` → conditional NRT refresh
2. TermQuery search by doc ID
3. Load stored CONTENT field from disk

Phase 2 — batch embed (per batch, already batched):
4. `embedDocumentBatch(batchContents)` — ORT inference (~2.9ms/doc)

Phase 3 — batch write (per batch via `updateDocumentsBatch`):
5. ONE `maybeRefreshBlocking()` for the whole batch
6. Per doc `readModifyWrite()`:
   a. TermQuery search by doc ID (sub-ms)
   b. Load ALL stored fields (entire document — content, title, etc.)
   c. Apply embedding update to field map (HashMap merge)
   d. **`fieldMapper.toDocument(fields)` — RE-ANALYZE ALL text fields**
   e. `writer.updateDocument()` — delete old + write new document

**The dominant cost is step 6d** — `toDocument()` re-runs the full
analyzer chain (ICU tokenization, stemming, synonym expansion) on the
`CONTENT` field for every document, even though only the embedding
vector changed. For a 1KB SciFact doc this is non-trivial, and it
runs 100 times per batch.

Also: Phase 1 content fetch is per-doc sequential (each doc gets its
own TermQuery + stored field load), not batched.

**Why batch=500 was SLOWER:** Larger batches don't amortize the per-doc
RMW cost (step 6 is O(n) per batch). The NRT refresh in step 5 is
O(1) but takes longer with more pending writes to merge. The 30s stall
at the 500-doc batch boundary was likely segment merge overhead.

**Viable optimization paths (from Phase 4 research):**

1. **Batch content fetch (item 21, DONE).** Added
   `getDocumentContentBatch()` — single shared searcher, ascending
   Lucene doc-ID order for LZ4 locality. Eliminates N separate
   `withSearcher()` acquisitions.

2. **Blue-green rebuild for model changes (item 20).** Avoid RMW
   entirely by rebuilding the index with inline embedding during
   migration. Pre-implementation verified: `canBatchEmbed` path is
   functional (not bitrotted), `precomputedEmbedding` bypasses
   `allowEmbeddingWrites`, commit metadata fingerprint stamps
   automatically via ECC COMPATIBLE state.

3. ~~**Skip re-analysis via pre-tokenized bypass.**~~ Investigated
   but not viable for backfill: `CachingTokenFilter` is in-memory
   only, doesn't survive JVM restart. Each doc backfilled only once.

4. ~~**Lucene doc values for vectors.**~~ `updateBinaryDocValuesField`
   does NOT update HNSW graph. Not viable for write path.

5. ~~**In-place KnnFloatVectorField update.**~~ No Lucene API exists.
   HNSW graphs are immutable per segment.

Sources (embedding speed research):
- [EmbeddingGemma model card](https://huggingface.co/google/embeddinggemma-300m)
- [ONNX variant](https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX)
- [HuggingFace blog](https://huggingface.co/blog/embeddinggemma)
- [Paper (arXiv 2509.20354)](https://arxiv.org/abs/2509.20354)
- [Static Embeddings (HuggingFace blog)](https://huggingface.co/blog/static-embeddings)
- [CPU Optimized Embeddings (HuggingFace + Intel)](https://huggingface.co/blog/intel-fast-embedding)
- [Speeding up Inference (sentence-transformers)](https://sbert.net/docs/sentence_transformer/usage/efficiency.html)
- [FastEmbed ONNX (2025)](https://johal.in/fastembed-onnx-lightweight-embedding-inference-2025/)
- [Best Open-Source Embedding Models Benchmarked](https://supermemory.ai/blog/best-open-source-embedding-models-benchmarked-and-ranked/)

Sources (Lucene RMW research):
- [IndexWriter (Lucene 10.0.0 API)](https://lucene.apache.org/core/10_0_0/core/org/apache/lucene/index/IndexWriter.html)
- [LUCENE-5189: Numeric DocValues Updates](https://issues.apache.org/jira/browse/LUCENE-5189)
- [LUCENE-9004: HNSW Approximate Nearest Vector Search](https://issues.apache.org/jira/browse/LUCENE-9004)
- [Field.java source (Apache Lucene GitHub)](https://github.com/apache/lucene/blob/main/lucene/core/src/java/org/apache/lucene/document/Field.java)
- [PreAnalyzedField (Solr 8.6.0 API)](https://solr.apache.org/docs/8_6_0/solr-core/org/apache/solr/schema/PreAnalyzedField.html)
- [CachingTokenFilter (Lucene API)](https://lucene.apache.org/core/7_4_0/core/org/apache/lucene/analysis/CachingTokenFilter.html)
- [StoredFieldVisitor (Lucene 9.11.0 API)](https://lucene.apache.org/core/9_11_0/core/org/apache/lucene/index/StoredFieldVisitor.html)
- [Updatable DocValues Under the Hood](http://shaierera.blogspot.com/2014/04/updatable-docvalues-under-hood.html)
- [Vespa Partial Updates](https://docs.vespa.ai/en/writing/partial-updates.html)
- [Vespa Attributes](https://docs.vespa.ai/en/content/attributes.html)
- [Elasticsearch Update API Reference](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html)
- [opensearch-project/k-NN #1087](https://github.com/opensearch-project/k-NN/issues/1087)

## Constraints

1. **Tika thread safety.** `Tika.parseToString()` creates fresh parser
   instances, but metadata objects may not be thread-safe. Verify before
   parallelizing.
2. **SQLite single-writer.** SQLite in WAL mode supports concurrent
   readers but only one writer. Batch operations must still serialize
   writes.
3. **Lucene single-writer.** `IndexWriter` is single-threaded. Phase 3
   writes cannot be parallelized at the Lucene level.
4. **User responsiveness.** `BREATH_HOLD_MS = 500ms` pauses indexing
   when user activity is detected. Any parallelism must respect this.

## Dependencies

- **278 (Indexing Throughput):** Baseline measurements and engine-only
  ceiling (2,800-3,600 docs/sec).
- **310 (Batch Lucene Writes):** Batch write infrastructure for backfill
  stages. Primary indexing uses `WritePathOps.indexDocument()` (not
  read-modify-write), so the batch RMW API doesn't apply here.

## Non-Goals

- Multi-writer Lucene
- Distributed indexing

## Resolved: EnronQA throughput (session 80e11c82, 2026-03-20)

EnronQA at 1.3 docs/sec vs SciFact at 9.3 docs/sec was not a regression.
Root cause: **content length** — EnronQA is ~8KB/doc (40x larger than
SciFact 200B). Embedding inference scales linearly with token count.
SciFact 5.2K re-verification on current codebase: 4.0 docs/sec (expected
for primary + SPLADE interleave). No regression detected.

## Code Locations

| File | Relevance |
|------|-----------|
| `worker-services/.../loop/IndexingLoop.java:736` | `canBatchEmbed = false` — the migration toggle point |
| `worker-services/.../loop/IndexingLoop.java:665` | `processBatch()` — main batch processing |
| `worker-services/.../loop/ops/IndexingDocumentOps.java:164` | `precomputedEmbedding` priority over `allowEmbeddingWrites` |
| `worker-services/.../loop/ops/EmbeddingBackfillOps.java` | Backfill orchestrator (uses `getDocumentContentBatch`) |
| `adapters-lucene/.../runtime/DocumentFieldOps.java` | `getDocumentContentBatch()` — batch fetch (item 21) |
| `adapters-lucene/.../runtime/WritePathOps.java:431` | `updateDocumentsBatch()` — batch RMW |
| `adapters-lucene/.../runtime/FieldMapper.java:132` | `toDocument()` — re-analysis happens here |
| `indexer-worker/.../server/KnowledgeServer.java:326` | Migration trigger point (schema mismatch catch) |
| `indexer-worker/.../server/ops/KnowledgeServerMigrationOps.java` | Blue-green migration orchestration |
| `worker-core/.../index/IndexGenerationManager.java` | Generation management (Blue/Green state) |
| `worker-core/.../embed/EmbeddingCompatibilityController.java` | Fingerprint state machine |
| `worker-core/.../embed/EmbeddingFingerprint.java` | Model SHA-256 computation |
| `indexer-worker/.../embed/EmbeddingMetadataOverlay.java` | Commit metadata fingerprint stamping |
| `telemetry/.../TracingBootstrap.java` | OTel tracing setup (Phase 0) |
| `worker-core/.../queue/JobQueue.java` | Interface with batch `markDone()` |
| `indexer-worker/.../server/KnowledgeServer.java:165` | `embeddingReadyLatch` — gates enumerator on embedding readiness |
| `modules/ui/build.gradle.kts:1585` | `applyHeadlessEvalContract()` — eval task env/sysprop forwarding |

### Phase 8: Full pipeline profiling (post-optimization)

All prior phases focused on individual stages (primary indexing, embedding
backfill, SPLADE). This phase profiles the **full pipeline end-to-end** —
from first file ingested to all backfill stages complete — to understand
the current bottleneck structure and time allocation.

**Methodology:** Clean-index SciFact (5184 docs) ingest via `runHeadlessEval`
+ `jseval run`. Poll `/api/status` at 5s intervals, recording:
- `indexedDocuments`, `pendingJobs` (primary indexing progress)
- `embeddingCompletedCount`, `embeddingPendingCount`, `embeddingCoveragePercent`
- `spladeCompletedCount`, `spladePendingCount`, `spladeCoveragePercent`
- `chunkEmbeddingCompletedCount`, `chunkVectorCoveragePercent`
- `pendingNerCount`, `completedNerCount`
- `throughputDocsPerSec`

35. [x] **Full pipeline timeline (clean SciFact 5184).**

    **Conditions:** Clean index, EmbeddingGemma INT8 default, GPU enabled
    (embedding + SPLADE), `runHeadlessEval` with `jseval run --dataset
    scifact --embedding --splade`. Polled `/api/status` every 5s.

    **Timeline:**

    | Stage | Start | End | Duration | Rate | Notes |
    |-------|------:|----:|---------:|-----:|-------|
    | Primary indexing | 32s | 58s | **26s** | **199 docs/sec** | Deferred embedding, SPLADE interleave active |
    | SPLADE interleave | 42s | ~490s | ~448s | ~5.8 docs/sec | batch=10 every 5s during primary + early backfill |
    | Embedding backfill | 69s | 763s | **694s** | **7.5 docs/sec** | GPU batch=8, 98ms/doc. Runs concurrently with SPLADE |
    | Chunk vector backfill | 763s | 982s | **219s** | 9.7 chunks/sec | 2117 chunks. Gates on embedding completion |
    | NER backfill | 993s | 1699s | **706s** | **7.3 docs/sec** | 100 docs/batch, ~14s/batch. Gates on chunk completion |
    | Disambiguation | 1699s | ongoing | — | 500 docs/sec | Scans all docs, negligible new entries |
    | SPLADE backfill | — | **NOT REACHED** | — | — | Blocked by disambiguation loop |

    **Total elapsed at measurement cutoff: 2662s (44 min), SPLADE at 0%.**
    Estimated total with SPLADE: +100s (5184 docs × 19ms/doc GPU) = ~46 min.

    **Critical finding: NER backfill overwrites SPLADE status.**
    SPLADE interleave during primary indexing built up 2603/5184 docs
    (50.2% coverage). NER backfill then ran read-modify-write on all
    5184 docs, resetting `SPLADE_STATUS` back to PENDING. SPLADE
    coverage dropped from 50.2% to 0% — all interleave work lost.
    This forces SPLADE to redo 5184 docs instead of 2581.

    **Why SPLADE 0% at end despite SPLADE "running independently":**
    SPLADE IS independent in the backfill priority order (item 4 of 5),
    but the single-threaded indexing loop runs backfill stages in
    priority order: embedding → chunks → NER → SPLADE → disambiguation.
    During this run, disambiguation entered a continuous scan loop
    (500 docs/sec, no new entries) that blocked SPLADE from running.

    **Stage dominance:**

    | Stage | Duration | % of total | Bottleneck |
    |-------|----------|------------|------------|
    | Primary indexing | 26s | 1% | — (fast) |
    | Embedding backfill | 694s | 26% | ORT inference (98ms/doc GPU) |
    | Chunk vectors | 219s | 8% | ORT inference |
    | NER backfill | 706s | 27% | NER ORT inference + RMW |
    | Disambiguation | ongoing | — | Loop scan, no actual work |
    | SPLADE (projected) | ~100s | 4% | Would be fast if reached |
    | **Total** | **~2760s** | **~46 min** | |

    **Embedding + NER dominate at 53% combined.** Primary indexing is
    only 1% — the deferred embedding strategy successfully made primary
    indexing a non-bottleneck.

    **Bugs identified:**
    - **BUG-1: NER RMW overwrites SPLADE status.** `readModifyWrite()`
      in `WritePathOps` reads all stored fields, applies the NER update,
      then rebuilds the entire document via `FieldMapper.toDocument()`.
      The SPLADE fields (`splade_*`) are stored-only (not in the update
      map), so they get dropped during reconstruction. Same class of bug
      as the original Phase 4 RMW concern, but for SPLADE rather than
      embedding.
    - **BUG-2: Disambiguation loop blocks SPLADE.** Disambiguation scans
      all docs every second looking for new entity clusters. When there
      are none (common for SciFact), it loops indefinitely, starving
      SPLADE of the indexing loop thread.

    **Confirmed:** SPLADE monitor ran for 20+ minutes post-NER
    completion — SPLADE remained at 0/5184 until backend was killed.
    Disambiguation loop fully starved SPLADE backfill.

36. [x] **Fix NER RMW overwriting SPLADE status (BUG-1).**

    **Root cause:** `splade`, `splade_status`, and `splade_retry_count`
    are all `stored: false` in the SSOT field catalog. When
    `readModifyWrite()` reads the old document via
    `storedFields().document()`, these fields are invisible. The
    reconstructed document drops all SPLADE data. Same structural
    risk exists for any non-stored field during any RMW operation.

    **Why embedding backfill doesn't have this problem:**
    `EmbeddingBackfillOps` explicitly includes the `vector` field and
    `embedding_status` in the update map passed to RMW. Additionally,
    `embedding_status` is `stored: true`, so it survives the stored-
    field read. SPLADE has neither: NER backfill doesn't know about
    SPLADE, and `splade_status` is not stored.

    **Fix:** In `WritePathOps.readModifyWrite()`, after reading stored
    fields and before merging the caller's updates, check if the update
    map contains SPLADE data. If not, explicitly set `splade_status=
    PENDING` and `splade_retry_count=0` in the fields map. This ensures
    the document is re-queued for SPLADE backfill after any RMW that
    doesn't preserve SPLADE data. The actual FeatureField sparse vector
    data cannot be recovered (it's in the inverted index, not stored
    fields), so re-processing is the correct approach.

    The fix is conservative: it always resets SPLADE to PENDING when
    not explicitly supplied, even for docs that never had SPLADE. This
    is harmless — SPLADE backfill will process them either way.

    **Verified:** Post-fix profiling (SciFact 5184, clean index, GPU)
    shows SPLADE coverage never regressed during embedding or NER
    backfill. SPLADE peaked at 5184 docs (100%), no drops detected.

37. [x] **Fix disambiguation loop blocking SPLADE (BUG-2).**

    **Root cause:** `DisambiguationBackfillOps` queries
    `ner_status=COMPLETED` with no "already processed" marker. It
    re-scans the same docs endlessly with 0 new entries but never
    signals "done" to the idle loop. The single-threaded indexing loop
    runs backfill stages sequentially, so disambiguation starves SPLADE.

    **Fix:** Added `disambiguationPassComplete` flag and
    `lastKnownNerCompletedCount` to `IndexingLoop`. After disambiguation
    runs one full pass, the flag prevents re-entry until NER produces
    new docs (detected by comparing `ner_status=COMPLETED` count
    against the cached value). This allows SPLADE backfill to run in
    subsequent idle iterations.

    **Verified:** Post-fix profiling shows disambiguation runs once
    then stops. SPLADE idle backfill runs unblocked in subsequent
    iterations.

    **Post-fix full pipeline results (SciFact 5184, clean, GPU):**

    | Metric | Pre-fix | Post-fix | Change |
    |--------|---------|----------|--------|
    | Primary indexing | 26s (199/s) | 27s (192/s) | same |
    | Embedding 100% | 694s (7.5/s) | 764s (6.8/s) | same |
    | SPLADE 100% | NEVER (0%) | 822s (6.3/s) | **FIXED** |
    | NER + chunks | ~925s | included in parallel | — |
    | Total all stages | >2662s (44+ min) | **~850s (14 min)** | **3x faster** |
    | SPLADE regression | YES (50%→0%) | **NO** | **FIXED** |

    Embedding and SPLADE run concurrently (both ~800s). NER and chunks
    gate on embedding completion and complete quickly (~50s). Total
    pipeline time is dominated by the longer of embedding vs SPLADE
    (~850s = 14 min), not their sum (~2700s = 45 min).

    **Remaining issue: embedding RMW causes 249% SPLADE churn.**

    Post-fix analysis of the worker logs reveals that embedding
    backfill's RMW resets SPLADE status to PENDING (correctly, per
    BUG-1 fix) but this causes SPLADE to re-encode the same docs
    repeatedly. SPLADE encoded 18,111 docs for 5,184 unique docs
    (3.5x the necessary work, 249% churn).

    SPLADE's effective rate is 49 docs/sec (GPU, 20ms/doc), so without
    churn it would finish in ~106s. With churn: 822s. The churn adds
    ~716s of wasted GPU time.

    **Why this happens:** Embedding and SPLADE backfill run concurrently
    in the idle loop. When embedding RMW updates doc D with a new
    vector, it drops D's SPLADE FeatureField data (non-stored) and
    resets `splade_status=PENDING`. SPLADE backfill then re-encodes D.
    If embedding batches 100 docs while SPLADE has already processed
    them, all 100 get re-queued.

    **Detailed per-stage profile (from worker logs):**

    | Stage | Docs processed | Avg/doc | Effective rate | Notes |
    |-------|---------------|---------|----------------|-------|
    | Embedding | 5184 | 99ms embed + 8.6ms write = 108ms | 9.3/sec | 2 slow-write batches (GPU OOM → CPU fallback) |
    | SPLADE | 18111 (5184 unique) | 20ms encode + 0.2ms write = 20ms | 49/sec effective | 249% churn from embedding RMW |
    | NER | 5184 | ~150ms/batch of 100 | 6.5/sec | 794s total, gates on embedding |
    | Chunks | 2117 | — | — | Completed quickly after embedding |

    **Root cause of the churn** is structural: Lucene's immutable
    segments require full document replacement on any field update.
    Non-stored fields (FeatureField, KnnFloatVectorField) cannot be
    read back during RMW, so they must be either (a) supplied in the
    update map (embedding does this for vectors) or (b) accepted as
    lost and re-queued (what BUG-1 fix does for SPLADE).

    **Fix options for the churn:**
    1. **Run SPLADE after embedding completes** — eliminates churn but
       makes pipeline sequential: 764s + 106s = 870s (similar to
       current 822s, negligible benefit).
    2. **Gate SPLADE on embedding completion** — same as option 1 but
       formalized. Acceptable since SPLADE encode is fast (106s).
    3. **Accept the churn** — current approach. 822s parallel vs 870s
       sequential. The churn costs GPU cycles but doesn't significantly
       affect wall-clock time for this dataset size.
    4. **Two-phase SPLADE: encode to sidecar, commit after embedding** —
       compute SPLADE vectors in memory/sidecar during embedding,
       write them to Lucene only after embedding completes. Eliminates
       churn without sequential latency. High complexity.

    **Recommendation:** Option 2 (gate SPLADE idle backfill on embedding
    completion). The wall-clock difference is negligible (~50s), but it
    eliminates 13K wasted GPU encodes. For larger datasets the churn
    scales linearly while the sequential overhead remains constant
    (~100s), making sequential the clear winner at scale.

39. [x] **Gate SPLADE backfill on embedding completion.**

    Added `pendingEmbedForSplade` check in `IndexingLoop` idle loop:
    SPLADE idle backfill only runs when `EMBEDDING_STATUS=PENDING`
    count is 0 (or embedding provider unavailable). SPLADE interleave
    during primary indexing is unchanged.

    **Also adopted Q4 model as default.** Replaced INT8 (298MB) with
    Q4 (188MB) in `models/onnx/embeddinggemma-300m/model.onnx`. INT8
    backed up as `model.onnx.int8-backup`.

    **Verification run (SciFact 5184, clean, Q4+GPU):**

    | Metric | Pre-fix (INT8) | Post-fix (INT8) | Q4 + item 39 |
    |--------|----------------|-----------------|--------------|
    | Primary indexing | 26s (199/s) | 27s (192/s) | **21s (247/s)** |
    | Embedding 100% | 694s (7.5/s) | 764s (6.8/s) | **115s (45.1/s)** |
    | SPLADE 100% | NEVER (0%) | 822s (churn) | 837s (6.4/s) |
    | SPLADE churn | N/A | 249% | **54%** |
    | Total | >2662s (44m) | ~850s (14m) | **837s (14m)** |

    **Q4 embedding is 6.6x faster** than INT8 (45.1 vs 6.8 docs/sec).
    Embedding dropped from the critical path (764s → 115s).

    **SPLADE is now the critical path** at 837s. Item 39 eliminated
    embedding→SPLADE churn, but NER→SPLADE churn (54%) remains:
    NER runs for ~690s post-embedding and its RMW resets SPLADE
    status on every processed doc (same BUG-1 mechanism).

    Without NER churn, SPLADE would finish at 147s + 106s = **253s**.
    With NER churn: 147s + 690s = **837s**. The NER churn adds ~580s.

    **Next optimization:** Gate SPLADE on NER completion too, or
    accept the current 14-min total. With NER+SPLADE sequential:
    embed 115s + NER 690s + SPLADE 106s = **911s (15.2 min)** —
    slightly worse than current 837s but eliminates all churn.

### Phase 9: Single-pass enrichment → tempdoc 334

The RMW churn problem (BUG-1, item 39) is structural: every backfill
stage does independent RMW, and each drops non-stored fields from other
stages. The BUG-1 fix resets `splade_status=PENDING` so data is
recovered, but at the cost of redundant re-encoding (54-249% churn).

**Spun out to tempdoc 334** ("Single-Pass Enrichment"). Approach A
(combined single-pass backfill) implemented and verified:
- SPLADE churn: 249% → **2%** (eliminated)
- Total pipeline: 837s → **782s (13 min)**
- SPLADE encodes: 18,111 → **5,185** (3.5x less GPU work)

**Remaining bottleneck: NER at 74% of batch time** (105ms/doc).
NER optimization items tracked in tempdoc 334 items 13-15.

41. [ ] **Canonical doc sync.** Update `indexing-throughput.md` with
    measured full-pipeline rates and stage breakdown. As of 2026-04-09:
    production model is `gte-multilingual-base` FP16 (tempdoc 358),
    measured 341.6s pipeline (15.0 docs/s) at git fdd90cb58. Canonical
    doc still shows pre-358 model and has stale `SPLADE_BACKFILL_BATCH_SIZE=50`
    (actual: 200). See tempdoc 334 Phase 15 for full regression analysis
    and optimization plan (items 36-40).

## Retrospective (2026-03-21)

### Agent failure modes observed during item 20

**1. Verifying correctness instead of value.**
Item 20 was marked complete after confirming vectors appeared in 10 docs.
No throughput measurement was run until the user pushed three times.
The tempdoc's purpose is "Primary Indexing Throughput" — every item
should end with a measured rate, not a boolean "vectors exist."

**2. Not tracing the full propagation path before testing.**
Four separate env var propagation bugs were found across four test
cycles (openTimeCommitUserData, WorkerSpawner forwarding,
EmbeddingConfig autoDiscovered, applyHeadlessEvalContract sysprops).
All were in the same chain: Gradle task → HEAD JVM → WorkerSpawner →
Worker subprocess. A single code trace from Gradle to Worker would
have caught all four before the first live test.

**3. Speculation presented as analysis.**
The pre-implementation note said "model loading (seconds) finishes
long before migration population (minutes/hours). Safe in practice."
This was untested speculation. For a 1K-doc dataset, enumeration
takes seconds — comparable to model loading. The timing gap was
predictable but wasn't tested.

**4. Context pressure incentivizing premature closure.**
Multiple shortcuts (marking items complete early, not doing full
verification, accepting partial results) correlated with deep
sessions under context pressure. Each required explicit user
pushback to correct.

**5. Repeated same-class bugs without building a mental model.**
The `runHeadless` vs `runHeadlessEval` sysprop gap was the same
class of bug already fixed in a prior cycle. The fix was applied
to one task but not the other because the agent didn't build a
model of "all Gradle tasks that spawn workers need sysprop
forwarding."

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Primary indexing throughput deep-dive (2011 lines) with agent-bug-pattern lessons in the tail. Investigation produced its findings; lessons are absorbed into agent-lessons.md and related rules.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

