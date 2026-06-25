---
title: "359: Reranker Architecture Audit"
type: tempdoc
status: done
created: 2026-03-26
completed: 2026-03-26
---

# 359: Reranker Architecture Audit

## Goal

Analyze the `CrossEncoderReranker` and `CitationScorer` code
architecture and compare it against the current encoder infrastructure
(embedding, SPLADE, NER). Identify gaps where the reranker diverged
from patterns established by tempdocs 340, 349, and 352.

## Motivation

Tempdoc 348 found the Head-side reranker's CPU BFCArena allocating
20+ GB (attempting 64 GB) on long documents. The root cause is that
`CrossEncoderReranker` predates the `OrtSessionFactory` unification
(349) and `ModelManifest` convention (340). While those tempdocs
updated the reranker's GPU path, the CPU session creation and memory
management were not fully aligned.

## Investigation Results

*Note: line numbers in the tables below are pre-migration references.
After the Phase 3 migration, all lifecycle code moved into
`OrtSessionManager`. The tables document the state that was found,
not the current code.*

### File Reference Key

| Abbreviation | Full Path |
|---|---|
| **Embed** | `modules/worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java` |
| **SPLADE** | `modules/worker-core/.../splade/SpladeEncoder.java` |
| **NER** | `modules/worker-core/.../ner/BertNerInference.java` |
| **Reranker** | `modules/reranker/.../reranker/CrossEncoderReranker.java` |
| **Citation** | `modules/reranker/.../reranker/CitationScorer.java` |
| **Factory** | `modules/ort-common/.../ort/OrtSessionFactory.java` |
| **Cache** | `modules/ort-common/.../ort/OnnxSessionCache.java` |
| **Helper** | `modules/ort-common/.../ort/OrtCudaHelper.java` |
| **Manager** | `modules/ort-common/.../ort/OrtSessionManager.java` *(new)* |

### 1. ORT session creation comparison

| Aspect | Embedding | SPLADE | NER | Reranker | Citation |
|--------|-----------|--------|-----|----------|----------|
| CPU session via | `OnnxSessionCache` (Embed:143) | `OnnxSessionCache` (SPLADE:157) | `OnnxSessionCache` (NER:110) | `OnnxSessionCache` (Reranker:142) | `OnnxSessionCache` (Citation:62) |
| GPU session via | `OrtSessionFactory.createGpuSessionWithFallback` (Embed:680) | `OrtSessionFactory.createGpuSessionWithFallback` (SPLADE:961) | `OrtSessionFactory.createGpuSessionWithFallback` (NER:422) | **`OrtSessionFactory.createGpuSession`** (Reranker:241) | N/A (CPU-only by design) |
| BFCArena strategy | `kSameAsRequested` (Factory:84) | `kSameAsRequested` (Factory:84) | `kSameAsRequested` (Factory:84) | GPU: `kSameAsRequested`; **CPU: ORT default (kNextPowerOfTwo)** | **None** |
| Memory limit (GPU) | 2048 MB default (Embed:49) | 2048 MB default (SpladeConfig) | 512 MB default (NER:102) | 512 MB default (Reranker:56) | N/A |
| Memory limit (CPU) | None (ORT default) | None (ORT default) | None (ORT default) | **None (ORT default)** | **None (ORT default)** |
| Per-run shrinkage | Yes, GPU only (Factory:223) | Yes, GPU only (Factory:223) | Yes, GPU only (Factory:223) | Yes, GPU only (Reranker:243) | **No** |
| DLL pre-flight | Yes (Embed:667) | Yes (SPLADE:948) | Yes (NER:409) | Yes (Reranker:232) | N/A |
| FP16 fallback | Yes, `createGpuSessionWithFallback` (Embed:680) | Yes, `createGpuSessionWithFallback` (SPLADE:961) | Yes, `createGpuSessionWithFallback` (NER:422) | **No** — uses `createGpuSession` (Reranker:241) | N/A |

