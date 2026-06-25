---
title: "283: Worktree GPU Runtime Path Unification"
type: tempdoc
status: done
created: 2026-03-13
updated: 2026-03-14
---

## Implementation progress

### Item 1: SPLADE model discovery — DONE (code + unit test)

- [x] `SpladeModelDiscovery.resolve()` now checks `<modelRoot>/splade/naver-splade-v3` in the
  model-root loop (after `<modelRoot>/onnx/splade`), so shared `modelsDir` is sufficient
- [x] Unit test `resolvesDevLayoutFromModelsDir` added and passing
- [x] **Live verified**: SPLADE active with 476/2965 docs completed — confirms dev-layout discovery

### Item 2: ORT native path in ResolvedConfig — DONE (code, no live verification yet)

- [x] `EnvRegistry.ORT_NATIVE_PATH` added (`justsearch.onnxruntime.native_path` /
  `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`)
- [x] `ResolvedConfig.Paths.ortNativePath` field added
- [x] `ResolvedConfigBuilder.buildPaths()` resolves the new key
- [x] `toWorkerSnapshot()` serializes the new path to worker config snapshot
- [x] `OrtCudaHelper.resolveOrtNativePath()` checks `ConfigStore` first, then legacy fallback
- [x] `WorkerSpawner` forwards `justsearch.onnxruntime.native_path` to worker process
- [x] `applyHeadlessEvalContract()` supports `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`
- [x] **Live verified**: `justsearch.onnxruntime.native_path` visible in `/api/effective-config`
  with candidates (jvm_arg ordinal 500, env_var ordinal 400). Previously verified via
  `runHeadlessEval` with `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` set (see evidence in earlier section)

### Item 3: First-class GPU eval launcher profile — DONE

- [x] `applyHeadlessEvalContract()` now forwards 5 GPU env vars: `JUSTSEARCH_GPU_LAYERS`,
  `JUSTSEARCH_EMBED_GPU_LAYERS`, `JUSTSEARCH_SPLADE_GPU_ENABLED`, `JUSTSEARCH_SPLADE_GPU_DEVICE_ID`,
  `JUSTSEARCH_SPLADE_GPU_MEM_MB`
- [x] `EnvRegistry` now registers SPLADE GPU vars (`SPLADE_GPU_ENABLED`, `SPLADE_GPU_DEVICE_ID`,
  `SPLADE_GPU_MEM_MB`) as first-class enum constants
- No changes needed to `eval-session.mjs` (has `envVars` passthrough) or `WorkerSpawner` (blanket
  `JUSTSEARCH_*` env forwarding)

### Item 4: Stage ORT runtime assets under managed path — ADDRESSED BY ITEM 2

Investigation found that the managed path convention `<DATA_DIR>/native-bin/onnxruntime/variants/`
exists and works for production (AI Pack installs via `PackInstallOps`). For dev/eval worktree
runs, Item 2's explicit `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` override is simpler and more reliable
than auto-staging DLLs. No additional staging mechanism needed.

### Item 5: Head-side GPU diagnostics — DONE

- [x] `EmbeddingService` now exposes `resolvedBackendId()`, `gpuLayers()`, and `getOrtCudaStatus()`
- [x] `OnnxEmbeddingBackend` made public with `encoder()` accessor for ORT CUDA status
- [x] Proto fields added: `splade_ort_cuda_*` (97-101), `embed_ort_cuda_*` (102-106),
  `embed_backend` (107), `embed_gpu_layers` (108), `splade_model_path` (109)
- [x] `IndexStatusOps` populates per-subsystem ORT CUDA status + embedding backend/gpu layers
- [x] `GrpcIngestService` exposes setters for SPLADE/embedding status suppliers
- [x] `KnowledgeServerGrpcWiring` returns `ingestService` in result for deferred wiring
- [x] `KnowledgeServer.createGrpcServer()` wires SPLADE + embedding status suppliers
- [x] `WorkerOperationalView` includes `spladeOrtCuda`, `embedOrtCuda`, `embedBackend`,
  `embedGpuLayers`, `spladeModelPath`
- [x] `WorkerStatusMapper` maps new proto fields to view
- [x] `SpladeEncoder.resolvedModelPath()` getter added for diagnostics
- [x] Schema baseline regenerated
- [x] **Live verified**: `/api/status` returns new fields:
  ```json
  {"embedBackend": "llama", "embedGpuLayers": 17,
   "spladeOrtCuda": null, "embedOrtCuda": null,
   "spladeModelPath": ""}
  ```
  `spladeOrtCuda`/`embedOrtCuda` are null as expected (SPLADE GPU not enabled, embedding uses
  llama backend). `embedBackend` and `embedGpuLayers` correctly report runtime state.
  `spladeModelPath` reports the resolved SPLADE model directory (empty when SPLADE not initialized).

### Verification — all pass

**Unit tests**: `SpladeModelDiscoveryTest` 3/3 (including new `resolvesDevLayoutFromModelsDir`),
`SpladeConfigTest` all passing, `configuration` module all passing.

**Live verification** (runHeadlessEval with `JUSTSEARCH_MODELS_DIR` + `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`):

1. **SPLADE discovered via shared modelsDir** — CONFIRMED:
   ```
   SPLADE model: found at dev layout D:\code\JustSearch\models\splade\naver-splade-v3
   SPLADE enabled: model=D:\code\JustSearch\models\splade\naver-splade-v3, maxSeqLen=512
   SpladeEncoder initialized: cpuModel=model.onnx, gpuModel=model_fp16.onnx, vocab=30522 tokens
   ```

