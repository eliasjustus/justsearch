---
title: "337: Unified GPU Policy"
type: tempdoc
status: done
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 337: Unified GPU Policy

## Problem

### Auto-detection fragility

Each ONNX model has independent GPU configuration: separate env
vars (`EMBED_GPU_ENABLED`, `SPLADE_GPU_ENABLED`, `NER_GPU_ENABLED`,
etc.), separate auto-detection in `applyHeadlessEvalContract()`.

This caused two bugs in tempdoc 334:
1. `EMBED_GPU_ENABLED` was missing from auto-detection — embedding
   ran CPU-only (30x slower) while SPLADE auto-detected fine.
2. NER GPU had to be manually added to auto-detection after
   implementing GPU support.

Each new GPU-capable model requires adding another `if` block.
The pattern invites omission bugs.

### Policy enforcement gap

`POLICY_GPU_ACCELERATION_ENABLED` is an enterprise policy veto gate
(admin forces CPU-only). Currently enforced for:
- llama-server: 3 enforcement layers (REST, service, process launch)
- Reranker: 1 enforcement layer (`KnowledgeHttpApiAdapter`)

**NOT enforced for**: embedding, SPLADE, NER, BGE-M3. Setting
`POLICY_GPU_ACCELERATION_ENABLED=false` leaves those models using
GPU if their individual flags are set.

## Design

### Resolution model

```
per_model = JUSTSEARCH_EMBED_GPU_ENABLED  (explicitly set, or absent)
master    = JUSTSEARCH_GPU_ENABLED        (auto-set when CUDA detected)
policy    = JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED  (admin veto)

effective = resolve_gpu_enabled(per_model, master) && policy
```

Three-priority resolution for `resolve_gpu_enabled`:
```java
Optional<String> perModel = EnvRegistry.EMBED_GPU_ENABLED.get();
if (perModel.isPresent()) return Boolean.parseBoolean(perModel.get());
return EnvRegistry.GPU_ENABLED.getBoolean(false);  // master switch
```

Per-model flag wins if explicitly set (even if `false`). Otherwise
falls back to master switch. Policy gate is an AND — admin can
veto everything regardless.

### Master switch scope

The master switch applies to **indexing-time models**:
- Embedding (OnnxEmbeddingEncoder)
- SPLADE (SpladeEncoder)
- NER (BertNerInference)
- BGE-M3 (BgeM3Encoder)

**Reranker and chunk reranker remain opt-in.** They run on the
search path (query-time, latency-sensitive). Auto-enabling them
could cause VRAM contention during search while backfill is
running — the scenario the `shouldUseGpu`/`isMainGpuActive`
arbitration exists to prevent.

### Auto-detection

`applyHeadlessEvalContract()` sets one var instead of three:
```kotlin
if (!ortNativePath.isNullOrBlank()) {
    if (!envValues.containsKey("JUSTSEARCH_GPU_ENABLED")) {
        envValues["JUSTSEARCH_GPU_ENABLED"] = "true"
    }
}
```

For production (Tauri shell), `ResolvedConfigBuilder` resolves the
master switch and propagates it via the config snapshot to the
Worker. Both paths converge in the encoder `fromEnv()` methods.

### Runtime callback (already unified)

All encoders already share `() -> !signalBus.isMainGpuActive()`.
No changes needed — the runtime cooperative yield is correct.

### BGE-M3 note

BGE-M3 has no sub-record in `ResolvedConfig` — its config reads
only from `EnvRegistry`. The master switch is also in `EnvRegistry`,
so BGE-M3 reads it the same way. The config snapshot pipeline not
carrying BGE-M3 config is pre-existing and unrelated to this work.

## Implementation

- [x] 1. Add `GPU_ENABLED` to `EnvRegistry` (master switch)
- [x] 2. Add `GpuConfigHelper` in `modules/configuration` — `resolveGpuEnabled()` + `isPolicyAllowed()`
- [x] 3. Update `EmbeddingConfig.fromEnv()` — three-priority + legacy layers + policy gate
- [x] 4. Update `SpladeConfig.fromEnv()` — three-priority + policy gate
- [x] 5. Update `NerConfig.fromEnv()` — three-priority + policy gate
- [x] 6. Update `BgeM3Config.fromEnv()` — three-priority + policy gate
- [x] 7. Update `ResolvedConfigBuilder` — `resolveModelGpuEnabled()` + `resolveMasterGpuEnabled()` for snapshot
- [x] 8. Update `applyHeadlessEvalContract()` — one `GPU_ENABLED` auto-detect, remove 3 per-model blocks
- [x] 9. Unit tests for `GpuConfigHelper` (6 tests: per-model wins, master fallback, policy veto)
- [x] 10. Verify: compilation clean + tests pass

## Verification

Live backend verification (2026-03-22, `GPU_ENABLED=true`):
- Master switch propagated to all indexing encoders:
  `nerGpuEnabled: true`, `embedGpuLayers: 1`
- GPU sessions not created (CUDA DLLs not present) — correct
  fallback to CPU
- Worker healthy (`worker.state: READY`)
- Unit tests: `GpuConfigHelperTest` 6/6 pass (per-model override,
  master fallback, policy veto)
- Not verified (requires CUDA DLLs): actual GPU session creation,
  per-model opt-out at runtime, policy veto at runtime. Logic
  covered by unit tests.

## Related

- **286** (Runtime Config Audit): noted GPU config inconsistency
  (`int gpuLayers` vs `boolean gpuEnabled`). Finding still open.
- **327** (Embedding Config Alignment): completed, unified
  embedding config to `boolean gpuEnabled`. Did not unify across
  all models.
- **329** (Head-Worker Config Pipeline): completed, audited config
  forwarding but not GPU policy.
- **334** items 1, 16: bugs caused by per-model auto-detection
  fragility.
