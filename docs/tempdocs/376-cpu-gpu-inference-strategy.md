---
title: "CPU vs GPU Inference Strategy"
status: done
created: 2026-04-06
updated: 2026-04-08
parent: 375
related: [375, 374, 378, 381]
---

# 376. CPU vs GPU Inference Strategy

Design and finalize the inference strategy for CPU-only vs GPU machines.
Discovered during sandbox validation (tempdoc 375): the current model
distribution is GPU-only, making enrichment broken or unusably slow on
CPU-only machines.

## Scope

This tempdoc defines the problem and inventories all issues. The
architecture design has been moved to **tempdoc 381** (Model Distribution
Architecture).

## Problem Statement

JustSearch's Install AI flow downloads 8.5 GB of models. On CPU-only
machines (no NVIDIA CUDA), enrichment either hangs (G29, fixed with
`BASIC_OPT` but still very slow) or produces unusably slow inference.
Users download 8.5 GB for zero visible benefit.

## Current State

### Model manifest declares FP16 for both CPU and GPU

Embedding (`onnx/gte-multilingual-base/model_manifest.json`):
```json
{"cpu": "model_fp16.onnx", "gpu": "model_fp16.onnx"}
```

SPLADE (`splade/naver-splade-v3/model_manifest.json`):
```json
{"cpu": "model.onnx", "gpu": "model_fp16.onnx"}
```

SPLADE is correct (FP32 for CPU, FP16 for GPU). Embedding is wrong —
both point to FP16, which is catastrophic on CPU.

### What happens on CPU with FP16

1. ORT CPU EP doesn't natively support FP16 operations
2. Graph optimizer inserts Cast (FP16->FP32) nodes before every operation
3. At `EXTENDED_OPT`, this causes 30-60+ min optimization (G29 — now
   mitigated to `BASIC_OPT`, reducing to ~5-10 min estimated)
4. Even after optimization, every inference call pays FP16->FP32 cast
   overhead at runtime — embedding that takes 20ms on GPU takes 2-5s on CPU
5. For a library of 1000 documents, enrichment could take hours

### What the registry ships

- Only FP16 embedding model (628 MB) — no FP32 variant (1.26 GB)
- FP32 exists on the dev machine at `models/onnx/gte-multilingual-base/model.onnx`
  but is not in the model registry
- SPLADE correctly ships both FP32 (model.onnx) and FP16 (model_fp16.onnx)
- Reranker ships FP32 (model.onnx) — FP16 fails to download (G30, 404)
- NER ships both FP32 and FP16

### G29 mitigation status

`OnnxSessionCache.optimizeAndCache()` now uses `BASIC_OPT` for FP16 models
on CPU (instead of `EXTENDED_OPT`). This reduces first-run optimization from
30-60+ min to an estimated 5-10 min. **Not yet validated in sandbox.**

Open question: does `BASIC_OPT` produce a functional model on CPU? The Cast
nodes are still inserted, but graph rewriting is less aggressive. Need to
verify that inference actually works and produces correct embeddings.

## Early Design Options (Historical)

These were the initial options considered before the full issues inventory
and design considerations were developed. Retained for context.

| Option | Summary | Limitation |
|--------|---------|------------|
| A: Ship FP32 CPU model | Add `model.onnx` to registry, update manifest | Fixes embedding only; doesn't address registry structure, detection, or disk budget |
| B: Pre-optimize offline | Ship `.optimized` graph | Tied to ORT version; still pays FP16→FP32 Cast overhead at runtime |
| C: CPU download profile | GPU detection at install time, skip GPU assets | Two download paths; detection may be wrong (switchable GPU, sandbox vGPU) |
| D: Warn and proceed | Show estimated performance before download | Doesn't fix the architectural problem; users still download 8.5 GB |

The full architecture design is in **tempdoc 381** (Model Distribution
Architecture).

---

## Comprehensive Issues Inventory

Investigated 2026-04-08. All issues documented regardless of severity.
Cross-referenced: tempdocs 375, 376, 378; model registry; all ONNX
encoder constructors; install pipeline; model discovery infrastructure.

### Category 1 — Embedding Model CPU/GPU Selection (Core Problem)

**I1. Embedding manifest declares FP16 for CPU**
- **File:** `models/onnx/gte-multilingual-base/model_manifest.json`
- **State:** `"cpu": "model_fp16.onnx", "gpu": "model_fp16.onnx"` — both
  variants point to the same FP16 file.
