---
title: "286: Runtime Configuration and Diagnostics Audit"
type: tempdoc
status: done
created: 2026-03-13
updated: 2026-03-20
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 286: Runtime Configuration and Diagnostics Audit

## Problem

Tempdoc 283 solved GPU runtime path unification for worktree evals by adding `ortNativePath` to
`ResolvedConfig`, `SpladeModelDiscovery` dev-layout support, and per-subsystem GPU diagnostics in
`/api/status`. In doing so it revealed a general pattern: some subsystems resolve runtime
dependencies outside the central configuration contract, use inconsistent asset discovery
conventions, and expose their runtime state only through logs.

This tempdoc audits all worker-side subsystems against three questions:

1. **Configuration completeness** — Is every runtime-critical path/setting in `ResolvedConfig`?
2. **Discovery consistency** — Does the subsystem resolve assets from `modelsDir` via
   `ResolvedPathResolver` uniformly?
3. **Diagnostic observability** — Is the subsystem's runtime state visible in `/api/status`?

The goal is to close remaining gaps so that any isolated launch (worktree, eval, CI) produces a
fully diagnosable runtime without log spelunking.

## Root cause

### The incomplete migration

The codebase has two model discovery approaches. The older approach (`OnnxModelDiscovery` in
`modules/reranker`, `NerModelDiscovery` in `modules/indexer-worker`) uses direct
`PlatformPaths.resolveDataDir()` calls and hardcoded fallback chains. The newer approach
(`SpladeModelDiscovery`, `EmbeddingOnnxModelDiscovery`) uses `ResolvedPathResolver`, which reads
`ResolvedConfig.Paths.modelsDir` and provides a unified 4-candidate search: explicit `modelsDir` →
`<dataDir>/models` → `<repoRoot>/models` → `<baseDir>/models`.

The migration commit `88d44bd0` ("complete ResolvedConfig consumer migration") partially updated
`OnnxModelDiscovery` to read `ConfigStore` for infrastructure path roots (`repoRoot`, `home`,
`dataDir`) but did not migrate it to use `ResolvedPathResolver` or add the reranker model path to
`ResolvedConfig.Ai`. This is a historical artifact, not an intentional separation — the reranker's
`OnnxModelDiscovery` was written before `ResolvedPathResolver` existed.

### The concrete failure: `modelsDir` divergence

The discovery mechanisms diverge under edge conditions:

| Scenario | SPLADE / Embedding | Reranker |
|----------|-------------------|----------|
| Explicit `-Djustsearch.models.dir=/custom` | Finds at `/custom/onnx/splade/` via `ResolvedPathResolver` | **Skips it** — not in its chain |
| Uninitialized `ConfigStore` (early worker startup) | Falls back through `ResolvedPathResolver` chain | **Skips sidecar/repoRoot** — `paths` is null |
| Worktree with non-standard CWD | Falls back `home → dataDir → repoRoot → userDir` | Only `home → userDir`, missing `dataDir` and `repoRoot` |

A worktree or CI launch that sets `modelsDir` will find SPLADE and embedding models but silently
fail to find the reranker model, causing it to auto-disable with no diagnostic trace in
`/api/status`.

### Worker snapshot gap (model path only)

The Head→Worker config snapshot (`ResolvedConfig.toWorkerSnapshot()`) writes 10 properties
including `justsearch.model.path` (embedding) and `justsearch.llm.model_path`. It did not write
`justsearch.rerank.model_path` — fixed in Phase 1b.

Note: `RerankerConfig.fromEnv()` is called only in the **Head** process
(`KnowledgeHttpApiAdapter`). The **Worker** calls `ChunkRerankerConfig.fromEnv()` only
(`KnowledgeServerGrpcWiring`). The two processes use different reranker configs with different
env var namespaces, so behavioral settings (`top_k`, `deadline_ms`, etc.) have no cross-process
disagreement. The only real gap was the model path, because `ChunkRerankerConfig.fromEnv()`
falls back to `resolveRerankerModelPath()` (shared). Phase 1b fixed this by adding
`rerankerModelPath` to the snapshot and making `resolveRerankerModelPath()` check `ConfigStore`
first.

### Dependency constraint

`modules/configuration` has no dependency on `modules/reranker` (and adding one would be
circular). This means `ResolvedConfigBuilder` cannot call `OnnxModelDiscovery` — it can only
store the *explicitly-configured* reranker model path (from env var / sysprop / yaml), not
auto-discovered paths. This follows the same pattern as `embedModel`: explicit paths flow through
`ResolvedConfig` + snapshot; auto-discovery happens at worker startup via the discovery class.