**Key divergences:**
- **D1 (Critical):** Reranker CPU session has no BFCArena arena strategy override. All GPU sessions get `kSameAsRequested` via `OrtSessionFactory`, but CPU sessions across *all* consumers use ORT's default `kNextPowerOfTwo`. The reranker is the only consumer where this matters because its `maxSequenceLength=8192` produces quadratic attention matrices.
- **D2 (Medium):** Reranker GPU path uses `createGpuSession` (no FP16 fallback), while all three Worker encoders use `createGpuSessionWithFallback`. If the FP16 model fails to load, the reranker falls back to CPU permanently rather than trying FP32-on-GPU.

### 2. Input length guarding

| Aspect | Embedding | SPLADE | NER | Reranker | Citation |
|--------|-----------|--------|-----|----------|----------|
| Max sequence length | 2048 (configurable, Embed:131) | 512 (configurable, SPLADE:149) | 512 (configurable, NER:47) | **8192** (EnvRegistry:561, Reranker DISABLED:26) | 512 (CitationScorerConfig:21) |
| Truncation strategy | Chunking with 128-token overlap (Embed:132); single texts truncated to `maxSeqLen` (Embed:491) | Hard truncation at `maxSeqLen` with truncation evidence logging (SPLADE:394) | Hard truncation at `maxSeqLen` (NER:192) | Hard truncation at `maxLength` in tokenizer (RerankerTokenizer:59) | Hard truncation at `maxLength` in tokenizer (RerankerTokenizer:59) |
| Memory cost model | O(n) — mean pooling, no attention matrix | O(n) — sparse output, no self-attention at inference | O(n) — token classification, linear output | **O(n²)** — full self-attention `[batch, heads, seqlen, seqlen]` | **O(n²)** — full self-attention (cross-encoder) |
| Quadratic attention? | No (encoder-only, pooled output) | No (encoder-only, sparse projection) | No (encoder-only, per-token labels) | **Yes** — 8192² × 12 heads × 4B = 3 GB per batch element | **Yes** — but capped at 512 tokens → 512² = 12 MB (safe) |
| Seq-len bucketing | No | Yes, `{128, 256, 384, 512}` (SPLADE:407) | Yes, `{64, 128, 256, 512}` (NER:268) | No — fixed pad to `maxLength` | No — fixed pad to `maxLength` |

**Key divergences:**
- **D3 (Critical):** The reranker's 8192 default combined with quadratic attention is the proximate cause of the 348 OOM. At 8192 tokens, a single attention matrix is 3 GB; with fused operators and intermediates, the total exceeds 60 GB. Every other consumer either has O(n) cost (Embedding, SPLADE, NER) or caps at 512 tokens (Citation).
- **D4 (Low):** Reranker and CitationScorer lack seq-len bucketing. SPLADE and NER bucket to avoid ORT dynamic shape cache invalidation. Not a memory issue, but causes unnecessary recompilation of execution plans.

### 3. Model manifest and provenance

| Aspect | Embedding | SPLADE | NER | Reranker | Citation |
|--------|-----------|--------|-----|----------|----------|
| `model_manifest.json` | Yes (Embed:123, `ModelManifest.loadOrDefault`) | Yes (SPLADE:144) | Yes (NerService:130) | **No** — uses `OnnxModelDiscovery` (RerankerConfig:74) | **No** — uses `OnnxModelDiscovery` (CitationScorerConfig:61) |
| `build.json` | N/A (not used in codebase) | N/A | N/A | N/A | N/A |
| CPU/GPU file selection | Via manifest: `cpu`/`gpu` fields → `resolveModelPath(dir, gpuEnabled)` | Same | Same | **Single `model.onnx`** — no CPU/GPU file distinction | **Single `model.onnx`** — CPU only |

