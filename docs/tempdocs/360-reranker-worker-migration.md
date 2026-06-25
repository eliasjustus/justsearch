---
title: "360: Migrate Reranker to Worker Process"
type: tempdoc
status: done
created: 2026-03-26
completed: 2026-03-26
---

# 360: Migrate Reranker to Worker Process

## Goal

Move the `CrossEncoderReranker` from the Head process to the Worker
process so all ORT model consumers share one runtime, one GPU
arbitration bus, and one lifecycle owner.

## Motivation

Tempdoc 359 unified the ORT session lifecycle into `OrtSessionManager`
but discovered during live verification that GPU reranking is
structurally impossible in the Head: the Head classpath includes the
CPU-only `onnxruntime.jar`, not `onnxruntime_gpu.jar`. The CUDA shared
provider fails to load regardless of DLL availability.

This is a symptom of a deeper architectural split: the reranker ended
up in the Head because it operates on search results, but every other
ORT consumer (embedding, SPLADE, NER, citation scorer) runs in the
Worker. The split causes:

1. **Two ORT runtimes** — Head loads CPU-only JAR, Worker loads GPU JAR.
   GPU reranking is impossible without changing the Head classpath.
2. **Two GPU arbitration paths** — Worker models share one signal bus;
   the Head reranker uses `() -> true` with no coordination.
3. **Two lifecycle patterns** — the reranker is wired through
   `KnowledgeHttpApiAdapter` while every other model goes through
   `KnowledgeServer.initDeferredModels()`.
4. **Split observability** — reranker `OrtCudaStatus` is in the Head,
   all other statuses are in the Worker.

## Implementation Results

### 1. gRPC contract design — done

- [x] `Rerank` RPC added to `SearchService` in `indexing.proto`
- [x] `RerankRequest` (query, document_texts, deadline_ms, top_k)
- [x] `RerankResponse` (sorted_indices, scores, skipped, elapsed_ms, skip_reason)
- [x] Full document texts sent (title + snippet, ~2KB each) — avoids
  Worker re-fetch round-trip. 20 docs × 2KB = 40KB over loopback is trivial.

### 2. Worker-side reranker lifecycle — done

- [x] `CrossEncoderReranker` instantiated in `KnowledgeServer.initDeferredModels()`
  following the same pattern as NER/SPLADE/Embedding
- [x] GPU arbitration: `() -> !signalBus.isMainGpuActive()` — same as all
  other Worker ORT consumers
- [x] `OrtCudaStatus` supplier added to `GpuDiagnosticSuppliers` record
  (new `rerankerOrtCudaStatus` field with backward-compatible constructor)
- [x] Config: `RerankerConfig.fromEnv()` in Worker, reads from `ConfigStore`
- [x] `WorkerAppServices.wireSearchReranker()` method added
- [x] `DefaultWorkerAppServices` implements `wireSearchReranker()`

### 3. Head-side client — done

- [x] `SearchRpcOps.rerank()` — builds `RerankRequest`, delegates to
  `SearchRpcExecutor` with `STANDARD` deadline category
- [x] `RemoteKnowledgeClient.rerank()` — public method for Head-side callers
- [x] `KnowledgeHttpApiAdapter.doSearch()` calls `knowledgeServer.client().rerank()`
  instead of instantiating `CrossEncoderReranker` directly
- [x] `closeReranker()` is now a no-op (Worker owns lifecycle)
- [x] `rerankerIfReady()` replaced with `isRerankerConfigured()` (boolean)
- [x] `onnxruntime` runtime dependency removed from `app-services/build.gradle.kts`
- [x] `reranker` module dependency retained (for `RerankerConfig` types)
- [x] Dependency lock files updated

### 4. DelegatingSearchService forward — done

- [x] `rerank()` forward added to `DelegatingSearchService.java`
  (not `DelegatingIngestService` — `Rerank` is on `SearchService`, not `IngestService`)

### 5. Chunk reranker alignment — done

