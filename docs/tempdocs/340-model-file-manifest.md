---
title: "340: Model File Manifest Convention"
type: tempdoc
status: done
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 340: Model File Manifest Convention

## Problem

Model directories contain multiple ONNX variants with implicit
naming conventions:

```
models/onnx/embeddinggemma-300m/
  model.onnx          # Q4 (GPU default)
  model_int8.onnx     # INT8 (CPU fallback)
  model.onnx.sha256   # fingerprint
  model.onnx.optimized # ORT graph-optimized cache

models/onnx/ner/
  model.onnx          # INT8 (CPU default)
  model_fp16.onnx     # FP16 (GPU)
  model.onnx.optimized # ORT cache

models/splade/naver-splade-v3/
  model.onnx          # FP32 (CPU, gets graph-optimized)
  model_fp16.onnx     # FP16 (GPU)
  model_int8.onnx     # INT8 (unused)
  v2-backup/          # old model files
```

Which file is the active default? Which is CPU vs GPU? The
convention is encoded in each encoder's constructor:
- `OnnxEmbeddingEncoder`: `model.onnx` (CPU), `model_fp16.onnx`
  (GPU), `model_int8.onnx` (CPU fallback when GPU not configured)
- `SpladeEncoder`: `model.onnx` (CPU), `model_fp16.onnx` (GPU)
- `BertNerInference`: `model.onnx` (CPU), `model_fp16.onnx` (GPU)

This caused the Q4 CPU regression: `model.onnx` was replaced with
Q4 (designed for GPU) but the CPU path still loaded it. The point
fix (334 item 16) patched `OnnxEmbeddingEncoder` to detect
`model_int8.onnx`, but the root cause — implicit conventions with
no single source of truth — remains across all three encoders.

## Design

### Schema — `model_manifest.json`

Each model directory gets a manifest declaring which files serve
which role:

```json
{
  "cpu": "model_int8.onnx",
  "gpu": "model_fp16.onnx",
  "tokenizer": "tokenizer.json",
  "pooling_config": "pooling_config.json",
  "label_config": "config.json"
}
```

| Field            | Required | Description |
|------------------|----------|-------------|
| `cpu`            | yes      | ONNX model file for CPU execution provider |
| `gpu`            | no       | ONNX model file for GPU (CUDA EP). Falls back to `cpu` if absent |
| `tokenizer`      | no       | Tokenizer file name (default: `tokenizer.json`) |
| `pooling_config` | no       | Pooling config for embedding (default: `pooling_config.json`) |
| `label_config`   | no       | Label mapping config for NER (default: `config.json`) |

### Deployed manifests

```json
// models/onnx/embeddinggemma-300m/model_manifest.json
{ "cpu": "model_int8.onnx", "gpu": "model.onnx", "tokenizer": "tokenizer.json", "pooling_config": "pooling_config.json" }

// models/onnx/ner/model_manifest.json
{ "cpu": "model.onnx", "gpu": "model_fp16.onnx", "tokenizer": "tokenizer.json", "label_config": "config.json" }

// models/splade/naver-splade-v3/model_manifest.json
{ "cpu": "model.onnx", "gpu": "model_fp16.onnx", "tokenizer": "tokenizer.json" }
```

### Utility — `ModelManifest.java`

Location: `modules/worker-core/.../ort/ModelManifest.java`

Lives in the `ort` package alongside `OrtCudaHelper` — shared ORT
infrastructure consumed by all three encoders. Not in
`modules/configuration` because this is an encoder-layer concern,
not path-resolution. `OnnxModelDiscovery` (in `configuration`)
answers "where is the model directory?"; `ModelManifest` answers
"which files in that directory serve which role?".

```java
// Record holding parsed manifest — Jackson 3.1 deserializes
// records via Class.getRecordComponents() (no -parameters flag
// needed, no annotations needed). Confirmed by existing pattern:
// IndexGenerationManager.State record + JSON.readValue().
record ModelManifest(String cpu, String gpu, String tokenizer,
    String pooling_config, String label_config) {

  // Read model_manifest.json from modelDir. Throws if missing.
  static ModelManifest load(Path modelDir);

  // Read manifest, or fall back to convention (model.onnx CPU,
  // model_fp16.onnx GPU) for backward compat with external dirs.
  static ModelManifest loadOrDefault(Path modelDir);

  // Resolve absolute path for the active model given GPU config.
  // Returns gpu path when gpuEnabled && gpu field is non-null,
  // otherwise returns cpu path.
  Path resolveModelPath(Path modelDir, boolean gpuEnabled);
}
```

Implementation notes:
- Jackson 3.x `readValue` throws unchecked `JacksonException`
  (extends `RuntimeException`), not checked `IOException`.
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled for forward compat.
- `loadOrDefault` falls back to `new ModelManifest("model.onnx",
  "model_fp16.onnx", "tokenizer.json", "pooling_config.json",
  "config.json")`.

### Encoder changes