- **Effect:** On CPU-only machines, the embedding encoder loads FP16. ORT
  CPU EP inserts Cast (FP16→FP32) nodes before every operation, causing
  ~2-5s per embedding inference call (vs 20ms on GPU). For a 1000-document
  library, enrichment takes hours.
- **Why it persists:** The FP32 model exists on the dev machine
  (`model.onnx`, 1.26 GB) but was never uploaded to the GitHub Releases
  registry. The manifest was written for GPU development.

**I2. FP32 embedding model not in the registry**
- **File:** `modules/ui/src/main/resources/ai/model-registry.v1.json`
- **State:** Only `onnx-embed-model` (FP16, 628 MB) is registered. No
  `onnx-embed-model-fp32` entry exists. Production installs receive only
  the FP16 variant.
- **On-disk reality:** Dev machine has both `model.onnx` (1.26 GB, FP32)
  and `model_fp16.onnx` (628 MB, FP16) in the same directory. Only the
  FP16 is distributed.

**I3. Registry distributes the incorrect manifest**
- **File:** `model-registry.v1.json:117-126` (asset `onnx-embed-manifest`)
- **State:** The manifest asset installs the file that declares `cpu →
  model_fp16.onnx`. Even if the FP32 model were added to the registry and
  downloaded, the distributed manifest would still point CPU users to FP16.
  Both the manifest content AND the registry entry for the FP32 model must
  be updated.

**I4. BASIC_OPT mitigation uses filename-sniffing heuristic**
- **File:** `modules/ort-common/.../OnnxSessionCache.java:78`
- **Code:** `boolean isFp16 = modelPath.getFileName().toString().contains("fp16");`
- **Fragility:** Any model file without `fp16` in its name (e.g., renamed,
  or a future model naming convention) would not trigger the mitigation and
  would fall back to EXTENDED_OPT, reintroducing the 30-60 min hang.
  The detection should be based on the model's actual data type (inspectable
  via ORT session metadata), not filename convention.

**I5. BASIC_OPT correctness is unvalidated**
- **File:** `OnnxSessionCache.java:75-80`
- **State:** The G29 mitigation changed from EXTENDED_OPT to BASIC_OPT for
  FP16 on CPU. This reduces optimization time from 30-60+ min to ~5-10 min
  estimated. However, nobody has verified that BASIC_OPT produces a
  functional optimized graph — the Cast nodes are still inserted, but
  graph rewriting is less aggressive. The model may produce incorrect
  embeddings (wrong vectors, NaN, zeros) under BASIC_OPT.

**I6. First-run CPU optimization blocks encoder construction**
- **File:** `OnnxEmbeddingEncoder.java:104-126`
- **State:** On CPU-only machines (`gpuEnabled=false`), `deferCpuSession`
  is false, so `OrtSessionManager.build()` eagerly creates the CPU
  session. This calls `OnnxSessionCache.createCachedSession()` which runs
  BASIC_OPT graph optimization synchronously. Estimated 5-10 min blocking
  the encoder constructor on first run. No `.optimized` cache exists yet.
  The worker appears hung during this period.

### Category 2 — Reranker FP16 Missing Asset

**I7. Reranker FP16 registry entry points to 404**
- **File:** `model-registry.v1.json:277-289` (asset `onnx-reranker-gte-fp16`)
- **State:** URL `https://github.com/eliasjustus/justsearch-releases/releases/download/models-v1/reranker-model_fp16.onnx`
  returns 404. The ONNX file was never built and uploaded.
- **Effect:** Install AI download of this asset fails. The install pipeline
  now continues past the failure (per-download failures `continue`), so
  other assets still install. But the reranker FP16 is marked `failed`.
- **GPU impact:** On GPU machines, `OrtSessionManager.tryCreateGpuSession`
  tries to load the missing `model_fp16.onnx`, gets `OrtException`, falls
  back via `createGpuSessionWithFallback` to load `model.onnx` (FP32) with
  the CUDA provider. This works but runs FP32 on GPU instead of FP16 —
  higher VRAM usage and ~2x slower inference than FP16 would provide.

**I8. Reranker has no model_manifest.json**
- **File:** `models/onnx/reranker/` — no `model_manifest.json` present
- **State:** Uses `ModelManifest.loadOrDefault()` convention: `model.onnx`
  for CPU, `model_fp16.onnx` for GPU. This is correct in the default case,
  but explicit manifests are the documented standard (per tempdoc 340).
  NER and citation-scorer directories also lack manifests.
- **Registry:** No manifest asset for reranker or NER in the registry.
  Only embedding and SPLADE have manifest entries.

