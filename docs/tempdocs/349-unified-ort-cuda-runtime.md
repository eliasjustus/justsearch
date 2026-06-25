---
title: "349: Testable ORT Session Creation"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-24
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 349: Testable ORT Session Creation

## Goal

ORT session creation is a first-class, testable operation. "Does this
model load with our production CUDA settings?" is answerable from a
single Gradle task or integration test, using the same JVM, same ORT
JAR, and same session options as production — without starting the
full application stack.

A Python smoke test is a secondary convenience for "is this ONNX file
corrupt?" checks. It explicitly does NOT validate production GPU
compatibility.

## Root Cause

**ORT session creation is coupled to encoder business logic.** Each
encoder (`SpladeEncoder`, `OnnxEmbeddingEncoder`, `BertNerInference`,
`BgeM3Encoder`) has its own `tryCreateGpuSession()` method that mixes:
- Session options (arena strategy, memory patterns, CUDA graphs)
- CUDA provider configuration (device ID, arena limit, extend strategy)
- Fallback logic (FP16 fails → retry FP32)
- Status reporting (`OrtCudaStatus`)
- Logging and error handling

These methods are 40-60 lines each and can't be invoked without
constructing the full encoder (which needs config objects, environment
instances, suppliers, etc.). The only way to test "does model X load
with our SPLADE GPU settings?" is to start the Head process, spawn
the Worker, ingest documents to trigger lazy session creation, and
check `/api/status`.

This means:
- Model validation takes ~60s instead of ~5s
- A model can pass a Python GPU check and fail in Java (different
  session options, different ORT version)
- No unit test coverage for session creation — it's tested only
  through end-to-end pipeline runs

## Motivation

Tempdoc 334 Phase 7: verified FP16 SPLADE model on CPU via Python
(5s), but needed 4 full pipeline restarts (~4 min each) to verify
GPU loading. The Python test passed (model loads on CPU with default
options) but couldn't replicate the production CUDA session config
that previously caused FP16 failures.

## Current State (verified 2026-03-23)

### Encoders with `tryCreateGpuSession()`

| Class | File | Lines |
|-------|------|-------|
| `SpladeEncoder` | `modules/worker-core/.../splade/SpladeEncoder.java` | 911–977 |
| `OnnxEmbeddingEncoder` | `modules/worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java` | 597–662 |
| `BgeM3Encoder` | `modules/worker-core/.../bgem3/BgeM3Encoder.java` | 407–462 |
| `BertNerInference` | `modules/worker-core/.../ner/BertNerInference.java` | 405–432 |

### Session options are identical across all four encoders

```java
cudaOpts.add("gpu_mem_limit", String.valueOf(memLimit));
cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
cudaOpts.add("enable_cuda_graph", "0");
opts.addCUDA(cudaOpts);
opts.setInterOpNumThreads(1);
opts.addConfigEntry("session.intra_op.allow_spinning", "0");
opts.addConfigEntry("session.use_device_allocator_for_initializers", "1");
opts.setMemoryPatternOptimization(false);
```

Per-run arena shrinkage is set on `RunOptions`, not `SessionOptions`:
```java
gpuRunOptions.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "gpu:0");
```

### Capability gaps in `BertNerInference`

`BertNerInference` is missing several features present in the other three:

| Feature | Splade | Embedding | BgeM3 | NER |
|---------|--------|-----------|-------|-----|
| DLL pre-flight check | yes | yes | yes | **no** |
| FP16→FP32 fallback | yes | yes | yes | **no** |
| Per-run arena shrinkage | yes | yes | yes | **no** |
| `OrtCudaStatus` tracking | yes | yes | yes | **no** |
| GPU retry after 60s | yes | yes | no | **no** |

These gaps are incidental — `BertNerInference` was added later and
didn't replicate all the hardening. The factory extraction (item 1)
will close them automatically.

### Existing infrastructure (do not reimplement)

> **Post-352 update:** `OnnxSessionCache`, `OrtCudaHelper`, and
> `OrtCudaStatus` moved to `modules/ort-common` (`io.justsearch.ort`)
> via tempdoc 352. Locations below reflect the final state.

- **`OnnxSessionCache`** (`modules/ort-common/`) — CPU session creation
  with per-machine graph optimization caching. All encoders already
  use this for CPU sessions. GPU sessions bypass it (optimized graph
  may vary by CUDA graph shape).
- **`OrtCudaHelper`** (`modules/ort-common/`) — DLL
  preloading (`prepareCudaDependencies`), native path resolution
  (`resolveOrtNativePath`), DLL presence check (`checkMissingCudaDlls`).