## Audit matrix

| Subsystem | Config complete? | Discovery consistent? | Observable in `/api/status`? |
|-----------|------------------|-----------------------|------------------------------|
| **ONNX Embedding** | YES (`modelsDir`, `ortNativePath`, `embed.gpu.layers`) | YES (`<modelsDir>/onnx/embedding`) | YES (`embedBackend`, `embedGpuLayers`, `embedOrtCuda`) |
| **SPLADE** | YES (`modelsDir`, `ortNativePath`, SPLADE GPU vars) | YES (283 fix: `<modelsDir>/splade/naver-splade-v3`) | YES (`spladeOrtCuda`, `spladeModelPath`) |
| **Reranker** | **NO** — model path ad-hoc in `RerankerConfig.fromEnv()` | **NO** — `OnnxModelDiscovery` via `PlatformPaths`, not `modelsDir` | PARTIAL (`ortCuda` GPU status only; no model path) |
| **LLM / llama-server** | YES (`serverExe`, `llmModelPath`, `gpuLayers`, `vlmModel`, `mmprojModel`) | YES (variant via `serverExe` path; model via `modelsDir`/explicit) | PARTIAL (lightweight in `/api/status`; full detail in `/api/debug/effective-config` + `/api/ai/runtime/status`) |
| **VDU** | YES (`vlmModel`, `mmprojModel` in `ResolvedConfig.Ai`) | YES (resolved via LLM model paths) | **NO** (`pending_vdu_count` in gRPC proto but unmapped to `WorkerOperationalView`) |
| **Disambiguation** | N/A (rule-based NER clustering, no model deps) | N/A | **NO** (state never surfaced) |
| **Lucene index** | YES (`indexBasePath`) | N/A (no model discovery) | YES (extensive index state) |

## Gap classification

### HIGH — blocks diagnosability

1. **Reranker model path not in `ResolvedConfig`** — ad-hoc in `RerankerConfig.fromEnv()`, outside
   central config contract
2. **Reranker discovery doesn't use `modelsDir`** — `OnnxModelDiscovery` has its own chain;
   `modelsDir` overrides don't apply
3. **Reranker model path not in `/api/status`** — resolved path invisible to diagnostics

### MEDIUM — diagnosable via other endpoints

4. **LLM GPU layers / variant ID not in `/api/status`** — available in `/api/debug/effective-config`
   and `/api/ai/runtime/status`; split is by design
5. **VDU `pending_vdu_count` unmapped** — proto field 11 set by worker, never wired through mapper
6. **VDU `hasVisionCapability` only in `/api/inference/status`** — not in main status endpoint

### MEDIUM — silent misconfiguration

7. **`JUSTSEARCH_EMBED_BACKEND` accepts invalid values silently** (found in 326).
   `EmbeddingService.createWithAutoDiscovery()` checks for `"auto"` and `"onnx"`
   (case-insensitive) to trigger ONNX discovery, then falls through to GGUF.
   Any unrecognized value (e.g., `"GPU"`) skips ONNX entirely and fails with a
   generic "No embedding model found" — no warning about the invalid value.
   The name `EMBED_BACKEND` strongly implies "compute device" (CPU/GPU), but it
   actually controls model format (`auto`/`onnx`/`llama`). GPU acceleration is
   a separate knob (`EMBED_GPU_LAYERS`). This naming mismatch is compounded by
   inconsistent GPU toggle patterns across subsystems:
   - SPLADE/reranker/BGE-M3: `*_GPU_ENABLED=true` (boolean)
   - Embedding: `EMBED_GPU_LAYERS=N` (integer, 0=off)
   - LLM: `GPU_LAYERS=N` (integer)
   - `EMBED_BACKEND` is not documented in `environment-variables.md`
   Fix: (a) log a warning on unrecognized `embedBackend` values in
   `createWithAutoDiscovery()`, (b) add `EMBED_BACKEND` to env vars docs.

### LOW — nice-to-have

8. **Disambiguation state not observable** — background enrichment, no user-facing failure mode
9. **`ortCuda` field name ambiguous** — should be `rerankerOrtCuda` to match `spladeOrtCuda` /
   `embedOrtCuda` convention

## Implementation plan