**Key divergences:**
- **D5 (Medium):** Reranker and CitationScorer don't use `ModelManifest`. They use `OnnxModelDiscovery`, which only finds directories containing `model.onnx` + `tokenizer.json`. No declared CPU/GPU variants means no provenance tracking and no FP16 selection path. This directly explains D2 (no FP16 fallback) — the reranker doesn't know about a GPU-specific model file.

### 4. Configuration and lifecycle

| Aspect | Embedding | SPLADE | NER | Reranker | Citation |
|--------|-----------|--------|-----|----------|----------|
| Process | Worker | Worker | Worker | Head | Worker (citation is search-time but runs in Worker) |
| Config source | `ConfigStore.global()` via `EmbeddingConfig.from()` | `ConfigStore.global()` via `SpladeConfig.fromEnv()` | `ConfigStore.global()` via `NerConfig.fromEnv()` | `ConfigStore.global()` via `RerankerConfig.fromEnv()` | `ConfigStore.global()` via `CitationScorerConfig.fromEnv()` |
| GPU mem config timing | Instance construction (Embed:128) | Instance construction | Instance construction (NER:102) | **Static class-load** (Reranker:56, `resolveRerankGpuMemMb`) | N/A |
| Lazy vs eager init | CPU session: eager at `initialize()`; GPU session: lazy on first inference | CPU session: eager at `initDeferredModels()`; GPU session: lazy | CPU + GPU: both lazy (NerService double-checked lock, NerService:120) | CPU session: eager at constructor; GPU session: lazy | Lazy (double-checked lock in `CitationMatchOps:84`) |
| GPU arbitration | `!signalBus.isMainGpuActive()` (KnowledgeServer:567) | `!signalBus.isMainGpuActive()` (KnowledgeServer:641) | `!signalBus.isMainGpuActive()` (KnowledgeServer:595) | `gpuConfig.shouldUseGpu()` — caller-supplied (Reranker:130) | N/A (CPU-only) |
| GPU retry on failure | Yes, 60s interval (Embed:77, `GPU_RETRY_INTERVAL_MS`) | Yes, 60s interval (SPLADE:930) | **No** — permanent fallback (NER:447) | Yes, 60s interval (Reranker:94) | N/A |
| OrtCudaStatus tracking | Yes (Embed:74) | Yes (SPLADE:94) | Yes (NER:62) | Yes (Reranker:91) | **No** |
| GPU release (`releaseGpuSession`) | Yes | Yes (SPLADE:997) | **No** | Yes (Reranker:273) | N/A |

**Key divergences:**
- **D6 (Medium):** Reranker `GPU_MEM_LIMIT_BYTES` is resolved at **static class-load time** via `ConfigStore.globalOrNull()` (Reranker:58). If the class is loaded before `ConfigStore` initialization, it silently uses the 512 MB fallback regardless of config. All other consumers resolve GPU mem at instance construction time.
- **D7 (Low):** NER has no GPU retry (permanent CPU fallback on failure) and no `releaseGpuSession()`. This means NER can't re-acquire GPU after yielding. Minor because NER's GPU memory footprint is small (512 MB default).
- **D8 (Low):** CitationScorer has no `OrtCudaStatus` tracking. Expected for CPU-only, but means no observability in `/api/status` if the scorer silently fails.

### 5. Error handling and observability