2. **ORT native path in worker snapshot** — CONFIRMED:
   ```json
   "justsearch.onnxruntime.native_path": "D:\\code\\JustSearch\\models\\splade\\naver-splade-v3"
   "justsearch.models.dir": "D:\\code\\JustSearch\\models"
   ```

3. **ORT native path resolved and propagated** — CONFIRMED:
   - Head: `Config: justsearch.onnxruntime.native_path=D:/code/JustSearch/models/splade/naver-splade-v3 (jvm_arg, ordinal=500)`
   - WorkerSpawner: `Forwarding system property justsearch.onnxruntime.native_path=...`
   - WorkerSpawner: `Forwarding environment variable JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=...`
   - Worker: `ortNativePath=D:\code\JustSearch\models\splade\naver-splade-v3` (in both OnnxEmbeddingEncoder and SpladeEncoder init logs)

4. **Acceptance criterion B** — CONFIRMED: Both resolve from shared `modelsDir` without extra overrides:
   - ONNX embedding: `found at D:\code\JustSearch\models\onnx\embedding`
   - SPLADE: `found at dev layout D:\code\JustSearch\models\splade\naver-splade-v3`

---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and tests before promotion.

# 283: Worktree GPU Runtime Path Unification

## Purpose

This tempdoc exists to explain one repo-level problem that has now shown up clearly during Stage 3A
verification:

Why do isolated worktree eval runs fail to pick up GPU-backed retrieval/runtime behavior even when
the machine, models, and CUDA-capable ONNX Runtime assets are present?

This is not just a "set one more env var" issue.

It is a configuration-contract problem involving:

1. path ownership
2. model discovery
3. native runtime discovery
4. GPU intent propagation
5. Head-to-Worker config propagation

The goal of this tempdoc is to describe the whole problem cleanly enough that we can fix it
structurally rather than continuing to patch individual launcher cases.

## Summary

The high-level root cause is:

There is no single source of truth for model location, ONNX Runtime native location, and GPU
activation across Head, Worker, and eval launchers.

Instead, the current system mixes:

- partially centralized resolved config
- ad hoc env/sysprop overrides
- repo-root-relative discovery
- data-dir-relative discovery
- subsystem-specific fallback rules

This mostly works in the default desktop/dev flow, because many assumptions line up accidentally.
It breaks down in isolated worktree and eval flows, where:

- `repoRoot` is the worktree
- shared models still live under the canonical repo root
- isolated `dataDir` has no staged runtime assets unless explicitly populated
- the eval launcher deliberately suppresses persisted UI settings

So the same run can simultaneously have:

- correct isolated `dataDir`
- correct isolated `indexBasePath`
- correct shared `modelsDir`
- incorrect or missing SPLADE model discovery
- incorrect or missing ORT CUDA native path
- CPU-only embeddings despite a usable GPU

## Thesis

This is primarily a path-contract and configuration-boundary problem, not a retrieval-quality
problem and not a generic backend-stability problem.

The practical consequence is:

- Stage 3A verification can be blocked or slowed by launcher/runtime-path drift even when the
  Stage 3A ranking code itself is correct
- worktree eval correctness currently depends on manual env knowledge that is not encoded in the
  supported launcher contract

## Scope

This tempdoc is about:

- worker-side ONNX embedding GPU activation
- worker-side SPLADE model and GPU activation
- ONNX Runtime CUDA native DLL discovery
- worktree-safe eval launches
- Head to Worker path propagation for isolated runs

This tempdoc is not primarily about:

- llama-server chat/runtime quality
- general desktop UI settings UX
- Stage 3A ranking behavior itself
- the older "GPU not used" issue for bundled llama-server variants

That older issue was real, but it was a different layer. The current problem is more general:
worker retrieval runtimes do not share one clean configuration contract.

## What was observed

During isolated Stage 3A SciFact smoke verification in a worktree:

- the backend launched correctly with isolated `dataDir` and `indexBasePath`
- the worker snapshot correctly recorded shared `modelsDir`
- dense embeddings loaded in CPU-only mode
- SPLADE was not discovered at all
- no ONNX Runtime CUDA native path was active for the worker

This produced a run that was technically alive but operationally wrong for intended verification:

- slow ingest
- no GPU-accelerated ONNX embeddings
- no SPLADE backfill
- retrieval readiness tail that did not represent the intended configuration

## Why this happens

### 1. Resolved config is only partially the source of truth

The resolved config model is strong but incomplete for this problem.

Today it carries first-class path concepts such as:

- `dataDir`
- `indexBasePath`
- `home`
- `modelsDir`
- `repoRoot`

Relevant code:

- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedPathResolver.java`

But it does not carry an explicit ONNX Runtime native path. That means ORT CUDA remains a
side-channel concern rather than a true resolved path.

So:

- some runtime paths are centralized
- one of the critical GPU runtime paths is not

That is the first structural inconsistency.

### 2. Worker ORT CUDA path resolution is ad hoc

The worker uses `OrtCudaHelper.resolveOrtNativePath(...)` to decide where ONNX Runtime CUDA DLLs
live.

Relevant code:

- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/ort/OrtCudaHelper.java`