Each encoder replaces its hardcoded path logic with:
```java
ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
this.modelPath = manifest.resolveModelPath(modelDir, false);   // CPU
this.gpuModelPath = manifest.resolveModelPath(modelDir, true);  // GPU
```

Production encoders use `loadOrDefault()` (not `load()`) so that
external model directories without a manifest degrade gracefully
to the pre-manifest naming convention. Bundled directories have
manifests and use them; external directories fall back.

The `OnnxEmbeddingEncoder` INT8 fallback logic (334 item 16)
becomes unnecessary — the manifest explicitly declares which file
is the CPU model.

### Test impact

**Unaffected** — model discovery tests (`SpladeModelDiscoveryTest`,
`NerModelDiscoveryTest`) create temp dirs with dummy files to test
directory discovery. The manifest is read inside the encoder
constructor, not the discovery layer. Discovery tests never
construct an encoder, so they don't need manifests.

**Unaffected** — integration tests (`OnnxEmbeddingEncoderIntegrationTest`,
`EmbeddingBatchSweepTest`, `SpladeBatchSweepTest`) point at real
`models/` directories, which will have manifests after item 2-4.

**Unaffected** — `EmbeddingServiceCacheTest`, `NerServiceTest` use
`DISABLED` configs and never construct real encoders.

### Scope exclusion — reranker

`CrossEncoderReranker` and `CitationScorer` use a single
`model.onnx` (CPU-only, no GPU variant selection). No manifest
needed — they don't have the CPU/GPU ambiguity problem.

## Implementation

- [x] 1. Create `ModelManifest` record + `load`/`loadOrDefault`/`resolveModelPath` in `modules/worker-core/.../ort/`
- [x] 2. Add `model_manifest.json` to `models/onnx/embeddinggemma-300m/`
- [x] 3. Add `model_manifest.json` to `models/onnx/ner/`
- [x] 4. Add `model_manifest.json` to `models/splade/naver-splade-v3/`
- [x] 5. Update `OnnxEmbeddingEncoder` to use `ModelManifest.loadOrDefault()` — remove hardcoded INT8/Q4 fallback logic
- [x] 6. Update `SpladeEncoder` to use `ModelManifest.loadOrDefault()`
- [x] 7. Update `BertNerInference` to use `ModelManifest.loadOrDefault()`
- [x] 8. Unit tests for `ModelManifest` (load, loadOrDefault, missing file, missing gpu field)
- [x] 9. Verify: `./gradlew.bat build -x test` + `./gradlew.bat :modules:worker-core:test`

## Outcome

### What was solved

The primary goal — eliminate implicit file selection conventions —
is fulfilled for **model ONNX files** and **tokenizers**. All 3
encoders now read `cpu`, `gpu`, and `tokenizer` from the manifest.
Swapping a model file requires updating one JSON field; encoders
pick it up automatically. The class of bug that caused the Q4 CPU
regression cannot recur through the manifest-driven path.

### Behavioral change: embedding CPU model on GPU systems

Previously, `OnnxEmbeddingEncoder` on GPU-configured systems used
`model.onnx` (Q4) for the CPU fallback session. Now it always uses
`model_int8.onnx` (the manifest's `cpu` value). This is an
improvement — if the CPU session runs (GPU failure fallback), INT8
is ~30x faster than Q4 on CPU. The old behavior was an artifact of
the implicit convention, not a deliberate design choice.

### Config file wiring — done (338)

Per-encoder config fields added to the manifest:
- `pooling_config` — wired into `OnnxEmbeddingEncoder.detectPoolingStrategy()`
- `label_config` — wired into `NerService.loadLabelMapping()`
- SPLADE has no config file and uses neither field

This completes 338's "config-driven model decoding" goal: all
implicit filename conventions are now manifest-driven.

### External model directories

Production encoders use `loadOrDefault()`, so external model
directories (e.g., `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`) without a
manifest degrade gracefully to the pre-manifest convention
(`model.onnx` CPU, `model_fp16.onnx` GPU). A DEBUG log message
notes when the fallback is active.

## Verification

Live backend verification (2026-03-22):
- All 3 encoders initialized from manifests (`embeddingReady: true`,
  `spladeModelPath` set, `nerModelPath` set)
- Embedding fingerprint computed — confirms correct model file loaded
- `/api/v2/search` returns 200 — search pipeline functional
- CPU fallback works (CUDA DLLs not present → encoders on CPU)
- Unit tests: `ModelManifestTest` 10/10 pass

## Related

- **286** (Runtime Config Audit): audited model discovery paths,
  noted inconsistencies.
- **338** (Config-Driven Model Decoding): overlaps — manifest is
  pillar 2 of making models self-describing. 338 can reference
  this tempdoc for the manifest piece. The `config` field wiring
  belongs there.
- **334** item 16: Q4 CPU fallback fix added `model_int8.onnx`
  detection to `OnnxEmbeddingEncoder`. This tempdoc replaces that
  point fix with the proper manifest-driven approach.
