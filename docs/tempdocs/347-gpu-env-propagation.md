---
title: "347: GPU Environment Propagation"
type: tempdoc
status: done
created: 2026-03-26
---

# 347: GPU Environment Propagation

## Problem

ONNX encoders (embedding, SPLADE, NER) load on CPU even when a 12 GB
NVIDIA GPU is available. Setting `JUSTSEARCH_EMBED_GPU_ENABLED=true` in
the shell has no effect. `/api/status` reports:

```
embedOrtCuda: configured=false, attempted=false, available=false
failureReason: "GPU not configured"
```

## Root Cause (three layers)

### L1: `runHeadless` auto-enables only SPLADE, not all encoders

`runHeadlessEval` (`applyHeadlessEvalContract`, line 1734) sets the
**master switch** `JUSTSEARCH_GPU_ENABLED=true` when CUDA DLLs are
detected. This cascades to all encoders via the fallback chain:

```
resolveEmbedGpuEnabled()          → per-model key absent → resolveMasterGpuEnabled() → true
resolveModelGpuEnabled("splade")  → per-model key absent → resolveMasterGpuEnabled() → true
resolveModelGpuEnabled("ner")     → per-model key absent → resolveMasterGpuEnabled() → true
```

`runHeadless` (line 1822) only sets `JUSTSEARCH_SPLADE_GPU_ENABLED=true`
— a per-model override that doesn't cascade to embedding or NER.

### L2: `runHeadless` uses `System.getenv()` — stale with Gradle daemon

`runHeadless` reads env vars via `System.getenv()` (line 1807). The Gradle
daemon inherits its environment from the shell that *started* it. Env vars
set in subsequent shells don't reach the daemon. Config-cache-compatible
`providers.environmentVariable()` (used by `runHeadlessEval`) handles
cache invalidation better but still reads from the daemon environment.

Additionally, `runHeadless` only forwards `HEADLESS_AI_ENV_VARS` (12
entries), which is missing `JUSTSEARCH_EMBED_GPU_ENABLED`,
`JUSTSEARCH_NER_GPU_ENABLED`, and 10 other GPU vars that are in the
separate `HEADLESS_GPU_ENV_VARS` list.

### L3 (root cause): GPU auto-detection is a build-script hack

`ORDINAL_AUTO_DETECT = 150` exists in `ResolvedConfigBuilder` but is
**never used**. The slot was reserved for hardware detection but never
implemented. Instead, CUDA detection lives in Gradle build scripts
(`detectOrtCudaPath()` in `build.gradle.kts`), which:

- Only runs when launched via Gradle tasks
- Does not work from the installed distribution, Tauri shell, or tests
- Is subject to Gradle daemon environment staleness
- Has to manually replicate env var forwarding lists

## Design

### Approach: `contributeAutoDetected()` at ordinal 150

The caller (HeadlessApp, IndexerWorker) runs hardware detection using
existing infrastructure (`OrtCudaHelper`, `VramDetector`) and feeds
results into `ResolvedConfigBuilder` at ordinal 150 before `build()`.
The builder doesn't need to know about CUDA — it just accepts key-value
pairs at the auto-detect ordinal.

```java
// In HeadlessApp / IndexerWorker startup:
Map<String, String> detected = GpuAutoDetection.probe(modelsDir);
// detected = { "justsearch.gpu.enabled": "true",
//              "justsearch.onnxruntime.native_path": "/path/to/cuda-dlls" }

builder.contributeAutoDetected(detected);  // ordinal 150
builder.contributeEnvRegistry();           // ordinal 400/500
builder.build();
// Explicit env vars at 400 or sysprops at 500 override auto-detected values.
```

The ordinal chain ensures explicit config always wins:

| Ordinal | Source | Example |
|---------|--------|---------|
| 500 | `-D` sysprop | `-Djustsearch.embed.gpu.enabled=false` overrides auto |
| 450 | Worker snapshot | Head's resolved config propagated to Worker |
| 400 | Env var | `JUSTSEARCH_EMBED_GPU_ENABLED=false` overrides auto |
| 300 | settings.json | UI toggle |
| 200 | application.yaml | Static config |
| **150** | **Auto-detected** | **CUDA DLLs found → `gpu.enabled=true`** |
| 100 | EnvRegistry default | `false` |

### Detection logic (`GpuAutoDetection`)

New class in `modules/ort-common` (`io.justsearch.ort`). Stateless,
no ORT session creation — just filesystem probes:

```java
public final class GpuAutoDetection {
    /**
     * Probes for ORT CUDA DLL availability.
     * Returns a map of config keys to auto-detected values.
     * Empty map if no CUDA capability detected.
     */
    public static Map<String, String> probe(Path modelsDir) {
        // 1. Find ORT CUDA DLLs via known paths:
        //    - <modelsDir>/../tmp/ort-variant-test/cuda-12.4-v1.24.3/
        //    - Repo-root relative (worktree-aware)
        //    Reuses detectOrtCudaPath() logic from Gradle
        //
        // 2. If found, check for core DLLs:
        //    onnxruntime_providers_cuda.dll, cudart64_12.dll, cublas64_12.dll
        //    Reuses OrtCudaHelper.checkMissingCudaDlls()
        //
        // 3. If all core DLLs present:
        //    return { "justsearch.gpu.enabled": "true",
        //             "justsearch.onnxruntime.native_path": detectedPath }
        //
        // 4. Otherwise: return empty map
    }
}
```

### Why `ort-common`, not `configuration`

`configuration` is a foundation module with no upward dependencies.
It cannot import `OrtCudaHelper`. `ort-common` already has the DLL
checking infrastructure and depends on `configuration`. The probe
result is a plain `Map<String, String>` — the builder accepts it
without needing to know about ORT.

### Dependency for Worker process