- [x] `RagContextOps.setSearchReranker()` accepts the shared GPU-capable instance
- [x] `getChunkReranker()` prefers the shared search reranker when available
- [x] Falls back to creating a local CPU-only instance if search reranker
  hasn't been wired yet (race-safe: rechecks under lock)
- [x] `GrpcSearchService.setSearchReranker()` wires to both the search
  handler and `RagContextOps`

### 6. CitationScorer alignment — deferred

- [ ] `CitationScorer` is CPU-only by design. Migration to `OrtSessionManager`
  would add complexity for zero benefit. Deferred per 359 analysis (D8).

### 7. GPL coordinator migration — done

- [x] `GplJobCoordinator` changed from `Supplier<CrossEncoderReranker>` to
  `boolean rerankerAvailable` flag
- [x] `scoreQueryDoc()` calls `knowledgeClient.rerank()` (remote RPC) instead
  of local `CrossEncoderReranker.rerank()`
- [x] `AppFacadeBootstrap` passes `adapter.isRerankerConfigured()` to constructor
- [x] Test updated: mock `knowledgeClient.rerank()` instead of mock
  `CrossEncoderReranker`; supplier-resolve-once test replaced with
  remote-rerank-per-call verification

## Design Decisions

### `maxAvgDocLengthChars` gate stays in Head

The eligibility check (`isRerankerEligible()`) stays in `KnowledgeHttpApiAdapter`.
It's cheap (uses `cachedAvgContentLengthChars` from the last status poll) and
avoids the gRPC round-trip entirely when triggered. No reason to send 40KB of
document text to the Worker just to have it decline.

### Full document texts over gRPC (not doc IDs)

The Head already builds `title + queryFocusedSnippet(content_preview)` for each
result. Sending doc IDs would require the Worker to re-fetch content it already
returned to the Head, adding a second Lucene read and ~5ms latency. With 20 docs
× ~2KB each, serialization overhead is negligible on loopback.

### RPC on SearchService, not IngestService

The `Rerank` RPC is a search-time operation. It was added to `SearchService`
and the forward to `DelegatingSearchService`, not `DelegatingIngestService`.

## Files Changed

| File | Change |
|------|--------|
| `indexing.proto` | Added `Rerank` RPC, `RerankRequest`, `RerankResponse` |
| `GrpcSearchService.java` | Added `searchReranker` field, `setSearchReranker()`, `rerank()` handler |
| `DelegatingSearchService.java` | Added `rerank()` forward |
| `RagContextOps.java` | Added `searchReranker` field, `setSearchReranker()`, updated `getChunkReranker()` |
| `WorkerAppServices.java` | Added `wireSearchReranker()` interface method |
| `DefaultWorkerAppServices.java` | Implemented `wireSearchReranker()` |
| `GpuDiagnosticSuppliers.java` | Added `rerankerOrtCudaStatus` field |
| `KnowledgeServer.java` | Added search reranker init in `initDeferredModels()` |
| `SearchRpcOps.java` | Added `rerank()` method |
| `RemoteKnowledgeClient.java` | Added `rerank()` public method |
| `KnowledgeHttpApiAdapter.java` | Replaced local reranker with remote RPC call |
| `GplJobCoordinator.java` | Changed to `boolean rerankerAvailable` + remote rerank |
| `AppFacadeBootstrap.java` | Updated GPL coordinator construction |
| `app-services/build.gradle.kts` | Removed `runtimeOnly(libs.onnxruntime)` |
| `GplJobCoordinatorTest.java` | Updated to mock remote rerank RPC |

## Expected Outcomes — Verification

- [x] `CrossEncoderReranker` runs in the Worker process
- [x] GPU reranking possible (Worker has `onnxruntime_gpu` on classpath)
- [x] All ORT consumers share one signal bus, one GPU arbitration
- [x] Head process has no ORT sessions (zero native memory from ORT)
- [ ] gRPC contract test for `Rerank` RPC (deferred — follows existing
  pattern where other SearchService RPCs lack dedicated contract tests)
- [x] Reranker `OrtCudaStatus` visible in Worker-side `/api/status`

## Accepted Trade-offs

### Chunk reranking now uses GPU (reverses 311 decision)

