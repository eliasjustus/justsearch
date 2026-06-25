---
title: "Model Distribution Architecture"
status: done
created: 2026-04-08
updated: 2026-04-09
parent: 376
related: [376, 375, 374, 378]
---

# 381. Model Distribution Architecture

Full architecture redesign of the model distribution and inference
selection pipeline. Rewrites of existing components (registry schema,
install service, manifest system, model discovery) are in scope where
the current design is structurally inadequate. No constraint to preserve
existing code or interfaces — the goal is a correct long-term
architecture, not incremental patches on the current system.

**Parent:** tempdoc 376 (CPU vs GPU Inference Strategy — problem
definition and issues inventory).

## Product Direction: GPU-Primary

JustSearch is designed for GPU-equipped machines. CUDA-accelerated
inference is the expected runtime for all model consumers (ONNX
enrichment and GGUF chat/RAG). CPU support is **graceful degradation**,
not a first-class experience.

What this means for the architecture:

- **GPU users get the full experience.** All models downloaded, all
  features enabled, no degradation messaging.
- **CPU users get working enriched search.** ONNX models (embedding,
  SPLADE, NER, reranker, citation-scorer) run on CPU with correct
  precision variants (FP32/INT8). Chat/RAG is not available — GGUF
  models are not downloaded because llama-server on CPU is unusably
  slow (minutes per response for an 8B model).
- **Hardware-aware downloads are still required** — not to optimize the
  CPU experience, but to avoid wasting disk space and bandwidth. Without
  filtering, adding FP32 variants to the registry means everyone
  downloads both FP16 and FP32 (~10+ GB). With filtering, each user
  downloads only their appropriate variant.