### Phase 1a: Migrate OnnxModelDiscovery to ResolvedPathResolver (HIGH gap 2)

The primary fix. Replace the hardcoded `PlatformPaths` discovery chain with
`ResolvedPathResolver.resolveBaseDir()` + `resolveModelRoots()`, following the
`SpladeModelDiscovery` pattern. This ensures `modelsDir` overrides apply to the reranker.

- [x] Migrate `OnnxModelDiscovery.resolve()` to use `ResolvedPathResolver`
  - `modules/reranker/.../OnnxModelDiscovery.java` — replace hardcoded chain with:
    ```java
    ResolvedConfig config = resolvedConfig(); // from ConfigStore.globalOrNull()
    Path baseDir = ResolvedPathResolver.resolveBaseDir(config, System.getProperty("user.dir"));
    for (Path root : ResolvedPathResolver.resolveModelRoots(config, baseDir)) {
        Path candidate = root.resolve("onnx").resolve(modelName);
        if (isCompleteModelDir(candidate)) return new Result(candidate, true);
    }
    ```
  - `modules/configuration/.../resolved/ResolvedPathResolver.java` (reference only, not modified)

### Phase 1b: Add explicit reranker path to ResolvedConfig + snapshot (HIGH gap 1)

Store the explicitly-configured reranker model path in `ResolvedConfig` and propagate via
snapshot. Auto-discovered paths are NOT stored here (dependency constraint: `ResolvedConfigBuilder`
cannot call `OnnxModelDiscovery`). This follows the `embedModel` pattern.

Drop `rerankerEnabled` — the enabled state is derived from model discovery + explicit flag in
`RerankerConfig.fromEnv()`, not a simple config key.

- [x] Add `Path rerankerModelPath` to `ResolvedConfig.Ai`
  - `modules/configuration/.../resolved/ResolvedConfig.java`
- [x] Resolve `justsearch.rerank.model_path` in `buildAi()`
  - `modules/configuration/.../resolved/ResolvedConfigBuilder.java`
- [x] Add `justsearch.rerank.model_path` to `toWorkerSnapshot()`
  - `modules/configuration/.../resolved/ResolvedConfig.java`
- [x] Update `RerankerConfig.fromEnv()` to read explicit path from `ConfigStore` first, falling
  back to `ConfigPrecedence.envOrProperty()` only if store is null
  - `modules/reranker/.../RerankerConfig.java`
  - Note: `ConfigPrecedence` does not read from `ConfigStore`/snapshot, so this fallback change
    is required for snapshot values to reach the reranker

### Phase 2: Status observability (HIGH gap 3, MEDIUM gaps 5-6)

Thread the reranker model path to `/api/status` via the same supplier pattern used for
`spladeModelPath` (`IndexStatusOps` volatile supplier, wired from `KnowledgeServer` after gRPC
creation).

- [x] Add reranker model path supplier to `GrpcIngestService` / `IndexStatusOps`
  - `modules/indexer-worker/.../services/GrpcIngestService.java` — add volatile supplier + setter
  - `modules/indexer-worker/.../services/IndexStatusOps.java` — read supplier when building
    status response
  - `modules/indexer-worker/.../server/KnowledgeServer.java` — wire supplier after gRPC creation
    (same location as SPLADE/embed wiring, lines 559-570)
- [x] Add `reranker_model_path` proto field to `StatusResponse`
  - `modules/ipc-common/.../proto/indexing.proto`
- [x] Map reranker model path in `WorkerStatusMapper` and add to `WorkerOperationalView`
  - `modules/app-services/.../worker/WorkerStatusMapper.java`
  - `modules/app-api/.../status/WorkerOperationalView.java`
- [x] Map `pending_vdu_count` from proto to `WorkerOperationalView` (same pattern as
  `pendingNerCount` — one mapper line, one view field)
  - `modules/app-services/.../worker/WorkerStatusMapper.java`
  - `modules/app-api/.../status/WorkerOperationalView.java`

## Related

- **337 (Unified GPU Policy):** Addresses GPU config inconsistency finding
  (gap 7 — `EMBED_BACKEND` naming, divergent GPU toggle patterns across
  subsystems).
- **340 (Model File Manifest):** Addresses model discovery inconsistency
  finding (gap 2 — `OnnxModelDiscovery` vs `ResolvedPathResolver` divergence).
- [x] Regenerate `status-response.schema.json` via schema test
  - `modules/app-api/.../resources/schemas/status-response.schema.json`
  - `modules/app-api/.../test/.../StatusRecordSchemaTest.java`