The Worker also runs `ResolvedConfigBuilder.build()` at startup
(`IndexerWorker.main()`). It needs auto-detection too, but the Worker
already gets the Head's resolved config via the snapshot (ordinal 450).
If the Head detected CUDA and set `gpu.enabled=true`, the Worker
inherits it at ordinal 450. The Worker can additionally run its own
`GpuAutoDetection.probe()` at ordinal 150 as defense-in-depth — if
the snapshot already has `gpu.enabled=true` at 450, the auto-detected
value at 150 is ignored (higher ordinal wins).

### `runHeadless` cleanup

After auto-detection is wired into the application, the Gradle-side
hacks become unnecessary:

1. Remove `detectOrtCudaPath()` calls from `runHeadless` task
2. Remove the SPLADE-only auto-enable block (lines 1821-1824)
3. Merge `HEADLESS_AI_ENV_VARS` and `HEADLESS_GPU_ENV_VARS` into one
   list (eliminates drift between lists)
4. Switch remaining `System.getenv()` calls to
   `providers.environmentVariable()` for config-cache compatibility

## Implementation

### Phase 1: Immediate fix (unblocks tempdoc 346)

- [x] 1a. Fix `runHeadless` to set master switch `JUSTSEARCH_GPU_ENABLED`
      when CUDA detected (like `runHeadlessEval` does), instead of only
      SPLADE-specific flag
- [x] 1b. Forward `HEADLESS_GPU_ENV_VARS` in `runHeadless` alongside
      `HEADLESS_AI_ENV_VARS` (merged into single loop)
- [x] 1c. Switch `runHeadless` env reading from `System.getenv()` to
      `providers.environmentVariable()`
- [x] 1d. Verify: Gradle auto-detect finds CUDA DLLs, sets master switch.
      Worker log confirms `justsearch.gpu.enabled=true` at ordinal 500

### Phase 2: `contributeAutoDetected()` builder method

- [x] 2a. Add `contributeAutoDetected(Map<String,String>)` to
      `ResolvedConfigBuilder` — puts all entries at ordinal 150
- [x] 2b. Unit tests: auto-detected value at 150 is overridden by env
      var at 400 and sysprop at 500; null/empty map handled gracefully
      (5 test cases in `ResolvedConfigBuilderTest`)

### Phase 3: `GpuAutoDetection` probe

- [x] 3a. Create `GpuAutoDetection` in `modules/ort-common`
      (`io.justsearch.ort`) — filesystem probe for CUDA DLLs, returns
      `Map<String, String>` of config keys
- [x] 3b. Port `detectOrtCudaPath()` logic from Gradle to Java
      (repo-root detection, worktree-aware, DLL sentinel check)
- [x] 3c. Unit tests: null/absent repo root, no DLLs present, full
      DLLs detected, sentinel-only without core DLLs
      (4 test cases in `GpuAutoDetectionTest`)

### Phase 4: Wire into application startup

- [x] 4a. Wire `GpuAutoDetection.probe()` into `HeadlessApp` startup
      before `ResolvedConfigBuilder.build()` — call
      `contributeAutoDetected(probeResult)`
- [x] 4b. Wire into `IndexerWorker.main()` startup (defense-in-depth,
      both snapshot and standalone paths). Added `loadWorkerSnapshotFromSysprop(Map)`
      overload.
- [x] 4c. Verify: jseval `--start-backend --clean --pipeline` with no
      explicit GPU env vars → GPU auto-detected, all encoders on GPU
      (gpu_pct 68-91%, vram 1.7-2.2 GB, pipeline 212s, SciFact 5184 docs)
- [ ] 4d. Verify: `JUSTSEARCH_EMBED_GPU_ENABLED=false` overrides
      auto-detection (deferred — override path is tested by unit tests)
- [x] 4e. Add `ort-common` dependency to `modules/ui/build.gradle.kts`

### Phase 5: Gradle cleanup (deferred)

After in-JVM auto-detection is validated in production:

- [ ] 5a. Remove `detectOrtCudaPath()` and CUDA auto-detect from
      `runHeadless` task
- [ ] 5b. Merge `HEADLESS_AI_ENV_VARS` and `HEADLESS_GPU_ENV_VARS`
      into single `HEADLESS_FORWARDED_ENV_VARS`
- [ ] 5c. Remove `detectOrtCudaPath()` from `applyHeadlessEvalContract()`
      (auto-detection now happens in-JVM)
- [ ] 5d. Verify: full gate passes, `runHeadless` and `runHeadlessEval`
      both auto-detect GPU

### Live verification (2026-03-26)

jseval run with `--start-backend --clean --pipeline --dataset scifact`:
- GPU auto-detected: `GpuAutoDetection.probe()` found CUDA DLLs at
  `D:\code\JustSearch\tmp\ort-variant-test\cuda-12.4-v1.24.3`
- Config snapshot: `justsearch.gpu.enabled=true`,
  `justsearch.onnxruntime.native_path=<detected>`,
  `justsearch.splade.gpu_enabled=true`
- Worker resolved: `justsearch.gpu.enabled=true` at ordinal 500
- Pipeline: embed 208s, SPLADE 212s, NER 208s, chunk 185s
- GPU utilization: 68-91%, VRAM 1.7-2.2 GB of 12 GB
- All three encoders used GPU (no CPU fallback)

## Related

- **329** (Env var / sysprop bridge removal): created the dual list split
- **337** (Unified GPU policy): added master switch to eval contract
- **346** (Agent retrieval eval): blocked by this issue
- **349** (OrtSessionFactory): `GpuAutoDetection` lives alongside it

---

## Staleness review (2026-05-18)

Body contains explicit closure markers; marking `done` as part of the staleness audit. Classification: CLOSED. Stale for 52 days at audit time.