- **Download profiles** (three tiers based on CUDA + VRAM):
  - **GPU-full** (CUDA functional + VRAM ≥ 7.5 GB): FP16 ONNX + GGUF +
    supporting files. ~8.3 GB. Full experience.
  - **GPU-lite** (CUDA functional + VRAM < 7.5 GB): FP16 ONNX +
    supporting files. No GGUF (~9 GB model doesn't fit). ~2.1 GB.
    Enriched search with GPU acceleration, no chat.
  - **CPU** (no CUDA): FP32/INT8 ONNX + supporting files. No GGUF.
    ~2.8 GB. Enriched search on CPU.
- **If hardware changes** (GPU added later), the user re-runs Install AI.
  The planner computes the delta and downloads the missing variants/GGUF.
- **VRAM threshold** reuses the existing `VramDetector.MINIMUM_VRAM_FOR_VDU`
  (7.5 GB) — this is already validated as the minimum for the Qwen3-VL-8B
  model (9.04 GB VRAM with mmproj, per registry notes).

## Root Cause

The 23 issues documented in tempdoc 376 share one meta-problem: **there's
no model lifecycle abstraction**. Models exist as loose files on disk.
Each stage of the pipeline (registry, download, arrangement, discovery,
manifest, session creation, observability) was built independently, with
its own assumptions about what came before and what comes after. The bugs
live in the seams.

Current pipeline — no state flows between stages:

```
Registry (flat file list)
  → Download (sequential, unfiltered)
    → Arrange (copy to targetPath, no cleanup)
      → Discover (scan directories, heuristic validation)
        → Manifest (static file, trusted blindly)
          → Session (fallback-heavy, observable only in logs)
```

## Architecture — Design Principles and Structural Patterns

The following sections describe the structural patterns and principles
the target architecture needs. Each subsection identifies what must be
true for the architecture to be correct long-term.

### A. Model Identity as a First-Class Concept

The system currently thinks in **files**. It needs to think in **models**.
An embedding model isn't 5 registry assets — it's one model with
variants, supporting files, and metadata.

```
Model "embedding" {
  variants: [
    { precision: FP32, targetEP: CPU, file: model.onnx, size: 1.26GB },
    { precision: FP16, targetEP: CUDA, file: model_fp16.onnx, size: 628MB }
  ]
  supporting: [ tokenizer.json, pooling_config.json ]
  manifest: model_manifest.json  // generated, not distributed
}
```

This collapses three problem areas into one concept:
- Registry structure (currently flat, no grouping)
- Manifest authority (currently a separately-authored artifact that drifts)
- Cross-model consistency (currently each consumer adopts different levels
  of infrastructure)

If the registry thinks in models-with-variants, the manifest can be
*derived* from registry + hardware profile rather than being a
separately-authored artifact.

With rewrites in scope, this means the registry schema (`model-registry.v1.json`)
can be replaced with a v2 schema that models packages natively. The flat
asset list doesn't need to be preserved — the install service that reads
it can be rewritten to match the new schema.

**Design question:** How detailed is the variant metadata? Just `cpu`/
`gpu`? Or `precision` + `targetEP` + `minOrtVersion` + `expectedLatency`?
The more metadata, the smarter selection can be — but the more that needs
to be maintained.

### B. Hardware Profile as a Propagated Value

The system detects hardware piecemeal: `VramDetector` checks VRAM,
`OrtCudaHelper` checks DLLs, `OrtSessionFactory` attempts CUDA. These
are scattered, cached differently, and not all available at every stage.

A hardware profile should be **computed once, early** and **propagated as
a value** through the pipeline:

```
HardwareProfile {
  gpuDetected: boolean       // GPU enumerated (Win32_VideoController)
  cudaFunctional: boolean    // CUDA runtime + DLLs present and loadable
  vramBytes: long            // Available VRAM (0 if no GPU)
  ortCudaAvailable: boolean  // ORT CUDA EP actually works (session test)
}
```

The critical distinction is between `gpuDetected` (the sandbox case — GPU
visible but CUDA broken) and `cudaFunctional` (CUDA runtime actually
works). The current system conflates these.

**Detection timing:** The hardware profile is needed at install time to
select the download profile. A full probe takes 1-3s, negligible.

**What to detect at install time (two checks):**
1. `cudaFunctional` — are ORT CUDA DLLs present? `GpuAutoDetection`
   already checks this at Head startup (ordinal 150) by scanning for
   `onnxruntime_providers_cuda.dll` + core CUDA DLLs. Reliable — it
   correctly handles the sandbox case (GPU enumerated but no CUDA
   runtime). Result stored in `ResolvedConfig` as
   `justsearch.gpu.enabled`.
2. `vramBytes` — total GPU VRAM. `VramDetector` queries via nvidia-smi.
   Needed to distinguish GPU-full (≥ 7.5 GB, download GGUF) from
   GPU-lite (< 7.5 GB, skip GGUF). Uses the existing
   `MINIMUM_VRAM_FOR_VDU` threshold.

**Both values already exist at Head startup** — they just aren't plumbed
to the install service (R3). The rewritten install service takes both
as constructor parameters.

**Persistence:** Profile is recorded in the install contract. Re-probe
on every Install AI run (detect hardware changes). The contract records
which profile was used, enabling the planner to compute deltas when
hardware changes (e.g., user adds GPU → re-runs install → planner
detects GPU-full, downloads FP16 + GGUF delta).

**Tricky cases to handle:**
- Laptop with switchable GPU (Optimus) — GPU detected but CUDA may not work
- Windows Sandbox: GPU enumerated via vGPU but no CUDA runtime (tempdoc 375)
- eGPU plugged in after install — GPU models weren't downloaded
- CUDA toolkit installed after initial setup

### C. Variant Selection as a Deterministic Function

Currently, variant selection is scattered: `ModelManifest.resolveModelPath()`
does a simple key lookup, `OrtSessionManager.tryCreateGpuSession()` has a
fallback chain, `OnnxSessionCache` filename-sniffs for optimization level.
These overlap and sometimes contradict.

The design should have **one selection function**:

```
selectVariant(model: ModelIdentity, hardware: HardwareProfile)
  → VariantSelection {
    modelFile: Path           // the file to load
    executionProvider: EP     // CPU or CUDA
    optimizationLevel: OptLevel
    expectedLatency: Duration // for observability
    degraded: boolean         // true if not the optimal variant
    degradationReason: String // "FP32 on CPU (FP16 unavailable)"
  }
```

This collapses manifest authority and optimization lifecycle into a single
pure function. The manifest becomes an implementation detail of how variant
metadata is stored, not a trust-critical config file.

**Graceful vs fail-fast:** Should this function handle missing files
gracefully (return a degraded selection) or fail fast (throw if the
expected variant isn't on disk)? Graceful handling is better UX but makes
bugs harder to detect. Possible middle ground: return the degraded
selection but emit a structured warning that the observability layer
surfaces.

### D. Precision Taxonomy Beyond CPU/GPU

The current model is binary: CPU or GPU. The actual precision landscape is
richer:

| Precision | Good for | Example in codebase |
|-----------|----------|-------------------|
| FP32 | CPU (correct, no Cast overhead) | SPLADE `model.onnx` |
| FP16 | CUDA GPU (half the VRAM, fast) | Embedding `model_fp16.onnx` |
| INT8 | CPU (quantized, smallest, fastest) | NER `model.onnx` (actually INT8, 135 MB) |

The NER model is labeled `model.onnx` but is actually INT8-quantized
(135 MB vs the FP32 original at ~540 MB). The naming convention
(`model.onnx` = CPU) masks the actual precision. This matters because:

- A variant selection system needs to know the actual precision, not just
  the filename.
- Future models may have INT4 or mixed-precision variants.
- The optimization strategy differs: INT8 models don't need EXTENDED_OPT
  and don't have the Cast node problem.
- The filename-sniffing heuristic in `OnnxSessionCache` (I4) is a symptom
  of precision being implicit.

**Design question:** Should precision be declared in the manifest/registry
explicitly (`"precision": "int8"`) or inferred from the model file?
Explicit declaration is more reliable. Inference (via ORT model metadata)
is more resilient to human error but adds complexity.

### E. Installation as a Verified Contract

After install completes, there should be a verifiable state file — a
"bill of materials" that says exactly what was installed, which variant
was selected for this hardware, and what the expected runtime behavior is:

```json
{
  "hardwareProfile": { "cudaFunctional": false, "vramBytes": 0 },
  "models": {
    "embedding": {
      "selectedVariant": "FP32-CPU",
      "modelFile": "onnx/gte-multilingual-base/model.onnx",
      "installedAt": "2026-04-08T10:00:00Z",
      "sha256": "..."
    }
  }
}
```

The runtime reads this on startup instead of scanning directories.
Discovery becomes **verification** (does the declared state match disk?)
rather than **search** (scan and hope).

**Discovery as fallback:** This is a significant departure from the
current discovery-based approach. Discovery is resilient to manual changes
(user drops a model file in the right directory, it gets picked up). A
contract-based approach would not find manually-placed models unless the
contract is updated. Discovery should remain as a fallback for dev/
power-user workflows, but the primary path should be contract-driven.

With rewrites in scope, `OnnxModelDiscovery` (and the per-model discovery
delegates like `EmbeddingOnnxModelDiscovery`) can be replaced by a
contract reader that validates installed state on startup. The
directory-scanning heuristic (`isCompleteModelDir` with its
`DEFAULT_REQUIRED_FILES` / manifest fallback) becomes unnecessary when
the install contract declares exactly what's present.

### F. Download Planning vs Download Execution

Currently `AiInstallService.runInstallInternal()` iterates all assets and
downloads each one. There's no planning phase — no point where the system
computes "given this hardware and these installed files, here's what I
need to download."

A planning phase would:

1. Load the registry
2. Probe hardware (→ HardwareProfile)
3. Scan existing installed models (→ current state)
4. Compute the delta: what's missing, what's the wrong variant, what
   needs updating
5. Present the plan to the user (total download size, what will change)
6. Execute

This collapses hardware detection timing, disk budget, backward
compatibility, and GPU-unaware downloads into one concept. The plan is
inspectable, testable, and can be shown to the user before any download
starts.

**GPU-primary simplification:** The planner has three profiles based on
two checks (`cudaFunctional` + `vramBytes`):

| Profile | Condition | ONNX variants | GGUF | Size |
|---------|-----------|---------------|------|------|
| GPU-full | CUDA + VRAM ≥ 7.5 GB | FP16 | Yes | ~8.3 GB |
| GPU-lite | CUDA + VRAM < 7.5 GB | FP16 | No | ~2.1 GB |
| CPU | No CUDA | FP32/INT8 | No | ~2.8 GB |

The GGUF decision uses the existing `VramDetector.MINIMUM_VRAM_FOR_VDU`
threshold (7.5 GB). The ONNX variant decision is binary: CUDA → FP16,
no CUDA → FP32/INT8. This is three profiles but only two independent
checks, not a complex matrix.

**Re-evaluation:** The plan needs to be re-runnable. If a user starts
on CPU (~2.2 GB install), then adds a GPU and re-runs Install AI, the
planner detects `cudaFunctional=true`, computes the delta (need FP16
variants + GGUF), and downloads only what's missing. This makes
"upgrade to GPU" a natural operation, not a special case.

**Hardware detection at install time (R3 impact):** The planner requires
`HardwareProfile` as input. Currently `AiInstallService` has no access
to GPU state (R3). The rewritten install service takes `HardwareProfile`
as a constructor parameter, computed from `GpuAutoDetection` results
that already exist at Head startup.

**Phase separation for failure isolation:** Validation (are registry
entries well-formed?) and download (fetch bytes) must be separate phases.
Currently, validation failures abort all downloads (I10). In the planning
model, validation runs across all assets first, producing a clean
downloadable set. Invalid entries are reported as warnings, not pipeline
aborts.

### G. Disk Budget — Arrange Without Doubling

`arrangeAssetIfNeeded` copies without deleting, doubling disk usage (I9).
The flat copies serve as a download cache — re-running Install AI skips
files whose hash matches.

**Research result (R2):** Hash verification checks the flat copy at
`modelsDir/<filename>`, not the arranged path. The flat copy IS the
download cache.

**Resolution:** With a rewritten install pipeline, download directly to
the target path and eliminate the arrange step entirely. The flat copy /
arrange pattern is a vestige of the flat registry schema — in a
package-based design, assets are downloaded to their final location.
Hash verification checks the target path. No flat copies, no doubling.

### H. The GGUF/LLM Dimension — Settled

**Decision:** GPU-primary. GGUF is core functionality for the primary
audience (GPU users). CPU users do not download GGUF — chat/RAG requires
GPU for acceptable performance.

GGUF models (chat 5 GB + mmproj 1.16 GB) use llama-server, not ORT.
On CPU, inference takes minutes per response for the 8B parameter model.
This isn't degraded — it's unusable. Downloading 6.2 GB for a feature
that doesn't work wastes bandwidth and disk space.

**In the registry v2 schema**, GGUF models declare
`minVramBytes: 7500000000` (7.5 GB). The download planner skips them
when VRAM is below the threshold (GPU-lite and CPU profiles). If the
user later upgrades and re-runs install, the planner downloads the delta.

**In the UI**, GPU-lite and CPU users see "enriched search" as the
available AI capability. The chat/RAG section shows "requires ≥7.5 GB
GPU VRAM" with guidance on what's needed.

### I. Model Update and ORT Version Lifecycle

The current system has no concept of model updates. The registry is
bundled with the app. A new registry means a new app version. There's no
mechanism for:

- Hot-updating models without an app upgrade
- Handling ORT version changes (which invalidate all `.optimized` caches)
- Rolling back a model update that produces worse results
- Adding a new precision variant to an existing model

**ORT version changes:** The `.optimized` cache already handles
invalidation via the sidecar's `ort:` field. But if optimization is
expensive (FP16 on CPU), an ORT upgrade re-triggers the long optimization.
This is another argument for shipping the correct precision — the
optimization problem only matters for pathological cases (FP16 on CPU).

**Registry versioning:** The registry moves to a v2 schema. The v1 schema
and all code that reads it are deleted. No backward-compatibility shims.

### J. Observability — Actionable, Not Just Informative

The system has multiple fallback layers (GPU→CPU session, FP16→FP32
model, missing model→skip, download failure→continue) but no unified way
to surface what degradation the user is actually experiencing.

**GPU-primary simplification:** Observability has two modes, not a matrix:

**GPU-full users (primary path):** No degradation messaging needed. All
models run on CUDA with FP16, chat/RAG fully available. If a specific
model falls back (e.g., reranker FP16 missing → FP32 on CUDA), report
at WARN level in `/api/status` but don't surface prominently in the UI.

**GPU-lite users (CUDA but insufficient VRAM for GGUF):**

| Level | What | Example |
|-------|------|---------|
| Install summary | Profile selected, GGUF skipped | "Installed GPU-accelerated search models (2.1 GB). Chat requires ≥7.5 GB VRAM (detected 6 GB)." |
| Runtime status | Which features work | "Enriched search: active (GPU). Chat/RAG: requires more VRAM." |
| Upgrade guidance | What to do | "Upgrade to a GPU with ≥8 GB VRAM, then re-run Install AI." |

**CPU users (no CUDA):**

| Level | What | Example |
|-------|------|---------|
| Install summary | Profile selected, GGUF skipped | "Installed search models (2.8 GB). GPU features require NVIDIA CUDA." |
| Runtime status | Which features work | "Enriched search: active (CPU). Chat/RAG: requires GPU." |
| Upgrade guidance | What to do | "Add an NVIDIA GPU with CUDA support, then re-run Install AI." |

**Key principle: every silent fallback should have a corresponding visible
signal.** The common case (GPU-full) should be clean. Observability
effort concentrates on the two degraded paths (GPU-lite, CPU).

### K. Prevention Through Structure

Beyond fixing the 23 issues, the design should make certain classes of
error **structurally impossible**:

1. **Manifest drift from reality:** If manifests are generated from
   installed state (not distributed), they can't point to nonexistent
   files.

2. **Untested CPU paths:** If there's a `--force-cpu` jseval mode and
   it's part of the gate, CPU regressions are caught before merge.

3. **Registry entries with broken URLs:** If the install planning phase
   validates URLs (HEAD request) before downloading, 404s are caught at
   plan time, not download time.

4. **Missing model variants:** If the registry schema requires both CPU
   and GPU variant declarations for every model (with explicit "not
   applicable" for CPU-only models like citation-scorer), a missing
   variant is a schema validation error, not a runtime surprise.

5. **Silent degradation:** If every `OrtSessionManager` reports its
   session type to a central registry, and the startup sequence asserts
   that no unexpected degradation occurred, silent fallbacks become test
   failures.

**Enforcement cost:** Schema validation catches errors at build time but
makes the schema harder to evolve. Startup assertions catch errors at
runtime but add fragility. The right balance depends on how often the
model distribution changes — if it's rarely touched, heavy enforcement is
worth it. If it evolves rapidly, lighter guardrails with good
observability may be more pragmatic.

### L. Cross-Model Consistency

Five model consumers (embed, SPLADE, NER, reranker, citation-scorer) each
handle model discovery, manifest loading, and session creation with
varying levels of infrastructure adoption:

| Consumer | OrtSessionManager | ModelManifest | GPU support | Manifest in registry |
|----------|-------------------|---------------|-------------|---------------------|
| Embed | Yes | Yes | Yes | Yes (broken) |
| SPLADE | Yes | Yes | Yes | Yes (correct) |
| NER | Yes | Yes (via caller) | Yes | No |
| Reranker | Yes | Yes | Yes | No |
| Citation | **No** | **No** | **No** | No |

This inconsistency means each consumer has different failure modes and
different levels of hardware awareness. A fix to the manifest system
benefits embed and SPLADE but leaves NER, reranker, and citation-scorer
relying on convention fallbacks.

**Target state:** All consumers use the same infrastructure (model
identity → variant selection → OrtSessionManager). Citation-scorer, which
is intentionally CPU-only, declares this explicitly in its model identity
(`variants: [{ precision: INT8, targetEP: CPU }]`) rather than being an
infrastructure exception.

With rewrites in scope, `CitationScorer` can be migrated to
`OrtSessionManager` and `ModelManifest`. The "CPU-only by design" decision
is preserved as a model identity declaration, not as a code-level bypass
of shared infrastructure. Similarly, `ModelManifest` itself can be
redesigned — the current record with `cpu`/`gpu` string fields could be
replaced by a richer structure that carries precision metadata per variant.

### M. No v1 Migration — Clean Replacement

This is pre-release software with no production installs. There is no
v1→v2 migration path. The v1 install system (`AiInstallService`,
`AiModelRegistry`, `AiInstallStatus`, `arrangeAssetIfNeeded`) is deleted
and replaced entirely by the v2 system.

If a user previously ran Install AI with v1, re-running Install AI with
the v2 code handles it naturally: the planner checks file existence on
disk, skips files that are already present, downloads what's missing, and
writes a fresh install contract. No special migration logic needed — a
"migration" is just a normal install plan with some files already present.

## Rewrite Scope

**Keep as-is (well-designed, working correctly):**
- `OrtSessionManager` — GPU/CPU session lifecycle, failure recovery,
  arbitration. The architecture is sound. It receives wrong inputs
  (from broken manifests), but the interface (cpuModelPath +
  gpuModelPath) is correct. No interface change needed — fix the
  inputs upstream.
- `OrtSessionFactory` — GPU session creation with FP16→FP32 fallback.
  GPU→CPU fallback uses CUDA provider (not CPU EP), doesn't go through
  `OnnxSessionCache`. Correct as-is.

**Modify (right structure, targeted changes):**
- `OnnxSessionCache` — Replace filename-sniffing heuristic (`contains
  ("fp16")`) with an explicit `OptLevel` parameter on
  `createCachedSession`. Callers that know they have FP16 on CPU
  (BgeM3Encoder's intentional fallback) pass `BASIC_OPT` explicitly.
  All other callers use `EXTENDED_OPT`. The heuristic is only needed
  for BgeM3Encoder's edge case — all main-path consumers (via
  OrtSessionManager.createCpuSession) always pass FP32/INT8.
- Encoder constructors (`OnnxEmbeddingEncoder`, `SpladeEncoder`,
  `BertNerInference`, `CrossEncoderReranker`) — receive model paths
  from the install contract / generated manifest instead of loading
  manifests themselves. The contract/manifest is correct, so
  OrtSessionManager gets correct paths.
- `/api/status` pipeline — add degradation reporting fields (profile
  tier, active variants, upgrade guidance).

**Rewrite (structurally inadequate):**
- `AiModelRegistry` / `model-registry.v1.json` — flat asset list →
  package-based model with variant metadata and profile tags.
- `AiInstallService` — monolithic download loop → plan-then-execute
  pipeline with three-profile hardware awareness.
- `ModelManifest` — `cpu`/`gpu` string fields → richer structure with
  precision metadata per variant, or eliminate entirely if manifests
  become generated from the install contract.
- `OnnxModelDiscovery` + per-model delegates — directory scanning →
  install contract reader with discovery as fallback for dev mode.
- `arrangeAssetIfNeeded` — eliminated by downloading to target paths.

## Design Layers

The architecture has natural dependencies. Design bottom-up:

```
Layer 1: Data Model
  HardwareProfile, ModelIdentity, VariantMetadata, InstallContract
  (Pure types — no behavior, no dependencies)

Layer 2: Selection Logic
  selectProfile(hardware) → DownloadProfile (GPU-full/GPU-lite/CPU)
  selectVariant(model, profile) → VariantSelection (which file to load)
  (Pure functions — depends only on Layer 1 types)

Layer 3: Registry Schema (v2)
  Package-based model definitions with variant metadata
  Each variant declares precision + targetEP + minVramBytes
  GGUF models tagged with minVramBytes threshold
  (Data definition — consumed by Layer 4)

Layer 4: Install Pipeline
  plan(registry, hardware, currentState) → InstallPlan
  execute(plan) → InstallContract
  Three profiles: GPU-full ~8.3 GB / GPU-lite ~2.1 GB / CPU ~2.8 GB
  Re-runnable for hardware upgrades (delta computation)
  (Depends on Layers 1-3)

Layer 5: Runtime Integration
  Contract reader → variant selection → session manager wiring
  (Depends on Layers 1-2, reads output of Layer 4)

Layer 6: Observability
  GPU: no degradation messaging (primary path)
  CPU: "enriched search active, chat requires GPU" + upgrade guidance
  (Depends on Layers 1-2, reads runtime state from Layer 5)

Layer 7: v1 Cleanup
  Delete all v1 install code: AiInstallService, AiModelRegistry,
  AiInstallStatus, AiModelAsset, arrangeAssetIfNeeded, install-state.json
  logic, model-registry.v1.json, and any code that reads the v1 format.
  Delete v1 manifest assets from registry (embed-model_manifest.json,
  splade-model_manifest.json) — manifests are now generated, not distributed.
  (Depends on Layers 4-5 being fully wired and verified)
```

Each layer is independently testable. Layers 1-2 can be designed and
unit-tested with no runtime dependencies. Layer 3 is a schema definition.
Layer 4 is the big rewrite. Layer 5 is bridge wiring (to be replaced by
Layer 8). Layer 6 is additive. Layer 7 is deletion. Layer 8 is the
composition root refactor that replaces the Layer 5 service locator
bridge with direct constructor injection.

```
Layer 8: Composition Root
  ModelSessionFactory: VariantSelection → pre-configured OrtSessionManager
  KnowledgeServer.initDeferredModels() as composition root
  Encoder constructors receive OrtSessionManager, not model paths
  Eliminates: ContractConfigContributor, ModelManifest as runtime
    artifact, per-encoder discovery, manifest generation during install
  (Depends on Layers 1-2, replaces Layer 5 bridge)
```

## Research Results (2026-04-08)

Three critical research tasks were executed to collapse design branches.

### R1. FP32 Optimization Time — Partially Answered

**Method:** Checked `.opt-meta` sidecars and file timestamps for all
optimized models on the dev machine (GPU, EXTENDED_OPT for all FP32).

**Finding:** Sidecars store only `mtime:size:ort` — no timing data. The
optimization elapsed time is logged (`OnnxSessionCache.java:89`) but not
persisted. File timestamps give upper-bound windows but are unreliable
(optimization starts when the encoder is first used, not when the file
was written):

| Model | Precision | Size | File gap (model → .optimized) |
|-------|-----------|------|------------------------------|
| SPLADE | FP32 | 995 MB | 71s (13:48:28 → 13:49:39) |
| Reranker | FP32 | 341 MB | 77 min (unreliable — download and first use were separated) |
| NER | INT8 | 135 MB | 218s (unreliable — same reason) |
| Embedding | FP16 | 628 MB | N/A (optimized on GPU, not CPU) |
| Embedding | FP32 | 1.26 GB | **Never optimized** (no .opt-meta exists) |

**Conclusion:** The SPLADE case (FP32, 995 MB, 71s upper bound) is the
best datapoint. The actual optimization time is likely less since model
file write and optimization start are separate events. The tempdoc's
claim of "~5s for FP32" is plausible for smaller models but unverified
for the 1.26 GB embedding model. **The FP32 embedding model has never
been optimized on this machine** — it exists on disk but the manifest
points to FP16, so it's never been loaded.

**Impact on design:** The pathological case (30-60+ min) is FP16-specific.
FP32 optimization, even if it takes 60-90s for a 1.26 GB model, is
acceptable (one-time cost, cached thereafter). The BASIC_OPT filename-
sniffing workaround (I4) becomes unnecessary if CPU always gets FP32.
The optimization lifecycle (section D) simplifies: keep the existing
`OnnxSessionCache` infrastructure as-is, just ensure correct precision
reaches it.

### R2. Hash Verification Path — Answered: Uses Flat Copy

**Method:** Read `AiInstallService.java:251-262`.

**Finding:** The skip-if-already-installed check uses:
```java
Path finalPath = modelsDir.resolve(asset.filename);  // FLAT path
if (Files.exists(finalPath)) {
    String got = sha256(finalPath);  // hashes the FLAT copy
    if (got.equalsIgnoreCase(asset.sha256)) {
        arrangeAssetIfNeeded(...);  // copies flat → target
        continue;                    // skips download
    }
}
```

The verification checks `modelsDir/<filename>` (the flat download path),
NOT `modelsDir/<targetPath>` (the arranged path). The flat copy IS the
download cache. If it's removed, re-install must re-download.

**Impact on design:** To eliminate flat copies (section G), the
verification must be changed to check the arranged path instead. Or,
with a rewritten install pipeline, download directly to the target path
and eliminate the arrange step entirely. The flat copy / arrange pattern
is a vestige of the flat registry schema — in a package-based design,
assets are downloaded to their final location.

### R3. GPU State at Install Time — Answered: Not Available

**Method:** Traced `AiInstallService` dependencies, `AppFacade` interface,
`VramDetector` ownership, and `GpuCapabilitiesService` wiring.

**Finding:** `AiInstallService` has **no access to GPU state**:
- `AppFacade` (passed to constructor) exposes search, indexing, documents,
  onlineAi, agent. **No GPU methods.**
- `VramDetector` is owned by `LocalApiServer` (line 123) and passed to
  `InferenceHandlers` and `StatusLifecycleHandler`. **Not passed to
  `AiInstallController`.**
- `GpuCapabilitiesService` — same pattern. Consumed by inference and
  status endpoints. **Not available to install.**
- `GpuAutoDetection` runs at Head startup (ordinal 150) and stores
  results in `ResolvedConfig`. This runs **before** install starts, but
  the result is in `ResolvedConfig`, not exposed through any interface
  `AiInstallService` has access to.

**Impact on design:** Hardware-aware install requires new wiring. With
GPU-primary direction, this is essential — the planner needs to know
whether to download the GPU profile (~8.2 GB) or CPU profile (~2.2 GB).
The simplest path: pass `GpuCapabilitiesService` (or a `HardwareProfile`
value computed from it) to the install service. `GpuAutoDetection`
already runs before install, so the data exists — it just needs to be
plumbed through. In the rewritten install service, `HardwareProfile`
is a required constructor parameter.

### R4. GGUF VRAM Requirements — Answered

**Finding:** Qwen3-VL-8B-Thinking needs ~9.04 GB VRAM with mmproj (per
registry notes). `InferenceLifecycleManager.switchToOnlineMode()` checks
VRAM and fails gracefully with `INSUFFICIENT_VRAM` if < 7.5 GB. VRAM
tiers already exist: 12GB+ (comfortable), 8GB (KV quantization), 4GB
(insufficient). GTX 1060 (6 GB) has CUDA but can't run GGUF.

**Impact on design:** The binary GPU/CPU profile is too coarse. Added
GPU-lite tier for CUDA-capable GPUs with < 7.5 GB VRAM. These get FP16
ONNX (GPU-accelerated search) but skip GGUF. The threshold reuses the
existing `MINIMUM_VRAM_FOR_VDU` constant.

### R5. FP16 Sniffing Call Sites — Answered

**Finding:** Four call sites for `OnnxSessionCache.createCachedSession`:
1. `OrtSessionManager.createCpuSession()` — always cpuModelPath (FP32/INT8). Safe.
2. `BgeM3Encoder` — can pass FP16 when FP32 doesn't exist. Intentional.
3. `CitationScorer` — always model.onnx (INT8). Safe.
4. GPU→CPU fallback in `OrtSessionFactory` — uses CUDA provider, not
   CPU EP, doesn't go through `OnnxSessionCache`. Not applicable.

**Impact on design:** FP16 sniffing can evolve to explicit `OptLevel`
parameter. BgeM3Encoder passes `BASIC_OPT`; all others use `EXTENDED_OPT`.
OrtSessionManager needs no interface change — correct manifests produce
correct paths.

### R6. Hardware Detection Reliability — Answered

**Finding:** `GpuAutoDetection` checks DLL presence (not functionality).
Correctly handles sandbox case (GPU visible, CUDA DLLs missing →
disables GPU). `VramDetector` uses nvidia-smi — works even without CUDA
runtime, so VRAM > 0 doesn't imply CUDA works. The two detectors are
independent but both values are available at Head startup.

**Impact on design:** Install planner needs BOTH `cudaFunctional` (from
`GpuAutoDetection` via `ResolvedConfig`) AND `vramBytes` (from
`VramDetector`) to select the correct profile. Both exist before install
starts — they just need to be plumbed through.

### Remaining Research Tasks

| Research task | Collapses which area | Status |
|---|---|---|
| ~~Measure FP32 optimization time~~ | ~~D~~ | R1: FP32 acceptable, FP16 workaround removable |
| ~~Check hash-verification path~~ | ~~G~~ | R2: uses flat copy, changeable |
| ~~Trace GPU detection at install time~~ | ~~B~~ | R3: not available, needs wiring |
| ~~GGUF VRAM requirements~~ | ~~H, F~~ | R4: 7.5 GB minimum, three-tier profiles |
| ~~FP16 sniffing call sites~~ | ~~K~~ | R5: evolve to explicit OptLevel parameter |
| ~~Hardware detection reliability~~ | ~~B, F~~ | R6: GpuAutoDetection + VramDetector, both available at startup |
| Audit `/api/status` GPU/model state | J — how much observability exists? | Deferred to Layer 6 implementation |
| Check ORT model precision metadata | D — can we infer precision? | Deferred — explicit declaration chosen |

## Architectural Gap: Service Locator → Composition Root

Investigated 2026-04-08. The v2 redesign built the correct data model
but wired it into the runtime via the wrong integration pattern. The
core issue is that the codebase uses the Service Locator anti-pattern
for model resolution, and the v2 code preserved that pattern instead
of replacing it.

### The Anti-Patterns Present

**Service Locator (Seemann, Fowler):** Each encoder constructor reaches
into the filesystem to discover its own model. It reads a manifest file,
resolves a filename, and constructs its own ORT session. The component
asks for its dependency at runtime instead of receiving it.

**Stringly-Typed / Primitive Obsession (Hanselman):** Model identity
flows through the system as `Path` and `String`. There is no type that
carries "this is the embedding model's FP32 variant for CPU EP." The
`VariantSelection` record exists but never reaches the encoder — it gets
flattened back to a directory path string in `ContractConfigContributor`.

**No Composition Root (Seemann):** `KnowledgeServer.initDeferredModels()`
constructs encoders but doesn't resolve model variants centrally. It
passes directory paths and lets each encoder discover its own files.

### The v2 Integration Gap

The v2 code resolves the right answer (via `VariantSelector`) but then
communicates it through the old service locator chain:

```
Contract → ContractConfigContributor → System.setProperty()
  → EnvRegistry → per-model Config class → encoder constructor
    → ModelManifest.loadOrDefault() → resolveModelPath()
      → OrtSessionManager
```

Seven hops, all string-based, with a manifest file re-deriving what
the contract already decided.

### The Correct Architecture

**Composition root resolves all variants, then injects them:**

```java
// KnowledgeServer.initDeferredModels() — the composition root
HardwareProfile hw = HardwareProfile.detect();
InstallContract contract = InstallContractIO.read(aiHome);

VariantSelection embed = VariantSelector.select("embedding", contract, hw, modelsDir);
VariantSelection splade = VariantSelector.select("splade", contract, hw, modelsDir);

// Encoders receive resolved selections — they don't discover anything
this.embeddingEncoder = new OnnxEmbeddingEncoder(embed, shouldUseGpu);
this.spladeEncoder = new SpladeEncoder(splade, shouldUseGpu);
```

**Encoder constructors take `VariantSelection`, not `Path`:**

```java
public OnnxEmbeddingEncoder(VariantSelection variant, BooleanSupplier shouldUseGpu) {
  // No manifest loading. No discovery. No path resolution.
  this.sessions = OrtSessionManager.builder("embed", variant.modelFile())
      .gpuConfig(variant.executionProvider() == CUDA ? gpuConfig : null)
      .build();
}
```

**What gets eliminated:**
- `ModelManifest` as a runtime artifact (the contract is the manifest)
- `OnnxModelDiscovery` + per-model delegates (composition root does
  resolution; discovery remains only as dev-mode fallback in the root)
- `ContractConfigContributor` (no system property intermediary needed)
- Per-model config classes' path resolution (the root provides paths)
- Manifest file generation during install (no consumer reads them)

### Deeper Centralization Required (decided 2026-04-09)

Injecting `VariantSelection` into encoder constructors centralizes model
**selection** but not model **loading configuration**. With that approach,
each encoder still independently:

- Creates its own `OrtSessionManager` (or calls `OnnxSessionCache` directly)
- Decides OptLevel — BgeM3Encoder has custom `BASIC_OPT` logic, others
  default to `EXTENDED_OPT`
- Configures GPU memory limits from per-encoder config
- Sets up the GPU/CPU lifecycle (lazy init, retry timers, signal bus)

**The "add a new precision" test:** If we add INT4 tomorrow:
- Registry JSON: 1 change (centralized) ✓
- `ModelPrecision` enum: 1 change ✓
- `VariantSelector`: 0 changes (generic) ✓
- Each encoder's OptLevel logic: **N changes** (scattered) ✗

**Decision:** The composition root should produce pre-configured
`OrtSessionManager` instances, not `VariantSelection` objects. Encoders
become pure inference logic — they receive a session manager and call
`selectSession()`. They don't know about paths, precision, OptLevel,
or CUDA configuration.

### Target Architecture

**Composition root (`KnowledgeServer.initDeferredModels()`):**

```java
InstallContract contract = InstallContractIO.read(aiHome);
HardwareProfile hw = HardwareProfile.detect();

// Resolve all variants centrally
VariantSelection embed = VariantSelector.select("embedding", contract, hw, modelsDir);
VariantSelection splade = VariantSelector.select("splade", contract, hw, modelsDir);
// ...

// Build sessions with correct config derived from variant metadata
OrtSessionManager embedSessions = ModelSessionFactory.create("embed", embed, gpuConfig, shouldUseGpu);
OrtSessionManager spladeSessions = ModelSessionFactory.create("splade", splade, gpuConfig, shouldUseGpu);

// Encoders receive ready-to-use session managers
this.embeddingEncoder = new OnnxEmbeddingEncoder(embedSessions, tokenizerPath, ...);
this.spladeEncoder = new SpladeEncoder(spladeSessions, tokenizerPath, ...);
```

**`ModelSessionFactory` — the centralized mapping:**

| VariantSelection | OptLevel | GPU config |
|---|---|---|
| `(FP16, CPU)` — degraded | `BASIC_OPT` | null (CPU-only) |
| `(FP32, CPU)` | `EXTENDED_OPT` | null (CPU-only) |
| `(INT8, CPU)` | `EXTENDED_OPT` | null (CPU-only) |
| `(FP16, CUDA)` | `EXTENDED_OPT` | GPU memory from per-model config |
| `(FP32, CUDA)` — degraded | `EXTENDED_OPT` | GPU memory from per-model config |

The factory derives OptLevel from (precision, EP) — one mapping in one
place. Per-encoder GPU memory limits are inherently per-model and come
from a config object, not from the encoder itself.

**What gets eliminated beyond the VariantSelection-only approach:**
- Per-encoder OptLevel logic (currently in BgeM3Encoder + implicit in others)
- Per-encoder OrtSessionManager construction (builder pattern in each encoder)
- Per-encoder GPU config resolution (each encoder reads its own config class)
- BgeM3Encoder's duplicated GPU lifecycle (custom double-checked lock pattern
  instead of OrtSessionManager)
- `ModelManifest` as a runtime artifact
- `OnnxModelDiscovery` + per-model delegates for production path
- `ContractConfigContributor` system property bridge
- Per-model `*Config.fromEnv()` path resolution

**What remains per-encoder (correctly):**
- Inference logic (tokenizer, input/output tensor handling)
- Model-specific parameters (max sequence length, batch size)
- Per-encoder GPU memory limits (different models need different VRAM)

**Complications:**
1. `OrtSessionManager` takes both cpuModelPath and gpuModelPath. With
   single-variant downloads (GPU_FULL → only FP16), both point to the
   same file. Functional but conceptual debt.
2. `CitationScorer` and chunk reranker are created lazily outside
   `initDeferredModels()`. The composition root must provide resolved
   config for these deferred consumers.
3. BgeM3Encoder does NOT use `OrtSessionManager` — it implements its
   own GPU lifecycle. Needs unification first.
4. Dev mode (no install contract) needs fallback to existing discovery.

### Status

The v2 data model (`VariantSelection`, `HardwareProfile`, `InstallContract`)
is correct. The install pipeline, observability, and v1 deletion are
complete. The remaining work is the composition root refactor:
creating `ModelSessionFactory`, refactoring encoder constructors to
receive `OrtSessionManager`, and eliminating the service locator chain.

## Implementation Progress

### Layers 1-3: Complete (committed + fixes applied)

**Commits on `worktree-381-model-distribution`:**
- `ce232d56a` — initial Layers 1-3 implementation
- `f47b428a1` — tempdoc updates, migration removal
- `7f53657dd` — v1 migration removal, Layer 7 addition
- `33f86b780` — 5 critical analysis fixes

**Files in `modules/configuration/src/main/java/io/justsearch/configuration/model/`:**
- `HardwareProfile.java` — GPU/CUDA/VRAM snapshot, profile selection
- `DownloadProfile.java` — three-tier enum (GPU_FULL/GPU_LITE/CPU)
- `ModelPrecision.java` — FP32, FP16, INT8, GGUF
- `ExecutionProvider.java` — CPU, CUDA, LLAMA_SERVER
- `ModelVariant.java` — one precision/EP variant of a model file
- `SupportingFile.java` — tokenizer, config, etc.
- `ModelPackage.java` — model with variants + supporting files + VRAM
  threshold. selectVariant falls back to first available when no
  EP-match exists (ensures CPU-only models work on GPU profiles).
- `ModelRegistry.java` — v2 registry containing model packages
- `InstallContract.java` — bill of materials (install output, runtime input)
- `InstallContractIO.java` — JSON read/write for contract (Jackson 3.x
  native exception handling)
- `VariantSelection.java` — resolved variant for runtime
- `VariantSelector.java` — selects variant from contract + hardware.
  Returns degraded selection with "missing from disk" when file absent.
- `InstallPlanner.java` — computes download plan from registry + hardware
- `InstallPlan.java` — download plan (planned downloads, skipped, delta)
- `ModelRegistryLoader.java` — deserializes v2 JSON → Layer 1 types

**Other files:**
- `modules/ui/src/main/resources/ai/model-registry.v2.json` — v2 registry
  with 6 packages. GGUF correctly tagged as GGUF/LLAMA_SERVER.
- `modules/gpu-bridge/.../VramDetector.java` — MINIMUM_VRAM_FOR_VDU now
  references `HardwareProfile.MINIMUM_VRAM_FOR_GGUF` (single constant).

**Tests (6 files, 35+ test cases):**
- `HardwareProfileTest` — profile selection for all tiers
- `ModelPackageTest` — variant selection per profile, fallback, empty
- `VariantSelectorTest` — optimal/degraded/skipped/missing-from-disk
- `InstallPlannerTest` — GPU/CPU/GPU-lite planning, delta computation
- `ModelRegistryLoaderTest` — v2 JSON deserialization, citation-scorer
  fallback, GGUF enum values
- `InstallContractIOTest` — JSON round-trip, path resolution

### Layers 4-7: Complete (committed)

**Commits on `worktree-381-model-distribution`:**
- `2d7dc84ef` — Layer 4: rewrite install pipeline with hardware-aware
  downloads (AiInstallService, DownloadExecutor, RuntimeRestoreUtil,
  AiInstallStatus, AiInstallController — all v2, VramDetector wired)
- `bfcb0c3db` — Layer 5: runtime integration via ContractConfigContributor
  (service locator bridge — to be replaced by Layer 8)
- `da62e1741` — Layer 6: observability (ModelDistributionStatusView in
  /api/status, upgrade guidance, variant degradation)
- `2fc63f6b4` — Layer 6 follow-up: wire modelDistribution status
- `ba2a62652` — Layer 6 follow-up: wire from install contract
- `8617914e2` — OnnxSessionCache explicit OptLevel (replaces filename sniffing)
- `8a77b5ce1` — jseval --cpu flag for CPU-only inference testing
- `4149839e6` — architectural gap analysis documentation
- `7c5d4da5b` — deeper analysis: service locator → composition root

**Layer 5 note:** `ContractConfigContributor` is a known intermediate
bridge (service locator pattern). It works correctly but will be
replaced by the Layer 8 composition root. Keeping it functional
until Layer 8 is implemented ensures no regression during the refactor.

### Critical Analysis — Resolved

9 issues were identified. 5 fixed, 4 accepted as tradeoffs.

**Fixed (commit `33f86b780`):**

| # | Issue | Fix |
|---|-------|-----|
| 1 | selectVariant null for CPU-only models on GPU profiles | Fall back to first available variant |
| 4 | GGUF tagged FP16/CUDA | New `GGUF` precision + `LLAMA_SERVER` EP enums |
| 5 | VariantSelector null ambiguity (missing vs not installed) | Return degraded selection with "missing from disk" reason |
| 6 | InstallContractIO double-wrapping Jackson exceptions | Let Jackson 3.x RuntimeExceptions propagate naturally |
| 9 | VRAM constant duplicated in HardwareProfile and VramDetector | VramDetector references HardwareProfile.MINIMUM_VRAM_FOR_GGUF |

**Accepted tradeoffs (not fixed):**

| # | Issue | Rationale |
|---|-------|-----------|
| 2 | Planner checks file existence, not hash | Intentional. Planning avoids hashing multi-GB files. Execution phase verifies hashes. |
| 3 | FP32 embedding placeholder in registry | Blocked on external work (build + upload to GitHub Releases). Not a code fix. |
| 7 | ModelRegistryLoader intermediate Raw records | Defensive, correct. Decouples JSON shape from domain types. Low maintenance. |
| 8 | Test helper duplication | Minor. 4 self-contained test files. Shared fixtures add coupling for little benefit. |

### Layer 8: Composition Root — Complete

Replaces the Layer 5 service locator bridge (`ContractConfigContributor`
→ system properties → per-encoder discovery) with direct constructor
injection of pre-configured `OrtSessionManager` instances.

**Phase 8a: Create `ModelSessionFactory`**

New class in `ort-common` (depends on both `VariantSelection` from
configuration and `OrtSessionManager` from ort-common). Pure function:

```java
ModelSessionFactory.create(
    consumerName,          // "embed", "splade", etc.
    VariantSelection,      // resolved model identity
    GpuSessionConfig,      // per-model GPU memory (null for CPU-only)
    BooleanSupplier,       // shouldUseGpu from signal bus
    Path nativePath,       // ORT CUDA DLL directory
    SessionOverrides)      // per-encoder: gpuRetryEnabled, deferCpu, onBeforeGpuRelease
  → OrtSessionManager
```

Centralizes the OptLevel mapping:
- `FP16 + CPU` → `BASIC_OPT` (avoids 30+ min Cast catastrophe)
- All other combinations → `EXTENDED_OPT`

Derives CPU/GPU model paths from `VariantSelection.modelFile()`:
- If EP is CUDA: both cpuModelPath and gpuModelPath point to the same
  FP16 file (single-variant download). OrtSessionManager's GPU→CPU
  fallback uses the same file on CUDA EP.
- If EP is CPU: cpuModelPath is the file, gpuModelPath is null (no GPU).

**Phase 8b: Unify BgeM3Encoder to use `OrtSessionManager`**

BgeM3Encoder currently duplicates the full OrtSessionManager GPU lifecycle:
- Custom `volatile OrtSession gpuSession` with double-checked lock
- Custom `gpuFailedAtMs` retry timer (60s)
- Custom `gpuSessionReleasing` flag
- Custom `OrtCudaStatus` management
- Direct calls to `OrtSessionFactory.createGpuSessionWithFallback()`

Refactor to use `OrtSessionManager` like all other encoders. The factory
creates the session manager with the correct OptLevel (derived from
VariantSelection precision). BgeM3's custom `BASIC_OPT` logic moves to
`ModelSessionFactory`.

Also removes the hardcoded filename constants (`FP16_MODEL_FILE`,
`FP32_MODEL_FILE`) and `Files.exists()` model path selection — the
composition root resolves the correct path from the contract.

**Phase 8c: Migrate CitationScorer to `OrtSessionManager`**

CitationScorer currently bypasses OrtSessionManager with a direct
`OnnxSessionCache.createCachedSession(env, modelPath)` call. Migrate to
`OrtSessionManager` (CPU-only config, no GPU). This unifies all 6
encoders on the same session lifecycle infrastructure.

**Phase 8d: Refactor encoder constructors**

Each encoder's constructor changes from "discover model, build session
manager" to "receive session manager, load tokenizer, detect features":

| Encoder | Removes from constructor | Receives instead |
|---|---|---|
| `OnnxEmbeddingEncoder` | ModelManifest, path resolution, OrtSessionManager builder | `OrtSessionManager` + `modelDir` |
| `SpladeEncoder` | SpladeConfig path, ModelManifest, OrtSessionManager builder | `OrtSessionManager` + `modelDir` |
| `BgeM3Encoder` | BgeM3Config path, filename constants, custom GPU lifecycle | `OrtSessionManager` + `modelDir` |
| `BertNerInference` | ModelManifest, path resolution, OrtSessionManager builder | `OrtSessionManager` + `modelDir` |
| `CrossEncoderReranker` | ModelManifest, ConfigStore GPU mem, OrtSessionManager builder | `OrtSessionManager` + `tokenizerPath` |
| `CitationScorer` | Direct OnnxSessionCache call | `OrtSessionManager` + `tokenizerPath` |

Each encoder keeps: inference logic, tokenizer loading, output format
detection (token_type_ids, pooling strategy, SPLADE output format),
model-specific parameters (maxSeqLen, batch size).

Convenience constructors for tests (CPU-only, 2-3 args) remain — they
internally create a CPU-only `OrtSessionManager` so existing tests
compile unchanged.

**Phase 8e: Refactor `KnowledgeServer.initDeferredModels()` as
composition root**

The method becomes the single place where all model dependencies are
resolved and injected:

1. Read install contract from AI Home via `InstallContractIO.read()`
2. If no contract (dev mode): fall back to existing discovery
   (`OnnxModelDiscovery` + `ModelManifest`) to construct
   `VariantSelection` objects. Discovery becomes a dev-mode utility
   used only in the composition root, not in each encoder.
3. Resolve `VariantSelection` per model via `VariantSelector.select()`
4. Build `HardwareProfile` from existing GPU detection state
5. Create `OrtSessionManager` per model via `ModelSessionFactory`
6. Pass session managers to encoder constructors
7. Keep existing wiring logic (appServices.wireXxx, GPU diagnostics,
   model ready latch, BGE-M3/SPLADE fallback chain)

**Phase 8f: Handle lazy consumers**

Three consumers create ORT sessions outside `initDeferredModels()`:

- **NerService**: Currently stores `NerConfig` and constructs
  `BertNerInference` lazily on first `extractEntities()`. Change to
  receive a pre-configured `OrtSessionManager` + model dir from the
  composition root. `NerService.ensureInitialized()` passes these to
  `BertNerInference` instead of loading manifest and building config.

- **CitationMatchOps**: Creates `CitationScorer` lazily via
  `getCitationScorer()`. Change to receive an `OrtSessionManager` +
  tokenizer path from the composition root (wired through
  `DefaultWorkerAppServices`). The lazy init uses these instead of
  `CitationScorerConfig.fromEnv()`.

- **RagContextOps (chunk reranker)**: Uses the shared search reranker
  from `KnowledgeServer`. CPU-only fallback creates its own
  `CrossEncoderReranker` — this fallback receives config from the
  composition root rather than from `RerankerConfig.ChunkRerankerConfig`.

**Phase 8g: Delete service locator artifacts**

- `ContractConfigContributor` — replaced by composition root direct
  reading. Remove the `contributeFromContract()` call from
  `IndexerWorker.java`.
- Manifest generation in `AiInstallService.writeModelManifests()` —
  no production consumer reads manifests anymore. Keep manifests only
  for dev mode (manually-placed models without a contract).
- Per-encoder `*Config.fromEnv()` path resolution — composition root
  resolves paths. Config classes still exist for model-specific
  parameters (maxSeqLen, GPU memory limits, activation flags).
- `ModelManifest.loadOrDefault()` calls in encoder constructors —
  session manager already has correct paths.
- Per-model discovery delegates (`EmbeddingOnnxModelDiscovery`,
  `SpladeModelDiscovery`, `NerModelDiscovery`, `BgeM3ModelDiscovery`)
  — base `OnnxModelDiscovery` stays for dev-mode fallback in the
  composition root only.

**EmbeddingService abstraction layer:**

`OnnxEmbeddingEncoder` is created through `EmbeddingService` →
`EmbeddingProviderRegistry` → `OnnxEmbeddingProvider.create()`. The
composition root either bypasses this abstraction (creates
`OnnxEmbeddingEncoder` directly with a session manager) or injects
through it (passes the session manager to `OnnxEmbeddingProvider`).
Since there's only one embedding provider ("onnx"), direct creation
is simpler.

**Module dependency for `ModelSessionFactory`:**

Needs both `VariantSelection` (configuration module) and
`OrtSessionManager` (ort-common module). Options:
- Place in `ort-common` with a dependency on `configuration` (natural —
  ort-common already has a dependency path to configuration)
- Place in `indexer-worker` alongside the composition root

**Dev mode fallback:**

When no install contract exists (running from source, first launch
before Install AI), the composition root constructs `VariantSelection`
objects from existing discovery:

```java
// In composition root — dev mode fallback
if (contract == null) {
  Path modelDir = OnnxModelDiscovery.resolve(explicitPath, "splade", ...);
  ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
  // Construct VariantSelection from discovered state
  embedSelection = VariantSelection.optimal(
      manifest.resolveModelPath(modelDir, gpuEnabled),
      gpuEnabled ? ModelPrecision.FP16 : ModelPrecision.FP32,
      gpuEnabled ? ExecutionProvider.CUDA : ExecutionProvider.CPU);
}
```

This keeps `OnnxModelDiscovery` and `ModelManifest` as utilities but
only used in the composition root's dev-mode path, not in each encoder.

**Layer 8 implementation status: Complete (2026-04-09)**

Commits on `worktree-381-model-distribution`:
- `7b146b0f9` — Phases 8a-8d: ModelSessionFactory, BgeM3 unification,
  CitationScorer migration. Created `ModelSessionFactory` in ort-common
  (centralized VariantSelection → OrtSessionManager mapping with
  OptLevel derivation). Unified BgeM3Encoder onto OrtSessionManager
  (deleted ~100 lines of duplicated GPU lifecycle). Migrated
  CitationScorer to OrtSessionManager. Added `cpuOptLevel` to
  OrtSessionManager.Builder and `setOnBeforeGpuRelease` setter.
- `4329cc27f` — Phase 8e: session-manager constructors for all 4
  standard encoders (OnnxEmbeddingEncoder, SpladeEncoder,
  BertNerInference, CrossEncoderReranker). Existing constructors
  delegate to the new ones.
- `e268f8cce` — Phase 8f (partial): composition root wired in
  `KnowledgeServer.initDeferredModels()` for BGE-M3, SPLADE, and
  search reranker. Contract reading + VariantSelector + ModelSessionFactory
  → encoder constructors. Dev-mode fallback via `resolveVariant()`.
- `d9b8c588f` — Phase 8f (complete): composition root for embedding
  and NER. Added `EmbeddingService.createWithBackend()` factory
  (bypasses provider registry). Added OrtSessionManager constructor
  to NerService (lazy tokenizer/label loading preserved). All 5 ONNX
  encoders now wired through the composition root.
- `38c188048` — Phase 8g: delete service locator artifacts.
  Removed `ContractConfigContributor` (system property bridge),
  its test, the `contributeFromContract()` call in IndexerWorker,
  and `writeModelManifests()` from AiInstallService.

### Layer 8 Critical Analysis — Resolved (2026-04-09)

Four centralization gaps identified and fixed (commit `0310e8bc6`):

- **Issue 1:** BgeM3 OptLevel duplication → replaced with
  `ModelSessionFactory.deriveCpuOptLevel()` call
- **Issue 2:** CitationMatchOps bypassed composition root → added
  `OrtSessionManager` injection via `wireCitationScorerSessions()`
- **Issue 3:** Convenience constructors bypassed OptLevel derivation →
  all 5 now call `deriveCpuOptLevel()`
- **Issue 4:** Embedding catch-block silently fell back to legacy →
  now logs error and does not mask factory failures

Post-merge fixes (commits `093983efe`, `fa588d8b2`, `974570ff5`):
- `shouldUseGpu` timing: embedding composition root now uses eager
  GPU (`() -> true` when gpuEnabled), matching old constructor behavior
- `resolveVariant` file-existence: checks `Files.exists()` for CPU
  and GPU model files (including `.optimized` cache) before returning

Dev server validation: all 5 ONNX encoders initialize correctly
through the composition root. BFCArena OOM errors during concurrent
GPU warmup are pre-existing (verified: same errors on pre-merge
baseline commit `b1d356bd1`).

**Centralization summary:**

| Check | Status |
|---|---|
| Production path centralized (contract → factory → inject) | **Yes** — all 5 encoders |
| OptLevel mapping centralized | **Yes** — `deriveCpuOptLevel()` called by all paths |
| `ContractConfigContributor` deleted | **Yes** |
| `writeModelManifests()` removed | **Yes** |
| `createCachedSession` only in OrtSessionManager | **Yes** |
| `createGpuSessionWithFallback` only in OrtSessionManager | **Yes** |
| "Add INT4" blast radius | **1 file** (`deriveCpuOptLevel` + enum) |
| CitationMatchOps wired through composition root | **Yes** |
| RagContextOps chunk reranker fallback | **Accepted** (transient startup guard) |

## Related Files

| File | Purpose |
|------|---------|
| `modules/ort-common/.../OnnxSessionCache.java` | Graph optimization caching, explicit OptLevel parameter |
| `modules/ort-common/.../OrtSessionManager.java` | Session lifecycle, CPU/GPU selection, failure recovery |
| `modules/ort-common/.../OrtSessionFactory.java` | GPU session creation with FP16→FP32 fallback |
| `modules/ort-common/.../ModelManifest.java` | Manifest loading — becomes dev-mode-only after Layer 8 |
| `modules/configuration/.../OnnxModelDiscovery.java` | Auto-discovery — becomes dev-mode-only after Layer 8 |
| `modules/ort-common/.../ModelSessionFactory.java` | Centralized VariantSelection → OrtSessionManager factory |
| `modules/configuration/.../VariantSelector.java` | Selects variant from contract + hardware |
| `modules/configuration/.../InstallContractIO.java` | Reads/writes install contract |
| `modules/worker-core/.../OnnxEmbeddingEncoder.java` | Embedding encoder — refactored in Phase 8d |
| `modules/worker-core/.../SpladeEncoder.java` | SPLADE encoder — refactored in Phase 8d |
| `modules/worker-core/.../BertNerInference.java` | NER encoder — refactored in Phase 8d |
| `modules/worker-core/.../bgem3/BgeM3Encoder.java` | BGE-M3 — unified to OrtSessionManager in Phase 8b |
| `modules/reranker/.../CrossEncoderReranker.java` | Reranker — refactored in Phase 8d |
| `modules/reranker/.../CitationScorer.java` | Citation scorer — migrated to OrtSessionManager in 8c |
| `modules/worker-core/.../embed/EmbeddingService.java` | Embedding service — simplified in Phase 8e |
| `modules/worker-core/.../ner/NerService.java` | NER lazy init — receives session manager in Phase 8f |
| `modules/worker-services/.../CitationMatchOps.java` | Citation lazy init — receives session manager in 8f |
| `modules/worker-services/.../RagContextOps.java` | Chunk reranker lazy init — receives config in 8f |
| `modules/indexer-worker/.../server/KnowledgeServer.java` | Composition root — rewritten in Phase 8e |
| `modules/ui/.../ai/install/AiInstallService.java` | Install pipeline — manifest generation removed in 8g |
| `modules/gpu-bridge/.../VramDetector.java` | VRAM detection |
| `modules/ui/.../api/LocalApiServer.java` | Head process controller wiring |