- **`OrtCudaStatus`** (`modules/ort-common/`) — structured status record.
  Factory methods: `notConfigured()`, `ready()`, `missingDlls()`,
  `providerFailed()`, `released()`.
- **`ModelManifest`** (`modules/worker-core/.../ort/`) — CPU/GPU model
  file selection via `model_manifest.json` or convention
  (`model.onnx` / `model_fp16.onnx`).

### Existing test coverage

- **No tests create a real `OrtSession`** in the standard test suite.
- `SpladeEncoderNativePathTest` — tests `OrtCudaHelper` path resolution.
- `ModelManifestTest` — tests manifest loading and model path selection.
- `OrtCudaSelfCheckTest` — tests `OrtCudaStatus` factory states.
- `SpladeBatchSweepTest` (`@Tag("experiment")`) — creates a real
  session only when model files are present on disk.
- **No custom Gradle tasks** for model verification exist.

## Work Items

### Primary: extract session creation

1. [x] **`OrtSessionFactory` in `worker-core` (`io.justsearch.indexerworker.ort`).**
   Extracts GPU session creation from all four encoders into a shared
   factory. Takes a model path + `GpuSessionConfig` and returns an
   `OrtSession`. The factory encodes the production session options
   (which are already identical across all encoders):

   | Setting | Value | Rationale |
   |---------|-------|-----------|
   | `arena_extend_strategy` | `kSameAsRequested` | Exact allocation; two sessions share GPU |
   | `enable_cuda_graph` | `0` | Allows arena shrinkage between calls |
   | `use_device_allocator_for_initializers` | `1` | Weights bypass arena (tempdoc 311) |
   | `setMemoryPatternOptimization` | `false` | Variable-length sequences |
   | `setInterOpNumThreads` | `1` | Reduce CPU contention |
   | `allow_spinning` | `0` | Reduce CPU contention |

   The factory also:
   - Calls `OrtCudaHelper.prepareCudaDependencies()` and
     `checkMissingCudaDlls()` (closing the BertNerInference gap)
   - Returns a result object with the session + metadata (model path
     used, whether fallback occurred, missing DLLs list)
   - Does NOT own fallback logic or `OrtCudaStatus` — callers retain
     those responsibilities

   `GpuSessionConfig` is a record, not an enum — it carries the
   per-encoder parameters that actually differ:

   ```java
   public record GpuSessionConfig(
       int gpuDeviceId,
       long gpuMemLimitBytes,
       Path ortNativePath
   ) {}
   ```

   Session options are **not parameterized** — they are hardcoded in
   the factory because they are identical across all encoders today.
   If they diverge in the future, parameterize then, not now.

2. [x] **`GpuRunOptionsFactory` (or static method on `OrtSessionFactory`).**
   Creates `RunOptions` with per-call arena shrinkage
   (`memory.enable_memory_arena_shrinkage = gpu:0`). Currently
   present in `SpladeEncoder`, `OnnxEmbeddingEncoder`, `BgeM3Encoder`
   but missing from `BertNerInference`. Extracting it ensures all
   encoders get shrinkage.

3. [x] **Refactor all four encoders to use `OrtSessionFactory`.**
   Replace `tryCreateGpuSession()` in `SpladeEncoder`,
   `OnnxEmbeddingEncoder`, `BgeM3Encoder`, and `BertNerInference`
   with factory delegation. Each encoder:
   - Constructs a `GpuSessionConfig` from its existing config
   - Calls `OrtSessionFactory.createGpuSession(env, modelPath, config)`
   - Retains its own FP16→FP32 fallback logic (try GPU model path,
     catch → retry with FP32 path)
   - Retains its own `OrtCudaStatus` updates
   - `BertNerInference` gains DLL pre-flight, FP16 fallback, and
     arena shrinkage for free

### Secondary: verification harness

4. [x] **`./gradlew.bat :modules:worker-core:verifyModel` task.**
   A `JavaExec` task using `sourceSets["main"].runtimeClasspath`
   (same pattern as `runHeadless` in `modules/ui/build.gradle.kts`).
   Takes `-Pmodel=<path>` and `-Pprofile=splade-gpu`. Uses
   `OrtSessionFactory` with the specified config. Runs a dummy
   input through the session. Reports: loaded successfully, output
   shape/dtype, inference latency.

   **Native library setup:** Passes `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`
   as an env var, auto-detected via `detectOrtCudaPath()` (same helper
   used by `runHeadless`). JVM gets `--enable-native-access=ALL-UNNAMED`
   from the `jvm-base` convention plugin automatically.

   **Dummy input:** All models accept `input_ids` + `attention_mask`
   (INT64, `[1, seq_len]`). A minimal input (`[CLS] test [SEP]`,
   `seq_len=3`) suffices for shape/dtype validation. `token_type_ids`
   is auto-detected from `session.getInputNames()`.

   **VRAM reporting: dropped.** ORT Java API (1.24.3) does not expose
   per-session VRAM allocation. The project's VRAM measurement uses
   NVML via Panama FFI (`NvmlService` in `ai-bridge`), which reports
   device-level totals, not per-session. Measuring VRAM delta
   (before/after session creation) is unreliable due to lazy
   allocation and shared device memory. Report inference latency
   and output shape instead.

   This runs in the same JVM with the same ORT JAR and the same
   session options as production. Zero version mismatch risk.