### Phase 3: Naming consistency (LOW gap 8)

Rename `ortCuda` → `rerankerOrtCuda`. 5 touch-points, atomic commit. No external consumers; no
API versioning policy; frontend does not yet render `spladeOrtCuda`/`embedOrtCuda`.

- [x] Rename `ortCuda` → `rerankerOrtCuda`:
  - `modules/app-api/.../status/WorkerOperationalView.java` — record field + `toMap()` key
  - `modules/app-services/.../worker/WorkerStatusMapper.java` — positional arg (compiler catches)
  - `modules/app-api/.../resources/schemas/status-response.schema.json` — auto-regenerated
  - `modules/ui-web/src/api/schemas.ts` — Zod schema key
  - `modules/ui-web/src/stores/systemTypes.ts` — TypeScript interface

### Phase 4: Regression tests for the migration (verification gap)

The code changes in Phases 1-3 are untested at key integration boundaries. Existing
`OnnxModelDiscoveryTest` covers sidecar/dataDir/installDir discovery but not `modelsDir` override
— the exact scenario that was broken before the migration. No test verifies snapshot round-trip
for `rerankerModelPath`, and no test verifies that `RerankerConfig.fromEnv()` reads from
`ConfigStore` before falling back to `ConfigPrecedence`.

- [x] Add `modelsDir` override test to `OnnxModelDiscoveryTest`
  - `modules/reranker/.../OnnxModelDiscoveryTest.java` — set `justsearch.models.dir`, create
    `<modelsDir>/onnx/reranker/` with sentinel files, verify discovery succeeds and is
    auto-discovered. Follow existing `sidecarPathIsAutoDiscovered` pattern.
- [x] Add `modelsDir` priority test — `modelsDir` should beat `dataDir` and `repoRoot`
  - Same file — set all three, verify `modelsDir` candidate wins
- [x] Add snapshot round-trip test for `rerankerModelPath`
  - `modules/configuration/.../ResolvedConfigBuilderTest.java` — in `WorkerSnapshot` nested
    class, set `justsearch.rerank.model_path`, write snapshot, load on worker side, assert
    path round-trips
- [x] Add `RerankerConfig.fromEnv()` ConfigStore precedence test
  - `modules/reranker/.../RerankerConfigTest.java` — set `ConfigStore` global with a known
    `rerankerModelPath`, call `RerankerConfig.fromEnv()`, assert the resolved model path
    matches the store value. Requires `TestResolvedConfigHelper` for lifecycle.

### Phase 5: Migrate NerModelDiscovery to ResolvedPathResolver (same root cause)

`NerModelDiscovery` is a copy of the pre-migration `OnnxModelDiscovery` — uses
`PlatformPaths.resolveDataDir()` directly, skips `modelsDir` overrides. Same fix pattern as
Phase 1a. The comment on line 22 of `NerModelDiscovery.java` says "consolidate when a third
consumer appears" — with the reranker migrated, the divergence is now gratuitous.

- [x] Migrate `NerModelDiscovery.resolve()` to use `ResolvedPathResolver`
  - `modules/indexer-worker/.../ner/NerModelDiscovery.java` — same pattern as
    `OnnxModelDiscovery`: `resolvedConfig()` + `resolveModelRoots()` loop
  - Note: NerModelDiscovery is in `modules/indexer-worker` which has the
    `IndexerWorkerGuardrailsTest` ban on `System.getenv()`/`System.getProperty()`. The current
    code uses `ConfigPrecedence.envOrProperty()` which reads sysprops — the guardrail test
    already fails (pre-existing). Migrating to `ResolvedPathResolver` + `ConfigStore` would
    fix this.
- [x] Add `modelsDir` override test for NER discovery

### Phase 6: Reranker settings snapshot propagation — audit and default divergence fix

**Audit result (2026-03-19):** The original Phase 6 concern (settings bypassing the snapshot via
`ConfigPrecedence.envOrProperty()`) was **resolved by Phases 1b-5**. Both `RerankerConfig.fromEnv()`
and `ChunkRerankerConfig.fromEnv()` now read from ConfigStore first (`ai.reranker().*`), falling
back to `EnvRegistry` only when ConfigStore is null. All behavioral fields already exist in
`ResolvedConfig.Ai.Reranker` and `.ChunkReranker` records. `buildReranker()` and
`buildChunkReranker()` resolve them from the resolution map. `toWorkerSnapshot()` auto-includes
any non-null resolved values via the resolutions loop (lines 126-129).