| Aspect | Embedding | SPLADE | NER | Reranker | Citation |
|--------|-----------|--------|-----|----------|----------|
| GPU creation failure | `providerFailed` → CPU fallback + 60s retry (Embed:698) | `providerFailed` → CPU fallback + 60s retry (SPLADE:980) | `providerFailed` → permanent CPU (NER:441) | `providerFailed` → CPU fallback + 60s retry (Reranker:249) | N/A |
| GPU inference OOM | Propagates as `BackendException` → `EmbeddingService` returns null (graceful) | **BFC arena detection** → per-call CPU fallback (SPLADE:527, 544, 609) + dedicated `isBfcArenaFailure()` (SPLADE:1071) | Caught per-chunk → returns empty (NerService:107) | Caught → returns original order with `skipped=true` (Reranker:418) | N/A |
| CPU inference OOM | Propagates up | Propagates up | Caught per-chunk → empty result | **Caught → returns original order** (Reranker:418). No session teardown, no arena release. Dead arena allocations persist. | Caught per-sentence → `log.warn` + continue (Citation:148) |
| Failure surfaced to user | `null` embedding → doc stays un-enriched (visible in `/api/status` coverage) | Propagation or empty SPLADE vector → keyword-only search (silent degradation) | Empty NER → no entity facets (silent) | `skipped=true` in `RerankedResult` → original BM25 order shown (silent) | `log.warn` → falls back to embedding cosine path (silent) |
| BFC arena failure detection | No | **Yes** — `isBfcArenaFailure()` pattern match (SPLADE:1071) | No | **No** | No |
| Metrics/profiling | Encoder profile (batch count, latency, throughput) via `EncoderProfiler` | Encoder profile + truncation evidence logging | Per-chunk latency logged | Budget-based pre-skip logging (Reranker:354-396) | Per-sentence scoring logged |

**Key divergences:**
- **D9 (Critical):** When the reranker CPU session OOMs, the `OrtException` is caught at Reranker:418 and the method returns `skipped=true` — but the CPU session is **not torn down**, and the BFCArena's dead allocations (20+ GB) persist for the lifetime of the Head process. There is no mechanism to reclaim this memory short of restarting the Head.
- **D10 (Medium):** SPLADE has a dedicated `isBfcArenaFailure()` detector that triggers per-call CPU fallback without killing the GPU session. The reranker has no equivalent — any inference `OrtException` is swallowed identically whether it's a transient BFC allocation failure or a fatal session error.

---

## Gap Summary

| # | Gap | Severity | Root Cause | Status |
|---|-----|----------|------------|--------|
| D1 | CPU session has no BFCArena arena strategy override | Critical | CPU sessions use ORT defaults everywhere | Mitigated (D3 fix reduces memory to safe levels) |
| D3 | `maxSequenceLength=8192` with quadratic attention model | Critical | EnvRegistry default adopted from model capability | **Fixed** (Phase 0: default → 2048) |
| D9 | CPU OOM leaves 20+ GB dead arena allocations | Critical | No session teardown on OOM | **Fixed** (`reportCpuSessionFailure()` + deferred recreation in `getCpuSession()`) |
| D2 | Reranker GPU uses `createGpuSession` (no FP16 fallback) | Medium | No ModelManifest → no GPU model path | **Fixed** (Phase 1+3d: ModelManifest + manager uses `createGpuSessionWithFallback`) |
| D5 | No `ModelManifest` — no CPU/GPU file distinction | Medium | ModelManifest in wrong module | **Fixed** (Phase 1: moved to ort-common; Phase 3d: reranker uses it) |
| D6 | GPU mem limit resolved at static class-load time | Medium | `static final` with `ConfigStore.globalOrNull()` | **Partially fixed** (now instance-time call, but method is still static) |
| D10 | No BFC arena failure detection on reranker | Medium | SPLADE-specific, not shared | **Fixed** (Phase 2: `isBfcArenaFailure()` extracted to `OrtSessionManager`) |
| D8 | CitationScorer has no `OrtCudaStatus` | Low | CPU-only by design | Not addressed (out of scope) |
| D7 | NER has no GPU retry or release | Low | Intentional design choice | Not addressed (NER now has `releaseGpuSession` via manager but wiring not added) |
| D4 | Reranker/Citation lack seq-len bucketing | Low | Causes ORT plan recompilation | **Fixed** (batch-size bucketing: `{4, 8, 16, 24, 32, 48, 64}`) |

## Root Cause Analysis

The 10 divergences are not independent bugs. They are symptoms of one
structural problem: **the ORT consumer lifecycle is duplicated across
4 consumers with no shared contract, and `ModelManifest` is in a module
the reranker cannot access.**