### Category 3 — Install Pipeline Issues

**I9. arrangeAssetIfNeeded copies without deleting originals**
- **File:** `AiInstallService.java:661-686`
- **Code:** `Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);`
- **State:** Assets are downloaded to `<modelsDir>/<filename>` (flat), then
  copied to `<modelsDir>/<targetPath>` (subdirectory). The flat-file
  original is never deleted. For 8.5 GB of assets, all arranged files
  consume double disk space.
- **Example:** `embed-gte-multilingual-base-fp16.onnx` (628 MB) at the
  flat path AND `onnx/gte-multilingual-base/model_fp16.onnx` (628 MB) at
  the target path = 1.26 GB for one model.

**I10. validateAssetConfigured abort vs download continue inconsistency**
- **File:** `AiInstallService.java:244-248` vs `284-292`
- **State:** `validateAssetConfigured()` failures call `fail()` + `return`,
  aborting the entire pipeline. Per-download failures (404, etc.) call
  `continue`, allowing remaining assets to proceed. Two failure modes exist
  but only the download path was made resilient.
- **Effect:** A misconfigured registry entry (missing URL, placeholder hash)
  stops all downloads, even for assets that are valid and could succeed.

**I11. No GPU detection at download time**
- **File:** `AiInstallService.java:170-374` (entire `runInstallInternal`)
- **State:** All 24 registry assets are downloaded unconditionally. No check
  for GPU availability. CPU-only users download:
  - FP16 embedding (628 MB) — unusable without GPU
  - FP16 SPLADE (498 MB) — unusable without GPU
  - FP16 NER (270 MB) — unusable without GPU
  - FP16 reranker (629 MB, 404) — unusable without GPU
  - GGUF chat model (5 GB) — unusably slow without GPU
  - GGUF mmproj (1.16 GB) — unusable without GPU
  Total GPU-only waste on CPU machines: ~8.2 GB of 8.5 GB total download.

**I12. applySettingsFromInstalledFiles only configures chat model**
- **File:** `AiInstallService.java:626-659`
- **State:** Only `UiSettings.setLlmModelPath()` is set. ONNX model paths
  (embed, SPLADE, NER, reranker, citation-scorer) are NOT configured via
  settings. They rely on auto-discovery by `OnnxModelDiscovery` scanning
  model roots. This works (Install AI places models into the expected
  directory structure), but means model paths are implicit — if the
  discovery logic changes or the directory structure drifts, models silently
  become unfindable.

**I13. Sequential downloads for 8.5 GB**
- **File:** `AiInstallService.java:238` comment: "Download each asset
  sequentially (v1)."
- **State:** 24 assets are downloaded one at a time. No parallelism.
  On slow connections, the total download time is purely additive.
  Documented as "v1" intentional limitation.

### Category 4 — GPU Detection and Session Heuristics

**I14. hasCudaProviderConfigured uses toString() heuristic**
- **File:** `OnnxSessionCache.java:130-137`
- **Code:** `opts.toString().toLowerCase().contains("cuda")`
- **State:** ORT Java API doesn't expose provider introspection. The
  fallback is to parse `toString()` for the string "cuda". This is
  undocumented ORT behavior — `toString()` format could change across
  ORT versions without warning. A false negative here would cause FP16
  GPU sessions to receive BASIC_OPT instead of EXTENDED_OPT, producing
  a suboptimal (but likely functional) optimized graph.

**I15. OnnxSessionCache sidecar uses mtime for cache invalidation**
- **File:** `OnnxSessionCache.java:149-166`
- **State:** Cache validity checks `mtime` + `size` + `ort version`. File
  mtime can change without content changing (e.g., `Files.copy` preserves
  content but not always mtime depending on OS). Conversely, a file could
  be replaced with different content at the same size and the cache would
  not invalidate (though this is unlikely for ONNX models). A content hash
  (SHA-256) would be more robust but adds first-run cost for large models.

### Category 5 — Model Discovery Edge Cases

**I16. Embedding discovery has no dev-layout fallback**
- **File:** `EmbeddingOnnxModelDiscovery.java:27-28`
- **Code:** `OnnxModelDiscovery.resolve(explicitPath, MODEL_NAME, null)`
- **State:** `devSubdir` is `null`. Unlike SPLADE discovery (which passes
  `"splade/naver-splade-v3"` as devSubdir), embedding has no dev-layout
  fallback path. This means embedding is only found in `onnx/gte-multilingual-base/`
  under a model root, or via explicit env var.