**Bug found during audit: `max_seq_len` default divergence.** The config migration (Phases 1b-5)
created a new primary resolution path (ConfigStore → `buildReranker()` → `ai.reranker().maxSeqLen()`)
but the domain-specific `fromEnv()` retained its own defaults as fallbacks. When the reranker model
was upgraded to GTE-ModernBERT (commit `f10d47dac`), the developer updated the default in
`fromEnv()` (512 → 8192) but not in `buildReranker()` (still 512). Since `fromEnv()` reads
ConfigStore first at runtime, the 8192 fallback is dead code — the effective default is 512.

Root cause: **duplicated defaults with no single source of truth.** The default for `max_seq_len`
exists independently in three locations:
- `EnvRegistry.RERANK_MAX_SEQ_LEN` — `defaultValue = null` (2-arg constructor, no default)
- `ResolvedConfigBuilder.buildReranker()` — `resolveInt("justsearch.rerank.max_seq_len", 512)`
- `RerankerConfig.fromEnv()` — `EnvRegistry.RERANK_MAX_SEQ_LEN.getInt(8192)`

If `EnvRegistry` had `defaultValue("8192")`, it would flow through `contributeEnvRegistry()` →
`putDefault()` → resolutions map → snapshot, and both consumer-side defaults would be irrelevant.
The duplication exists because all reranker `EnvRegistry` entries use the 2-arg constructor (no
default), forcing each consumer to hardcode its own.

Impact: the reranker truncates to 512 tokens despite GTE-ModernBERT supporting 8192. All other
defaults between `buildReranker()`/`buildChunkReranker()` and `fromEnv()` match — only `max_seq_len`
diverges.

- [x] Audit which `RerankerConfig` settings should propagate via snapshot — **all already do**
  via ConfigStore-first reading in `fromEnv()` + auto-inclusion in `toWorkerSnapshot()`
- [x] Add selected reranker settings to `ResolvedConfig` and `toWorkerSnapshot()` — **already done**
  (all fields in `Ai.Reranker` and `ChunkReranker` records)
- [x] Update `RerankerConfig.fromEnv()` to read propagated settings from `ConfigStore` — **already
  done** for both `RerankerConfig` and `ChunkRerankerConfig`
- [x] Fix `max_seq_len` default divergence: update `buildReranker()` default from 512 → 8192
  - `EnvRegistry.RERANK_MAX_SEQ_LEN` — added `defaultValue("8192")` (first usage of 3-arg
    constructor, making EnvRegistry the authoritative default source)
  - `ResolvedConfigBuilder.buildReranker()` — fallback updated 512 → 8192
  - `RerankerConfig.DISABLED` constant — updated 512 → 8192
  - `ResolvedConfigBuilderTest` — added `rerankerMaxSeqLenDefault` test verifying EnvRegistry
    default flows through; updated parity test to account for entries with `defaultValue()`

### Phase 7: Validate `EMBED_BACKEND` values (MEDIUM gap 7)

Gap 7 was classified during the audit but never assigned to a phase.

**Root cause: string-typed dispatch without validation.** `embedBackend` is a free-form `String`
that acts as an implicit enum (`auto`, `onnx`, `llama`). `createWithAutoDiscovery()` whitelists
`"auto"` and `"onnx"` for the ONNX branch, then falls through to GGUF for *everything* else —
including typos and misunderstandings like `"GPU"`. Any unrecognized value silently skips ONNX
discovery and fails with a generic "No embedding model found" with no hint that the backend value
was the problem. The name `EMBED_BACKEND` compounds this by implying compute device (CPU/GPU)
rather than model format (onnx/llama).

- [x] Log warning on unrecognized `embedBackend` values in `createWithAutoDiscovery()`
  - `EmbeddingService.java` — warns on values other than `auto`, `onnx`, `llama`
  - `EmbeddingServiceConfigGateTest` — added `unrecognizedBackendFallsThrough` test
- [x] Add `JUSTSEARCH_EMBED_BACKEND` to `docs/reference/configuration/environment-variables.md`
  - Values: `auto` (default — try ONNX first, fall back to GGUF), `onnx` (ONNX only),
    `llama` (GGUF only)

## Structural issue: GPU config inconsistency + dead GGUF embedding code