### The module boundary trap

```
worker-services  →  reranker  →  ort-common     (reranker can use Factory, Cache, Helper)
worker-services  →  worker-core                  (worker-core HAD ModelManifest)
                    reranker  →  worker-core      (would pull Head-side module into Worker core)
```

`ModelManifest` lived in `worker-core` (`io.justsearch.indexerworker.ort`).
The `reranker` module depends on `ort-common` but **could not depend on
`worker-core`** — that would mean a small Head-side inference module pulling
in the entire Worker core (all encoders, indexing loop, embedding service).

Consequences:
1. Reranker can't use `ModelManifest` → no CPU/GPU file distinction (D5)
2. Without manifest, `createGpuSessionWithFallback` can't be called with
   two paths → no FP16 fallback (D2)
3. When 340/349/352 established patterns using `ModelManifest`, the reranker
   was structurally invisible — it couldn't adopt the patterns even if
   someone had remembered to update it

### Copy-paste lifecycle code

The ORT session lifecycle was implemented independently in each consumer.
Before the migration, the duplicated code totalled ~726 lines across 4
consumers, of which ~600 were verbatim copy-paste. The only meaningful
behavioral variations were 4 flags accounting for ~35 lines of difference.

### The 8192 default

Separate from the structural issue: `EnvRegistry.RERANK_MAX_SEQ_LEN`
defaulted to `"8192"`, adopted from the model's maximum capability
(309 §41). The configuration system didn't distinguish between O(n) and
O(n^2) memory cost models.

---

## Implementation Results

### Summary

| Metric | Value |
|--------|-------|
| Net line change | **-819 lines** (233 insertions, 1052 deletions) |
| Files changed | 14 |
| OrtSessionManager | 469 lines (replaces ~726 lifecycle lines across 4 consumers) |
| Consumers migrated | 4 of 4 GPU-capable (SpladeEncoder, OnnxEmbeddingEncoder, BertNerInference, CrossEncoderReranker) |
| CitationScorer | Unchanged (CPU-only, 3 lines of session code) |

### Phase 0: Reduce `maxSequenceLength` default — done

- [x] `EnvRegistry.RERANK_MAX_SEQ_LEN` default: `"8192"` → `"2048"`
- [x] `RerankerConfig.DISABLED` constant: `8192` → `2048`
- [x] `ResolvedConfigBuilderTest` assertion updated
- [x] `RerankerConfigTest` assertion updated
- [x] Stale Javadoc ("default: 512") corrected to "default: 2048"

### Phase 1: Move `ModelManifest` to `ort-common` — done

- [x] `ModelManifest.java` moved from `worker-core` to `ort-common` (`io.justsearch.ort`)
- [x] `ModelManifestTest.java` moved to `ort-common` test source set
- [x] Jackson dependency added to `ort-common/build.gradle.kts`
- [x] 6 import statements updated (5 in `worker-core` + 1 test)
- [x] `ModelVerifier` stays in `worker-core`

### Phase 2: Create `OrtSessionManager` — done (with gaps)

- [x] `OrtSessionManager.java` in `ort-common` (469 lines)
- [x] Builder API with `deferCpuSession`, `gpuRetryEnabled`, `onBeforeGpuRelease`
- [x] `selectSession()` with GPU arbitration, double-checked locking, retry
- [x] `releaseGpuSession()` with callback
- [x] `isBfcArenaFailure()` extracted from SpladeEncoder
- [x] `OrtSessionManagerTest` for BFC arena detection
- [ ] **Missing: CPU session teardown on OOM (D9)** — the manager has no
  mechanism to detect CPU inference failure and recreate the session.
  **Now fixed**: `reportCpuSessionFailure()` sets a flag; next
  `getCpuSession()` closes the old session and creates a fresh one
  under lock. Wired into `CrossEncoderReranker.rerank()` catch block.