That logic currently checks:

- `onnxruntime.native.path`
- fallback `JUSTSEARCH_NATIVE_PATH`
- otherwise the model directory itself

This is not aligned with the resolved path model.

It means ORT CUDA availability depends on one of these being true:

1. the launcher explicitly passed a native path
2. the model directory itself contains ORT CUDA DLLs
3. the worker derived one through a best-effort staging/variant path

That is too fragile for isolated evals.

### 3. WorkerSpawner partially bridges the gap, but only partially

`WorkerSpawner` already contains workaround logic that shows the underlying architectural problem.

Relevant code:

- `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java`

It forwards:

- `justsearch.embed.gpu.layers`
- `justsearch.gpu.layers`
- `onnxruntime.native.path` if explicitly set

Then it tries to recover an ORT native path via:

- `JUSTSEARCH_NATIVE_PATH`
- or a derived `<dataDir>/native-bin/onnxruntime/variants/<variantId>/...`

This is useful operationally, but conceptually it is a patch layer:

- runtime path derivation is happening in the process spawner
- not in the central resolved path contract

That is why worktree behavior depends on exactly how the launcher was assembled.

### 4. ONNX embedding discovery and SPLADE discovery use different mental models

This is the most concrete current-code inconsistency.

#### ONNX embedding discovery

Relevant code:

- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/embed/onnx/EmbeddingOnnxModelDiscovery.java`

It resolves from model roots and checks:

- `<modelRoot>/onnx/embedding`

With a shared `modelsDir` pointing at the canonical repo models, this works fine:

- `d:\code\JustSearch\models\onnx\embedding`

#### SPLADE discovery

Relevant code:

- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/splade/SpladeModelDiscovery.java`

It checks:

- `<modelRoot>/onnx/splade`
- then `<repoRoot>/models/splade/naver-splade-v3`
- then `<baseDir>/models/splade/naver-splade-v3`

But the actual shared model layout in this repo is:

- `d:\code\JustSearch\models\splade\naver-splade-v3`

That means a shared `modelsDir` is enough for embeddings but not enough for SPLADE.

This is the second major structural inconsistency.

### 5. Worktrees separate `repoRoot` from shared assets

Worktree launches intentionally make some paths different:

- isolated worktree root
- isolated backend `dataDir`
- isolated `indexBasePath`

But the actual large shared assets often remain under the canonical repo root:

- shared models
- shared ORT test/staging directories
- some native runtime bundles

This is exactly where discovery-by-convention starts to fail.

If one subsystem assumes:

- "models are under `repoRoot/models`"

and another assumes:

- "models are under resolved `modelsDir`"

then a worktree launch will expose the difference immediately.

### 6. GPU intent is fragmented across subsystems

GPU intent today is not one concept.

Examples:

- embeddings use `justsearch.embed.gpu.layers` with fallback to `justsearch.gpu.layers`
- llama-server runtime activation uses `justsearch.gpu.layers`
- SPLADE uses its own flags:
  - `JUSTSEARCH_SPLADE_GPU_ENABLED`
  - `JUSTSEARCH_SPLADE_GPU_DEVICE_ID`
  - `JUSTSEARCH_SPLADE_GPU_MEM_MB`

Relevant code:

- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/embed/EmbeddingService.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/splade/SpladeConfig.java`
- `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`

This is not inherently wrong, but the launcher has to know about all of it.

Today the worktree-safe eval launcher knows about:

- data dir
- index base path
- repo root
- models dir

but not enough about:

- embed GPU intent
- SPLADE GPU intent
- ORT CUDA native-path intent

So the launch contract is incomplete for GPU-backed retrieval verification.

### 7. Head-side GPU auto-selection is not the right abstraction for this problem

The Head has logic to auto-select a CUDA llama-server variant.

Relevant code:

- `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`

That logic may be useful for chat/runtime activation, but it does not solve worker-side ONNX
retrieval acceleration.

The current problem is not:

- "Head did not choose the right llama-server binary"

It is:

- "Worker retrieval runtimes do not have one unified path and GPU configuration contract"

So we should not confuse those two layers.

## Root cause statement

The root cause is a partially centralized configuration system in which the most important retrieval
runtime inputs are still resolved by subsystem-specific convention.

In one sentence:

JustSearch centralizes some runtime paths, but not all of them, and the missing pieces are exactly
the ones that matter for GPU-backed retrieval in isolated worktree runs.

## Why the issue stays hidden in normal dev flows

The default desktop/dev flow often masks the problem because:

- persisted UI settings may already contain useful paths
- repo root and asset root may be the same place
- local dev layouts often include native/model files in expected conventional locations
- shared dev index/data dirs allow previously staged artifacts to remain available

Once we remove those accidental supports by using:

- isolated `dataDir`
- isolated `indexBasePath`
- `IN_MEMORY` UI settings
- worktree `repoRoot`

the real configuration contract is exposed.

That is why this surfaced during worktree-managed Stage 3A verification rather than earlier.

## Long-term resolution

The correct long-term fix is not one change. It is a small unification program.

### 1. Make ONNX Runtime native path first-class in resolved config

Add a canonical config key for ORT native runtime location, for example:

- `justsearch.onnxruntime.native_path`
- `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`

Then:

- add it to `EnvRegistry`
- add it to `ResolvedConfig`
- add it to the worker snapshot
- make `OrtCudaHelper` prefer that first-class path
- stop treating ORT native path as an ad hoc launcher hint

### 2. Stop overloading `JUSTSEARCH_NATIVE_PATH`

`JUSTSEARCH_NATIVE_PATH` currently acts like a generic native-runtime hint.

That is too ambiguous.

Split responsibilities:

- ORT CUDA path: `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`
- llama/native runtime selection: existing server/variant controls

The more explicit these are, the easier worktree/eval launches become to reason about.

### 3. Make SPLADE discovery support shared `modelsDir`

`SpladeModelDiscovery` should treat the shared models root as a first-class source for the existing
repo layout:

- `<modelsDir>/splade/naver-splade-v3`

not only:

- `<modelsDir>/onnx/splade`

This change is the simplest high-leverage fix because it removes one of the current manual override
requirements immediately.

### 4. Create a first-class GPU eval/worktree launcher profile

Do not force every evaluation script to know the full set of env vars.

Add a supported launcher profile that explicitly sets:

- embed backend
- embed GPU layers
- ORT native path or ORT variant
- SPLADE model path
- SPLADE GPU options

This should live in:

- eval/session wrappers
- direct backend launcher
- Gradle `runHeadlessEval`-style contract

The important rule is:

GPU evals must derive absolute paths from the canonical repo root or an explicit shared asset root,
not from the worktree root.

### 5. Stage ORT native assets in a managed location

The repository already contains usable ORT CUDA directories, but they currently behave more like
experimental assets than supported runtime artifacts.

We should converge on one managed location such as:

- `native-bin/onnxruntime/variants/<variantId>/...`

and then teach the launcher/runtime to stage or reference that deterministically.

At that point:

- `onnxruntimeVariantId`

becomes meaningful as a supported input rather than an incomplete hint.

### 6. Clean up Head-side GPU selection ordering — OUT OF SCOPE

> Not addressed in this tempdoc. Future work if Head-side GPU selection causes issues.

Any Head logic that derives GPU runtime behavior should either:

- run after resolved config is fully built

or

- be fully independent of `ConfigStore`

It should not mix pre-config and post-config assumptions.

### 7. Improve diagnostics — ADDRESSED (Item 5 + gap fix)

> Item 5 addressed the core diagnostic needs. The SPLADE model path gap was resolved separately.
> One minor gap remains: no explicit "GPU not requested" reason string when `gpuLayers == 0`.

Status and snapshot surfaces should expose the actual retrieval-runtime configuration, not force
log spelunking.

We should be able to answer from runtime state alone:

- which embedding backend is active — **YES** (`embedBackend` in `/api/status`)
- whether embedding GPU was requested — **IMPLICIT** (`embedGpuLayers > 0`)
- whether embedding GPU is actually active — **YES** (`embedOrtCuda.available` or `embedGpuLayers`)
- which ORT native path is active — **YES** (`embedOrtCuda.nativePath`, `spladeOrtCuda.nativePath`)
- which SPLADE model path was resolved — **YES** (`spladeModelPath` in `/api/status`)
- whether SPLADE GPU was requested — **IMPLICIT** (`spladeOrtCuda.attempted`)
- whether SPLADE GPU is actually active — **YES** (`spladeOrtCuda.available`)
- why CPU fallback happened — **PARTIAL** (`failureReason` for init errors; no message for "not requested")

## Recommended implementation order

Items 1-5 were the implementation scope. Long-term items 6-7 (above) are out of scope.

1. ~~fix `SpladeModelDiscovery` shared-model-root support~~ — **done** (Item 1)
2. ~~add first-class ORT native-path config and worker-snapshot propagation~~ — **done** (Item 2)
3. ~~add a first-class GPU eval launcher profile~~ — **done** (Item 3)
4. ~~stage ORT runtime assets under a supported managed path~~ — **addressed by Item 2**
5. ~~tighten Head-side GPU selection ordering and diagnostics~~ — **done** (Item 5 + gap fix)

## Acceptance criteria

This tempdoc should be considered addressed when all of the following are true.

### A. Worktree GPU evals no longer require hidden manual knowledge — MET

A supported worktree eval launch can produce:

- isolated `dataDir`
- isolated `indexBasePath`
- shared or explicit `modelsDir`
- discovered SPLADE model
- active ORT native path
- GPU-backed ONNX embeddings

without depending on persisted UI settings or undocumented env combinations.

**Status**: The eval launcher (`applyHeadlessEvalContract`) now forwards all GPU env vars and ORT
native path. SPLADE discovery works from shared `modelsDir`. ORT native path is first-class in
`ResolvedConfig` and propagated via worker snapshot. Agent instructions in `.claude/rules/branch-safety.md`
now document setting `JUSTSEARCH_MODELS_DIR=D:\code\JustSearch\models` when running the dev stack
from a worktree.

### B. Shared `modelsDir` is enough for both embeddings and SPLADE — MET

If `modelsDir` points at the canonical shared repo models directory, both of these resolve without
extra overrides:

- ONNX embedding model
- SPLADE model

**Status**: Live verified. `SpladeModelDiscovery` now checks `<modelRoot>/splade/naver-splade-v3`.

### C. ORT CUDA path is diagnosable from config/status artifacts — MET

The active ORT native path is visible in:

- worker snapshot and/or
- effective config and/or
- `/api/status`

and is no longer only discoverable indirectly from logs.

**Status**: `justsearch.onnxruntime.native_path` in `/api/effective-config`. Per-subsystem ORT CUDA
status in `/api/status` with `nativePath` and `failureReason`. SPLADE model path in `/api/status`
via `spladeModelPath`.

### D. GPU fallback reasons are explicit — PARTIALLY MET

If a GPU-capable run still falls back to CPU, the reason is surfaced directly, for example:

- no GPU intent configured
- no ORT native path
- invalid ORT variant
- SPLADE model missing
- provider init failure

**Status**: `OrtCudaView.failureReason` covers init errors (DLL missing, CUDA unavailable).
`embedBackend`, `embedGpuLayers`, `spladeModelPath` indicate runtime state. Remaining gaps:

1. When GPU is not configured, `spladeOrtCuda` and `embedOrtCuda` are **null** in `/api/status`
   (suppressed by `@JsonInclude(NON_NULL)`). A client cannot distinguish "GPU not configured" from
   "GPU attempted and failed" from "subsystem not yet initialized." This is a semantic ambiguity in
   the API contract, not just a cosmetic gap.
2. `missingDlls` is always `List.of()` for SPLADE and embedding ORT CUDA — only the reranker
   populates a structured DLL list. If SPLADE or embedding CUDA fails due to missing DLLs, the
   reason appears only in the `failureReason` string.
3. When GPU is simply not requested (`gpuLayers=0`), there is no explicit "GPU not requested"
   reason string — it must be inferred from `embedGpuLayers == 0`.

See Item 10 for the fix.

## Relation to other tempdocs

This tempdoc connects several earlier strands:

- `03-config-ssot-precedence.md`
  - configuration precedence was fixed, but not all important runtime paths were promoted into the
    centralized model
- `81-gpu-not-used-investigation.md`
  - earlier GPU work focused on llama-server/runtime packaging; the current issue is broader and
    worker-side
- `271-backend-lifecycle-isolation.md`
  - isolated launches are now better defined, which is what exposed this configuration gap clearly
- `280-stage3-qddf-and-chunk-level-fusion.md`
  - Stage 3A verification surfaced the issue, but the problem is repo-level and should not remain
    trapped inside that ranking tempdoc

## Upstream dependencies (as of 2026-03-14)

### ONNX Runtime — on latest (1.24.3)

JustSearch pins `onnxruntime` / `onnxruntime_gpu` at 1.24.3 (released March 5, 2026). This is the
latest release. No newer version exists; 1.25 is not yet announced (ORT releases quarterly).

- Java CUDA 12 support resolved in ORT 1.18.0 (mid-2024). The `onnxruntime_gpu` Maven artifact
  ships CUDA 12.x binaries. Issue #19960 (Java linked against CUDA 11.x) is long fixed.
- Java native library extraction still uses `%TEMP%/onnxruntime-java<random>/`. No Java equivalent
  of Python's `preload_dlls()` exists. The `copyCudaDllsToOrtTempDir` heuristic remains necessary.
- 1.24.3 is a security/stability patch (heap OOB fixes, GatherND, SkipLayerNorm). No Java, native
  path, or CUDA provider behavioral changes.

### CUDA Toolkit — 13.x released, not yet supported by ORT

NVIDIA released CUDA Toolkit 13.0 (August 2025), 13.1 (December 2025), 13.2 (March 2026).
JustSearch's `OrtCudaHelper.CUDA_DEPENDENCY_DLL_ORDER` hardcodes CUDA 12 DLL names
(`cudart64_12.dll`, `cublas64_12.dll`, etc.). These won't match CUDA 13 DLLs when the time comes.

ORT 1.24.3 still targets CUDA 12.x. CUDA 13 support in ORT is experimental with open issues
(missing kernel symbols, Flash Attention incompatibility on Blackwell/sm_120). No official ORT
release ships CUDA 13 binaries yet. **No immediate action needed**, but when ORT eventually ships
CUDA 13 support, the hardcoded DLL list will need updating.

### cuDNN — 9.20.0, compatible

cuDNN is at 9.20.0 (March 2026). The DLL naming convention uses major version suffix
(`cudnn64_9.dll`), so minor/patch updates within 9.x remain compatible. No action needed until a
hypothetical cuDNN 10.x.

### SPLADE — v3 still latest

SPLADE v3 (`naver/splade-v3`, March 2024) remains the latest production model. No v4 or updated
ONNX export. Discovery code targeting `naver-splade-v3` remains correct. Research has moved toward
alternatives like SPLARE (sparse autoencoders), but these are academic, not production models.

### Implications for Phase 2

No upstream blockers. All Phase 2 items remain valid as written. The CUDA 13 transition (future)
reinforces the value of Item 9 (canonical ORT key as single source of truth) — fewer hardcoded
assumptions to update when the CUDA version changes.

## Bottom line

All 10 items across both phases are implemented, committed, and live-verified.

**Phase 1** (Items 1–5) resolved the acute worktree GPU eval failures: ORT native path is first-class
in `ResolvedConfig`, SPLADE discovery works from shared `modelsDir`, the eval launcher forwards all
GPU env vars, and per-subsystem GPU diagnostics are in `/api/status`.

**Phase 2** (Items 6–10) delivered structural unification: shared model discovery class (-272 lines
of duplication), EnvRegistry completeness for GPU-critical keys, SPLADE GPU promotion to typed config
fields, canonical ORT key with legacy deprecation warnings, and always-present diagnostic fields.

**Remaining gap — transferred to tempdoc 300**:

- **Dual config resolution**: `ResolvedConfigBuilder` + `ConfigStore` (ordinal-based) and
  `ConfigPrecedence.envOrProperty()` (simple sysprop-then-env) coexist. 46 `envOrProperty()` calls
  remain across 12 classes. The heaviest: `RerankerConfig` (19), `CitationScorerConfig` (5),
  `SpladeConfig` (5), `NerConfig` (4). Values set via settings.json or YAML are invisible to these
  subsystem configs. Item 8 migrated SPLADE GPU specifically; the rest remain on the legacy path.

**Minor diagnostic gaps** (not transferred — documented limitations):

- `missingDlls` only populated for reranker, not SPLADE/embed.
- No "GPU not requested" reason string when `gpuLayers == 0`.

---

## Phase 2: Structural Unification

Items 1–5 resolved the acute worktree GPU eval failures. The following items address the structural
issues that the original diagnosis identified but the Phase 1 implementation did not fully resolve.

These were identified through a systematic investigation of the model discovery, config propagation,
ORT CUDA activation, and diagnostics systems across the full codebase.

### Item 6: Unify ONNX model discovery classes — DONE

**Problem**: Five independent discovery implementations across two modules with gratuitous
structural differences (no dev fallback on embedding, dead code in SPLADE, inconsistent `baseDir`
computation). See investigation gap 6 for the full comparison table.

**Fix**: Move the reranker's `OnnxModelDiscovery` (the cleanest, most parameterized, best-tested
implementation — 13 tests vs 2–3 for others) into `modules/configuration` (confirmed as the
correct home by gap 7: it owns `ResolvedPathResolver` and `ConfigStore`, and both `reranker` and
`indexer-worker` already depend on it). Add a `requiredFiles` parameter (default: `model.onnx` +
`tokenizer.json`; SPLADE adds `vocab.txt`). Refactor the three indexer-worker discoverers to
delegate to it. Remove SPLADE's dead `resolveExplicitRepoRoot()` step (lines 65–72). Add a dev
fallback to embedding discovery.

**Test contracts to preserve** (from gap 6): (1) `autoDiscovered` flag: explicit=false, standard
discovery=true, dev fallback=false. (2) Null return when required files missing. (3) Priority:
modelsDir > dataDir > repoRoot > baseDir > dev fallback. (4) Null/blank explicit path triggers
auto-discovery.

**Key files**:
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/OnnxModelDiscovery.java` (new, moved from reranker)
- `modules/reranker/src/main/java/io/justsearch/reranker/OnnxModelDiscovery.java` (becomes thin delegate)
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/splade/SpladeModelDiscovery.java` (becomes thin delegate)
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/embed/onnx/EmbeddingOnnxModelDiscovery.java` (becomes thin delegate)
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/ner/NerModelDiscovery.java` (becomes thin delegate)

### Item 7: Register GPU-critical reranker keys in `EnvRegistry` — DONE

**Problem**: Of ~18 `JUSTSEARCH_RERANK_*` env vars used by `RerankerConfig.java`, only ONE
(`RERANK_GPU_MEM_MB`) is registered in `EnvRegistry`. The rest are read via direct
`ConfigPrecedence.envOrProperty()` calls. The most critical gap is `JUSTSEARCH_RERANK_MODEL_PATH`:
it has a typed field in `ResolvedConfig.Ai`, is written to the worker snapshot, but is not in
`EnvRegistry` — so on a fresh Head startup without a prior snapshot, the env var is silently
ignored by `ResolvedConfigBuilder`.

**Fix**: Register the 6 GPU-critical reranker keys in `EnvRegistry` (path + GPU activation keys
that matter for the config-contract problem; operational tuning knobs like `top_k` and `deadline_ms`
are lower priority and can stay on `ConfigPrecedence`):

- `RERANK_MODEL_PATH("justsearch.rerank.model_path", "JUSTSEARCH_RERANK_MODEL_PATH")`
- `RERANK_GPU_ENABLED("justsearch.rerank.gpu.enabled", "JUSTSEARCH_RERANK_GPU_ENABLED")`
- `RERANK_GPU_DEVICE_ID("justsearch.rerank.gpu.device_id", "JUSTSEARCH_RERANK_GPU_DEVICE_ID")`
- `RERANK_CHUNKS_MODEL_PATH("justsearch.rerank.chunks.model_path", "JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH")`
- `RERANK_CHUNKS_GPU_ENABLED("justsearch.rerank.chunks.gpu.enabled", "JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED")`
- `RERANK_CHUNKS_GPU_DEVICE_ID("justsearch.rerank.chunks.gpu.device_id", "JUSTSEARCH_RERANK_CHUNKS_GPU_DEVICE_ID")`

**Key file**: `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`

### Item 8: Promote SPLADE GPU config to `ResolvedConfig.Ai` — DONE

**Problem**: `SPLADE_GPU_ENABLED`, `SPLADE_GPU_DEVICE_ID`, and `SPLADE_GPU_MEM_MB` are in
`EnvRegistry` but not extracted as typed fields in `ResolvedConfig.Ai`. `SpladeConfig.fromEnv()`
reads them via `ConfigPrecedence.envOrProperty()` (gap 8: a thin wrapper over
`System.getProperty()` / `System.getenv()` with no `ConfigStore` awareness), bypassing the
centralized config system entirely.

**Fix**: Add `spladeGpuEnabled` (boolean), `spladeGpuDeviceId` (int), `spladeGpuMemMb` (int) to
`ResolvedConfig.Ai`. Update `buildAi()` to extract them. Migrate `SpladeConfig.fromEnv()` from
`ConfigPrecedence.envOrProperty(...)` to `EnvRegistry.SPLADE_GPU_ENABLED.getBoolean(false)` etc.
(the keys are already registered; `SpladeConfig` just doesn't use them — see gap 8).

**Key files**:
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/splade/SpladeConfig.java`