Phases 6-7 fixed specific symptoms. Investigation revealed a deeper issue: the
GGUF/llama.cpp embedding path is dead code, which means the `int gpuLayers`
config pattern for embedding is vestigial — embedding is ONNX-only and should
use the same `boolean gpuEnabled` pattern as every other ONNX subsystem.

### GGUF embedding is dead code (verified 2026-03-20)

The `"llama"` `EmbeddingProvider` was removed from ServiceLoader. Evidence:

- `META-INF/services/io.justsearch.aibridge.embed.EmbeddingProvider` registers
  only `OnnxEmbeddingProvider` — no llama/GGUF provider exists.
- `EmbeddingProviderRegistryTest` asserts `assertFalse(ids.contains("llama"),
  "llama provider was removed")`.
- If `EMBED_BACKEND=llama` is set, `EmbeddingService.initialize()` throws
  `BackendException("No embedding provider found for backend: llama")` on
  `registry.resolve("llama")` — it discovers the GGUF model file but crashes
  when trying to use it.
- No `.gguf` embedding model exists in `models/` (only the LLM chat model).
- The only active embedding path is `OnnxEmbeddingProvider` →
  `OnnxEmbeddingEncoder` (ONNX Runtime).

**Stale artifacts from the llama.cpp embedding era:**

| Location | What | Status |
|----------|------|--------|
| `EmbeddingService.java` class Javadoc | Says "using llama.cpp" | Stale doc |
| `EmbeddingProvider.java` Javadoc | References "LlamaCppBackend" | Stale doc |
| `createWithAutoDiscovery()` GGUF branch | Discovers model, sets `backendId="llama"` | Dead code — init throws |
| `EmbeddingModelResolver.java` | Discovers `nomic-embed-text-v1.5*.gguf` filenames | Dead code — no provider |
| `EmbeddingFingerprint.java` GGUF fallback | Falls back to `EmbeddingModelResolver.discover()` | Returns null in practice |
| `EnvRegistry.EMBED_BACKEND` `"llama"` value | Documented as valid | References removed functionality |
| `IndexingLoop.java` comment (line 59) | "future: LlamaCpp CPU mode" | Outdated comment |
| `EMBED_GPU_LAYERS` / `Ai.embedGpuLayers` | Integer layer count config | Vestigial — ONNX is all-or-nothing |

### GPU config inconsistency (updated with GGUF finding)

All five ONNX subsystems use all-or-nothing GPU. Only LLM (llama-server)
supports partial layer offloading. But config tells a different story:

| Subsystem | GPU toggle | Env var | Actual GPU model |
|-----------|-----------|---------|-----------------|
| SPLADE | `boolean gpuEnabled` | `SPLADE_GPU_ENABLED=true` | ONNX all-or-nothing |
| Reranker | `boolean gpuEnabled` | `RERANK_GPU_ENABLED=true` | ONNX all-or-nothing |
| Chunk reranker | `boolean gpuEnabled` | `RERANK_CHUNKS_GPU_ENABLED=true` | ONNX all-or-nothing |
| BGE-M3 | `boolean gpuEnabled` | `BGE_M3_GPU_ENABLED=true` | ONNX all-or-nothing |
| **Embedding** | **`int gpuLayers`** | **`EMBED_GPU_LAYERS=N`** | **ONNX all-or-nothing** (converts `N > 0` to boolean) |
| LLM | `int gpuLayers` | `GPU_LAYERS=N` | llama.cpp partial offloading |

Embedding's `int gpuLayers` is a relic of the llama.cpp era. The ONNX backend
converts it to `boolean gpuEnabled = (gpuLayers > 0)` in `OnnxEmbeddingProvider.create()`.
A user setting `EMBED_GPU_LAYERS=16` gets identical behavior to `EMBED_GPU_LAYERS=1`.

Additional gaps found during investigation:

- **BGE-M3 bypasses ConfigStore entirely** — `BgeM3Config.fromEnv()` reads
  `EnvRegistry` directly, not via `ConfigStore`/`ResolvedConfig`. Settings
  don't flow through the snapshot. Same root cause as the reranker gap fixed
  in Phase 1b, but for a different subsystem.
- **ONNX embedding lacks `gpuDeviceId` config** — hardcoded to device 0 in
  `OnnxEmbeddingProvider.create()`, unlike SPLADE/reranker/BGE-M3 which all
  expose device ID.