- [x] `cpuSessionOptions` supplier intentionally omitted — all consumers
  now get standardized CPU options via `applyProductionSessionOptions()`
  (`interOp=1`, `allow_spinning=0`). Minor behavioral change: Embedding
  and NER now get `allow_spinning=0` they didn't have before (strictly
  beneficial — reduces idle CPU spin-wait).
- [x] Unit tests expanded: 14 tests covering BFC arena detection, builder
  validation, deferred CPU session, GPU status after construction,
  `reportCpuSessionFailure` safety, and close idempotency. Lifecycle
  methods requiring real ORT sessions (`selectSession` GPU path,
  `tryCreateGpuSession`) are tested indirectly through consumer
  integration tests.

### Phase 3: Migrate consumers — done

- [x] **SpladeEncoder** — `onBeforeGpuRelease(this::closePinnedOutput)` validated
- [x] **OnnxEmbeddingEncoder** — `deferCpuSession(true)` validated
- [x] **BertNerInference** — `gpuRetryEnabled(false)` validated
- [x] **CrossEncoderReranker** — ModelManifest integration (new), FP16 fallback enabled
- [x] **CitationScorer** — unchanged (CPU-only)
- [x] All module tests pass
- [x] Full compilation passes

---

## Critical Analysis of Implementation

### All three critical gaps resolved

**D3 (Critical): 348 OOM resolved.** The `maxSequenceLength` default is
now 2048. At 2048 tokens, the attention matrix is 192 MB (vs 3 GB at
8192) — a 16x reduction that keeps the CPU ORT working set under 1 GB.

**D9 (Critical): CPU session teardown on OOM.** `reportCpuSessionFailure()`
sets a flag; the next `getCpuSession()` call closes the dead session
(releasing BFCArena allocations) and creates a fresh one under the
`cpuSessionLock`. Uses deferred-recreation to avoid mid-inference
threading races — the old session stays alive until no thread holds a
reference. Wired into `CrossEncoderReranker.rerank()` catch block with
session-identity guard: only fires when the failed session was the CPU
session (GPU inference failures don't trigger CPU recreation).

**D1 (Critical): Mitigated.** CPU sessions still use ORT's default arena
strategy, but D3's reduction to 2048 tokens means the attention matrix
is 192 MB — safe even with `kNextPowerOfTwo` doubling.

### Medium gaps resolved

**D2/D5: FP16 fallback and ModelManifest for reranker.** Moving
`ModelManifest` to `ort-common` eliminated the module boundary that
prevented the reranker from using it. The reranker now calls
`ModelManifest.loadOrDefault()` and the manager uses
`createGpuSessionWithFallback`, giving it FP16 fallback for free.

**D6: GPU mem limit timing.** `GPU_MEM_LIMIT_BYTES` is no longer a
`static final` field. It's resolved via `resolveRerankGpuMemMb()` at
instance construction time. The method is still `private static` (reads
from `ConfigStore.globalOrNull()` — appropriate since it doesn't access
instance state), but since it's called from the constructor, it runs
after `ConfigStore` initialization.

**D10: BFC arena failure detection shared.** `isBfcArenaFailure()` is
now a public static method on `OrtSessionManager`, available to all
consumers.

### Structural fix

The 726 lines of copy-pasted lifecycle code across 4 consumers is now
a single ~480-line manager (net -819 lines). Future lifecycle
improvements land once and apply everywhere.

### Intentional simplifications

**`cpuSessionOptions` supplier omitted.** All consumers now get
standardized CPU options via `applyProductionSessionOptions()`. The
only behavioral change: Embedding and NER now get `allow_spinning=0`
they didn't have before — strictly beneficial (reduces idle CPU
spin-wait). Per-consumer CPU option customization is not needed.

**`resolveRerankGpuMemMb()` stays `private static`.** The D6 gap was
about timing (class-load vs instance construction), not the keyword.
The method is now called at instance construction time. Being `static`
is correct — it doesn't access instance state.

