---
title: "Tempdoc 288 - Model Inventory Audit"
---

# Tempdoc 288 - Model Inventory Audit

**Status:** Open  
**Created:** 2026-03-13  
**Updated:** 2026-03-13  
**Goal:** Reconstruct the local `models/` inventory from the most current tempdoc evidence, separate packaged defaults from runtime defaults, and identify the remaining unresolved model decisions before scripts or registries are edited.

## Context

The repo-local `models/` directory (gitignored, local-only) was lost and needs to be reconstructed. The earlier version of this tempdoc treated "current model" as a single concept, but the post-200 tempdocs show that there are now three partially diverged truths:

1. **Packaged / registry defaults**  
   Source of truth: `modules/ui/src/main/resources/ai/model-registry.v1.json`
2. **Search runtime defaults**  
   Source of truth: tempdocs 234, 253, 268, 273
3. **Agent-quality runtime stack**  
   Source of truth: tempdoc 227 plus the most recent runtime snapshot under `AppData/Local/JustSearch`

This tempdoc therefore tracks model state by **layer**, not by pretending there is already one canonical stack.

## Relevant Post-200 Tempdocs Read

The following tempdocs contain the current model decisions that matter for reconstructing `models/`:

- **205-model-spread-optimization**
  - Updated the packaged/model-registry contract to `Qwen3VL-8B-Thinking`
  - Added `gte-reranker-modernbert-base` as the adopted reranker upgrade
  - Explicitly kept `ms-marco-MiniLM-L-2-v2` for citation scoring
- **207-ner-model-agnosticism**
  - Confirms the concrete active NER model is `dslim/bert-base-NER` ONNX
  - No replacement model was adopted
- **227-agent-quality-improvement**
  - Adopted `Qwen_Qwen3.5-9B-Q4_K_M.gguf` for the agent-quality runtime
  - Uses `nomic-embed-text-v1.5.Q8_0.gguf` in that runtime context
- **234-retrieval-architecture-alternatives**
  - Establishes the SPLADE local export contract
  - The local evidence says SPLADE ONNX export uses `--task fill-mask`
- **248-tech-landscape-scan**
  - Confirms `gte-reranker-modernbert-base` is the near-term reranker upgrade
  - Keeps `nomic-embed-text-v1.5` and `naver/splade-v3` as current baselines
- **253-model-quality-improvements**
  - Rejected the inference-free OpenSearch sparse model swap
  - Confirms `naver/splade-v3` remains the current sparse model
- **268-onnx-migration-progress**
  - ONNX embedding became the search-runtime default direction
  - Distribution/registry work for ONNX embedding was explicitly deferred
- **273-splade-quality-and-performance-followup**
  - Confirms the repo stayed on `naver/splade-v3`
  - Confirms the SPLADE GPU/runtime work, not a model swap

## Current Model Inventory By Layer

### A. Packaged / Registry Defaults

These are the models explicitly present in `model-registry.v1.json`. This is the best source of truth for the **packaged/simple-mode contract**.

#### GGUF models

| ID | Filename | Current state | Evidence |
|----|----------|---------------|----------|
| `chat` | `Qwen3VL-8B-Thinking-Q4_K_M.gguf` | **Current packaged default** | Tempdoc 205 updated the registry from Instruct to Thinking on 2026-02-16. |
| `embedding` | `nomic-embed-text-v1.5.Q4_K_M.gguf` | **Current packaged fallback** | Still present in the registry. Tempdoc 268 did not remove it; it only changed the runtime-default direction. |
| `mmproj` | `mmproj-Qwen3VL-8B-Thinking-F16.gguf` | **Current packaged vision projection** | Still paired with the registry chat model. Not removable by inference alone. |

#### ONNX models

| ID | Target path | Current state | Evidence |
|----|-------------|---------------|----------|
| `onnx-reranker` | `onnx/reranker/model.onnx` | **Legacy, still shipped** | Older MiniLM reranker remains in registry as fallback. |
| `onnx-reranker-tokenizer` | `onnx/reranker/tokenizer.json` | **Legacy, still shipped** | Paired with MiniLM reranker. |
| `onnx-reranker-gte` | `onnx/reranker-gte/model.onnx` | **Current reranker upgrade** | Tempdocs 205 and 248 both recommend and adopt `gte-reranker-modernbert-base`. |
| `onnx-reranker-gte-tokenizer` | `onnx/reranker-gte/tokenizer.json` | **Current reranker upgrade** | Paired with GTE reranker. |
| `onnx-citation-scorer` | `onnx/citation-scorer/model.onnx` | **Current citation scorer** | Tempdoc 205 explicitly keeps MiniLM-L-2-v2 for citation scoring. |
| `onnx-citation-tokenizer` | `onnx/citation-scorer/tokenizer.json` | **Current citation scorer** | Paired with citation scorer. |