5. [x] **Integration tests for model loading.** `@Tag("experiment")`
   tests (excluded from default runs, same as `SpladeBatchSweepTest`).
   For each model in `models/`, loads with production session options
   (CPU) and verifies output shape against expected dimensions:

   | Model | Expected output shape |
   |-------|-----------------------|
   | SPLADE (MLM) | `[1, seq_len, 30522]` |
   | SPLADE (PRESPARSE) | `output_idx: [1, nnz]`, `output_weights: [1, nnz]` |
   | Embedding | `[1, seq_len, 768]` |
   | NER | `[1, seq_len, 9]` |

   Each test uses `assumeTrue(modelFile.exists())` to skip gracefully
   when model files are absent (LFS not checked out, CI without models).
   GPU tests require explicit opt-in via env var.

### Tertiary: Python smoke test (convenience only)

6. [x] **`scripts/models/verify-model.py` — CPU-only ONNX check.**
   Loads model on CPU with default ORT options. Checks output
   shape/dtype. Usage: quick "is this file valid ONNX?" after model
   conversion. **Explicitly documented as NOT validating production
   GPU compatibility** — use the Gradle task for that.

## Design Decisions

### Record, not enum, for session config

The original proposal used a `SessionProfile` enum. Exploration
revealed the session *options* are identical across all encoders —
the only things that differ are `gpuDeviceId`, `gpuMemLimitBytes`,
and `ortNativePath`. An enum would either carry redundant identical
fields or force artificial distinctions. A simple record carries
only what actually varies.

### Fallback stays in encoders, not in the factory

FP16→FP32 fallback involves catching `OrtException`, knowing two
model paths, and updating `OrtCudaStatus`. This is encoder-level
policy. The factory is a pure session-creation function: model path
in, session out, exception on failure. Encoders call it twice if
needed (once for FP16, once for FP32 on failure).

### `OnnxSessionCache` is CPU-only — not extended for GPU

`OnnxSessionCache` caches graph-optimized model files on disk. GPU
sessions use different graph optimizations depending on CUDA graph
shapes, so caching the optimized graph is not safe. The factory
creates GPU sessions directly via `env.createSession()`.

## Implementation Notes (verified 2026-03-23)

### Model input tensor specs

All four model types accept the same input schema:
- `input_ids`: INT64, `[batch, seq_len]` — always required
- `attention_mask`: INT64, `[batch, seq_len]` — always required
- `token_type_ids`: INT64, `[batch, seq_len]` — auto-detected via
  `session.getInputNames().contains("token_type_ids")`. Present in
  BERT-based models, absent in Gemma/DistilBERT/XLM-RoBERTa.

This uniformity means a single dummy-input constructor works for the
verification harness across all model types.

### Gradle task pattern

Follow the `runHeadless` pattern (`modules/ui/build.gradle.kts:1791`):
- `JavaExec` task with `sourceSets["main"].runtimeClasspath`
- `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` passed as environment variable
- Auto-detection via `detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)`
- Convention plugin adds `--enable-native-access=ALL-UNNAMED` automatically

The `verifyModel` task lives in `worker-core` (not `indexer-worker`)
because the factory and ORT dependency live there. `worker-core` already
has `api(libs.onnxruntime.gpu)` as a dependency.

### Test infrastructure

- Global convention: `excludeTags("evidence", "experiment")` in all
  `Test` tasks (`JvmBaseConventionsPlugin.kt:63`)
- Model-loading tests should use `@Tag("experiment")` — consistent
  with `SpladeBatchSweepTest` and excluded from CI by default
- `assumeTrue(modelFile.exists())` for graceful skip when LFS models
  are absent
- Max heap 384m from convention; sufficient for CPU model loading

### VRAM reporting not feasible via ORT API

ORT Java API 1.24.3 has `endProfiling()` for trace-based profiling
but no per-session memory query. VRAM is measured via NVML
(`NvmlService` in `ai-bridge`), which reports device-wide totals.
Before/after delta is unreliable (lazy allocation, shared memory,
other processes). Dropped from item 4 scope.