Tempdoc 311 made the chunk reranker CPU-only to "avoid VRAM contention
with Embedding + SPLADE." The rationale was that the Head-side reranker
couldn't coordinate with the Worker's signal bus. Now the reranker is
in the Worker with `!signalBus.isMainGpuActive()` arbitration — same as
embedding/SPLADE/NER. VRAM contention is managed. If the GPU is busy
(LLM active), the reranker falls back to CPU automatically.

### `maxSequenceLength` for chunk reranking uses search config

The shared search reranker uses `RERANK_MAX_SEQ_LEN` (default 2048).
Chunk inputs are typically 512–2048 chars — well under this limit.
No behavioral impact from using the search config's value.

### GPL training overhead from remote reranking

GPL's `scoreQueryDoc` now makes a gRPC round-trip per call instead of
a local function call. For a 1000-doc corpus (12,000 CE calls × ~4ms
each = ~48s overhead), this is 0.16% of total job time (dominated by
~8h of LLM query generation at 30s/doc). Accepted as negligible.

If the Worker is briefly unavailable during a GPL job, the circuit
breaker opens and CE scores default to 1.0 — acceptable for an offline
training job producing noisy labels.

## Live Verification Results

All items verified via live backend with SciFact corpus.

| Check | Evidence |
|-------|----------|
| Worker loads reranker | Worker log: "Search reranker ready (gpu=false)" |
| `Rerank` gRPC RPC works | Worker log: "Rerank completed: 5 docs in 14308ms" (3 calls) |
| `rerankerOrtCuda` in `/api/status` | `{configured:false}` (CPU, correct — GPU is opt-in) |
| `rerankerModelPath` in `/api/status` | `D:\code\JustSearch\models\onnx\reranker` |
| Head has no ORT sessions | `onnxruntime` removed from Head classpath |
| Pipeline enrichment completes | embed/SPLADE/NER/chunk all reach 100% |

### gRPC deadline iteration

Moving a local function call to gRPC introduces a transport deadline that
didn't exist before. The pre-migration reranker had no external timeout —
the 200ms budget only gates whether to *start* inference. ORT inference
itself runs to completion regardless of time.

| Deadline | Result |
|----------|--------|
| `STANDARD` (5s) | `DEADLINE_EXCEEDED` — ORT warm-up alone > 5s |
| `CONTENT_FETCH` (10s) | `DEADLINE_EXCEEDED` — 5 docs × 2048 seq on CPU > 10s |
| `RERANK` (30s) | `DEADLINE_EXCEEDED` — 20 docs × 2048 seq on CPU = ~42s |
| `RERANK` (60s) | **Success** — 20 docs completed in 41685ms |

CPU inference cost at `maxSeqLen=2048`: ~2.1s per document (quadratic
attention). With `topK=20` (default), a single rerank call takes ~42s.
The 60s RERANK deadline accommodates this. Practical improvements:
- `JUSTSEARCH_RERANK_GPU_ENABLED=true` (now possible post-migration)
- Reduced `RERANK_MAX_SEQ_LEN` (e.g., 512 for 4x speedup)
- Lower `JUSTSEARCH_RERANK_TOP_K` (linear reduction)

### ORT session warm-up (F5, implemented)

First inference includes ORT execution plan compilation for the batch
shape. Warm-up inference in `initDeferredModels()` moves this cost to
startup. Measured: warm-up=6992ms (1 doc), steady-state=~2.1s/doc.

### Reordering not independently verified

CE executed and completed (`cross_encoder: executed`, 41685ms), but
the result ordering matched BM25 order. Could not perform an A/B
comparison (same query with/without CE on the same index) because
restarting the backend against the existing index hits a pre-existing
schema mismatch (`entity_*_raw` SORTED_SET vs SORTED) that crashes
`initDeferredModels()` before the reranker loads.

### Root cause: NaN scores from batch padding (fixed)

Batch-size bucketing (`padBatch()`) filled padding rows with all-zero
`attention_mask`. ModernBERT's global attention layers compute across
the entire batch tensor — all-zero mask rows cause softmax(0-denom) →
NaN that propagates to ALL rows. Fixed by setting `attentionMask[0]=1`
in padding rows.

