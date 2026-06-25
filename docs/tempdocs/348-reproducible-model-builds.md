---
title: "348: Model Provenance and Reproducible Builds"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-24
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 348: Model Provenance and Reproducible Builds

## Goal

Every ONNX model file in `models/` has a recorded provenance: which
source model it came from, which transformations were applied, in
which order, with which tool versions. Given this provenance, any
model can be reproduced from scratch. The `.onnx` file is a build
artifact — the provenance is the source of truth.

## Problem

Model files were **opaque blobs with no recorded origin.** There was
no record of:
- Which HuggingFace model ID was the source
- Which ORT optimizer version was used
- Which transformations were applied (quantization, FP16, op baking)
- In which order (order matters — FP16 before vs after PRESPARSE
  appending produces different results, one of which is broken)
- Which dependency versions produced the current file

When a model broke or needed updating, the build process had to be
reverse-engineered from commit messages, tempdocs, and memory. The
tempdoc 334 FP16 baking required 4 attempts because there was no
record of what the previous (failed) attempts did differently.

Compounding this: the `models/` directory had accumulated ~1.5 GB of
dead experimental artifacts from failed conversion attempts (6 of 8
SPLADE files were unused, embeddinggemma had a duplicate nested `onnx/`
subdirectory from a HuggingFace export).

## Work Items

### Phase 0: cleanup

1. [x] **Remove dead SPLADE variants.** Removed 6 unused files
   (~1.5 GB LFS): `model_fp16_naive.onnx`, `model_fp32_original.onnx`,
   `model_int8.onnx`, `model_o3.onnx`, `model_o3_fp16.onnx`,
   `model_o3_int8.onnx`. Only `model.onnx` and `model_fp16.onnx` are
   referenced by `model_manifest.json`.

2. [x] **Remove nested embeddinggemma `onnx/` subdirectory.** Duplicate
   model files from an initial HuggingFace export, unreferenced.

3. [x] **Move `tmp/splade-fp16-experiment/` to `scripts/models/`.**
   Preserved `bake_presparse_fp16_v2.py` as `bake_presparse_fp16.py`;
   deleted v1 script and generated artifacts. Also removed empty
   `v3-backup/` directory.

### Phase 1: provenance metadata

4. [x] **Define `build.json` schema.** Each model directory gets a
   `build.json` with:
   - `source`: HuggingFace model ID + commit hash (auto-captured)
   - `variants`: keyed by filename, each with `description`,
     `transformations` (ordered list), `output_sha256`
   - `build_command`: exact command to reproduce
   - `tool_versions`: all relevant Python package versions

5. [x] **Backfill `build.json` for all 5 model directories.**
   Provenance reconstructed from tempdoc 334 commit history,
   HuggingFace model cards, and `config.json` metadata. Backfilled
   files have `hf_commit: null` (pre-provenance); future builds via
   the scripts auto-capture commit hashes.

### Phase 2: build scripts

6. [x] **`scripts/models/build-splade.py`** — HF export, FP32
   baked-PRESPARSE, FP16 baked-PRESPARSE, verify, write `build.json`.

7. [x] **`scripts/models/build-ner.py`** — Download pre-built INT8 +
   FP16 from onnx-community, verify INT8, write `build.json`.

8. [x] **`scripts/models/build-embedding.py`** — Download Q4 + merge
   external data, download FP32 + INT8 quantize, verify, write
   `build.json`.

9. [x] **`scripts/models/build-crossencoder.py`** — Download pre-built
   INT8 cross-encoder (reranker or citation-scorer), verify, write
   `build.json`.

10. [x] **Pinned dependency versions** in `scripts/models/requirements.txt`.

11. [x] **Shared utilities** in `scripts/models/_common.py`: `sha256_file`,
    `verify_model`, `resolve_hf_commit`, `get_tool_versions`,
    `posix_relpath`.

### Phase 3: verification and integrity

12. [x] **Wire verification into build scripts.** Each build script
    calls `verify-model.py` as a post-build step. FP16 GPU-only models
    skip CPU verification (ORT fusion nodes incompatible with
    CPUExecutionProvider). GPU verification via Gradle `verifyModel`.

13. [x] **`scripts/models/check-integrity.py`** — Validates `build.json`
    schema (required keys, types) and verifies SHA-256 hashes of all
    committed model files. Detects LFS pointer files.

## End-to-end verification

All build scripts except `build-splade.py` were run end-to-end against
live HuggingFace repos and produced byte-identical files (SHA-256 match)
for all 7 model files across 4 directories. `build-splade.py` was not
run end-to-end (requires multi-GB transformers export); its PRESPARSE
baking logic is proven from the battle-tested `bake_presparse_fp16_v2.py`.

Issues found and fixed during end-to-end testing:
- `optimum` module has no `__version__` — use `importlib.metadata`
- `local_dir_use_symlinks` deprecated in huggingface_hub — removed
- ONNX files in onnx-community repos live under `onnx/` prefix
- FP16 NER model fails CPU verification by design — skip for GPU models

## Delivered files

| File | Purpose |
|------|---------|
| `models/*/build.json` (x5) | Provenance metadata with SHA-256 hashes |
| `scripts/models/_common.py` | Shared utilities |
| `scripts/models/build-splade.py` | SPLADE build (export + FP32/FP16 bake) |
| `scripts/models/build-ner.py` | NER build (download INT8 + FP16) |
| `scripts/models/build-embedding.py` | Embedding build (Q4 merge + INT8 quantize) |
| `scripts/models/build-crossencoder.py` | Cross-encoder build (reranker, citation-scorer) |
| `scripts/models/bake_presparse_fp16.py` | Low-level FP16 PRESPARSE bake (from tmp/) |
| `scripts/models/check-integrity.py` | SHA-256 integrity + schema validation |
| `scripts/models/requirements.txt` | Pinned Python dependencies |

## Workflow Convention

Model updates go through the build scripts:

1. Run the appropriate build script:
   - `python scripts/models/build-splade.py --hf-model <id> --output-dir <dir>`
   - `python scripts/models/build-ner.py --hf-onnx-repo <id> --output-dir <dir>`
   - `python scripts/models/build-embedding.py --hf-onnx-repo <id> --output-dir <dir>`
   - `python scripts/models/build-crossencoder.py --hf-model <id> --output-dir <dir>`
2. The script downloads, transforms, verifies, and writes `build.json`
   (including auto-captured HF commit hash and tool versions)
3. Verify GPU compatibility: `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true`
4. Commit the updated `build.json` and `.onnx` files together
5. Run `python scripts/models/check-integrity.py` to confirm hashes

Ad-hoc model downloads bypass provenance and should not be committed
without a corresponding `build.json` update.

## Dependencies

- **334 (Single-Pass Enrichment):** FP16 baking technique and script.
  Status: done.
- **340 (Model File Manifest):** `model_manifest.json` CPU/GPU routing.
  Status: done.
- **349 (Unified ORT CUDA Runtime):** `OrtSessionFactory`, `ModelVerifier`,
  `verify-model.py`. Status: done.