## What this tempdoc does NOT do

- **Shared CUDA runtime directory.** Not needed — the Java
  verification harness uses the same CUDA runtime as production
  (`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`).
- **Python ORT version alignment.** Not needed — the authoritative
  test runs in Java. Python is convenience-only for format checks.
- **Python CUDA EP testing.** Actively discouraged — it gives false
  confidence because it can't replicate Java session options.

### Completing unification: CrossEncoderReranker

7. [x] **Move `OrtSessionFactory` + `GpuSessionConfig` to `modules/reranker`.**
   The dependency arrow is `worker-core → reranker`, so the factory
   must live in `reranker` for both modules to use it. Moved the two
   files, updated imports in the four `worker-core` encoders, and
   refactored `CrossEncoderReranker.tryCreateGpuSession()` to delegate
   to the factory. DLL pre-flight checks and `prepareCudaDependencies`
   are now the caller's responsibility (the factory is pure session
   options). `GpuSessionConfig` no longer carries `ortNativePath`
   since DLL handling is not a session-creation concern.

   The reranker also gained `setMemoryPatternOptimization(false)` which
   it was previously missing — an incidental bug fix.

### Polish: regressions and rough edges from implementation

8. [x] **Fix DLL warning log regression.** The encoder DLL-check
   pattern uses `.stream().findAny().ifPresent()` which discards the
   list of missing DLLs from the log message. Restore the original
   pattern: `if (!missing.isEmpty()) log.info("...", path, missing)`.

9. [x] **Fix ModelVerifier resource management.** The `token_type_ids`
   tensor is created outside the try-with-resources and closed in a
   `finally` block, while `input_ids`/`attention_mask` are in the TWR
   *and* in the `inputs` map (double-close). Restructure so all
   tensors are managed through the map with a single `finally` close.

10. [x] **`applyProductionSessionOptions` parity.** The method sets
    `setMemoryPatternOptimization(false)`, but production CPU sessions
    (in encoder constructors) do NOT set this. Either: (a) remove it
    from `applyProductionSessionOptions` and only apply it in
    `createGpuSession`, or (b) add it to the encoder CPU session
    constructors for true parity. Investigate which is correct by
    checking whether memory pattern optimization benefits or harms
    the CPU path with variable-length sequences.

11. [x] **Extract FP16→FP32 fallback into factory helper.** The
    try-catch-retry block is copy-pasted identically in four encoders.
    Add `createGpuSessionWithFallback(env, gpuPath, cpuPath, config)`
    that returns a result record with the session and the path used.
    CrossEncoderReranker doesn't need this (single model path).

12. [x] **Move `detectOrtCudaPath` to build-logic.** Currently
    duplicated in `modules/ui/build.gradle.kts` and
    `modules/worker-core/build.gradle.kts`. Extract to a shared Kotlin
    function in `build-logic/` so CUDA version changes happen once.

### Remaining: hardening

13. [x] **Unit test for factory session options via `getConfigEntries()`.**
    ORT Java API 1.24.3 exposes `SessionOptions.getConfigEntries()`
    which returns all entries added via `addConfigEntry()`. This makes
    two of the factory's options verifiable without a GPU:
    - `session.intra_op.allow_spinning = 0` (via `applyProductionSessionOptions`)
    - `session.use_device_allocator_for_initializers = 1` (via `createGpuSession`)

    The four native-setter options (`setInterOpNumThreads`,
    `setMemoryPatternOptimization`, CUDA arena strategy, CUDA graphs)
    remain untestable — they go through JNI and are not tracked in the
    Java-side config map. Partial coverage is better than none.

    Approach: extract a package-private `configureGpuSessionOptions`
    method that populates a caller-supplied `SessionOptions`, then test
    it by inspecting `getConfigEntries()`.

14. **Deferred to tempdoc 352: Extract `ort-common` module.**
    Move `OrtSessionFactory`, `GpuSessionConfig`, `OrtCudaStatus`,
    `OnnxSessionCache`, and `OrtCudaHelper` to a dedicated
    `ort-common` module. Also fixes pre-existing
    `CrossEncoderReranker` DLL preloading gap. See tempdoc 352 for
    full investigation, work items, and risk assessment.

## Dependencies

- **334 (Single-Pass Enrichment):** Discovered the validation gap.
  FP16 SPLADE model passed Python CPU check, needed 4 Java pipeline
  restarts for GPU verification.
- **348 (Model Provenance):** Build scripts can invoke the Gradle
  verification task as a post-build step.