A/B testing after the fix confirmed reordering on all tested queries:
BM25 rank #3 moved to CE rank #1 for "gene therapy for cancer treatment."

### GPU VRAM profiling

Tested `RERANK_GPU_MEM_MB` × `RERANK_MAX_SEQ_LEN` × `RERANK_TOP_K`
combinations to find viable GPU configurations.

**Why `maxSeqLen=2048` fails on GPU:** The attention tensor for
batch=8 × heads=12 × seq=2048 × 4B = **1.5GB** in a single allocation.
No BFCArena setting fits this alongside model weights. Tested at 2048MB
arena — still OOM.

**`maxSeqLen=512` enables GPU:** Attention tensor drops to ~100MB per
batch, total working set fits in 2048MB arena.

| maxSeqLen | topK | GPU MEM (MB) | Result | CE latency |
|-----------|------|-------------|--------|------------|
| 2048 | 5-20 | 2048 | OOM | — |
| 512 | 20 | 512 | OOM | — |
| 512 | 20 | 1024 | OOM | — |
| 512 | 20 | 1536 | OOM | — |
| **512** | **20** | **2048** | **Success** | **2.2s** |
| 512 | 10 | 2048 | Success | 1.4s |
| 512 | 5 | 2048 | Success | 0.7-1.0s |

For comparison: CPU at seq=2048 topK=20 takes **42s**. GPU at seq=512
topK=20 takes **2.2s** — a **19x speedup**.

**Now the default config** (no env vars needed):

| Setting | Old default | New default |
|---------|------------|-------------|
| `gpu.enabled` | `false` | `true` |
| `gpu_mem_mb` | `512` | `2048` |
| `max_seq_len` | `8192`/`2048` | `512` |

Updated in `ResolvedConfigBuilder`, `EnvRegistry`, `RerankerConfig.DISABLED`,
and `CrossEncoderReranker.resolveRerankGpuMemMb()` fallback.

Live verified: zero env vars → GPU reranker defaults to `gpu=true,
mem=2048MB, seq=512`. CE executes in 2.4s at topK=20 with valid
scores and confirmed reordering.

**VRAM budget on 12GB GPU:** embed ~2GB + SPLADE ~1GB + NER ~0.5GB +
reranker ~2GB = ~5.5GB. Leaves ~6.5GB for the LLM.

`maxSeqLen=512` is sufficient for search reranking: SciFact snippets
(title + 1500-char content_preview) are typically 200-400 tokens.
Truncating at 512 loses minimal context. Users with more VRAM can
override via `JUSTSEARCH_RERANK_MAX_SEQ_LEN=2048` (CPU fallback) or
with a future GPU with more memory.

## Verification Retrospective

### What went wrong during verification

1. **Unnecessary restarts and re-ingestion**: Most verification iterations
   were config changes (`RERANK_GPU_ENABLED`, `RERANK_TOP_K`, `MAX_SEQ_LEN`)
   or search tests — neither requires re-ingestion. But `RerankerConfig` is
   read once at `initDeferredModels()` and baked into the `CrossEncoderReranker`
   constructor, making config immutable after startup. This forced a full
   restart cycle per config change. The re-ingestion on top of that was
   entirely unnecessary — the index data doesn't change between config tests.
   Ran 5+ `jseval --clean` cycles (~5min enrichment each = ~25min wasted).

2. **Late diagnosis of schema mismatch**: Spent 30+ minutes debugging
   gRPC dispatch (inspecting JARs with javap, adding debug logs, clean
   rebuilds) when `grep ERROR worker.log` would have shown the root cause
   in 5 seconds.

3. **Deadline not anticipated**: Shipped STANDARD (5s), iterated through
   CONTENT_FETCH (10s) → RERANK 30s → RERANK 60s. Each iteration required
   rebuild + restart. Should have recognized during implementation that
   moving a local call to gRPC introduces a transport timeout that didn't
   exist before.

