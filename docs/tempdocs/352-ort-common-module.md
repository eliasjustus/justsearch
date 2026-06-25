---
title: "352: Extract ort-common Module"
type: tempdoc
status: done
created: 2026-03-24
updated: 2026-03-24
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 352: Extract ort-common Module

## Goal

Shared ORT infrastructure (`OrtSessionFactory`, `GpuSessionConfig`,
`OrtCudaStatus`, `OnnxSessionCache`, `OrtCudaHelper`) lives in a
dedicated `ort-common` module with correct semantic ownership. The
`reranker` module contains only reranker-specific code. The
`CrossEncoderReranker` DLL pre-flight gap is fixed.

## Motivation

Tempdoc 349 unified GPU session creation across all five ORT consumers
but placed `OrtSessionFactory` and `GpuSessionConfig` in `reranker`
due to the dependency arrow (`worker-core → reranker`). This works but
is semantically wrong — every developer who encounters GPU session
creation infrastructure in a reranker package will be confused.

Investigation (2026-03-24, tempdoc 349 item 14) confirmed the split is
justified at scale: 5 shared ORT infrastructure classes with 21+
external call sites across `worker-core` and `worker-services`, none
of which are reranker-related. The split also unblocks a pre-existing
bug where `CrossEncoderReranker` skips DLL preloading.

## Current State

### Classes currently in `reranker` that are shared ORT infra

| Class | Purpose | External consumers |
|-------|---------|-------------------|
| `OrtSessionFactory` | Centralized GPU session creation with production options | 5 encoders in `worker-core`, 1 test |
| `GpuSessionConfig` | Record: `(gpuDeviceId, gpuMemLimitBytes)` | 5 encoders in `worker-core` |
| `OrtCudaStatus` | Structured CUDA observability record | 10 call sites in `worker-core` + `worker-services` |
| `OnnxSessionCache` | CPU session graph optimization cache | 0 external (used by `CrossEncoderReranker`, `CitationScorer` internally) |

### Class currently in `worker-core` that is shared ORT infra

| Class | Purpose | Consumers |
|-------|---------|-----------|
| `OrtCudaHelper` | Windows DLL preloading, native path resolution, DLL checks | 5 files in `worker-core`; **not accessible from `reranker`** (dependency goes wrong way) |

### Classes that stay in `reranker` (6 files)

`CrossEncoderReranker`, `RerankerTokenizer`, `RerankerConfig`,
`CitationScorer`, `CitationScorerConfig`, `WorkerModelDiscovery`

### Pre-existing DLL gap in `CrossEncoderReranker`

The reranker calls its own `checkMissingDlls()` (advisory, checks 5
DLLs) but skips `OrtCudaHelper.prepareCudaDependencies()` (preloads
DLLs via `System.load()` + copies to ORT temp dir). It works in
practice because another encoder in the same Worker JVM always
preloads first. But if the reranker were the first GPU session
created (e.g., in a future standalone reranker service), GPU session
creation would fail on Windows.

Once `OrtCudaHelper` is in `ort-common`, the reranker can call
`prepareCudaDependencies` directly and retire its own
`checkMissingDlls` in favor of `OrtCudaHelper.checkMissingCudaDlls`.

## Work Items

### Module creation

1. [x] **Create `modules/ort-common/` with `build.gradle.kts`.**
   Package: `io.justsearch.ort`.

   ```kotlin
   plugins {
     `java-library`
     id("jvm-test-suite")
     id("conventions.jvm-base")
   }

   dependencies {
     // Expose ORT types without forcing a runtime variant choice.
     // Consumers (worker-core) use onnxruntime-gpu; others use CPU.
     compileOnlyApi(libs.onnxruntime)

     implementation(project(":modules:configuration"))
     implementation(libs.slf4j.api)

     // Jackson for OnnxSessionCache model manifest reading
     implementation(libs.jackson.databind)
   }
   ```

   Register in `settings.gradle.kts` in the library layer
   (before `reranker`).

2. [x] **Move classes to `io.justsearch.ort`.**

   | Class | From | To |
   |-------|------|----|
   | `OrtSessionFactory` | `io.justsearch.reranker` | `io.justsearch.ort` |
   | `GpuSessionConfig` | `io.justsearch.reranker` | `io.justsearch.ort` |
   | `OrtCudaStatus` | `io.justsearch.reranker` | `io.justsearch.ort` |
   | `OnnxSessionCache` | `io.justsearch.reranker` | `io.justsearch.ort` |
   | `OrtCudaHelper` | `io.justsearch.indexerworker.ort` | `io.justsearch.ort` |