- **Two LLM GPU layer fields** — `Ai.gpuLayers` (shared, also embedding
  fallback) and `Llm.llmGpuLayers` (dedicated). The shared field couples
  LLM and embedding GPU config.

### What was done

**Dead code cleanup (done in this session):** Removed `EmbeddingModelResolver`
(class + test), GGUF fallback branch in `EmbeddingService.createWithAutoDiscovery()`,
`discoverModelPath()`, stale Javadocs in `EmbeddingService`, `EmbeddingProvider`,
`IndexingLoop`, `GrpcIngestService`. Fixed `resolvedBackendId` default from
`"llama"` to `"onnx"`. Removed `"llama"` from `EMBED_BACKEND` docs. Removed
GGUF fallback in `EmbeddingFingerprint.discoverModelPath()`.

### Phase 8: Remaining stale artifacts

- [x] Fix `modules/indexer-worker/README.md` stale reference to
  "native LlamaCppBackend"
- [ ] Remove `InferenceConfig.embeddingModelPath` vestigial field — verified
  unused by `LlamaServerOps` (zero grep hits). Deferred: touches Brain module
  (`InferenceConfig`, `InferenceLifecycleManager`, test) — separate cleanup

### Phase 9: Embedding config sub-record (investigation complete 2026-03-20)

**Investigation findings (verified against source code):**

1. **`OnnxEmbeddingProvider.create()` converts `gpuLayers` to boolean.**
   Line 33: `boolean gpuEnabled = gpuLayers > 0`. The encoder receives
   `boolean gpuEnabled` and `int gpuDeviceId` — never the layer count.
   The integer is preserved only for observability reporting.

2. **Blast radius of changing `embedGpuLayers` is frontend-safe.**
   Proto `int32 embed_gpu_layers` (field 108) → `WorkerStatusMapper` →
   `WorkerOperationalView.int embedGpuLayers` → JSON schema `integer`.
   Frontend does NOT reference `embedGpuLayers` — no TS type, no component.

3. **BGE-M3 is production-capable but inactive** (no model ships). Bypasses
   ConfigStore entirely. Defer migration to when BGE-M3 ships.

4. **No deprecated-alias pattern in the codebase.** No env var mapping exists.

5. **`embedDimensionOverride` (`justsearch.embed.dimension`) is dead** — no
   code reads it. Remove during sub-record extraction.

6. **`embedModelName` (`justsearch.embed.model`) is llama-server-only** — read
   only by `InferenceConfig.java` (Brain module) for GGUF model filename
   resolution. Dead for the ONNX embedding path.

7. **`LocalIntentTranslatorConfig` is a redundant intermediary.** The ONNX
   provider extracts only 3 of ~15 fields (`modelPath`, `contextLength`,
   `gpuLayers`). The encoder also reads `gpuMemMb` directly from ConfigStore,
   bypassing the intermediary entirely. The `gpuDeviceId` gap exists because
   `LocalIntentTranslatorConfig` has no such field.

**The `Ai` record currently has 9 embed-related fields scattered as top-level
parameters (out of 30 total). Every other ONNX subsystem has a clean sub-record.
Embedding should too.**

**Proposed `Ai.Embedding` sub-record** (following `Ai.Splade` pattern):

```java
public record Embedding(
    boolean enabled,       // was aiEmbedEnabled
    Path modelPath,        // was embedModel (key: justsearch.model.path)
    String backend,        // was embedBackend
    int gpuLayers,         // was embedGpuLayers (keeps int; ONNX converts > 0)
    int gpuDeviceId,       // NEW — replaces hardcoded 0
    int gpuMemMb,          // was embedGpuMemMb
    int contextLength) {}  // was embedContextLength
```

**Dropped fields:**
- `embedDimensionOverride` — dead, no consumers
- `embedModelName` — llama-server-only, dead for ONNX; keep on `Ai` if
  `InferenceConfig` still needs it, or remove with the Brain GGUF cleanup

**Files to modify:**