4. **Background task confusion**: Launched 6+ background tasks, lost track
   of which backend was alive with which code. Tested against stale Worker
   instances. Directly caused the 30-minute javap debugging detour.

5. **Built verification tools too late**: The `jseval dev`, `jseval search`,
   and `jseval logs` commands eliminated most friction — but were built after
   already burning through multiple painful manual verification cycles. If
   built first, every subsequent test would have been a one-liner.

### Root cause: unnecessary restart dependency

The verification workflow assumed "change → restart → re-ingest → test" as
the atomic unit. The actual dependency graph:

- **Code change** → needs restart (Worker runs from installDist JARs)
- **Config change** → needs restart (config read once at init, immutable)
- **Search test** → needs running backend + indexed data (no restart)

Most iterations were config changes or search tests. Re-ingestion was never
necessary after the first clean ingest. Even restarts were only necessary
because `RerankerConfig` is immutable after construction — if the reranker
supported runtime reconfiguration (re-read config, close old session, create
new), all VRAM/seqLen combinations could have been tested on a single running
backend.

### Tooling gaps identified (all implemented as F1-F5)

## Follow-up: Tooling and Warm-up Improvements

### F1. Persistent dev backend (`jseval dev`) — done

`jseval dev [--clean]` starts the eval backend and blocks until Ctrl-C.
Checks health endpoint first — if a backend is already running, attaches
(skips start, skips stop on exit). On Ctrl-C, stops the backend it
started. Uses the existing `backend.py` start/stop — no MCP integration
needed (separate system, separate data dirs).

### F2. Single-query search command (`jseval search`) — done

`jseval search --query "vitamin D" [--mode hybrid] [--limit 10] [--json]`
sends one search to a running backend and dumps the full response:
pipeline component status (CE, LambdaMART, expansion, chunk merge),
timing, and top results. Reuses `MODE_PIPELINES` from `retriever.py`.
`--json` emits the raw API response.

### F3. Structured log tailing (`jseval logs`) — done

`jseval logs [--source worker|head] [--filter PATTERN] [--level WARN] [--tail] [--lines N]`
Discovers log paths from running backend via `/api/status`. Parses
LogstashEncoder JSON lines, skips non-JSON. Filters by message content,
level. `--tail` polls for new content. Handles non-UTF8 characters
in stack traces gracefully.

### F4. Preflight model wiring validation — done

Extended `jseval preflight` to distinguish "model path found" (health)
from "model loaded and wired" (status). Checks `rerankerOrtCuda`,
`spladeOrtCuda.attempted`, `embedOrtCuda.attempted` in `/api/status`.
When paths exist but ORT status is absent, flags
`init_deferred_models_likely_failed`. Console output shows per-model
wiring state: `reranker: wired, gpu=no (cpu-only)`.

### F5. ORT session warm-up in `initDeferredModels()` — done

After creating the `CrossEncoderReranker`, runs a dummy inference
(`reranker.rerank("warmup", List.of("warmup"), 30000)`) to compile
the ORT execution plan at startup. Logs warm-up time. If warm-up
fails, logs the error and continues (non-fatal).

## Post-Implementation Root Cause Analysis

Critical review of whether the implemented fixes addressed root causes
or just symptoms.

### NaN batch padding — root cause resolved

1. **Dedicated method:** `padAttentionMask(original, targetRows, cols)`
   derives `actualBatchSize` from `original.length` internally —
   eliminates the mismatch risk of the previous 4-arg signature.
2. **Javadoc corrected:** `padBatch()` warns callers about attention
   mask requirements. `padAttentionMask()` explains ModernBERT global
   attention.
3. **Three regression tests:** padding with anchor (2→4 rows),
   no-padding (returns original), single-doc-to-bucket (1→4 rows).

### Schema mismatch — root cause resolved with tests

The `multiValued ? SORTED_SET : SORTED` fix in `ComponentsFactory`.
Guarded by three tests in `ComponentsFactoryTest`:

1. `schemaCheckPassesForMultiValuedKeywordField` — writes multi-valued
   keyword (SORTED_SET), reopens, verifies no throw.