3. [x] **Update imports across all consumers (~36 call sites).**

   | Module | Files affected | Classes imported |
   |--------|---------------|-----------------|
   | `worker-core` | `SpladeEncoder`, `OnnxEmbeddingEncoder`, `BgeM3Encoder`, `BertNerInference`, `ModelVerifier` | `OrtSessionFactory`, `GpuSessionConfig`, `OrtCudaHelper` |
   | `worker-core` | `GpuDiagnosticSuppliers`, `EmbeddingService` | `OrtCudaStatus` |
   | `worker-services` | `RagContextOps`, `IndexStatusOps`, `GrpcSearchService`, `GrpcIngestService` | `OrtCudaStatus` |
   | `reranker` | `CrossEncoderReranker`, `CitationScorer` | `OrtSessionFactory`, `GpuSessionConfig`, `OrtCudaStatus`, `OnnxSessionCache` |

4. [x] **Update module dependencies.**

   - `ort-common`: new module (deps listed above)
   - `reranker`: add `api(project(":modules:ort-common"))`; remove
     direct `compileOnlyApi(libs.onnxruntime)` (comes transitively)
   - `worker-core`: add `api(project(":modules:ort-common"))` with
     `exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")`
     (same pattern as existing reranker exclude). Remove `OrtCudaHelper`
     from source tree.
   - `worker-services`, `app-services`: no change needed (get
     `ort-common` transitively via `reranker`)

### Fix DLL gap

5. [x] **Unify DLL handling in `CrossEncoderReranker`.**
   Replace `checkMissingDlls(nativePath)` (reranker's own method)
   with `OrtCudaHelper.checkMissingCudaDlls(nativePath)` and add
   `OrtCudaHelper.prepareCudaDependencies(nativePath)` call before
   session creation. Delete the now-redundant `checkMissingDlls`
   method and `REQUIRED_CUDA_DLLS` constant from
   `CrossEncoderReranker`.

### Test relocation

6. [x] **Relocate or update test files.**

   | Test | Current location | Action |
   |------|-----------------|--------|
   | `OrtCudaSelfCheckTest` | `reranker` test suite | Tests both `OrtCudaStatus` (moving) and `CrossEncoderReranker.checkMissingDlls` (being deleted). Split: `OrtCudaStatus` factory-method tests → `ort-common` test suite; `CrossEncoderReranker` DLL check tests → update to use `OrtCudaHelper` or delete (covered by `SpladeEncoderNativePathTest`). |
   | `OrtSessionFactoryModelTest` | `worker-core` test suite | Update import from `io.justsearch.reranker.OrtSessionFactory` → `io.justsearch.ort.OrtSessionFactory`. Stays in `worker-core` (needs model files on disk). |
   | `SpladeEncoderNativePathTest` | `worker-core` test suite | Update import from `io.justsearch.indexerworker.ort.OrtCudaHelper` → `io.justsearch.ort.OrtCudaHelper`. Stays in `worker-core` (tests encoder-level path resolution). |

## ORT Variant Exclusion Strategy

The project manages two ORT JAR variants:
- `libs.onnxruntime` — CPU-only (used by `app-services` at runtime)
- `libs.onnxruntime.gpu` — GPU (used by `worker-core` / `indexer-worker`)

Current pattern: `reranker` uses `compileOnlyApi(libs.onnxruntime)` so
ORT types are visible at compile time but no runtime JAR is forced.
`worker-core` excludes the CPU JAR from `reranker` and substitutes GPU.

After split: `ort-common` uses `compileOnlyApi(libs.onnxruntime)` (same
pattern). `worker-core` must exclude CPU ORT from **both** `ort-common`
and `reranker`. Verify with:
```
./gradlew.bat :modules:worker-core:dependencies --configuration runtimeClasspath
```
Ensure exactly one ORT JAR (`onnxruntime_gpu`) appears.

If the exclude-per-consumer pattern becomes fragile, consider a
`configurations.configureEach` rule in `worker-core` that globally
excludes CPU ORT:
```kotlin
configurations.configureEach {
  exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
}
```

## Verification Plan

1. `./gradlew.bat build -x test` — compilation across all modules
2. `./gradlew.bat :modules:worker-core:dependencies --configuration runtimeClasspath` — verify single ORT JAR
3. `./gradlew.bat :modules:ort-common:test` — new module's tests pass
4. `./gradlew.bat :modules:reranker:test` — reranker tests pass after class removal
5. `./gradlew.bat :modules:worker-core:test` — worker-core tests pass with new imports
6. `./gradlew.bat :modules:worker-services:test` — downstream tests pass
7. `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=models/onnx/ner/model.onnx` — verification harness still works

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| ORT variant exclusion chain breaks (both CPU + GPU JARs on classpath) | Medium | High — `LinkageError` at native load | Verify with `dependencies --configuration runtimeClasspath`; consider global exclude |
| Merge conflicts with parallel agents working in `worker-core` / `worker-services` | Context-dependent | Medium | Coordinate via user; use worktree |
| `OnnxSessionCache` has hidden dependency on `reranker`-internal class | Low | Low | Check all imports before moving |
| `OrtCudaSelfCheckTest` split loses coverage | Low | Low | Verify test counts before/after |

## Dependencies

- **349 (Testable ORT Session Creation):** Created the factory and
  placed it in `reranker` as a pragmatic first step. This tempdoc
  completes the module boundary cleanup.
