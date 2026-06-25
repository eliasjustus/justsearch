---
title: "327: Embedding Config Pattern Alignment"
type: tempdoc
status: done
created: 2026-03-20
depends-on: [286]
---

# 327: Embedding Config Pattern Alignment

## Problem

Tempdoc 286 (Phases 7-9) cleaned up dead GGUF embedding code, extracted an
`Ai.Embedding` sub-record, and added `gpuDeviceId`. But embedding config still
diverges from the established pattern used by SPLADE, Reranker, NER, and
CitationScorer in five ways. These aren't cosmetic — they mean embedding is
harder to configure, debug, and extend than every other model subsystem.

## The established pattern

Every other ONNX model subsystem follows this structure:

```
ResolvedConfig.Ai.Splade          -- sub-record in centralized config
SpladeConfig.fromEnv()            -- domain config record, reads ConfigStore first
SpladeModelDiscovery.resolve()    -- model discovery via ResolvedPathResolver
SpladeEncoder                     -- runtime consumer, receives SpladeConfig
```

Key properties:
- `fromEnv()` is a single factory that reads all settings from ConfigStore,
  falls back to EnvRegistry, and returns an immutable config record
- `Boolean enabled` (nullable) supports three states: `true`/`false`/`null`
  (auto-enable when model discovered at standard location)
- GPU settings are self-contained within the sub-record — no cross-field fallbacks
- `fromEnv()` consolidates all config reads in one place — no scattered static
  methods across multiple classes

## The five gaps

### 1. No `EmbeddingConfig` record (most significant)

Embedding has no equivalent of `SpladeConfig.fromEnv()`. Config reads are
scattered across four static methods in two classes:

| Method | Class | What it reads |
|--------|-------|--------------|
| `resolveEmbedBackend()` | `EmbeddingService` | `ai.embedding().backend()` |
| `resolveEmbedGpuLayers()` | `EmbeddingService` | `ai.embedding().gpuLayers()` + `ai.gpuLayers()` fallback |
| `resolveEmbedContextLength()` | `EmbeddingService` | `ai.embedding().contextLength()` |
| `resolveEmbedGpuMemMb()` | `OnnxEmbeddingEncoder` | `ai.embedding().gpuMemMb()` |
| `resolveEmbedGpuDeviceId()` | `OnnxEmbeddingProvider` | `ai.embedding().gpuDeviceId()` |

The fix: create `EmbeddingConfig` record with `fromEnv()` that consolidates all
five reads. The scattered methods become one-line delegations or are inlined.

### 2. `enabled` is `boolean`, not `Boolean` (nullable)

`Ai.Embedding.enabled` is `boolean` (default `true`). SPLADE, Reranker, and NER
use `Boolean` (nullable) for three-state auto-enable logic:
- `true` — force enabled
- `false` — force disabled
- `null` — auto-enable when model is discovered at a standard location

Embedding's `createWithAutoDiscovery()` already has implicit auto-enable logic
(returns `null` when no model found), but there's no way to explicitly force-enable
or let `enabled=null` trigger auto-discovery. A user who sets `AI_EMBED_ENABLED=true`
but has no model gets `null` (disabled) with no warning — the flag is silently
ignored.

### 3. `int gpuLayers` instead of `boolean gpuEnabled`

Embedding uses `int gpuLayers` (0=CPU, >0=GPU). Every other ONNX subsystem uses
`boolean gpuEnabled`. The ONNX provider converts `gpuLayers > 0` to boolean
immediately — the integer value is meaningless for ONNX Runtime. This was a
llama.cpp artifact; the GGUF path is now deleted.

Changing to `boolean gpuEnabled` requires:
- New `EMBED_GPU_ENABLED` env var
- Keep `EMBED_GPU_LAYERS` as accepted alias (`> 0` → `true`)
- Update `Ai.Embedding` record field
- Status pipeline stays `int` (proto field 108 unchanged — report `gpuEnabled ? 1 : 0`)

### 4. `embedModelName` on parent `Ai` instead of sub-record

Every other subsystem's model fields are inside its sub-record. `embedModelName`
(`justsearch.embed.model`) sits on `Ai` because `InferenceConfig` (Brain module)
reads it. But `InferenceConfig.embeddingModelPath` is vestigial — verified in 286:
`LlamaServerOps` has zero references to it. Once that dead field is removed,
`embedModelName` has no consumers and can be deleted entirely.