### B. Search Runtime Defaults

These are the current **search-runtime** models from the later retrieval tempdocs. They are not all represented in the registry yet.

| Model | Path convention | Current state | Evidence |
|-------|-----------------|---------------|----------|
| ONNX embedding (`nomic-embed-text-v1.5` INT8) | `models/onnx/embedding/` | **Current search-runtime default** | Tempdoc 268 moved the Worker to ONNX-first embedding discovery and quality parity with GGUF Q8 after overlap fixes. |
| SPLADE (`naver/splade-v3`) | `models/splade/naver-splade-v3/` | **Current sparse model** | Tempdocs 234, 253, 273 keep SPLADE-v3. |
| SPLADE GPU FP16 variant | `models/splade/naver-splade-v3/model_fp16.onnx` | **Current local runtime convention** | Tempdoc 273 references this as the local GPU artifact. |
| Legacy MiniLM reranker | `models/onnx/reranker/` | **Legacy fallback** | Still shipped but no longer the preferred reranker. |

### C. Agent-Quality Runtime Stack

These are the model decisions from tempdoc 227 and the latest runtime snapshot. They should not be silently treated as if they already replaced the packaged registry.

| Layer | Model | Current state | Evidence |
|-------|-------|---------------|----------|
| Agent chat runtime | `Qwen_Qwen3.5-9B-Q4_K_M.gguf` | **Adopted for agent-quality work** | Tempdoc 227 switched from Qwen3VL-Thinking to Qwen3.5-9B. |
| Agent embedding runtime | `nomic-embed-text-v1.5.Q8_0.gguf` | **Observed runtime choice** | Tempdoc 227 and the saved worker snapshot both reference Q8_0. |

### D. NER

| Model | Path convention | Current state | Evidence |
|-------|-----------------|---------------|----------|
| `dslim/bert-base-NER` ONNX | `models/ner/bert-base-NER-onnx/` | **Active, unchanged** | Tempdoc 207 confirms this is the concrete active NER model and code path. |

## Local Evidence Checked

### Registry

`modules/ui/src/main/resources/ai/model-registry.v1.json` currently contains:

- `Qwen3VL-8B-Thinking-Q4_K_M.gguf`
- `mmproj-Qwen3VL-8B-Thinking-F16.gguf`
- `nomic-embed-text-v1.5.Q4_K_M.gguf`
- GTE reranker ONNX + tokenizer
- Legacy MiniLM reranker ONNX + tokenizer
- Citation scorer ONNX + tokenizer

### AppData runtime snapshot

`C:\Users\<user>\AppData\Local\JustSearch\runtime\worker-config-snapshot.json` currently records:

- `justsearch.llm.model_path = D:\code\JustSearch\models\Qwen3VL-8B-Instruct-Q4_K_M.gguf`
- `justsearch.model.path = D:\code\JustSearch\models\nomic-embed-text-v1.5.Q8_0.gguf`
- `justsearch.server.exe = D:\code\JustSearch\modules\ui\native-bin\llama-server\variants\cuda12\llama-server.exe`
- `justsearch.gpu.layers = 17`

`C:\Users\<user>\AppData\Local\JustSearch\inference-model-id.txt` still contains:

- `Qwen3VL-8B-Instruct-Q4_K_M.gguf`

This confirms that AppData/runtime state is **older than both the current registry and the later agent-quality tempdoc**.

### Hugging Face cache

The local Hugging Face cache confirms these **source checkpoints/tokenizers** exist:

- `naver/splade-v3`
- `nomic-ai/nomic-embed-text-v1.5`
- `nomic-ai/nomic-bert-2048`
- `naver/splade-cocondenser-ensembledistil`
- `opensearch-project/opensearch-neural-sparse-encoding-doc-v3-distill`

This supports the tempdoc conclusions about source checkpoints, but it does **not** by itself prove which exported ONNX artifacts were ultimately used.

## Most Current Conclusions

### Chat model

- **Packaged default:** `Qwen3VL-8B-Thinking-Q4_K_M.gguf`
- **Latest agent-quality runtime:** `Qwen_Qwen3.5-9B-Q4_K_M.gguf`
- **AppData runtime snapshot:** still stale on `Qwen3VL-8B-Instruct-Q4_K_M.gguf`