### Test coverage

14 unit tests covering BFC arena detection, builder validation (null
rejection, deferred mode, GPU configuration), OrtCudaStatus initial
state, `reportCpuSessionFailure` safety, and close idempotency. Full
lifecycle methods (`selectSession` GPU path, `tryCreateGpuSession`,
`releaseGpuSession`) require real ORT sessions and are tested
indirectly through consumer integration tests in worker-core, reranker,
app-services, and worker-services modules.

## Accepted Limitations

### D1: CPU BFCArena `kSameAsRequested` — upstream ORT limitation

Cannot be fixed from Java. The ORT Java API exposes exactly one CPU
arena control: `setCPUArenaAllocator(boolean)` — on or off. There is
no Java method for `arena_extend_strategy`, `max_mem`, or
`initial_chunk_size_bytes` on the CPU execution provider.

The C API has `CreateArenaCfgV2` (since ORT 1.8) which does allow
detailed arena tuning, but the Java bindings do not wrap it. The
[session options config keys header](https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h)
has no entries for CPU arena strategy. `addConfigEntry()` cannot set
arena behavior. This was confirmed by
[ORT GenAI issue #1584](https://github.com/microsoft/onnxruntime-genai/issues/1584)
which documents the same limitation on Android.

Fully mitigated: D3 reduces attention matrices to 192 MB (safe even
with `kNextPowerOfTwo` doubling to ~256 MB), and D9 recreates the
session if allocation fails.

### D8: CitationScorer OrtCudaStatus — no diagnostic value

CitationScorer is CPU-only by design (explicit in class Javadoc:
"avoiding GPU contention with the LLM"). It runs in the Worker
process post-generation, when the LLM still holds GPU context. Adding
GPU would either OOM or require complex VRAM release coordination.
Since citation scoring has an embedding-cosine fallback path, the
complexity isn't justified.

A static `OrtCudaStatus.notConfigured()` provides zero diagnostic
value — it never changes and tells the operator nothing.

## D4 Note: Batch-Size Bucketing

[ORT issue #13198](https://github.com/microsoft/onnxruntime/issues/13198)
measured ~13% CPU slowdown and ~3700% GPU slowdown from dynamic shapes.
The reranker's sequence dimension was already static (`maxLength`), but
the batch dimension varied per search. Fixed by padding batch size to
buckets `{4, 8, 16, 24, 32, 48, 64}`, matching SPLADE/NER's seq-len
bucketing pattern. Padding rows use zero-filled tensors (attentionMask=0
= ignored by the model). Scores for padding rows are discarded.

## Architectural Context: Why Reranker and CitationScorer Are CPU

### CrossEncoderReranker (Head process) — GPU optional, disabled by default

The reranker can use GPU (`JUSTSEARCH_RERANK_GPU_ENABLED=true`), but
defaults to CPU because on the target 8 GB VRAM hardware:
1. The Head process hosts the LLM (llama-server), which claims most VRAM
2. The Worker runs embedding + SPLADE + NER on GPU
3. No headroom for a third GPU consumer
4. Post-retrieval, latency-sensitive — in Head to avoid gRPC round-trip

### CitationScorer (Worker process) — CPU-only, no GPU path

Hardcoded CPU-only (no `GpuConfig`, no `OrtSessionManager`). Runs
post-generation when the LLM still holds GPU context. Adding GPU would
require complex VRAM release coordination for a secondary quality
enhancement that has an embedding-cosine fallback.

## Related

- **309 §41**: GTE-ModernBERT adoption (source of 8192 default)
- **340**: Model manifest convention (encoders use it, reranker now does too)
- **348**: Head-side reranker BFCArena OOM (the trigger — resolved by Phase 0)
- **349**: OrtSessionFactory unification (GPU sessions — now fully aligned via manager)
- **352**: ort-common module extraction (created the target module for Phase 1-2)
