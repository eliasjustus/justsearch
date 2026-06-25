---
title: "338: Config-Driven Model Decoding"
type: tempdoc
status: done
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 338: Config-Driven Model Decoding

## Problem

Model decoders hardcode assumptions about specific model variants.
`BioTagDecoder` originally had `static final int B_MISC = 1` —
the label index for `dslim/bert-base-NER`. Swapping to
`dslim/distilbert-NER` (where index 1 = B-PER) would have silently
misclassified every entity.

Similarly, `OnnxEmbeddingEncoder` hardcodes `model.onnx` as the
CPU model and `model_fp16.onnx` as the GPU model. When Q4
quantization was adopted, `model.onnx` became Q4 — but the CPU
path still loaded it, causing 30x performance regression on CPU.

## Proposed Solution

Each model directory should be self-describing:

1. **Label/output mapping** from model metadata (config.json
   `id2label`, `pooling_config.json`). Already done for NER and
   embedding pooling.

2. **Model file manifest** declaring CPU and GPU variants:
   ```json
   {
     "cpu": "model_int8.onnx",
     "gpu": "model_fp16.onnx",
     "tokenizer": "tokenizer.json"
   }
   ```
   Encoders read this instead of hardcoding path resolution logic.

3. **Input schema detection** from ONNX graph
   (`session.getInputNames()`). Already done for `token_type_ids`
   detection in NER and embedding.

## Outcome

All three pillars are now implemented:

### Pillar 1: Label/output mapping — done (334 item 15)

- `LabelMapping` reads `id2label` from `config.json` at NER load
  time. `BioTagDecoder` no longer hardcodes label indices.
- `detectPoolingStrategy` reads `pooling_config.json` for embedding
  pooling mode (CLS vs MEAN_POOL).
- SPLADE output format auto-detected from ONNX graph outputs
  (`output_idx` + `output_weights` → PRESPARSE, else MLM_LOGITS).

### Pillar 2: Model file manifest — done (340)

Tempdoc 340 implemented `model_manifest.json` convention:
- `ModelManifest` record in `modules/worker-core/.../ort/`
- Manifests deployed to all 3 model directories
- All 3 encoders read model + tokenizer paths from manifest
- `loadOrDefault()` provides graceful fallback for external dirs

The manifest schema covers `cpu`, `gpu`, and `tokenizer` fields.
Config files (e.g., `pooling_config.json`, `config.json`) were
deliberately excluded — each encoder reads different config files
for different purposes, and a single `config` field doesn't map to
reality. See "Remaining opportunity" below.

### Pillar 3: Input schema detection — done (334)

- `token_type_ids` auto-detected via `session.getInputNames()` in
  both `OnnxEmbeddingEncoder` and `BertNerInference`.
- Supports BERT (has token_type_ids) and DistilBERT (does not)
  without configuration changes.

### Pillar 2b: Config file wiring — done (340)

Config file resolution is now manifest-driven via two per-encoder
fields:
- `pooling_config` — used by `OnnxEmbeddingEncoder.detectPoolingStrategy()`
- `label_config` — used by `NerService.loadLabelMapping()`
- SPLADE has no config file and uses neither field

Defaults (`pooling_config.json` and `config.json` respectively)
are applied when fields are absent, preserving backward compat.

## Related

- **207** (NER Model Agnosticism): completed via 334 item 15.
- **286** (Runtime Config Audit): noted model discovery
  inconsistencies — resolved by 340 manifest convention.
- **327** (Embedding Config Alignment): completed embedding config
  unification.
- **334** items 15-16: point fixes that 338 and 340 generalized.
- **340** (Model File Manifest): implemented pillar 2.