### 5. Shared `gpuLayers` fallback coupling

`resolveEmbedGpuLayers()` falls back to `ai().gpuLayers()` (the shared LLM knob)
when `embedding().gpuLayers()` is -1 (not set). No other subsystem has this
cross-field dependency. SPLADE GPU is SPLADE GPU; reranker GPU is reranker GPU.
Embedding GPU depends on the LLM GPU setting.

This coupling dates from when embedding ran via llama-server (same process, shared
GPU allocation). With ONNX embedding in a separate session, the coupling is no
longer meaningful — a user setting `GPU_LAYERS=32` for their LLM should not
silently enable GPU for embedding.

## Implementation plan

### Phase 1: Create `EmbeddingConfig` record

Create `modules/worker-core/.../embed/EmbeddingConfig.java` following the
`SpladeConfig`/`RerankerConfig` pattern:

```java
public record EmbeddingConfig(
    Boolean enabled,              // nullable — three-state auto-enable
    Path modelPath,               // from discovery
    String backend,               // "auto" or "onnx"
    boolean gpuEnabled,           // was int gpuLayers > 0
    int gpuDeviceId,              // default 0
    int gpuMemMb,                 // default 2048
    int contextLength) {          // default 2048

  public static final EmbeddingConfig DISABLED =
      new EmbeddingConfig(false, null, "auto", false, 0, 2048, 2048);

  public static EmbeddingConfig fromEnv() {
    ConfigStore store = ConfigStore.globalOrNull();
    ResolvedConfig.Ai ai = store != null ? store.get().ai() : null;
    // ... reads ai.embedding().* first, EnvRegistry fallback
  }

  public boolean isReady() { return enabled != null && enabled && modelPath != null; }
}
```

- [x] Create `EmbeddingConfig` record with `fromEnv()`
- [x] Migrate `EmbeddingService.createWithAutoDiscovery()` to use `EmbeddingConfig`
- [x] Remove scattered `resolveEmbed*()` static methods from `EmbeddingService`,
  `OnnxEmbeddingEncoder`, `OnnxEmbeddingProvider`, and `EmbeddingFingerprint`
- [x] Wire `EmbeddingConfig` into `KnowledgeServer` and `IndexingLoop` init
  (same pattern as `NerConfig`/`SpladeConfig` — caller creates config, passes
  it to the service factory)
- [x] `EmbeddingService` public constructor requires `(Path, EmbeddingConfig)`;
  path-only constructor is package-private. `embeddingConfig` field is `final`.
- [x] No-arg `createWithAutoDiscovery()` removed — all callers provide explicit
  `EmbeddingConfig`

### Phase 2: Align `enabled` to `Boolean` (nullable, three-state)

- [x] Change `Ai.Embedding.enabled` from `boolean` to `Boolean`
- [x] Update `ResolvedConfigBuilder.buildEmbedding()` to use `resolveNullableBoolean()`
- [x] Implement auto-enable logic in `EmbeddingConfig.fromEnv()`:
  `null` + model discovered at standard location → auto-enable
- [x] Align with `createWithAutoDiscovery()` behavior (currently returns null
  when no model — should respect explicit `enabled=true` vs auto)

### Phase 3: Migrate `gpuLayers` to `gpuEnabled` + remove fallback coupling

- [x] Add `EMBED_GPU_ENABLED` to `EnvRegistry`
- [x] Change `Ai.Embedding.gpuLayers` → `Ai.Embedding.gpuEnabled` (boolean)
- [x] `EmbeddingConfig.fromEnv()` reads `EMBED_GPU_ENABLED` first; if not set,
  accepts `EMBED_GPU_LAYERS > 0` as alias (backward compat, no breakage)
- [x] Remove the `ai().gpuLayers()` fallback from GPU resolution — embedding
  GPU is self-contained
- [x] Update status pipeline: `embedGpuLayers` proto field stays `int32`,
  report `gpuEnabled ? 1 : 0` (backward compatible)

### Phase 4: Clean up `embedModelName` and `InferenceConfig`

- [x] Remove `InferenceConfig.embeddingModelPath` vestigial field + resolution
  logic (Brain module)