### Item 9: Deprecate legacy ORT native keys — DONE

- [x] `WorkerSpawner`: best-effort derivation now produces canonical key
  (`-Djustsearch.onnxruntime.native_path=`) instead of legacy `onnxruntime.native.path`. Checks
  both sysprop AND env var before falling through to derivation (prevents double-injection where
  derived sysprop at ordinal 500 could override operator's explicit env var at ordinal 400).
- [x] `WorkerSpawner`: `JUSTSEARCH_NATIVE_PATH` hint logs deprecation warning via `log.warn()`
- [x] `OrtCudaHelper`: deprecation warning (once per JVM via static flag) when legacy keys
  resolve the ORT native path
- [x] `runHeadless`: forwards `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` alongside legacy key
- [x] `applyHeadlessEvalContract`: GPU env var forwarding list expanded with `EMBED_GPU_MEM_MB`,
  `EMBED_BACKEND`, `RERANK_GPU_ENABLED`, `RERANK_GPU_MEM_MB` (gap C follow-up)
- [x] Legacy `onnxruntime.native.path` forwarding preserved for backwards compatibility

Note: `looksLikeOnnxRuntimeNativeDir()` hardcodes CUDA 12 DLL names — future CUDA 13 concern.

**Key files**:
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/ort/OrtCudaHelper.java`
- `modules/ui/build.gradle.kts` (both `runHeadless` and `applyHeadlessEvalContract`)

### Item 10: Wire missing diagnostic fields — DONE

- [x] 5 vector format fields added to `WorkerOperationalView`: `vectorFormatConfig`,
  `vectorFormatStored`, `vectorFormatActual`, `vectorSegmentsFloat32`, `vectorSegmentsQuantized`
- [x] `WorkerStatusMapper` wires proto fields 76–80 to the new record components
- [x] GPU status (`spladeOrtCuda`, `embedOrtCuda`) always present (never null). When GPU not
  configured: `OrtCudaView.notConfigured()` → `{attempted:false, failureReason:"GPU not configured"}`
- [x] `OrtCudaView.notConfigured()` factory + `REASON_NOT_CONFIGURED` constant extracted to
  eliminate 6 duplicated string literals
- [x] `rerankerOrtCuda` fallback harmonized to use same `notConfigured()` as SPLADE/embed
- [x] Compact constructor null-guards for `spladeOrtCuda`/`embedOrtCuda` enforce always-present
  contract at the record level
- [x] `toMap()` bridge updated (unconditional serialization for all 3 ORT CUDA views)
- [x] `StatusRecordSchemaTest` constructor calls updated, schema baseline regenerated
- [x] All 15 schema tests pass (drift, contract, backward compat)

**Key files**:
- `modules/app-api/src/main/java/io/justsearch/app/api/status/OrtCudaView.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/status/WorkerOperationalView.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java`
- `modules/app-api/src/main/resources/schemas/status-response.schema.json`
- `modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java`

### Phase 2 implementation order

1. ~~**Item 9** (deprecate legacy ORT keys)~~ — **DONE**
2. ~~**Item 10** (wire missing diagnostic fields)~~ — **DONE**
3. ~~**Item 6** (unify discovery classes)~~ — **DONE**
4. ~~**Items 7 + 8** (EnvRegistry registration + SPLADE GPU promotion)~~ — **DONE**

### Phase 2 acceptance criteria

### E. All ONNX model discoverers share one implementation — MET

Shared `OnnxModelDiscovery` in `modules/configuration/src/main/java/.../resolved/OnnxModelDiscovery.java`
handles all 4 model types. Per-module discoverers are thin delegates passing `modelName`, `devSubdir`,
`requiredFiles`, and `devLayoutAutoDiscovered`. Net -272 lines of duplicated logic.

### F. All worker-relevant GPU/path env vars are registered in `EnvRegistry` — MET

6 GPU-critical reranker keys registered: `RERANK_MODEL_PATH`, `RERANK_GPU_ENABLED`,
`RERANK_GPU_DEVICE_ID`, `RERANK_CHUNKS_MODEL_PATH`, `RERANK_CHUNKS_GPU_ENABLED`,
`RERANK_CHUNKS_GPU_DEVICE_ID`. Operational tuning knobs (`top_k`, `deadline_ms`, etc.) left on
`ConfigPrecedence` — they work correctly and are not part of the config-contract problem.

### G. SPLADE GPU config is first-class in `ResolvedConfig` — MET

`spladeGpuEnabled`, `spladeGpuDeviceId`, `spladeGpuMemMb` are typed fields in `ResolvedConfig.Ai`,
extracted by the builder. `SpladeConfig.fromEnv()` migrated from `ConfigPrecedence.envOrProperty()`
to `EnvRegistry.SPLADE_GPU_ENABLED.getBoolean()` etc.

### H. Legacy ORT keys are deprecated with warnings — MET

Using `JUSTSEARCH_NATIVE_PATH` or `onnxruntime.native.path` produces a deprecation log message
pointing to the canonical `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`. WorkerSpawner derivation now
produces the canonical key. Warning fires once per JVM lifetime (deduplication flag).

### I. All computed status fields are surfaced at `/api/status` — MET

Vector format proto fields (76–80) mapped through to `WorkerOperationalView`. GPU status fields
always present (never null) via `OrtCudaView.notConfigured()` with explicit `"GPU not configured"`
reason. All three ORT CUDA views (reranker, SPLADE, embed) harmonized.

---

## Structural observations (not implementation items)

The following are architectural observations relevant to this area but not actionable as tempdoc
items. Recorded here for future reference.

### Dual config resolution systems

Two resolution systems coexist: `ResolvedConfigBuilder` + `ConfigStore` (ordinal-based, modern)
and `ConfigPrecedence.envOrProperty()` / `EnvRegistry.get()` (simple sysprop-then-env, legacy).
The same config key can resolve to different values depending on which system reads it —
`ResolvedConfigBuilder` applies ordinals (settings.json at 300 < env var at 400 < JVM arg at 500),
while `EnvRegistry.get()` only checks sysprop then env var (never YAML or settings.json).

This is a known migration state, not a bug. But subsystem configs that read directly via
`ConfigPrecedence` (e.g., `SpladeConfig`, `RerankerConfig`, `NerConfig`) will not see values set
via settings.json or YAML — only sysprop and env var. Item 8 addresses this for SPLADE GPU
specifically. The full migration is scoped in tempdoc 300.

### Two-tier env var registration

`EnvRegistry` has 86 centrally registered keys. But `ResolvedConfigBuilder.contributeYaml*()`
methods register ~50+ more env vars ad-hoc via `putAdHocEnvSysprop()`. These ad-hoc env var names
exist only as string literals in the builder. There is no single place to see all supported env
vars; the documentation (`docs/reference/configuration/environment-variables.md`) is the de facto
registry but is not verified by code.

### `copyCudaDllsToOrtTempDir` TOCTOU heuristic

`OrtCudaHelper.copyCudaDllsToOrtTempDir()` scans `%TEMP%/onnxruntime-java*/` for the most recently
modified directory containing `onnxruntime.dll`. The code acknowledges the TOCTOU race in its own
Javadoc. If SPLADE and embedding both call `prepareCudaDependencies` concurrently (they can, since
they initialize independently), there is a narrow window where both try to copy the same DLL. The
`IOException` catch handles this, but it is a code smell. The `PRELOADED_NATIVE_DLLS`
`ConcurrentHashMap.newKeySet()` prevents duplicate `System.load()` calls but does not guard the
file-copy step. In practice, only one JustSearch Worker runs per machine, so the risk is low.

## Investigation gaps — summary

All 15 investigation gaps were resolved on 2026-03-14. Key findings are folded into the item
descriptions above. This section provides a reference index.

| # | Topic | Items | Key finding |
|---|-------|-------|-------------|
| 1 | Eval launcher | 3, 9 | GPU vars forwarded as env-only (ordinal 400). 4 GPU vars missing from list. `runHeadless` uses legacy ORT key. |
| 2 | WorkerSpawner ORT | 9 | Two parallel channels (canonical + legacy) fire independently. Legacy is dead code when canonical resolves. |
| 3 | Reranker GPU config | 7, 8 | Only 1 of ~18 reranker env vars in `EnvRegistry`. 6 GPU-critical keys identified for registration. |
| 4 | Proto file | 10 | Exact field names/types: vector format (76–80), GPU diagnostics (97–110). |
| 5 | WorkerOperationalView | 10 | Vector format fields confirmed absent — zero matches in view and mapper. |
| 6 | Discovery tests | 6 | Reranker has 13 tests (gold standard). NER 7, SPLADE 3, embedding 2. `TestResolvedConfigHelper` shared fixture. |
| 7 | Module deps | 6 | `modules/configuration` is the correct home. `infra-core` can't work (upstream, no `configuration` dep). |
| 8 | ConfigPrecedence | 8, 9 | Thin `System.getProperty()`/`System.getenv()` wrapper. SPLADE GPU keys already in `EnvRegistry` but unused by `SpladeConfig`. |
| 9 | Active tempdocs | 7, 8 | 286 Phases 1–5 merged; Phase 6 unstarted. Items 7/8 **unblocked** — complementary, not conflicting. |
| 10 | Schema tests | 10 | `StatusRecordSchemaTest` with drift detection. 4 artifacts to update; baseline regeneration command documented. |
| A | Phase 1 verification | all | All modules build, all tests pass. `EMBED_GPU_MEM_MB` added by another agent (additive). |
| B | `runHeadless` | 9 | Less impactful than expected — uses real settings.json. Main gap is legacy ORT key. |
| C | Eval GPU forwarding | 3 | 4 missing GPU vars in eval launcher. Gradle→Head boundary, not Head→Worker. |
| D | Third-party ORT | — | No alternative solves Windows CUDA DLL problem. `OrtCudaHelper` remains best approach. |
| E | headless-config YAML | — | No GPU defaults. Benign. |