**I17. Discovery requires model_manifest.json for FP16-only directories**
- **File:** `OnnxModelDiscovery.java:127-146`
- **State:** `DEFAULT_REQUIRED_FILES` = `["model.onnx", "tokenizer.json"]`.
  A directory with only `model_fp16.onnx` (no `model.onnx`) must have a
  `model_manifest.json` to be discovered. This fallback works currently
  (the manifest is installed by the registry), but if the manifest asset
  fails to download, the embedding model directory becomes invisible to
  auto-discovery — even though the FP16 model file is there.

**I18. No existence check on manifest-resolved model paths**
- **File:** `ModelManifest.java:104-109`
- **Code:** `return modelDir.resolve(cpu);` — returns path without checking
  `Files.exists()`.
- **State:** If the manifest declares a model file that doesn't exist
  (e.g., `model.onnx` in a directory with only `model_fp16.onnx`),
  `resolveModelPath` returns a non-existent path. The error surfaces
  later as an ORT exception during session creation, not as a clear
  "model file not found" error. This makes debugging harder.

### Category 6 — Cross-Model Consistency Gaps

**I19. Model manifest coverage is inconsistent**
- **State summary:**
  | Model | Manifest exists | In registry | Correct CPU/GPU |
  |-------|----------------|-------------|-----------------|
  | Embedding | Yes | Yes | **No** (FP16 for both) |
  | SPLADE | Yes | Yes | Yes (FP32/FP16) |
  | NER | No | No | Convention correct (INT8/FP16) |
  | Reranker | No | No | Convention correct (FP32/FP16*) |
  | Citation | No | No | CPU-only, single model |
  
  \* Reranker FP16 doesn't exist on production installs (404).

**I20. CitationScorer bypasses OrtSessionManager entirely**
- **File:** `modules/reranker/.../CitationScorer.java:60-62`
- **State:** Uses `OnnxSessionCache.createCachedSession(env, modelPath)`
  directly. No `OrtSessionManager`, no GPU support, no ModelManifest. All
  other ONNX consumers (embed, SPLADE, NER, reranker) use
  `OrtSessionManager` with manifest-driven path selection. This is
  documented as intentional (D8 in tempdoc 359), but it means
  CitationScorer is the only consumer without GPU acceleration capability
  or session lifecycle management (no failure recovery, no BFCArena reset).

**I21. Reranker GPU falls back to FP32 silently on fresh installs**
- **File:** `OrtSessionManager.java:379-392`, `OrtSessionFactory.java:152-165`
- **State:** Because the FP16 reranker download 404s (I7), GPU machines
  have only `model.onnx` (FP32). `tryCreateGpuSession` tries `model_fp16.onnx`
  → OrtException → `createGpuSessionWithFallback` → loads `model.onnx`
  (FP32) with CUDA provider. Result: reranker runs FP32 on GPU — functional
  but higher VRAM usage (~341 MB vs ~171 MB FP16) and slower inference.
  The fallback is logged at WARN but the user has no visibility into
  degraded GPU utilization.

### Category 7 — Download Size and User Experience

**I22. Total download is 8.5 GB with no hardware-aware filtering**
- **State:** All users download the same 24 assets regardless of hardware.
  On CPU-only machines, the only useful downloads are:
  - FP32 SPLADE (995 MB) — `model.onnx` for CPU
  - NER INT8 (135 MB) — `model.onnx` for CPU
  - NER config/tokenizer (~3 MB)
  - Reranker FP32 (341 MB)
  - Reranker config/tokenizer (~19 MB)
  - Citation scorer + tokenizer (~24 MB)
  - Embedding FP16 (628 MB) — usable but degraded (I1)
  - Embedding tokenizer/manifest/pooling (~17 MB)
  Total CPU-useful: ~2.2 GB. Total downloaded: ~8.5 GB. Waste: ~6.3 GB.

**I23. GGUF chat model has no CPU fallback strategy**
- **State:** The GGUF chat model (5 GB) and mmproj (1.16 GB) require
  llama-server, which needs GPU VRAM for acceptable performance. On CPU,
  inference is very slow (minutes per response). These 6.16 GB are
  downloaded regardless, with no warning or opt-out for CPU-only users.
  No quality gate exists to detect that chat will be unusable on CPU.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

CPU vs GPU inference strategy exploration. ADR-0019 (CPU vs GPU model selection — FP32 for CPU, FP16 for GPU, model-manifest selection) is the structural decision that consumed this strategy work.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