- [x] Remove `embedModelName` from `Ai` record (no remaining consumers)
- [x] Remove `EnvRegistry.EMBED_MODEL` if no other consumers
- [x] Remove `resolveString("justsearch.embed.model", "")` from `buildAi()`

## Relationship to other work

- **286 Phase 9** extracted `Ai.Embedding` sub-record — this tempdoc completes
  the alignment by adding the domain config record and fixing the remaining type
  and coupling differences.
- **326** activates the NER pipeline — `NerConfig.fromEnv()` is one of the
  reference implementations this tempdoc follows.
- The `EmbeddingConfig` record is analogous to `SpladeConfig`, `RerankerConfig`,
  and `NerConfig` — all follow the same pattern.

## Acceptance criteria

- [x] `EmbeddingConfig.fromEnv()` exists and consolidates all embedding config
  reads in one place
- [x] `Ai.Embedding.enabled` uses nullable `Boolean` with three-state auto-enable
- [x] `Ai.Embedding` uses `boolean gpuEnabled` (not `int gpuLayers`)
- [x] No scattered `resolveEmbed*()` methods in `EmbeddingService`,
  `OnnxEmbeddingEncoder`, or `OnnxEmbeddingProvider`
- [x] Embedding GPU config is self-contained — no fallback to `ai().gpuLayers()`
- [x] `embedModelName` and `InferenceConfig.embeddingModelPath` removed
- [x] All existing embedding tests pass (pre-existing failures verified on main)
- [x] `EMBED_GPU_LAYERS > 0` still works as backward-compatible alias for
  `EMBED_GPU_ENABLED=true`
- [x] `EmbeddingConfig` is explicit at all construction sites — `KnowledgeServer`,
  `IndexingLoop`, and test call sites all pass config, no hidden `fromEnv()` calls
- [x] `gpuDeviceId` and `gpuMemLimitBytes` added to `LocalIntentTranslatorConfig`
  so GPU settings flow through the config chain (no direct ConfigStore reads in
  `OnnxEmbeddingProvider` or `OnnxEmbeddingEncoder`)

## Phase 5: Indirect cleanup

Ripple effects from Phases 1-4 that need resolution.

### 5a. Documentation (high confidence)

- [x] `docs/reference/configuration/environment-variables.md`: remove
  `JUSTSEARCH_EMBED_MODEL`, add `JUSTSEARCH_EMBED_GPU_ENABLED`, mark
  `JUSTSEARCH_EMBED_GPU_LAYERS` as legacy alias, remove `GPU_LAYERS` fallback
  paragraph
- [x] `indexing.proto` field 108 comment: update from "Number of GPU layers
  offloaded" to reflect boolean semantics (0=CPU, 1=GPU)
- [x] Remove `embeddingModelPath` from `OnlineAiRuntimeIntrospection.RuntimeInfo`
  (always null, never serialized to any API response)

### 5b. Dead UI embedding model picker (needs investigation)

`BrainRuntimeSection.tsx` shows a "Embedding Engine" `ModelSlotCard` that
writes to `UiSettings.embeddingModelPath` → `justsearch.model.path` sysprop.
But `EmbeddingConfig.fromEnv()` uses `EmbeddingOnnxModelDiscovery` (reads
`EMBED_ONNX_MODEL_PATH`), not `justsearch.model.path`. The UI control is a
no-op.

Investigation found: `justsearch.model.path` is propagated by `WorkerSpawner`
to the Worker as `JUSTSEARCH_MODEL_PATH`, but the Worker's
`EmbeddingOnnxModelDiscovery` ignores it (uses `EMBED_ONNX_MODEL_PATH` only).
The UI control is a no-op for ONNX embedding. This predates tempdoc 327 and
is out of scope — flagged for separate cleanup.

### 5c. `OnlineAiRuntimeIntrospection.embeddingModelPath` always null

We pass `null` from `OnlineAiServiceImpl` since `InferenceConfig` no longer
has the field. The record still carries the field; the `/api/ai/runtime`
endpoint serializes it as `null`.

Investigation found: `RuntimeInfo` is never serialized to any API response.
Frontend reads `embeddingModelPath` only from `/api/settings/v2`. Field
removed from the record — zero API or frontend impact.