2. `schemaCheckDetectsMismatchWhenMultiValuedChanges` — writes multi-
   valued (SORTED_SET on disk), reopens with single-valued mapper
   (expects SORTED), verifies `SCHEMA_MISMATCH` exception.
3. `schemaCheckDetectsMismatchWhenSingleValuedBecomesMultiValued` —
   writes single-valued (SORTED on disk), reopens with multi-valued
   mapper (expects SORTED_SET), verifies `SCHEMA_MISMATCH` exception.
   Both directions covered.

### Config default sync — root cause resolved

**Root cause:** `EnvRegistry` entries used 2-arg constructors (no
default string) for most reranker keys. Without a declared default,
`contributeEnvRegistry()` registers no ordinal-100 entry, forcing
`ResolvedConfigBuilder.resolveInt(key, fallback)` to use its own
hardcoded fallback. Two independent default locations, no sync.

**Fix:** Added declared default strings (3-arg constructor) to all 16
reranker and chunk-reranker `EnvRegistry` entries. Now
`contributeEnvRegistry()` registers ordinal-100 entries for every key.
The `ResolvedConfigBuilder` fallback arguments are dead code (ordinal
100 always wins). Dead-code status documented with a comment above
`buildReranker()`. Single source of truth: `EnvRegistry`.

**Entries changed (search reranker):**
`RERANK_GPU_ENABLED` `"true"`, `RERANK_GPU_MEM_MB` `"2048"`,
`RERANK_GPU_DEVICE_ID` `"0"`, `RERANK_TOP_K` `"20"`,
`RERANK_DEADLINE_MS` `"200"`, `RERANK_MIN_HITS` `"5"`,
`RERANK_MAX_AVG_DOC_LENGTH_CHARS` `"16000"`.
`RERANK_MAX_SEQ_LEN` already had `"512"`.

**Entries changed (chunk reranker):**
`RERANK_CHUNKS_GPU_ENABLED` `"false"`, `RERANK_CHUNKS_GPU_DEVICE_ID`
`"0"`, `RERANK_CHUNKS_TOP_K` `"10"`,
`RERANK_CHUNKS_MAX_GPU_CANDIDATES` `"50"`, `RERANK_CHUNKS_DEADLINE_MS`
`"150"`, `RERANK_CHUNKS_MIN_HITS` `"3"`, `RERANK_CHUNKS_MAX_SEQ_LEN`
`"512"`, `RERANK_CHUNKS_ORDER` `"auto"`.

**Regression guard:** Two tests form a constraint pair:
1. `EnvRegistryTest.allRerankEntriesWithDefaults_haveDeclaredDefaultValue`
   — structural: asserts all 16 entries have non-null `defaultValue()`.
   Catches someone reverting to 2-arg constructor.
2. `ResolvedConfigBuilderTest.rerankerDefaultsFromEnvRegistry` — builds
   config via `contributeEnvRegistry()`, cross-references resolved values
   against `EnvRegistry.*.defaultValue()` directly (not hardcoded ints).
   Catches drift in either direction: changing EnvRegistry without
   updating the builder, or removing the EnvRegistry default.

**Remaining secondary locations** (not mechanically linked but harmless):
- `ResolvedConfigBuilder.buildReranker()` fallback arguments — dead
  code, documented as such.
- `RerankerConfig.DISABLED` constant — compile-time fixture for the
  disabled case (no ConfigStore). Values match by convention.
- `CrossEncoderReranker.resolveRerankGpuMemMb()` fallback (2048) —
  only reached when `ConfigStore.globalOrNull()` is null (standalone).

These secondary locations cannot cause production bugs because the
primary path (`ConfigStore` → ordinal chain → `EnvRegistry` defaults)
is always used in production. They could cause confusion in tests or
standalone usage if they drift, but that risk is documented and
acceptable.

## Related

- **348**: Head-side reranker BFCArena OOM (the original trigger)
- **359**: OrtSessionManager extraction (unified lifecycle, discovered
  GPU impossibility in Head)
- **361**: Agentic workflow improvements (derived from this tempdoc's
  retrospective, including I6 configuration skill proposal)