| File | Change |
|------|--------|
| `ResolvedConfig.java` | Add `Embedding` sub-record inside `Ai`; remove 7-8 flat embed fields from `Ai`; add `Embedding embedding()` accessor; update `toWorkerSnapshot()` putPath |
| `ResolvedConfigBuilder.java` | Extract `buildEmbedding()` method (same pattern as `buildReranker()`); wire into `buildAi()` |
| `EnvRegistry.java` | Add `EMBED_GPU_DEVICE_ID` entry |
| `EmbeddingService.java` | `resolveEmbedGpuLayers()` → read `ai.embedding().gpuLayers()`; `resolveEmbedBackend()` → read `ai.embedding().backend()` |
| `OnnxEmbeddingEncoder.java` | `resolveEmbedGpuMemMb()` → read `ai.embedding().gpuMemMb()` |
| `OnnxEmbeddingProvider.java` | Read `gpuDeviceId` from config instead of hardcoding 0; simplify by reading `Ai.Embedding` directly via ConfigStore |
| `AiClientConfig.java` | Read `ai.embedding().enabled()` instead of `ai.aiEmbedEnabled()` |
| `WorkerSpawner.java` | Read `ai.embedding().modelPath()` instead of `ai.embedModel()` |
| `InferenceConfig.java` | Either read `ai.embedding().modelPath()` or keep reading from `Ai` if `embedModelName` stays there |
| `status-response.schema.json` | Auto-regenerated by schema test |
| Callers using `ai.embedGpuLayers()` etc. | Mechanical rename to `ai.embedding().gpuLayers()` etc. |

**What stays on `Ai`:**
- `aiClassifyEnabled` — not embedding, parallel feature flag
- `gpuLayers` — the shared LLM/embedding fallback knob (used by `InferenceConfig` and as embedding fallback)
- All LLM-specific fields

- [x] Investigation complete — all open questions answered with code evidence
- [x] Implementation decision: extract `Ai.Embedding` sub-record following
  established pattern; add `gpuDeviceId`; drop dead fields
- [x] Extract `Ai.Embedding` sub-record and migrate all consumers
  - `ResolvedConfig.Ai` reduced from 31 → 25 constructor params
  - `Ai.Embedding(enabled, modelPath, backend, gpuLayers, gpuDeviceId, gpuMemMb, contextLength)`
  - All consumers migrated: `EmbeddingService`, `OnnxEmbeddingEncoder`,
    `AiClientConfig`, `WorkerSpawner`, `toWorkerSnapshot()`
  - `embedModelName` kept on `Ai` (still read by `InferenceConfig` in Brain module)
- [x] Add `EMBED_GPU_DEVICE_ID` to `EnvRegistry` + wire through `OnnxEmbeddingProvider`
  (replaces hardcoded `0`)
- [x] Remove dead `embedDimensionOverride` field (zero consumers)
- [x] Document GPU toggle note in `environment-variables.md`

## Deferred items

| Item | Rationale |
|------|-----------|
| **LLM status endpoint split** (MEDIUM #4) | The split between `/api/status` (lightweight, polled) and `/api/debug/effective-config` (heavy, diagnostic) is intentional. No code change needed — diagnostic workflow docs should mention all three endpoints. |
| **Disambiguation observability** (LOW #8) | Rule-based NER clustering with no user-facing failure mode. No model dependencies. If disambiguation gains model-based clustering, revisit. |

## Acceptance criteria

- [x] All subsystems in the audit matrix have definitive answers (no question marks)
- [x] Root cause identified and documented
- [x] Gaps classified with rationale
- [x] Phase 1a complete — `OnnxModelDiscovery` migrated to `ResolvedPathResolver`; `modelsDir`
  overrides apply to reranker
- [x] Phase 1b complete — explicit reranker path in `ResolvedConfig.Ai`, propagated via snapshot;
  `RerankerConfig.fromEnv()` reads from `ConfigStore`
- [x] Phase 2 complete — reranker model path + VDU pending count in `/api/status`
- [x] Phase 3 complete — `ortCuda` → `rerankerOrtCuda` rename
- [x] Phase 4 complete — regression tests for `modelsDir` override, snapshot round-trip, and
  ConfigStore precedence
- [x] Phase 5 complete — `NerModelDiscovery` migrated to `ResolvedPathResolver`
- [x] Phase 6 complete — `max_seq_len` default divergence fixed (512 → 8192)
- [x] Phase 7 complete — `EMBED_BACKEND` validation warning + env vars docs
- [x] Phase 8 complete — README fixed; `InferenceConfig.embeddingModelPath` removal
  deferred (verified dead but touches Brain module)
- [x] Phase 9 complete — `Ai.Embedding` sub-record extracted, `gpuDeviceId` added,
  dead `embedDimensionOverride` removed
- [x] `/api/status` + `/api/debug/effective-config` sufficient to diagnose any subsystem's
  runtime state without log spelunking

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 57 days at audit time.

