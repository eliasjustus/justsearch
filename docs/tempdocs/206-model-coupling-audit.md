---
title: Model Coupling Audit — What Breaks on Model Switch
status: done
created: 2026-02-13
updated: 2026-02-13
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 206: Model Coupling Audit

## Purpose

Full codebase audit of everything coupled to the current Qwen3-VL-8B-Instruct model. This inventory tells you exactly what breaks, what needs adding, and what's already safe if JustSearch switches to a different model (or a multi-model architecture per tempdoc 205).

Organized by severity: breaks → needs adding → should update → already abstracted.

---

## Must change (breaks on model switch)

| File | Line(s) | What's coupled | Impact |
|------|---------|---------------|--------|
| `modules/app-inference/.../InferenceConfig.java` | 95-96 | Hardcoded default filenames: `Qwen3VL-8B-Instruct-Q4_K_M.gguf`, `mmproj-Qwen3VL-8B-Instruct-F16.gguf` | Wrong model loaded if no override. Text-only model doesn't need mmproj at all. |
| `modules/ui/.../EffectiveConfigController.java` | 116, 122 | Same hardcoded defaults for config display | Shows wrong model name in UI status |
| `modules/ui/src/main/resources/ai/model-registry.v1.json` | 8-19 | Pinned filename, SHA256, download URL, file size for Qwen3VL-8B | Download/verification fails for different model |
| `modules/app-services/.../vdu/VduProcessor.java` | 17, 27-47 | Javadoc says "Qwen3-VL". Prompts assume vision input capability. `PASS1_MAX_TOKENS=2048`, `PASS2_MAX_TOKENS=512` tuned for VL model. | VDU pipeline sends image data to text-only model → errors or garbage |
| `scripts/ai/pack-author.ps1` | 84-103 | Auto-discovery assumes largest GGUF = chat model, `embed` substring = embedding model | Heuristic breaks with 3+ model files (chat + VDU + embed) |
| Pack manifests (e.g., `tmp/offline-installer-sandbox/.../pack-manifest.v1.json`) | - | References `Qwen3VL-8B-Instruct-Q4_K_M.gguf` by exact filename and SHA256. Only has roles `model.chat` and `model.embedding` — no `model.vdu`. | Pack install puts wrong model in wrong role |

---

## Must add (new capability needed for multi-model split)

| Area | What's missing | Why |
|------|---------------|-----|
| `AgentLoopService` SSE parsing | `delta.reasoning_content` handling | Thinking models emit reasoning in a separate SSE field. Currently silently dropped. Need to: parse it, log it, exclude from user response. |
| Sampling parameters | `temperature`, `top_p`, `top_k` in chat completion requests | Qwen3 thinking requires `temp=0.6, top_p=0.95`. Currently uses llama-server defaults. `OnlineModeOps.java` builds the request body — add params there. |
| `InferenceConfig` | Second model path for VDU | Currently: one `vlmModel` + optional `mmprojModel`. Split needs: `chatModel` (text) + `vduModel` (VL) + `mmprojModel` (paired with VDU). Or dynamic swap logic. |
| `InferenceLifecycleManager` | Model swap / dual-model support | Currently manages one llama-server with one model. Split needs either: (a) reload with different model for VDU batch, or (b) second process, or (c) CLI tool for VDU. |
| `model-registry.v1.json` schema | New entry type for VDU model | Currently 4 entries (chat, embed, 2x ONNX). Need a 5th for VDU with its own SHA256, URL, mmproj pairing. |
| Pack manifest schema | `model.vdu` role | Add alongside existing `model.chat` and `model.embedding` roles. |
| `EnvRegistry` | Chat model env var | Currently `VLM_MODEL` (vision-language). A text chat model needs `CHAT_MODEL` or rename the existing var. |

---

## Should update (works but misleading or suboptimal)

| File | What | Why |
|------|------|-----|
| `LlamaServerOps.java:192-195` | `--mmproj` flag construction | Currently adds `--mmproj` if `cfg.mmprojPath() != null`. With split: text chat model must NOT get `--mmproj`; VDU model must. Conditional logic needs to know which model is loaded. |
| `LlamaServerOps.java:198` | `--jinja` flag | Required for all Qwen3 models (tool calling + thinking). Verify new model's template works with `--jinja`. |
| `InferenceConfig.java:121-129` | mmproj disable logic | "If user overrides LLM path but NOT mmproj, disable mmproj to avoid mismatch." This heuristic was designed for VL models. With text chat model, mmproj should always be null. |
| `scripts/bench/baselines/rag-eval-baseline.json:5` | `"model_name": "Qwen3-4B-Instruct-2507-Q4_K_M"` | Benchmark baseline pinned to specific model. New model needs new baseline. |
| `docs/explanation/05-ai-architecture.md` | Architecture doc references Qwen3VL | Documentation drift — update after model change. |
| `docs/explanation/14-ai-pack-spec.md` | Example manifests use Qwen3VL filenames | Same — update examples. |

---

## Already abstracted (no changes needed)

| Area | Why it's fine |
|------|--------------|
| **Frontend (React/TS)** | No hardcoded model names. Reads model info from backend status endpoints. |
| **llama-server process management** | Model-agnostic — takes a path, starts process, monitors health. |
| **OpenAI-compatible API** | `ToolCallParser` uses standard `tool_calls` format. Any model producing OpenAI-compatible tool calls works. |
| **GPU layer count** | Configurable via `JUSTSEARCH_GPU_LAYERS`. Model-agnostic. |
| **ONNX models** (reranker, citation scorer) | Independent of chat/VDU model. Separate runtime. |
| **Embedding model** | Already separate process in Worker. Own env var (`EMBED_MODEL`). |
| **VRAM tier detection** | Hardware-based (`VramFlagsUtil`), not model-specific. |
| **Agent tools** (search, browse, ingest, file_operations) | Tool definitions are model-agnostic. Any model with function calling can use them. |

---

## Minimum viable model switch

If adopting Split A from tempdoc 205 (Qwen3-8B text + Qwen3-VL-2B VDU), the minimum code changes:

1. **`InferenceConfig.java`** — Change defaults, add chat vs VDU model distinction (~20 lines)
2. **`model-registry.v1.json`** — Add Qwen3-8B entry + Qwen3-VL-2B entry (~30 lines)
3. **`LlamaServerOps.java`** — Conditional `--mmproj` based on model type (~5 lines)
4. **`AgentLoopService.java`** — Parse `reasoning_content` from SSE, log it (~15 lines)
5. **`OnlineModeOps.java`** — Add sampling params to request body (~5 lines)
6. **`pack-author.ps1`** — Handle 3-model pack (chat + VDU + embed) (~15 lines)
7. **`pack-manifest.v1.json` schema** — Add `model.vdu` role (~5 lines)

**Estimated total: ~95 lines across 7 files.** The `InferenceLifecycleManager` swap logic (if needed) would be the largest addition beyond this.

---

## Related tempdocs

- **205** — Model Spread Optimization (multi-model architecture research, VRAM budgets, validation plan)
- **186** — LLM Tool Execution Capability (agent quality analysis, B1-B4 bugs, thinking experiments)
- **123** — AI Engine External Tools Research (inference engine capabilities, model alternatives)

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 68 days at audit time.