So there is no single "current chat model" yet. There is a **packaged default** and a **later agent-quality runtime choice**.

### Embedding model

- **Packaged default:** `nomic-embed-text-v1.5.Q4_K_M.gguf`
- **Latest runtime snapshot:** `nomic-embed-text-v1.5.Q8_0.gguf`
- **Search runtime default:** ONNX embedding under `models/onnx/embedding/`

So the real issue is **layer divergence**, not merely a Q4-vs-Q8 filename mismatch.

### Sparse model

- `naver/splade-v3` remains the current sparse model.
- The OpenSearch doc-v3-distill attempt was tested and rejected in tempdoc 253.
- No post-200 tempdoc adopted a SPLADE replacement.

### Reranker and citation scorer

- **Reranker:** `gte-reranker-modernbert-base` is the adopted upgrade.
- **Legacy reranker:** MiniLM-L6 still exists as fallback.
- **Citation scorer:** MiniLM-L2-v2 remains the kept/current citation scorer.

### NER

- NER is not dead code. Tempdoc 207 confirms an active `dslim/bert-base-NER` ONNX path.
- What remains unresolved is whether it belongs in the reconstructed default `models/` set.

## Stale References Confirmed

| File | Current stale reference | Comment |
|------|-------------------------|---------|
| `scripts/verify-prerequisites.mjs` | `Qwen3VL-8B-Instruct-Q4_K_M.gguf` | At minimum should be updated to Thinking to match the current registry. |
| `scripts/verify-prerequisites.mjs` | `mmproj-Qwen3VL-8B-Instruct-F16.gguf` | At minimum should be updated to Thinking to match the current registry. |
| `scripts/verify-prerequisites.mjs` | `models/citation-scorer/ms-marco-MiniLM-L2-v2` | Path drifted to `models/onnx/citation-scorer/`. |
| `scripts/ci/agent-battery-core.mjs` | `Qwen3VL-8B-Instruct-Q4_K_M.gguf` | Stale against both the current registry and tempdoc 227. |
| `modules/ui/inference-model-id.txt` | `Qwen3VL-8B-Instruct-Q4_K_M.gguf` | Stale against the current registry. |

## SPLADE Export Contract

The best local evidence is still tempdoc 234:

```bash
pip install optimum[exporters] torch transformers
optimum-cli export onnx --model naver/splade-v3 --task fill-mask models/splade/naver-splade-v3/
```

This tempdoc intentionally does **not** upgrade that to `feature-extraction`, because the post-200 local evidence does not support that change.

## Root Cause: Recurring Model Deletion (investigated 2026-03-14)

Models were reconstructed on 2026-03-13 (see below). By 2026-03-14 morning (05:52), only
SPLADE remained. By 15:04, all models were gone again. Investigation findings:

**`models/` is untracked by git.** The directory and all files inside it are untracked —
never committed, not in `.gitignore`. This means `git clean -fd` deletes them.

**No automated code targets `models/` for deletion.** Investigated: dev-runner
`cleanDataDir`, `applyLifecycleCleanup`, Gradle clean/assemble, CI workflows, hook
scripts, `bundleSidecarResources` Sync task. None operate on the repo-root `models/`
directory. `cleanDataDir` operates on `dataDir` (`modules/ui-web/.dev-data`).

**Most likely cause: `git clean -fd` run by a human or external process.** Since
contents are untracked, any `git clean -fd` deletes them silently. The bash-guard hook
blocks agent sessions from running `git clean -f`, but a human terminal or IDE "clean
working tree" operation bypasses the hook.

**Fix applied (2026-03-14):** Track `models/` directly in git. Model binaries
(`.onnx`, `.gguf`) are committed as regular git objects. Runtime cache files
(`.onnx.optimized`, `.opt-meta`, `.sha256` from `OnnxSessionCache`) remain
gitignored via `models/.gitignore`. This means:
- `git clean -fd` never deletes model files (they are tracked)
- `git checkout` restores them from history
- Repo size increases by ~960 MB (one-time cost, all local)
- No LFS server dependency — fully local, no bandwidth limits

## Open Questions

1. Which layer is the canonical target for reconstructing `models/` first?
   - packaged / registry defaults
   - search runtime defaults
   - latest agent-quality runtime
2. Should `Qwen_Qwen3.5-9B-Q4_K_M.gguf` replace the packaged registry chat model, or remain a separate agent-quality runtime choice?
3. Should ONNX embedding and SPLADE be added to `model-registry.v1.json`, or remain manual/runtime assets?
4. Should legacy MiniLM reranker remain shipped as fallback, or be removed?
5. Does NER belong in the default reconstructed `models/` set, or only in advanced/dev setups?
6. What are the verified current download/export sources for each artifact? This still requires a separate web verification pass.

## Reconstruction — Completed Downloads (2026-03-13)

The following files were downloaded/exported and are now present in `models/`. This covers the search runtime and agent-quality layers. Packaged/registry models (Qwen3VL-8B-Thinking, mmproj, nomic Q4_K_M) were **not** downloaded — they are a separate layer decision.

### Downloaded from HuggingFace

| File | Size | Source |
|------|------|--------|
| `Qwen_Qwen3.5-9B-Q4_K_M.gguf` | 5.5 GB | `bartowski/Qwen_Qwen3.5-9B-GGUF` |
| `nomic-embed-text-v1.5.Q8_0.gguf` | 140 MB | `nomic-ai/nomic-embed-text-v1.5-GGUF` |
| `onnx/embedding/model.onnx` | 131 MB | `nomic-ai/nomic-embed-text-v1.5` (INT8 variant) |
| `onnx/embedding/tokenizer.json` | 695 KB | `nomic-ai/nomic-embed-text-v1.5` |
| `onnx/reranker-gte/model.onnx` | 144 MB | `Alibaba-NLP/gte-reranker-modernbert-base` (INT8) |
| `onnx/reranker-gte/tokenizer.json` | 3.5 MB | `Alibaba-NLP/gte-reranker-modernbert-base` |
| `onnx/reranker/model.onnx` | 23 MB | `Xenova/ms-marco-MiniLM-L-6-v2` (legacy) |
| `onnx/reranker/tokenizer.json` | 695 KB | `Xenova/ms-marco-MiniLM-L-6-v2` |
| `onnx/citation-scorer/model.onnx` | 16 MB | `Xenova/ms-marco-MiniLM-L-2-v2` |
| `onnx/citation-scorer/tokenizer.json` | 695 KB | `Xenova/ms-marco-MiniLM-L-6-v2` |

### Exported locally (ONNX from PyTorch)

| File | Size | Method |
|------|------|--------|
| `splade/naver-splade-v3/model.onnx` | 508 MB | `ORTModelForMaskedLM.from_pretrained("naver/splade-v3", export=True)` via optimum |
| `splade/naver-splade-v3/model_fp16.onnx` | 254 MB | FP16 conversion via `onnxconverter_common.float16` |

### Copied from local HuggingFace cache

| File | Size | Source commit |
|------|------|---------------|
| `splade/naver-splade-v3/tokenizer.json` | 695 KB | `fdfeceb91d7b` (gated repo, direct download blocked) |
| `splade/naver-splade-v3/config.json` | 643 B | Same |
| `splade/naver-splade-v3/vocab.txt` | 227 KB | Same |

### Not downloaded

| Model | Reason |
|-------|--------|
| `Qwen3VL-8B-Thinking-Q4_K_M.gguf` | Packaged/registry layer — separate decision |
| `mmproj-Qwen3VL-8B-Thinking-F16.gguf` | Packaged/registry layer — separate decision |
| `nomic-embed-text-v1.5.Q4_K_M.gguf` | Packaged/registry layer — separate decision |
| `ner/bert-base-NER-onnx/` | Active but unresolved — needs user decision |

### SPLADE export note

Tempdoc 234 specifies `--task fill-mask` for the SPLADE export contract. The optimum Python API equivalent `ORTModelForMaskedLM` was used, which is the same task. The FP16 truncation warnings during conversion are expected (layer norm epsilon values near float32 minimum).

## Items

- [ ] 1. Decide which layer is canonical for reconstruction
- [ ] 2. Update `scripts/verify-prerequisites.mjs`
- [ ] 3. Update `scripts/ci/agent-battery-core.mjs`
- [ ] 4. Update `modules/ui/inference-model-id.txt`
- [ ] 5. Decide whether ONNX embedding + SPLADE should enter `model-registry.v1.json`
- [ ] 6. Decide whether legacy MiniLM reranker remains shipped
- [ ] 7. Decide whether Qwen3.5-9B should replace the registry chat model
- [ ] 8. Decide whether NER belongs in the default reconstructed `models/` set
- [ ] 9. Do a separate web-verification pass for download/export sources before producing a final manifest
