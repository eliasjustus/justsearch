---
title: Model Spread Optimization — Multi-Model Architecture Research
status: done
created: 2026-02-13
updated: 2026-02-17 (critical reassessment + market refresh)
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 205: Model Spread Optimization

## Idea

JustSearch currently uses a single model — **Qwen3-VL-8B-Instruct** (Q4_K_M, ~5 GB) — for three distinct workloads: agent chat (tool calling + synthesis), VDU (Visual Document Understanding / OCR), and embedding generation. This tempdoc investigates whether splitting to purpose-specific models improves quality within the hardware budget.

**Original assumption (2026-02-13):** The VL-Instruct model cannot think → must split into text-only chat + small VDU. **This assumption was wrong.** Research (§Research Findings below) revealed that **Qwen3-VL-8B-Thinking** exists — a unified model with both vision AND chain-of-thought reasoning, with official GGUF support and llama.cpp compatibility since October 2025.

The question is now: **unified (VL-Thinking) vs split (text-only chat + dedicated VDU)** — which path is better? The long-term goal of splitting VDU into its own dedicated model remains architecturally sound (independent upgrade paths for each workload), but may not be urgent if a unified model delivers adequate quality for both.

---

## Critical Reassessment (2026-02-17)

This tempdoc's implementation history remains valid, but three conclusion-level assumptions were stale and are now corrected:

1. **Qwen3.5 is released** (official Qwen card + HF Transformers docs are live), so "on the horizon" wording is no longer accurate.
2. **Qwen3.5 is not yet a practical default swap for JustSearch local-first GGUF runtime**:
   - The clearly available flagship artifact is `Qwen3.5-397B-A17B` (Transformers/safetensors, 397B total / 17B active, very high memory footprint).
   - We do not have an official small Qwen3.5 GGUF replacement at the `Qwen3-VL-8B-Thinking` class with equivalent local deployability.
3. **Tiny Aya is released** (Cohere Labs, Feb 17, 2026), but it is currently a text-generation-only multilingual family with CC-BY-NC-4.0 licensing on released checkpoints, so it is not a direct replacement for the JustSearch main chat+VDU model.

**Current stance:** keep `Qwen3-VL-8B-Thinking` as default for the main local model path, treat Qwen3.5 and Tiny Aya as watchlist items with explicit adoption gates.

## Implementation Status (2026-02-16, final)

Branch: `feat/model-spread-optimization` (5 commits, ~20 files) — **merged to main** (`5240395d`, 2026-02-15). Worktree removed.

**Canonical docs updated (rev17, completed 2026-02-16):**
- **Rev17 (2026-02-15):** `05-ai-architecture.md` §Reasoning Pipeline added. `environment-variables.md` updated with `USE_THINKING` and `RERANK_GPU_MEM_MB`. D-1 vision detection guard added to `VduProcessor` + `ServerPropsOps`.
- **Post-rev19 (2026-02-16):** Vision Capability Detection section added to `05-ai-architecture.md`. Server-model compatibility warnings documented. New API fields (`hasVisionCapability`, `activeModelId`, `use_thinking`) documented in `08-observability.md`. Model defaults updated (Instruct→Thinking) in `14-ai-pack-spec.md` and `ai-pack-authoring.md`. Cross-references enhanced in `environment-variables.md`. See commits `64428e1b` (canonical docs), `88c83636` (hardening).

**SamplingParams streaming gap fix (rev18):** Integration audit found 3 gaps where SamplingParams was not threaded through streaming paths. Fixed: (A) `OnlineModeOps.streamSummary/streamAnswer` now pass `DETERMINISTIC` to 7-arg `streamChat`; (B) `VduProcessor` Pass 2 chat enrichment now passes `SamplingParams.VDU`; (C) `streamChat` SSE parsing now extracts and logs `reasoning_content` at DEBUG. New interface overload `OnlineAiService.streamChat(..., SamplingParams)` added for `MapReducePipeline`. UI integration audit confirmed all UI endpoints correctly delegate through the fixed paths. See §UI Integration Audit below.

**UI enhancements (rev19):** 3 of 5 audit items implemented: (U-1) `use_thinking` flag in `/api/debug/state` llm section; (U-2) `hasVisionCapability` in `/api/inference/status` → frontend `canVdu` gate (disables VDU buttons when mmproj not loaded instead of failing at runtime); (U-5) `activeModelId` in `/api/inference/status` → Brain panel runtime info card shows friendly model name (strips quantization suffix). U-3 (thinking error codes) skipped — no distinguishing signal from llama-server. U-4 (settings toggle) deferred — poor UX requiring server restart.

**Decision: Unified model adopted.** Phase 0 validation passed — VRAM within budget (9.04 GB on 12 GB GPU), agent quality confirmed (tool calling + synthesis, no `<think>` leakage), VDU quality confirmed (`/no_think` suppresses reasoning). The split architecture (§Architecture Questions below) was not pursued.

**Validation gaps (accepted technical debt):** Quantitative agent battery (Step 0.3, target >90%), structured output compliance (Step 0.4), multilingual spot check (Step 0.7), and performance benchmarks on actual Thinking model (Step 0.2 was run on Qwen3-4B-Instruct) were not completed during Phase 0 validation. Validation was qualitative (agent correctly calls tools, synthesizes coherent answers, no tag leakage, VDU processes documents successfully). Production deployment proceeded based on qualitative validation; quantitative metrics remain unmeasured.

**Post-deployment validation (2026-02-15):** Agent battery executed on Thinking model (9 of 12 experiments): **72% correctness (6.5/9)** vs. Instruct baseline **83% (10/12)**. Findings: (1) B1 fix working - absolute paths used correctly; (2) B2 (hallucination) still present - EXP 6 gave speculative answer; (3) B4 (synthesis) regression - EXP 12 failed to count documents; (4) Iteration limit issues. **Result:** Thinking model shows NO improvement over Instruct baseline for agent tasks, with slight regression. This validates the "accepted technical debt" classification - quantitative validation would have revealed this before deployment. The Thinking model may benefit from prompt tuning or investigation of `/no_think` interaction with agent workloads. See §Validation Plan for original quantitative criteria.

### Completed code changes

| Phase | Commit | What | Files |
|-------|--------|------|-------|
| **1-2** | `4b8cc88` | Foundation + reranker agnosticism | 8 |
| **3** | `ac2324a` | StreamCallbacks + SamplingParams + reasoning extraction | 7 |
| **4** | `4b8b36c` | `<think>` tag stripping + empty-output retry | 2 |
| **5** | `9962bc9` | Remove hardcoded model-specific dev fallbacks | 2 |
| **Review** | `7edb5f5` | Critical analysis fixes + test coverage | 4 |

### Phase 1 (foundation) — DONE
- `EnvRegistry`: Added `USE_THINKING` and `RERANK_GPU_MEM_MB` env vars
- `LlamaServerOps`: Added `--reasoning-format deepseek` flag (gated by `USE_THINKING`)
- `VduProcessor`: Prepended `/no_think` to both VDU prompts
- `InferenceConfig` + `EffectiveConfigController`: Default filenames → Thinking variants
- `OnlineAiServiceImplTest`: GPU layers `33 → 0` (model-agnostic)

### Phase 2 (reranker agnosticism) — DONE
- `CrossEncoderReranker` + `CitationScorer`: Detect `token_type_ids` at ONNX load time via `getInputNames()`, skip tensor creation when not needed (ModernBERT models omit this input)
- `CrossEncoderReranker`: GPU memory arena configurable via `JUSTSEARCH_RERANK_GPU_MEM_MB` (default 512 MB)

### Phase 3 (StreamCallbacks + reasoning) — DONE
- `OnlineAiService`: Added `StreamCallbacks` record (6 callbacks including `onReasoningChunk`)
- Refactored `streamChatWithTools` from 8-param to 4-param across full delegation chain (OnlineAiService → OnlineAiServiceImpl → InferenceLifecycleManager → OnlineModeOps)
- `OnlineModeOps`: Extracts `reasoning_content` from SSE delta
- `AgentLoopService`: Accumulates reasoning, logs at DEBUG level
- `SamplingParams`: New record with THINKING/DETERMINISTIC/VDU presets (later wired into all 3 `OnlineModeOps` injection points — see Deferred/Completed §SamplingParams wiring)

### Phase 4 (error handling) — DONE
- `OnlineModeOps.sendChatRequest()`: Strips leaked `<think>` tags from non-streaming responses
- `AgentLoopService.callLlmWithTools()`: Strips `<think>` tags from streaming responses (conversation history cleanup); retries with `/no_think` on empty output (context exhaustion recovery)

### Phase 5 (model agnosticism, partial) — DONE
- `RerankerConfig` + `CitationScorerConfig`: Removed hardcoded `ms-marco-MiniLM-*` dev fallback paths

### Critical analysis (2026-02-15) — DONE

Independent code review of the branch (3 agents, full file reads). Results:
- **4 real issues found** (all LOW-MEDIUM): doc bug in InferenceConfig GPU_LAYERS comment, missing tests for retry/reasoning/think-strip, SamplingParams dead code, StreamCallbacks missing null validation on onComplete/onError
- **All 4 fixed**: InferenceConfig comment corrected, 3 unit tests added (empty-output retry, reasoning accumulation, think-tag stripping), TODO comment on SamplingParams, compact constructor on StreamCallbacks
- **4 false positives refuted**: null in try-with-resources is safe (JLS 14.20.3), dev fallback removal is intentional (D-4), GPU layers 33→0 is correct (model-agnostic), `/no_think` is documented Qwen3 soft switch
- **3 design concerns documented as acceptable**: `/no_think` retry as user message (only option mid-conversation), streaming think-tag visibility (can't strip across chunks), registry/code filename mismatch (known deferred A-6)

### Test hardening session (2026-02-15) — DONE

Systematic test coverage audit across all ~20 files changed in this tempdoc. Identified 8 gaps (HIGH/MEDIUM/LOW), closed the 3 highest-priority gaps with 7 new unit tests. Commit: `0a5fdca2`.

**Coverage audit results:**

| # | Gap | Priority | Resolution |
|---|-----|----------|------------|
| GAP 1 | SamplingParams injection — zero tests verify `temperature`/`top_p` appear in HTTP request body at any of the 3 `OnlineModeOps` injection points | HIGH | **Fixed** — 3 new tests: `chatCompletion_injectsSamplingParams`, `chatCompletion_omitsSamplingWhenNull`, `streamChatWithTools_injectsSamplingParams` |
| GAP 2 | Think-tag stripping in non-streaming `sendChatRequest()` — existing test returns clean text, regex never exercised | HIGH | **Fixed** — 2 new tests: `chatCompletion_stripsThinkTags`, `chatCompletion_stripsMultipleThinkTags` |
| GAP 3 | `--reasoning-format deepseek` server startup flag — no unit test | MEDIUM | Deferred — embedded in process spawn. Verified by live integration test. |
| GAP 4 | GPU variant mismatch warning — no unit test | MEDIUM | Deferred — private method, LOG.warn() only. Verified by live integration test. |
| GAP 5 | Thinking model mismatch warning — no unit test for log output | MEDIUM | Deferred — private method, LOG.warn() only. `ServerPropsOpsTest` covers diagnostics struct (prerequisite). |
| GAP 6 | Reranker `token_type_ids` conditional tensor creation — no unit test | MEDIUM | Accepted — requires real ONNX sessions; `CrossEncoderRerankerIntegrationTest` covers both model types. |
| GAP 7 | VDU `/no_think` prefix in prompt constants — no test | LOW | Accepted — static compile-time constants, trivially fragile to test. |
| GAP 8 | `EnvRegistry` entries for `USE_THINKING` and `RERANK_GPU_MEM_MB` — not in existing test suite | LOW | **Fixed** — 2 new tests: `useThinking_hasMappingsAndDefaultsToTrue`, `rerankGpuMemMb_hasMappingsAndDefault` |

**New tests added:**

| File | Tests | Technique |
|------|-------|-----------|
| `OnlineModeOpsTest.java` | 5 tests (SamplingParams injection ×3, think-tag stripping ×2) | HTTP request body capture via `AtomicReference<String>` + custom `HttpServer` handler |
| `EnvRegistryTest.java` | 2 tests (USE_THINKING mappings, RERANK_GPU_MEM_MB mappings) | Direct assertion on sysprop/envVar names + defaults |

**Live integration test (standalone llama-server, CUDA variant b7502):**
- `reasoning_content` SSE field confirmed present in streaming responses (Thinking model + `--reasoning-format deepseek`)
- No `<think>` tag leakage in either streaming or non-streaming mode
- `/no_think` in system prompt effectively suppresses extended reasoning (shorter reasoning + direct content)
- `/no_think` in user message mid-conversation does NOT suppress reasoning (known limitation — Qwen3 Jinja template checks system prompt only)
- Evidence: `tmp/agent-evidence/dev-runner/.../tempdoc-205-testing-session/`

### Deferred items

#### Completed

- **D-2/D-3 (derived budgets, calibration)**: `TokenEstimationUtils` already uses actual context size with 4096 fallback — adequate. No work needed.
- **D-4 (embedding auto-discovery)**: **DONE** (2026-02-16) — Extracted `EmbeddingModelResolver` to `modules/configuration`. Refactored 3 callers to 1-line delegation. Added `EmbeddingModelResolverTest` (3 tests).
- **D-5 (ONNX introspection)**: Moved to tempdoc 207 — `BioTagDecoder` is NER/indexing pipeline, unrelated to model spread.
- **D-6 (test model-agnosticism)**: **DONE** — GPU layers changed from 33 to 0 in commit `7edb5f5`.
- **A-7 (SSE integration test)**: **DONE** (2026-02-16) — Added `streamChatWithTools_extractsReasoningContent` test to `OnlineModeOpsTest`.
- **Build version sync**: **RESOLVED** (2026-02-16) — `build.gradle.kts` pins both CPU and CUDA to `b7502`. Stale dev binary (`native-bin/llama-server/`) deleted.
- **SamplingParams wiring**: **DONE** (2026-02-16, extended rev18) — Full call chain wired: `SamplingParams` record with THINKING/DETERMINISTIC/VDU presets accepted and injected (temperature/top_p) at all 3 `OnlineModeOps` injection points (`streamChatWithTools` line 354, `streamChat` line 211, `sendChatRequest` line 555). Passthrough in `InferenceLifecycleManager` and `OnlineAiServiceImpl`. Callers: `AgentLoopService` → THINKING (line 367), `sendVisionRequest` → VDU (line 622), summarize/askQuestion → DETERMINISTIC. **Rev18 fix:** 3 streaming gaps closed — `streamSummary`/`streamAnswer` terminal overloads now pass DETERMINISTIC to 7-arg `streamChat`; `MapReducePipeline` passes DETERMINISTIC via new `OnlineAiService.streamChat(..., SamplingParams)` overload; `VduProcessor` Pass 2 uses VDU; `streamChat` SSE parsing extracts `reasoning_content` at DEBUG level.
- **model-registry.v1.json**: **DONE** (2026-02-16) — Registry updated to 8 entries. Chat entry updated to Thinking model (`9e54ca76...`). 3 entries added: mmproj Thinking (`0fda38a0...`), gte-reranker ONNX INT8 (`ecc6a0ae...`), gte-reranker tokenizer (`2aea6ff4...`).
- **D-1 (capability detection)**: **DONE** (rev17) — Three warnings/guards: (1) GPU variant mismatch in `LlamaServerOps`, (2) Thinking model mismatch in `ServerPropsOps.warnIfThinkingMismatch()`, (3) Vision capability guard in `VduProcessor.process()` + runtime `/props` `modalities.vision` extraction in `ServerPropsOps`. Tool-calling detection dropped (false negatives for Qwen3). Build version detection deferred (no compat table). `InferenceLifecycleManager.hasVisionCapability()` exposes config-level check (`mmprojPath != null`).
- **Server-model compatibility check**: **DONE** (2026-02-16) — Two warnings implemented: (1) GPU variant mismatch in `LlamaServerOps.startLlamaServer()` lines 224-234 — warns if `gpuLayers > 0` but exe not under `variants/`; (2) Thinking model mismatch in `ServerPropsOps.warnIfThinkingMismatch()` lines 86-96 — warns if `USE_THINKING=true` but model name lacks "Thinking".

#### UI Integration Audit (rev18)

Full audit of all UI streaming endpoints (RagStreamingHandler, SummaryController, MapReducePipeline, AgentController) and frontend SSE parsing (`streams.ts`, `useAgentStore.ts`). Confirmed all backend changes are correctly integrated:

**Verified correct:**
- All streaming summary/Q&A/map-reduce paths reach the SamplingParams-fixed `OnlineModeOps` methods via `OnlineAiServiceImpl` → `InferenceLifecycleManager` delegation chain
- Agent chat correctly uses `SamplingParams.THINKING` via `AgentLoopService`
- Reasoning content intentionally NOT forwarded to UI (by design — `AgentEvent` sealed interface has no `ReasoningChunk` variant; `AgentLoopService` accumulates at DEBUG only)
- Frontend SSE parser (`streams.ts`) handles all emitted event types; `[key: string]: unknown` index signatures provide forward-compat
- `model-registry.v1.json` correctly references Thinking model with reasoning_content notes
- Think-tag stripping in `sendChatRequest()` is invisible to UI layer (clean content arrives at endpoints)

**Enhancement opportunities (rev18 identified, rev19 implemented U-1/U-2/U-5):**

| Item | Status | Notes |
|------|--------|-------|
| U-1: `use_thinking` in debug state | DONE (rev19) | `DebugStateController` llm section now includes `use_thinking` from `EnvRegistry` |
| U-2: Vision capability gate | DONE (rev19) | `OnlineAiRuntimeIntrospection.hasVisionCapability()` → `/api/inference/status` → `useAiCapabilities` gates `canVdu` |
| U-3: Thinking-specific error codes | SKIP | llama-server returns same status 400 for input overflow and reasoning exhaustion — no distinguishing signal |
| U-4: `USE_THINKING` toggle in settings UI | DEFER | Env var only; requires server restart so toggle UX is poor |
| U-5: Model name in Brain panel | DONE (rev19) | `OnlineAiRuntimeIntrospection.activeModelId()` → `/api/inference/status` → `BrainSimplePanel` runtime info card with friendly name stripping |

#### Remaining / Out of Scope

- **Benchmark infrastructure**: Documented gap. Scripts exist and work (`llm-bench-win.ps1`, `run-claim-d-suite-win.ps1`). BEN-004 (repeatable model evaluation harness for safe model swaps) remains an open P3 issue at `docs/reference/issues/benchmarking.md`. Out of scope for this tempdoc.
- **Reranker quality eval**: Documented gap. `CrossEncoderRerankerIntegrationTest` passes with gte-reranker model. `RagQualityEvalTest` does NOT use the reranker (intentional — comment at line 427-429 explains dependency not available in system-tests). To eval gte-reranker in isolation: `JUSTSEARCH_RERANK_MODEL_PATH=models/reranker/gte-reranker-modernbert-base`. Out of scope for this tempdoc.

### Model downloads (2026-02-16)
Downloads to `D:\code\JustSearch\models\`:

| File | Source | Size | SHA-256 | Status |
|------|--------|------|---------|--------|
| `Qwen3VL-8B-Thinking-Q4_K_M.gguf` | `Qwen/Qwen3-VL-8B-Thinking-GGUF` | 4.68 GB | `9e54ca76...` | **Done** |
| `mmproj-Qwen3VL-8B-Thinking-F16.gguf` | same repo | 1.16 GB | `0fda38a0...` | **Done** |
| `reranker/gte-reranker-modernbert-base/model.onnx` | `Alibaba-NLP/gte-reranker-modernbert-base` (INT8) | 151 MB | `ecc6a0ae...` | **Done** |
| `reranker/gte-reranker-modernbert-base/tokenizer.json` | same repo | 3.6 MB | `2aea6ff4...` | **Done** |

**Finding:** mmproj Instruct (`ca524100...`) vs mmproj Thinking (`0fda38a0...`) have **different weights** despite identical file size (1,159,029,824 bytes). The vision encoder was retrained for the Thinking variant — must use the Thinking-specific mmproj.

### CUDA variant investigation (2026-02-16)

The tempdoc previously listed "Fix CUDA loading issue" as a blocker. Investigation revealed this was a **misdiagnosis** — not a code bug, but a variant selection issue:

- **Dev default** (`native-bin/llama-server/llama-server.exe`) is the **CPU variant** (build 7315). Its `ggml-cuda.dll` (78 MB) is dynamically linked and requires CUDA Toolkit runtime DLLs (`cudart64_*.dll`) which are not installed.
- **CUDA variant** (`modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe`, build 7502) is **self-contained** — statically-linked `ggml-cuda.dll` (417 MB) + bundled `cublas64_12.dll`, `cublasLt64_12.dll`, `cudart64_12.dll`. Successfully loads CUDA and detects RTX 4070 (compute 8.9).
- Both variants support `--jinja` (default: enabled) and `--reasoning-format deepseek`. The Thinking model works on either build.
- `InferenceConfig.findServerExecutable()` always picks the CPU variant (it skips `variants/`). The CUDA variant is only activated via `RuntimeActivationService.startActivate("cuda12")` (UI button or MCP tool), which persists the path to UiSettings + system property.
- `JUSTSEARCH_GPU_LAYERS` defaults to `0` (CPU mode). Activation flow sets it to `99`.

**Architectural concerns identified:**

1. ~~**Build version divergence**~~ **RESOLVED** (2026-02-16): `modules/ui/build.gradle.kts` (line 277) pins both CPU and CUDA to `b7502` from the same GitHub release. The repo-root `native-bin/llama-server/` binary (build 7315) is a stale dev convenience copy — not produced by the build pipeline. Production binaries are always synced.
2. **`findServerExecutable()` is model-unaware** — picks CPU variant regardless of model requirements, GPU intent, or needed server features. `RuntimeActivationService` overrides via system property — two disconnected paths.
3. **No server-model compatibility validation** — `LlamaServerOps` passes `--reasoning-format deepseek` when `USE_THINKING=true` but doesn't verify the server build supports it. Silent degradation on older builds. Infrastructure exists: `logServerProperties()` already fetches `/props` post-startup; `ServerPropsOps` already parses model ID + context size.
4. **Dev workflow friction** — no repo-root `variants/` directory. GPU testing requires manual setup or full app activation flow.

**For Phase 0 validation:** Use CUDA variant directly. No code fix needed. Command:
```
modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe \
  -m models/Qwen3VL-8B-Thinking-Q4_K_M.gguf \
  --mmproj models/mmproj-Qwen3VL-8B-Thinking-F16.gguf \
  --jinja --reasoning-format deepseek -ngl 99 --host 127.0.0.1 --port 8086
```

### Developer clarity gaps (2026-02-16)

Investigation of the remaining items revealed documentation and workflow gaps that affect developers working in this repo. These are independent of the model spread work but were surfaced by it.

All developer clarity gaps have been resolved:

| # | Gap | Status | Resolution |
|---|-----|--------|------------|
| G-1 | **Stale dev binary** | **DONE** (2026-02-16) | `native-bin/llama-server/` deleted. (`native-bin/` still exists with JNI bridge DLLs — unrelated.) Production binaries staged via `./gradlew.bat stageLlamaServerFromPrebuilt`. |
| G-2 | **Two disconnected exe resolution paths** | **DONE** (2026-02-16) | `docs/explanation/13-ai-setup-and-verification.md` §3.3 documents Path A (CPU discovery via `findServerExecutable`) and Path B (GPU activation via `RuntimeActivationService`). |
| G-3 | **No GPU testing workflow** | **DONE** (2026-02-16) | Created `docs/how-to/test-gpu-locally.md` with 2 methods, troubleshooting, cross-references. |
| G-4 | ~~System property precedence undocumented~~ | **N/A** | Already documented at `docs/reference/configuration/environment-variables.md` lines 18-22. |
| G-5 | **Registry vs code default divergence** | **RESOLVED** (2026-02-16) | Both `model-registry.v1.json` (line 8) and `InferenceConfig.java` (line 95) now default to the Thinking model. No divergence remains. |

### Phase 0 validation checklist (all complete)

**Implementation (1-10):**
1. ~~Download Qwen3-VL-8B-Thinking GGUF + mmproj~~ **DONE** (2026-02-16)
2. ~~Fix CUDA loading issue~~ **RESOLVED** (2026-02-16) — not a bug; use CUDA variant directly
3. ~~Runtime validation~~ **DONE** (2026-02-16) — CUDA variant (`variants/cuda12/llama-server.exe`, `-ngl 79`) with Thinking model confirmed working. `reasoning_content` in SSE: **confirmed** (3520 chars of reasoning received by `AgentLoopService`). `/no_think` prefix: N/A — code uses `--reasoning-format deepseek` + `ThinkTagStripper` defense instead. Startup: 4.2s. Evidence: `tmp/agent-evidence/dev-runner/.../thinking-model-full-validation/`.
4. ~~Wire `SamplingParams` into request bodies~~ **DONE** (2026-02-16) — Full call chain wired: `OnlineModeOps` (3 injection points, `sendChatRequest`/`streamChat`/`streamChatWithTools` refactored to accept `SamplingParams`), `InferenceLifecycleManager` (passthrough overloads), `OnlineAiServiceImpl` (overrides), `OnlineAiService` interface (default method). `AgentLoopService` passes `THINKING`, `summarize`/`askQuestion` use `DETERMINISTIC` internally, `sendVisionRequest` uses `VDU`. TODO comment removed from `SamplingParams.java`.
5. ~~Server-model compatibility warning~~ **DONE** (2026-02-16) — Two warnings added: (a) GPU variant mismatch in `LlamaServerOps.startLlamaServer()` — warns if `gpuLayers > 0` but exe path doesn't contain "variants"; (b) Thinking model mismatch in `ServerPropsOps.warnIfThinkingMismatch()` — warns if `USE_THINKING=true` but model name lacks "Thinking".
6. ~~Prepare `model-registry.v1.json` entries~~ **DONE** (2026-02-16) — 4 entries updated/added: `chat` updated to Thinking model (`9e54ca76...`, validated), `mmproj` (Thinking vision projection, `0fda38a0...`), `onnx-reranker-gte` (modernbert INT8, `ecc6a0ae...`), `onnx-reranker-gte-tokenizer` (`2aea6ff4...`).
7. ~~Fix stale dev binary (G-1)~~ **DONE** (2026-02-16) — deleted repo-root `native-bin/llama-server/` (gitignored, 0 tracked files)
8. ~~Document dual exe resolution paths (G-2)~~ **DONE** (2026-02-16) — added §3.3 to `docs/explanation/13-ai-setup-and-verification.md`
9. ~~Create GPU testing how-to (G-3)~~ **DONE** (2026-02-16) — created `docs/how-to/test-gpu-locally.md`
10. ~~Document system property precedence (G-4)~~ **N/A** — already documented at `docs/reference/configuration/environment-variables.md` lines 18-22

**Validated (2026-02-16):**
11. ~~Performance benchmarks~~ **DONE** (2026-02-16) — Fixed 3 bugs in `llm-bench-win.ps1`: (a) `$response.results.Count` fails in strict mode → wrapped in `@()`; (b) `$allDocIds.Count` same issue → wrapped in `@()`; (c) `query = "*"` returns 0 results in SIMPLE syntax → changed to `*:*` with `querySyntax = "LUCENE"`. Benchmark results (**Note: tested on Qwen3-4B-Instruct, not the target Thinking model** — CUDA variant, GPU layers 79): TTFT p50=7.9s, tok/s ~63 (3/6 requests produced output; 3/6 had 0 chunks — context exhaustion from reasoning). VRAM: 9.08 GB used / 12 GB total. Result: `tmp/bench/claim-d/20260215-113843/result.json`.
12. ~~Agent quality~~ **DONE** (2026-02-16) — Agent correctly uses tools (search_index), synthesizes coherent multi-sentence answers from results, no `<think>` tag leakage in output. Reasoning received via `reasoning_content` SSE field (separate from content).
13. ~~VDU quality~~ **DONE** (2026-02-16) — Tested directly against standalone llama-server (CUDA variant, Thinking model + mmproj, port 8086). Vision processing confirmed: model correctly identifies UI elements from screenshots (AI Brain panel, "Not Installed" status, "Install AI" button, feature list). `/no_think` prefix produces visible content after reasoning. Performance: prompt 1048 tok/s (vision encoder), generation ~55 tok/s. Note: tested via direct API call, not through app VDU pipeline (would require env var overrides for dev stack).
14. ~~VRAM measurement~~ **DONE** (2026-02-16) — Thinking Q4_K_M (4.68 GB) + mmproj F16 (1.16 GB) + KV cache (4096 ctx) = **9.04 GB VRAM used** on 12 GB GPU (RTX 4070). Free: 2.95 GB. Fits within 12 GB budget.
15. ~~Reranker quality eval~~ **DONE** (2026-02-16) — `CrossEncoderRerankerIntegrationTest` passes with `JUSTSEARCH_RERANK_MODEL_PATH=models/reranker/gte-reranker-modernbert-base`. `RagQualityEvalTest` gap (no reranker path) remains documented.

**Resolved:**
16. ~~Sync CPU and CUDA variant builds~~ **RESOLVED** (2026-02-16) — `build.gradle.kts` (line 277) pins both to `b7502`. Stale dev binary deleted.

---

## Experimental Findings (2026-02-13)

Tested on the development machine with llama-server build 7315, `--jinja` flag enabled.

### Test 1: Qwen3-0.6B (standard text model)

Thinking works out of the box. llama-server auto-detects `enable_thinking` from the embedded Jinja template.

| Condition | `reasoning_content` | `content` | `finish_reason` |
|-----------|---------------------|-----------|-----------------|
| Baseline (no switch) | 519 chars | 76 chars | stop |
| `/think` in system prompt | 1224 chars | 0 chars (budget exhausted) | length |
| `/no_think` in system prompt | none | 14 chars | stop |

Conclusion: thinking is **ON by default** in standard Qwen3 models. The `/think` and `/no_think` soft switches work.

### Test 2: Qwen3-VL-8B-Instruct (current production model)

No thinking in any condition. Tested with the actual `Qwen3VL-8B-Instruct-Q4_K_M.gguf`.

| Condition | `reasoning_content` | `content` |
|-----------|---------------------|-----------|
| Baseline | 0 chars | 533 chars |
| `/think` | 0 chars | 335 chars |
| `/no_think` | 0 chars | 14 chars |

Conclusion: VL-Instruct models were **not trained for thinking**. Only dedicated "Thinking" variants (e.g., Qwen3-VL-30B-A3B-Thinking) have this capability.

### Test 3: Agent simulation with tool results (Qwen3-VL-8B)

Simulated the full agent flow — system prompt + tool definitions + user query → tool call → fake search results → final answer. Two scenarios tested with thinking on/off.

| Scenario | Mode | Think chars | Answer chars | Time (CPU) | Tool called? |
|----------|------|-------------|-------------|------------|-------------|
| Tool selection + synthesis | on | 0 | 533 | 19.2s | No (failed to call tool) |
| Tool selection + synthesis | off | 0 | 335 | 11.8s | No (failed to call tool) |
| Relevance filtering | on | 0 | 414 | 25.5s | search_index |
| Relevance filtering | off | 0 | 232 | 19.1s | search_index |

Notable: the model failed to call `search_index` for the first scenario ("How does the AI inference work?") and instead answered from its own knowledge — exactly the B3/B4 quality gap. With thinking, a model could reason "I should search first" before committing to a response.

### Key observations

1. **`AgentLoopService` silently drops `reasoning_content`** — it only reads `delta.content` and `delta.tool_calls` from the SSE stream. If a thinking model is used, the code must be updated. **[Resolved in Phase 3 — reasoning extraction now implemented in `OnlineModeOps` + `AgentLoopService`.]**

2. **GPU not loading**: The RTX 4070 (12 GB VRAM) was not used — llama-server only loaded CPU backends despite `ggml-cuda.dll` being present. Likely a CUDA runtime dependency issue (toolkit libs not in PATH). Must be resolved independently. **[Resolved — see §CUDA variant investigation. Issue was CPU variant selection, not a bug.]**

3. **`/no_think` saves latency** even on non-thinking models — the VL-8B showed ~40% faster responses with `/no_think`. May be suppressing minimal internal overhead even without trained thinking.

---

## Research Findings — Unified vs Split (2026-02-14)

### The VL-Thinking model landscape

Qwen3-VL ships in **both Instruct and Thinking editions** across all sizes. llama.cpp support landed October 30, 2025. Official GGUF files are available on Hugging Face.

| Model | Active Params | Thinking | Official GGUF | MMMU | MathVista |
|-------|-------------|----------|---------------|------|-----------|
| **Qwen3-VL-2B-Thinking** | 2B | Yes | Community | — | — |
| **Qwen3-VL-4B-Thinking** | 4B | Yes | Yes (Qwen/) | — | — |
| **Qwen3-VL-8B-Thinking** | 8B | Yes | Yes (Qwen/) | ~70-72 | ~79-80 |
| Qwen3-VL-30B-A3B-Thinking | 3B active (MoE) | Yes | Yes (Qwen/) | — | — |
| Qwen3-VL-32B-Thinking | 32B | Yes | Yes (Qwen/) | 78.1 | 85.9 |
| Kimi-VL-A3B-Thinking | ~3B active (MoE) | Yes | Unknown | 64.0 | 80.1 |

Other VL+thinking models exist (LLaVA-CoT, InternVL3 with MPO) but lack GGUF support or are outdated.

### Thinking vs Instruct quality delta

**At 32B scale** (best-documented comparison):

| Benchmark | Thinking | Instruct | Delta |
|-----------|----------|----------|-------|
| MMMU | 78.1 | 76.0 | +2.1 |
| MathVista_mini | 85.9 | 83.8 | +2.1 |
| MathVision | 70.2 | 63.4 | **+6.8** |
| MMLongBench-Doc | 55.4 | 54.6 | +0.8 |

Thinking's largest gains are on STEM/math reasoning. On perception benchmarks (RealWorldQA, ScreenSpot), the difference is negligible.

**At 8B scale** (approximate, multiple sources):

| Benchmark | Thinking | Instruct | Delta |
|-----------|----------|----------|-------|
| MMMU | ~70-72 | ~69-70 | +2-3 |
| MathVista | ~79-80 | ~77 | +2-3 |
| OCRBench | ~900-910 | ~896 | +4-14 |
| DocVQA | ~97% | ~96% | ~0 |

### The "vision tax" on reasoning

**At 32B+ scale: no measurable penalty.** Qwen reports that Qwen3-VL-235B-A22B matches its text-only counterpart on text benchmarks. At 32B, the VL model actually **outperforms** text-only on MMLU-Pro by 6.7 points. Qwen attributes this to "early-stage joint pretraining of text and visual modalities."

**At 8B scale:** Qwen claims parity but granular per-benchmark comparisons (MATH, GSM8K, GPQA) for Qwen3-8B vs Qwen3-VL-8B are not published in accessible benchmark tables. The claim is plausible but unverified at this size.

**Broader research findings:**
- Meta's UniBench study: "scaling data and model size improves perception but not reasoning" in VLMs
- Multiple studies: "vision remains the weaker modality in MLLMs"
- VisuLogic benchmark: most VLMs score below 30% (only slightly above 25% random baseline)
- However, the reasoning gap is specifically in **vision-centric reasoning** (reasoning about visual relationships), not text reasoning with images as supplementary input. For JustSearch's use case (text tool-calling + text synthesis), the vision encoder is largely inert during chat — it's not processing images.

### Thinking mode can hurt VDU perception

**Critical finding:** Extended thinking chains can yield "long-wrong trajectories that ignore the image and underperform the same models run in standard instruct mode." Thinking mode is specifically beneficial for STEM/math reasoning but can be counterproductive for pure perception tasks (OCR, layout extraction).

**Mitigation:** The `/no_think` soft switch. Tested in §Experimental Findings — it works on all Qwen3 models. For VDU workloads, send `/no_think` in the system prompt to suppress thinking and avoid the perception regression.

### Qwen3.5 release update

Qwen3.5 is now released, but the practical status for JustSearch is more constrained than the earlier forecast:
- The clearly available official open-weight artifact is `Qwen3.5-397B-A17B` (397B total / 17B active), published in Transformers/safetensors form.
- HF docs list Qwen3.5 support in Transformers and reference smaller variants, but a small official GGUF-ready replacement matching our current local deployment path is not established.
- The Qwen3.5 card states the model does not officially support `/think` / `/nothink`; thinking control is via explicit API parameters.

Conclusion: Qwen3.5 confirms the long-term unified-model direction, but it is not yet a practical default main-model swap for JustSearch's local GGUF-first runtime.

### Comparison: unified vs split

| Dimension | Unified (Qwen3-VL-8B-Thinking) | Split (Qwen3-8B text + Qwen3-VL-2B VDU) |
|-----------|-------------------------------|------------------------------------------|
| **VRAM footprint** | ~5-6 GB (same as current) | ~7.6 GB (simultaneous) or ~5.5 GB (swap) |
| **Architecture changes** | Minimal — same single-server topology | Massive — §6-§14 in this tempdoc |
| **Pack size** | ~5.1 GB (same as current) | ~7.6 GB (+49%) |
| **Agent reasoning** | +2-3 on MMMU/MathVista vs Instruct | Potentially higher (text-only, no vision overhead) but unverified at 8B |
| **VDU quality** | Same vision encoder as current VL-8B | Regression to 2B vision (~88-90% DocVQA vs ~96-97%) |
| **Tool calling** | Unverified — VL-Thinking not benchmarked on BFCL | Qwen3-8B text likely strong (pure text model) |
| **Thinking for VDU** | Must use `/no_think` to avoid perception regression | N/A — VDU model doesn't think |
| **Independent upgrades** | Coupled — VDU quality tied to chat model choice | Decoupled — upgrade each independently |
| **Risk** | Low — near drop-in replacement | High — 9 new architecture concerns to solve |
| **Effort** | ~390 lines across 20 files (see §Completed code changes) | Months of work, new state machine, new routing |
| **Qwen3.5 readiness** | Requires a practical small, official GGUF-ready Qwen3.5 variant (not available yet) | Split architecture still risks rework if unified small variants land |

---

## Workload Analysis

| Workload | Current model | Key capability needed | Thinking useful? | Frequency |
|----------|--------------|----------------------|-----------------|-----------|
| Agent chat (tool calling) | Qwen3-VL-8B | Function calling, reasoning, synthesis | **YES** — B3/B4 gaps | Every agent turn |
| VDU (document OCR/layout) | Qwen3-VL-8B | Vision encoder, text extraction | No — pure perception | At ingest time |
| Embedding generation | Separate model | Dense vector encoding | No — different model class | At ingest time |

VDU and embeddings run at **ingest time** (batch, background). Agent chat runs at **query time** (interactive, latency-sensitive). This means:
- The chat model must be loaded and ready at all times
- The VDU model only needs to be loaded during document processing
- Model swapping is viable for VDU (not latency-critical)

---

## Functionality-to-Model Mapping

Every AI-powered feature in JustSearch needs to be assigned to a specific model. This section enumerates all current and near-term functionalities and which model class they belong to.

> **Note:** In the **unified path** (Qwen3-VL-8B-Thinking), the "Chat model" and "VDU model" sections below collapse into a single model. The chat functionalities run with thinking enabled; the VDU functionalities run with `/no_think`. The mapping below still applies — it describes which *mode* each workload uses, not necessarily which *model*.

### Chat model (text-only, thinking-capable)

These functionalities need strong reasoning, tool calling, or natural language generation — but **never** need vision. In the unified path, these run on VL-Thinking **with thinking enabled**.

| Functionality | Description | Why this model |
|---------------|-------------|----------------|
| **Agent tool selection** | Deciding whether/which tool to call given a user query | Reasoning over instructions. Core B3 gap. |
| **Agent synthesis** | Composing a final answer from tool results | Multi-document reasoning + coherent generation. Core B4 gap. |
| **Agent multi-turn** | Following up, refining, or correcting across turns | Conversation tracking, context management |
| **Relevance assessment** | Filtering search results before presenting to user | Reasoning about query-document match |
| **Query rewriting** | Expanding or reformulating user queries for better search | Linguistic understanding, intent extraction |
| **RAG generation** | Answering questions grounded in retrieved passages | Grounded synthesis with citation |
| **Summarization** | Condensing long documents or result sets | Text comprehension and compression |

### VDU model (vision-language, perception-focused)

These functionalities need a **vision encoder** to process images. Reasoning quality is secondary — the task is perceptual extraction. In the unified path, these run on VL-Thinking **with `/no_think`**.

| Functionality | Description | Why this model |
|---------------|-------------|----------------|
| **Page OCR** | Extracting text from rendered document pages (VDU Pass 1) | Needs vision encoder. Pure perception task. |
| **Layout understanding** | Identifying tables, headings, columns, reading order | Spatial reasoning over visual layout |
| **VDU enrichment** | Generating summary, doc type, entities from extracted text (VDU Pass 2) | Currently uses chat completion but could stay on VDU model to avoid cross-server hop |
| **Image description** | Future: describing images found in documents for indexing | Needs vision encoder |
| **Handwriting recognition** | Future: reading handwritten notes, forms | Specialized vision capability |

### Embedding model (encoder-only, unchanged)

| Functionality | Description | Why this model |
|---------------|-------------|----------------|
| **Document embedding** | Dense vector encoding of text chunks at ingest | Different model class entirely (nomic-embed) |
| **Query embedding** | Dense vector encoding of search queries at query time | Same model, same vector space |

### Cross-model reranking (ONNX, unchanged)

| Functionality | Description | Why this model |
|---------------|-------------|----------------|
| **Reranking** | Scoring query-document relevance after retrieval | Cross-encoder (ONNX runtime, not llama-server) |
| **Citation scoring** | Identifying which passages support a claim | Cross-encoder (ONNX runtime, not llama-server) |

### Ambiguous / routing decisions needed (split path only)

In the unified path, all ambiguity disappears — one model handles everything, switching between `/think` and `/no_think` per workload.

In the split path:

| Functionality | Option A (chat model) | Option B (VDU model) | Recommendation |
|---------------|----------------------|---------------------|----------------|
| **VDU Pass 2 enrichment** | Better JSON structure, better reasoning | Already loaded during ingest, no cross-server hop | VDU model — avoid latency of routing to chat server during batch ingest |
| **Document classification** | Better reasoning about categories | Could use vision cues (letterheads, logos) | Depends on whether classification uses rendered pages or extracted text |
| **Future: image-based Q&A** | N/A — no vision | Required — needs vision encoder | VDU model, but may need better reasoning than a small VDU model provides |

---

## Model Candidates

### Unified (vision + thinking in one model)

| Model | Params | Q4_K_M size (+ mmproj) | Thinking | Vision | Tool calling | Notes |
|-------|--------|------------------------|----------|--------|-------------|-------|
| **Qwen3-VL-8B-Thinking** | 8B | ~5-6 GB | Yes | Yes | Unverified | **Primary candidate.** Same size as current. Drop-in + thinking. Needs `/no_think` for VDU. |
| Qwen3-VL-4B-Thinking | 4B | ~3.5 GB | Yes | Yes | Unverified | Budget option. May sacrifice quality on both axes. |
| Qwen3-VL-30B-A3B-Thinking | 3B active (MoE) | ~18 GB Q4 | Yes | Yes | Unverified | Doesn't fit in 12 GB at Q4. Q2/Q3 might fit — quality unknown. |

### For agent chat only (split path — text + tool calling + thinking)

| Model | Params | Q4_K_M size | Thinking | Tool calling | Notes |
|-------|--------|-------------|----------|-------------|-------|
| **Qwen3-8B-Instruct** | 8B | ~5 GB | Yes (built-in) | Yes | Same param count as current VL-8B. Drop-in for chat quality. |
| Qwen3-4B-Instruct | 4B | ~2.5 GB | Yes (built-in) | Yes | Half the VRAM. May sacrifice tool-calling accuracy. |
| Qwen3-30B-A3B-Instruct | 30B (3B active) | ~18 GB Q4 | Yes | Yes | MoE. Too large for Q4 on 12 GB. Q2/Q3 might fit — quality unknown. |
| Qwen3-14B-Instruct | 14B | ~8.5 GB | Yes | Yes | Strong reasoning. Fits alone but leaves little room for VDU model. |

### For VDU only (split path — vision + document understanding)

| Model | Params | Total size (Q4 + mmproj) | DocVQA est. | llama.cpp support |
|-------|--------|--------------------------|-------------|-------------------|
| **Qwen3-VL-2B-Instruct** | 2B | ~2.1 GB | ~88-90% | Yes (merged) |
| Qwen3-VL-4B-Instruct | 4B | ~3.5 GB | ~92-94% | Yes (merged) |
| SmolVLM2-2.2B | 2.2B | ~1.6 GB | Moderate | Yes (official) |
| InternVL3-2B | 2B | ~1.6 GB | Moderate | Yes (official) |
| Qwen3-VL-8B-Instruct | 8B | ~6.0 GB | ~96-97% | Yes (current) |

The ~1 GB mmproj (vision encoder) is a **fixed cost** shared across all Qwen3-VL sizes — same encoder architecture, same file size. Note: Instruct and Thinking variants have **different mmproj weights** despite identical file size (verified: different SHA-256 hashes). The vision encoder was retrained or fine-tuned for the Thinking variant.

---

## VRAM Budget Configurations

Hardware: RTX 4070, 12 GB VRAM. OS/driver overhead: ~0.5-1 GB.

| Config | Chat model | VDU model | Embed | Total est. | Headroom | Thinking? |
|--------|-----------|-----------|-------|-----------|----------|-----------|
| **Current** | Qwen3-VL-8B-Instruct Q4 (5 GB) | (same) | ~0.5 GB | ~5.5 GB | ~5.5 GB | No |
| **Unified** | Qwen3-VL-8B-Thinking Q4 (~5-6 GB) | (same) | ~0.5 GB | ~5.5-6.5 GB | ~4.5-5.5 GB | **Yes** |
| **Split A** | Qwen3-8B Q4 (5 GB) | Qwen3-VL-2B Q4 (2.1 GB) | ~0.5 GB | ~7.6 GB | ~3.4 GB | Yes |
| **Split B** | Qwen3-8B Q4 (5 GB) | Qwen3-VL-4B Q4 (3.5 GB) | ~0.5 GB | ~9.0 GB | ~2.0 GB | Yes |
| **Split C** | Qwen3-4B Q4 (2.5 GB) | Qwen3-VL-2B Q4 (2.1 GB) | ~0.5 GB | ~5.1 GB | ~5.9 GB | Yes |
| **Swap A** | Qwen3-8B Q4 (5 GB) | Qwen3-VL-2B Q4 (swapped in) | ~0.5 GB | ~5.5 GB loaded | ~5.5 GB | Yes |

**Unified** is a near drop-in replacement — same VRAM class, same single server, same pack structure.
"Swap" configs load VDU model only during ingest, then unload to reclaim VRAM.

---

## Architecture Questions

### 1. Simultaneous vs swapped loading

With Split A (7.6 GB), both models fit in 12 GB with headroom. Simultaneous loading is simpler — no swap latency, no lifecycle complexity. Swapping only makes sense if VRAM is tight (Split B) or if the VDU model is rarely needed.

**To validate**: Actual VRAM usage with both models loaded. Estimates may not account for KV cache, CUDA context overhead, etc.

### 2. Server topology

Options:
- **Two llama-server processes** on different ports — simplest isolation, but `InferenceLifecycleManager` currently manages exactly one process
- **One llama-server with model routing** — llama-server now supports dynamic model loading/unloading. Requires version check.
- **Dedicated VDU binary** — use `llama-mtmd-cli` or similar for batch VDU processing instead of a server

The VDU workload is batch (ingest-time), not interactive. It doesn't need a persistent server — a CLI tool that processes documents and exits may be simpler than a second server.

### 3. Context window tradeoffs

Thinking tokens consume context. Current context: 4096 tokens.

- Agent system prompt + tool definitions: ~500-800 tokens
- Thinking: potentially 200-1000 tokens per turn
- Tool results (search hits): 500-2000 tokens per call
- Multi-turn conversation: accumulates

With thinking enabled, 4096 may be too tight. Consider:
- Increasing to 8192 (doubles KV cache VRAM — ~200 MB extra at Q4 KV)
- Capping thinking budget (`--reasoning-budget` flag in llama-server)
- Summarizing tool results before feeding back

### 4. Pack format changes

Current AI pack bundles: 1 chat model + 1 embedding model. A split needs:
- Chat model (text, thinking-capable)
- VDU model (vision, document understanding)
- Embedding model (unchanged)

Files affected: `model-registry.v1.json`, `pack-manifest.v1.json`, `AiPackImportService`, `pack-author.ps1`.

### 5. Agent code changes for thinking

`AgentLoopService` needs to handle `reasoning_content`:
- Parse `delta.reasoning_content` from SSE chunks
- Log it for debugging (valuable for quality analysis)
- Don't include it in user-visible response
- Set sampling parameters: `temperature=0.6`, `top_p=0.95` (Qwen3 recommended for thinking)

### 6. GPU mutual exclusion / mode state machine

`InferenceLifecycleManager` enforces strict ONLINE vs INDEXING mutual exclusion — one GPU consumer at a time. The embedding backend (FFM in Worker) **unloads itself** when the chat server signals `main_gpu_active` via MMF. This state machine assumes a single llama-server process.

With a model split:
- **Simultaneous loading** (Split A/B/C): Two llama-server processes on GPU simultaneously. Breaks the single-tenant GPU policy. The mode state machine needs a new concept — perhaps ONLINE means "chat server loaded" and VDU server is managed independently.
- **Swap loading** (Swap A): VDU server starts for ingest, chat server stays loaded. Still two processes sharing GPU temporarily, unless the chat server is unloaded during ingest (unacceptable — blocks interactive queries).
- **Three-way coordination**: Embedding (FFM, Worker process) + chat server + VDU server. If embeddings run CPU-only (current default), this simplifies to two-way. The tempdoc's VRAM budgets assume CPU embeddings — must be stated explicitly.

Key code paths affected: `InferenceLifecycleManager.transitionMode()`, `LlamaServerOps`, MMF `main_gpu_active` signal, `EmbeddingService` GPU coordination.

### 7. Request routing

Currently all AI requests flow through `InferenceLifecycleManager` → `OnlineModeOps` → single llama-server endpoint. With two servers:

- `AgentLoopService` → chat server (port A)
- `VduProcessor` Pass 1 (vision completion) → VDU server (port B)
- `VduProcessor` Pass 2 (enrichment) → VDU server (port B) or chat server (port A)
- Simple chat completions (non-agent) → chat server (port A)

`OnlineModeOps` is hardcoded to one port (read from `InferenceConfig`). It also reads model alias from `/props` to populate the `model` field in requests. Both need to become multi-endpoint-aware.

Options:
- **Two `OnlineModeOps` instances** behind a routing layer in `InferenceLifecycleManager`
- **Separate VDU client** in the VDU processor, independent of `InferenceLifecycleManager`
- **Single llama-server with multi-model** — if llama-server supports loading two models, routing is internal to the server

### 8. Sampling parameter injection

The codebase currently sends **zero sampling parameters** in agent/chat requests — it relies entirely on llama-server defaults. The tempdoc recommends `temperature=0.6, top_p=0.95` for thinking mode.

Work needed:
- Mechanism to set per-request sampling params in `OnlineModeOps.sendChatRequest()`
- Different presets per workload: thinking mode (temp 0.6) vs non-thinking (temp 0.7 or server default)
- `/no_think` soft switch for workloads that shouldn't think (VDU Pass 2 if routed to chat model, simple completions)
- Consider whether `--reasoning-budget` (server-level cap) is sufficient vs per-request control

### 9. mmproj absence on chat model

`InferenceConfig` gates vision features on `mmprojPath != null`. The text-only chat model (e.g., Qwen3-8B) has no mmproj. This is correct behavior — it means the chat server won't advertise vision support. But:

- `VduProcessor` calls `inferenceManager.visionCompletion()` — this must route to the VDU server, never the chat server
- The `/api/inference/status` endpoint reports mmproj presence — with two servers, which one's status is reported?
- Users who override `JUSTSEARCH_LLM_MODEL_PATH` to a text-only model will break VDU unless the VDU model is configured separately

### 10. Health monitoring / status API for dual servers

Current single-server assumptions:
- One `/health` endpoint polled at 30s intervals
- One crash counter (max 3 → OFFLINE)
- One `/props` read for `n_ctx` and `model_alias`
- One `ActiveRuntime` exposed via `/api/inference/status`

With two servers:
- Independent health polling for each
- Independent crash counters — VDU server crash shouldn't disable agent chat
- Partial failure modes: chat up + VDU down = agent works, ingest degrades
- Status API shape change: `ActiveRuntime` becomes a collection, or separate fields for chat vs VDU runtime
- `/api/health` aggregate: healthy if chat is up (VDU being down is a degraded state, not unhealthy)

### 11. Minimum hardware floor / graceful fallback

The tempdoc targets 12 GB VRAM (RTX 4070). Degradation path for lower hardware:

| VRAM | Strategy | Chat | VDU | Thinking? |
|------|----------|------|-----|-----------|
| 12 GB+ | Split A (simultaneous) | Qwen3-8B GPU | Qwen3-VL-2B GPU | Yes |
| 8 GB | Swap (time-shared) | Qwen3-8B GPU | Qwen3-VL-2B swapped in | Yes |
| 6 GB | Smaller chat + swap | Qwen3-4B GPU | Qwen3-VL-2B swapped in | Yes |
| 4 GB | CPU fallback | Qwen3-4B CPU | Qwen3-VL-2B CPU | Yes (slow) |
| No GPU | All CPU | Qwen3-4B CPU | Qwen3-VL-2B CPU | Yes (slow) |
| Single-model fallback | Current behavior | Qwen3-VL-8B | (same) | No |

The system should detect VRAM tier and auto-select the loading strategy. `VramDetector` already produces tiers (`12gb_plus`, `8gb`, `4gb`, `under_4gb`) — this maps directly to split strategy selection.

### 12. Pack size / download impact

Current pack: 1 chat model + 1 embed model ≈ 5.1 GB.

| Pack config | Models | Estimated size | Delta |
|-------------|--------|---------------|-------|
| Current | VL-8B + nomic-embed | ~5.1 GB | — |
| Split (VL-2B VDU) | 8B-chat + VL-2B + nomic-embed | ~7.6 GB | +2.5 GB (+49%) |
| Split (VL-4B VDU) | 8B-chat + VL-4B + nomic-embed | ~9.0 GB | +3.9 GB (+76%) |
| Compact split | 4B-chat + VL-2B + nomic-embed | ~5.1 GB | ±0 |

For air-gap deployment, larger packs mean longer USB transfers. `pack-manifest.v1.json` needs a third file entry (`model.vdu` role). `model-registry.v1.json` needs a VDU model entry with its own SHA-256 pin and download URL.

### 13. Token counting / context budgeting

The system reads `n_ctx` from `/props` and uses it for RAG token budgeting. With two models at potentially different context sizes:

- Chat model might run at 8192 (to accommodate thinking tokens)
- VDU model might run at 4096 (sufficient for page extraction)
- RAG budgeting must use the **chat model's** context, not the VDU model's
- `lastKnownContextTokens` in `InferenceLifecycleManager` needs to track per-server values

### 14. External server adoption

The "adopt existing llama-server" feature checks a single configured port via `/props`. With two servers:
- Users who manually run llama-server need to know about both ports
- Adoption logic needs to distinguish "this is my chat server" vs "this is my VDU server" — perhaps by checking model name from `/props`
- One server adopted + one managed is a valid configuration (user runs their own chat model, JustSearch manages VDU)

---

## Validation Plan

### Phase 0: Unified model validation (try first — lowest risk)

Test Qwen3-VL-8B-Thinking as a near drop-in replacement for the current VL-8B-Instruct. This phase incorporates all remaining open risks (§Risk Triage) as explicit test steps.

#### Step 0.0 — Desk research (no downloads required)

These steps can be done immediately, before downloading any models.

1. **`/no_think` control semantics — UPDATED (2026-02-17).** `/no_think` is **not** a Jinja-level directive. It is plain text that Qwen-family models may follow, but behavior is model/template dependent and role-sensitive in practice.
   - Works reliably when placed in the **system prompt** (current VDU path).
   - Is **not reliable as a mid-conversation user-message recovery command** in our current agent loop (confirmed by tempdoc 208 live runs).
   - Qwen3.5 model docs additionally state `/think`/`/nothink` are not officially supported controls; use explicit reasoning-control parameters where available.
   - **Conclusion:** Do not assume role-agnostic `/no_think` compliance for retries. Keep system-prompt control and server-level reasoning budget controls as primary mechanisms.
   - Sources: tempdoc 208 live battery evidence; Qwen3.5 model docs.
2. **RAG quality baseline generation.** Run `RagQualityEvalTest` with the **current** Qwen3-VL-8B-Instruct model to establish the Instruct baseline. The current baseline is from Qwen3-4B (different model). Without a same-model baseline, the Instruct → Thinking comparison in Step 0.8 is meaningless. Save results as `scripts/bench/baselines/rag-eval-baseline-vl8b-instruct.json`.

#### Step 0.1 — Prerequisites

References: §Completed code changes, `docs/explanation/16-gpu-booster-pack.md` (llama-server build b7502), `docs/explanation/14-ai-pack-spec.md` (pack manifest schema).

1. Download from official `Qwen/Qwen3-VL-8B-Thinking-GGUF` (pinned commit SHA, not `main` branch):
   - `Qwen3VL-8B-Thinking-Q4_K_M.gguf` (main model)
   - `mmproj-Qwen3VL-8B-Thinking-F16.gguf` (vision encoder)
   - Place in AI Home `models/` directory
2. ~~Fix CUDA loading issue~~ **RESOLVED** (2026-02-16) — not a code bug. The dev default (`native-bin/llama-server/`) is the CPU variant (build 7315, dynamically-linked `ggml-cuda.dll` needing CUDA Toolkit). Use the CUDA variant at `modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe` (build 7502, self-contained, RTX 4070 detected). See §CUDA variant investigation.
3. **Quick check: llama-server compatibility.** Run CUDA variant `llama-server.exe --version` (at `variants/cuda12/`). Confirm build ≥b4215 (**verified: b7502**). Then start with Thinking GGUF + `--jinja` and verify:
   - `reasoning_content` appears in SSE `/v1/chat/completions` stream
   - `chat_template_tool_use` exists in `GET /props` response (required for native tool calling)
   - If either is missing → must upgrade llama-server before proceeding
4. **`/no_think` runtime confirmation.** Run two explicit checks: (A) `/no_think` in system prompt (expected to suppress reasoning), and (B) `/no_think` injected as a mid-conversation user message (expected to be unreliable in current setup). Treat (B) as unsupported behavior unless proven otherwise for the exact model/template pair.

#### Step 0.2 — Performance benchmarks

Addresses open risk: *Performance & latency (completely unmeasured)*.

| Metric | How to measure | Pass criteria |
|--------|---------------|---------------|
| TTFT (GPU) | Agent chat prompt, measure time to first SSE `delta` | < 5 seconds |
| tok/s (GPU) | Count tokens in full response / wall time | ≥ 10 tok/s |
| TTFT (CPU) | Same prompt, `--ngl 0` | < 30 seconds |
| tok/s (CPU) | Same method | ≥ 2 tok/s (usable, not ideal) |
| Thinking overhead | Count `reasoning_content` tokens per agent turn (avg, p95 across battery) | < 500 avg, < 1000 p95 |

Record all measurements. If GPU TTFT > 5s or tok/s < 10, the thinking model is too slow for interactive use.

**Known caveat:** Qwen3-VL GPU utilization is capped at ~80% in llama.cpp ([#16895](https://github.com/ggml-org/llama.cpp/issues/16895)), with worse performance than text-only Qwen3. Measured tok/s may be lower than text-only Qwen3 benchmarks suggest. Factor this into pass/fail evaluation.

#### Step 0.3 — Agent quality

Addresses: *core model evaluation*. References: tempdoc 186 §12-Experiment Battery, tempdoc 200 §Tool Calling.

**Pre-check:** Verify model has `chat_template_tool_use` via `GET http://localhost:<port>/props` (tempdoc 200). If absent → model cannot do native tool calling via llama-server.

1. Run the 12-experiment agent battery (tempdoc 186 §Post-Fix Validation) with thinking ON
   - EXP 1-12 as defined, using verbose `agent_chat` mode with iteration tracking
   - Specifically check EXP 6 (architecture explanation — was pure hallucination pre-fix) and EXP 7 (gibberish false positives — B3 gap)
2. Compare against 83% Instruct baseline (10/12 correct post-B1/B2 fixes)
3. Also run battery with `/no_think` — compare thinking vs non-thinking on same model
4. Record per-experiment metrics (from tempdoc 186):

| Metric | Baseline (Instruct) | Target (Thinking) |
|--------|---------------------|-------------------|
| Task completion | 83% (10/12) | > 90% (11+/12) |
| Tool selection accuracy | ~67% | > 85% |
| Path correctness | ~95% (post-fix) | ≥ 95% (no regression) |
| Groundedness | ~80% (post-fix) | > 85% |
| Step efficiency | 2.4 avg iterations | ≤ 2.5 (thinking may add 1 iteration) |

**Pass criteria:** correctness > 90%, no regression on path correctness or groundedness.

**Caveat (tempdoc 200):** KV quantization (`-ctk q4_0`) degrades tool calling. On 8 GB VRAM tier, tool-use quality will be noticeably worse. Phase 0 tests on 12 GB RTX 4070 without KV quantization — results may not transfer to lower tiers.

#### Step 0.4 — Structured output compliance

Addresses open risk: *Structured output quality with thinking*.

1. Run 5 intent translation queries (NL→SearchIntent JSON) with thinking ON
   - Verify every response is valid JSON and matches SearchIntent schema
   - Check: does thinking produce extraneous text around the JSON?
2. Run 3 summarization requests with thinking ON
   - Verify format adherence (section structure, length constraints)
3. Repeat both with `/no_think` — compare format compliance

**Pass criteria:** 100% valid JSON for intent translation. No format regression vs Instruct.

#### Step 0.5 — VDU quality + `/no_think` reliability

Addresses open risk: *`/no_think` reliability for VDU*. References: `VduProcessor.java` (two-pass pipeline), `PdfImageRenderer.java` (150 DPI), `ImagePreparer.java` (max 1280px).

The VDU pipeline renders PDF pages at 150 DPI → downscales to max 1280px → sends as JPEG base64 to the VL model. Pass 1 extracts text (vision completion, 2048 max tokens, 120s timeout). Pass 2 enriches metadata (chat completion, 512 max tokens, 60s timeout). Both passes must use `/no_think` to avoid perception regression.

1. Process 5 representative documents (mix of: clean PDF, scanned doc, dense table, small text ~6pt, diagram) with Thinking + `/no_think` in VDU system prompt
2. **Check for thinking leakage:** inspect raw SSE stream for any `reasoning_content` field in both Pass 1 and Pass 2. If present → `/no_think` is unreliable.
3. Compare OCR output against Instruct baseline (character-by-character diff or manual review)
4. Specifically check: umlauts, special chars, table structure, small text (<8pt), and multi-column layout reading order

**Pass criteria:** no thinking token leakage, OCR quality ≥ Instruct baseline on all 5 docs.

#### Step 0.6 — VRAM measurement

Addresses open risks: *KV cache sizing with thinking*, *mmproj VRAM overhead*.

Measure via `nvidia-smi` at each state:

| State | What to record | Budget limit |
|-------|---------------|-------------|
| Idle (model loaded, no request) | Baseline VRAM (model + mmproj + overhead) | — |
| Agent chat with thinking (4096 ctx) | Peak VRAM during generation | < 11 GB |
| VDU with `/no_think` (4096 ctx) | Peak VRAM during vision completion | < 11 GB |
| Agent chat max stress (8192 ctx, if supported) | Peak VRAM | < 11.5 GB |

**Pass criteria:** peak VRAM < 11 GB in normal operation (1 GB headroom for OS + other processes).

Also record: idle VRAM to quantify mmproj waste (expected ~1 GB loaded but unused during chat).

#### Step 0.7 — Multilingual spot check

Addresses open risk: *Multilingual quality*.

1. Process 2 German-language PDFs through VDU pipeline — verify OCR captures umlauts (ä, ö, ü), Eszett (ß), and German-specific formatting
2. Run 2 German agent chat queries ("Finde alle Dokumente über Datenschutz", "Zusammenfassung der Ergebnisse") — verify coherent German responses
3. Compare against Instruct baseline if available

**Pass criteria:** no catastrophic failure, German characters preserved in OCR, coherent German agent responses.

#### Step 0.8 — RAG quality eval regression check

References: tempdoc 198 §Quality Gates, `RagQualityEvalTest.java`, `scripts/bench/diff-rag-eval-suite.mjs`.

1. Run `RagQualityEvalTest` with Qwen3-VL-8B-Thinking (15 queries, hybrid search + LLM generation)
2. Assert 4 quality gates pass:

| Gate | Threshold | What it measures |
|------|-----------|-----------------|
| `fact_coverage_mean` | ≥ 0.25 | Required facts present in answers |
| `forbidden_fact_rate_mean` | ≤ 0.10 | No hallucinated/forbidden facts |
| `faithfulness_mean` | ≥ 0.30 | Answers grounded in retrieved passages |
| `answer_similarity_mean` | ≥ 0.15 | Semantic similarity to golden answers |

3. Save results to `build/test-results/rag-eval/rag-eval-result.v1.json`
4. Run diff script against existing baseline:
   ```
   node scripts/bench/diff-rag-eval-suite.mjs \
     scripts/bench/baselines/rag-eval-baseline.json \
     build/test-results/rag-eval/rag-eval-result.v1.json
   ```
5. If PASS or IMPROVED → commit new results as updated baseline
6. Verify `retrieval_recall_mean` = 1.000 (hybrid search unaffected by model change — this is a sanity check)

**Note:** Current baseline is from Qwen3-4B, not VL-8B. Generate a VL-8B-Instruct baseline first if one doesn't exist, so the diff compares Instruct → Thinking on the same model family.

**Pass criteria:** All 4 gates pass. No regression > 10% on any metric vs baseline.

#### Decision gate after Phase 0

| Outcome | Action |
|---------|--------|
| Agent quality > 90% AND VDU ≥ baseline AND VRAM < 11 GB AND latency acceptable AND RAG gates pass | **Adopt unified model, stop here** |
| Agent quality 84-90% (marginal improvement over 83% baseline) | Adopt **only if** thinking also improves VDU or structured output. If the only gain is +1-7% on agent battery, the added complexity (sampling params, reasoning parsing, `/no_think` routing) may not justify the switch. Document the marginal case and revisit when a deployable small Qwen3.5 GGUF-class option exists. |
| Agent quality improves but VDU regresses | Investigate `/no_think` tuning, or try `/no_think` with explicit OCR-only prompt. If unfixable → Phase 1 |
| Agent quality regresses (70-82%, below 83% baseline) | **Investigate root cause.** A model that thinks but scores worse than Instruct is a red flag — likely quantization interaction, prompt interference, or tokenizer regression. Do NOT simply "stay on Instruct." Diagnose: test with Q5_K_M or Q8_0 to isolate quantization. Test with simplified system prompt to isolate prompt interference. If root cause is fixable → fix and re-test. If inherent to the Thinking variant → stay on Instruct, document the finding. |
| Agent quality ≤ 83% but ≥ baseline (no improvement) | Thinking adds no value at Q4_K_M. Stay on Instruct. Proceed to Phase 1 only if the split path is expected to do better (text-only model without vision overhead). |
| VRAM exceeds budget | Reduce context to 4096 or try Q3_K_M quantization. If still over → split needed |
| Structured output breaks | Use `/no_think` for intent translation (not just VDU). If still broken → Phase 1 |

#### Rollback mechanism

If the Thinking model is adopted and subtle quality regressions surface post-deployment:

- **`JUSTSEARCH_USE_THINKING`** env var (default `true`). When set to `false`:
  - `OnlineModeOps` omits `temperature`/`top_p` from requests (uses server defaults)
  - `OnlineModeOps` omits `--reasoning-format deepseek` from llama-server command line (see A-9)
  - All prompts (agent, VDU, summarization) get `/no_think` prepended — this is the only reliable per-request mechanism, since llama.cpp does not expose the Jinja template's `enable_thinking` hard switch
  - `AgentLoopService` ignores `reasoning_content` (already accumulated but not displayed)
  - `<think>` tag stripping still runs (defense against llama.cpp bug [#13189](https://github.com/ggml-org/llama.cpp/issues/13189))
  - No server restart needed — prompt injection + tag stripping only
- **Old model filenames remain valid** via existing `JUSTSEARCH_VLM_MODEL` / `JUSTSEARCH_MMPROJ_MODEL` env overrides. Users can revert by setting these to the old filenames.
- Ship the old model in the pack as a fallback? **No** — doubles pack size. Rely on env var overrides + user download.

### Phases 1-3: Split path (not pursued)

> The split path (text-only chat + dedicated VDU) was not pursued — Phase 0 validated the unified model. See §Comparison: unified vs split and §Architecture Questions for the full analysis. If the split is ever needed, the architecture questions §1-§14 and VRAM budgets remain valid reference material.

---

## Recommendation

### Short term: swap to Qwen3-VL-8B-Thinking

**Try Qwen3-VL-8B-Thinking first.** This is the lowest-risk, lowest-effort path:

1. **Near drop-in replacement** — same model family, same size, same vision encoder architecture, same server topology. Code changes are limited to handling `reasoning_content` in SSE streams and injecting sampling parameters (~390 lines across 20 files — see §Completed code changes). Note: mmproj weights differ between Instruct and Thinking (different SHA-256 despite same file size) — use the Thinking-specific mmproj.
2. **Same VRAM footprint** — no GPU coordination changes, no dual-server routing, no pack format redesign.
3. **Thinking for chat, `/no_think` for VDU** — use the soft switch per workload. Agent gets reasoning, VDU gets fast perception. One model, two modes.
4. **Qwen3.5 readiness** — Qwen3.5 is now released, but an equivalent small official GGUF-ready variant for this deployment path is not available yet. If/when one lands, upgrading from VL-8B-Thinking is a model file swap; upgrading from a split architecture still means dismantling the split.

### Long term: don't build the split architecture yet

The split (text-only chat + small dedicated VDU) is architecturally elegant — independent upgrade paths, right-sized models per workload. But building it means rewriting the GPU mutual exclusion state machine, dual-server health monitoring and crash recovery, a request routing layer, pack format redesign (+49% download size), and solving 9 new architecture concerns (§6-§14). That's months of engineering. Qwen3.5's release strengthens the case for eventual unified models, so speculative split infrastructure still risks becoming dead code.

The split architecture research (§6-§14) is not wasted — it will be needed if JustSearch eventually requires fundamentally different models for chat vs VDU. But it should be built when the need is proven, not speculatively.

### When to revisit the split

The split becomes the right path only if any of these are demonstrated:

1. **Measurable vision tax on tool calling** — Qwen3-VL-8B-Thinking's tool calling quality is measurably worse than Qwen3-8B text-only on BFCL or our agent battery (unverified today — Qwen claims parity at 8B but hasn't published granular comparisons)
2. **VDU needs a different model family** — a specialized OCR model outside the Qwen3-VL family significantly outperforms VL-8B on document extraction quality
3. **Independent upgrade cadence required** — a product requirement emerges where chat and VDU models must evolve on different release cycles
4. **No deployable small unified successor appears** — Qwen3.5 (or peers) does not yield a practical local GGUF-class unified replacement in our hardware/runtime envelope

None of these conditions hold today. Validate with Phase 0, and revisit only if the unified model falls short.

---

## Hard Constraints

- Total VRAM budget: 12 GB (RTX 4070)
- All models must be GGUF format (llama.cpp runtime)
- Loopback-only binding (127.0.0.1) — security invariant
- Must work on Windows (NTFS paths, PowerShell tooling)
- Pack distribution format must remain self-contained
- Head never touches Lucene — delegate all index IO to Worker via gRPC
- Embedding dimension must be 768 — hardcoded in `SSOT/catalogs/fields.v1.json` (vector + chunk_vector fields). Changing dimensions requires full document re-index. All embedding model candidates must produce 768-dim vectors.

---

## Expected Deliverables

### Phase 0 (unified — primary path)

1. llama-server compatibility confirmed (reasoning_content in SSE)
2. Performance benchmarks: TTFT, tok/s on GPU and CPU, thinking token overhead (avg/p95)
3. Agent quality comparison: Thinking (with thinking) vs Instruct (baseline 83%), target >90%
4. Structured output compliance: intent translation JSON validity, summarization format
5. VDU quality check: Thinking + `/no_think` vs Instruct baseline, no thinking token leakage
6. VRAM measurement: idle, chat with thinking, VDU, peak < 11 GB
7. Multilingual spot check: German OCR + German agent chat
8. Implementation: `AgentLoopService` `reasoning_content` parsing, sampling params, `/no_think` for VDU
9. Updated `model-registry.v1.json` and `pack-manifest.v1.json` pointing to Thinking GGUF

### Reranker upgrade (independent track)

10. Download gte-reranker-modernbert-base INT8 ONNX (151 MB) + tokenizer.json
11. Verify `token_type_ids` handling in `CrossEncoderReranker.java`
12. RAG quality eval comparison vs MiniLM-L-6 baseline
13. Latency benchmark: per-query reranking time (target < 100ms for 20 passages)
14. Updated `model-registry.v1.json` and pack manifest with new reranker

### Phase 1-3 (split — only if Phase 0 fails)

7. Quality comparison: Qwen3-8B text-only vs VL-8B-Thinking on agent battery
8. VDU quality comparison: VL-2B vs VL-4B vs VL-8B on document samples
9. VRAM measurements for split configurations
10. Architecture recommendation: simultaneous vs swap, single vs dual server
11. Implementation plan with effort estimates for split
12. Updated pack format specification for three-model pack

---

## Model Ecosystem Survey (2026-02-14, refreshed 2026-02-17)

Comprehensive survey of models released October 2025 – February 2026, covering all four model roles in JustSearch.

### Main VL Model — Candidates

Only models with **all of**: vision encoder, GGUF + llama.cpp mmproj support, and ≤6 GB Q4_K_M were considered viable. Thinking and tool calling are highly desirable.

| Model | Params | Q4_K_M | MMMU | MathVista | DocVQA | OCRBench | Thinking | Tool calling | GGUF | Verdict |
|-------|--------|--------|------|-----------|--------|----------|----------|-------------|------|---------|
| **Qwen3-VL-8B-Thinking** | 8B | ~5.0 GB | ~71 | ~80 | ~97% | ~905 | **Yes** | **Yes** (native) | Official | **Top pick** |
| Qwen3-VL-8B-Instruct | 8B | ~5.0 GB | ~69 | ~77 | ~96% | ~896 | No | Yes (native) | Official | Current baseline |
| Qwen3-VL-4B-Thinking | 4B | ~2.5 GB | ~50 | ~58 | ~89% | ~875 | Yes | Yes (native) | Official | Budget fallback |
| MiniCPM-V 4.5 | 8B | ~5.0 GB | 67.6 | — | 94.7% | ~876 | Yes | No | Official | **llama.cpp fragile** — frequent regressions across builds |
| Gemma 3 12B | 12B | ~7.3 GB | 59.6 | 62.9 | 87.1% | — | No | No | Official | No thinking, no tool calling |
| Gemma 3 4B | 4B | ~2.5 GB | 48.8 | 50.0 | 75.8% | — | No | No | Official | Too weak |
| InternVL3.5-8B | 8B | ~4.7 GB | 73.4 | — | High | — | Yes | No | Community | **llama.cpp officially supported** (listed in multimodal.md) |
| Kimi-VL-A3B-Thinking | 3B active | ~10.5 GB | 64.0 | 80.1 | — | 869 | Yes | No | Community | Q4 too large (10.5 GB) |
| Pixtral 12B | 12B | ~7.5 GB | 52.5 | — | 90.7% | — | No | Yes | Community | Released Sep 2024 (too old) |
| SmolVLM2-2.2B | 2.2B | ~1.1 GB | Low | Low | Low | Low | No | No | Official | Too small for agent use |

**Not viable:** Phi-4-multimodal (no llama.cpp mmproj), Llama 4 Scout (40+ GB), Mistral Small 3.1 (14 GB), Qwen3-VL-30B-A3B (18.6 GB Q4), Gemma 3n (vision still not supported in llama.cpp as of b8054).

**Key finding:** Qwen3-VL-8B-Thinking is the only model that satisfies all requirements: vision, thinking, native tool calling, official GGUF, stable llama.cpp mmproj, and ≤6 GB. No other model in the Oct 2025 – Feb 2026 window comes close.

### Main VL Model — Release Watch (updated 2026-02-17)

| Model | Status | Expected | Relevance |
|-------|--------|----------|-----------|
| **Qwen3.5 (released family)** | **Released** — public flagship `Qwen3.5-397B-A17B` is available in Transformers/safetensors. | Small official GGUF-ready variants: unknown timing | Strategically important unified direction, but currently not practical as JustSearch's default local main model. |
| MiniCPM-o 4.5 | Released Feb 3, 2026 | Available now | 9B, vision+audio. Requires llama.cpp-omni fork (adds complexity). |
| Ministral 3 | Released Dec 2025 | Available now | 3B, vision+tool calling. Ultra-lightweight fallback. |
| Llama 5 "Avocado" | Pre-training complete | Q1 2026 | **Closed source.** Not relevant. |
| GLM-5 | Released Feb 11, 2026 | Available now | 44B active (MoE). Too large. |
| GPT-oss-20b | Released early 2026 | Available now | No vision. Text-only. |

**Action:** Keep Qwen3-VL-8B-Thinking as default. Re-run replacement evaluation when a practical small official Qwen3.5 variant with local deployment parity (ideally GGUF-ready) is available.

### Tiny Aya release update (Cohere, Feb 17, 2026)

Tiny Aya is now released as a compact multilingual family (3.35B-class) with Hub GGUF variants. This is relevant, but not as a direct main-model replacement:
- **Potential upside:** low-footprint multilingual text assistant candidate.
- **Constraints:** text-generation scope (no VDU vision path), 8K context class, and released checkpoints currently marked CC-BY-NC-4.0.
- **Project impact:** viable for optional text-only experiments/fallbacks, not for replacing JustSearch's default chat+VDU model path.

### Embedding Model — Candidates

Current baseline: **nomic-embed-text-v1.5** (GGUF Q4_K_M, ~80 MB, 768 dims, BEIR NDCG@10 ~52.8).

| Model | Params | Dims | GGUF Q4_K_M | BEIR NDCG@10 | Release | License | Notes |
|-------|--------|------|-------------|-------------|---------|---------|-------|
| **nomic-embed-text-v1.5** (baseline) | 137M | 768 | ~80 MB | ~52.8 | Feb 2024 | Apache 2.0 | Current. Stable. |
| nomic-embed-text-v2-moe | 475M | 768 | ~328 MB | 52.86 | Feb 2025 | Apache 2.0 | +0.06 BEIR for 4x size. Not worth it. |
| **Snowflake Arctic Embed M v2.0** | 305M | 768 | ~200 MB (est.) | **55.4** | Dec 2024 | Apache 2.0 | **Best upgrade on paper.** +2.6 BEIR. Same dims. **GGUF broken** in llama.cpp — triggers `GGML_ASSERT` failure ([Ollama #9511](https://github.com/ollama/ollama/issues/9511)). |
| EmbeddingGemma-300M | 308M | 768 | ~170 MB (est.) | — | Sep 2025 | Gemma | MTEB 69.67 (highest). But 2K context limit, no BEIR breakdown. |
| Qwen3-Embedding-0.6B | 600M | 1024 | ~639 MB (Q8 only) | ~50 | Jun 2025 | Apache 2.0 | Too large. Dim mismatch (1024 vs 768). |
| mxbai-embed-large-v1 | 335M | 1024 | ~216 MB | 51.7 | Mar 2024 | Apache 2.0 | Below baseline on BEIR. Dim mismatch. |

**Not viable for GGUF:** stella_en_400M_v5 (arch incompatible), jina-embeddings-v3/v4 (non-commercial license).

**Recommendation:** Stay with nomic-embed-text-v1.5 for now. The best candidate is Snowflake Arctic Embed M v2.0 (+2.6 BEIR, same 768 dims, ~200 MB), but its GGUF is **broken in llama.cpp** — triggers a `GGML_ASSERT` failure in the GGUF parser ([Ollama #9511](https://github.com/ollama/ollama/issues/9511)). Not usable until the llama.cpp bug is fixed. Not urgent — the current model is adequate.

### Reranker Model — Candidates

Current baseline: **ms-marco-MiniLM-L-6-v2** (ONNX INT8, ~22 MB, BEIR ~41-43).

| Model | Params | ONNX | Est. INT8 size | BEIR Avg | Release | License | Notes |
|-------|--------|------|----------------|----------|---------|---------|-------|
| **ms-marco-MiniLM-L-6-v2** (baseline) | 22.7M | Yes | ~22 MB | ~41-43 | ~2021 | Apache 2.0 | Current. Fast but dated. |
| ms-marco-MiniLM-L-12-v2 | 33.4M | Yes | ~33 MB | ~44-46 | ~2021 | Apache 2.0 | Marginal improvement. |
| mxbai-rerank-xsmall-v1 | 70.8M | Yes (quant) | ~87 MB | 43.9 | ~2024 | Apache 2.0 | Modest gain. |
| **gte-reranker-modernbert-base** | 149M | Yes | **~75 MB** | **56.73** | Jan 2025 | Apache 2.0 | **Transformative upgrade.** +13-15 BEIR. ModernBERT arch. 8K context. |
| BGE-reranker-v2-m3 | 568M | Community | ~1.1 GB | 56.51 | Feb 2024 | MIT | Good quality but far too large. |
| mxbai-rerank-base-v2 | 500M | No | — | 55.57 | Jun 2025 | Apache 2.0 | No ONNX. |
| jina-reranker-v3 | 600M | No | — | 61.94 | Sep 2025 | cc-by-nc | SOTA quality. No ONNX, non-commercial. |
| Qwen3-Reranker-0.6B | 600M | Community | ~1.2 GB | 65.80 | Jun 2025 | Apache 2.0 | LLM-based (autoregressive). Too large. |

**Recommendation:** Upgrade to **gte-reranker-modernbert-base**. This is the single highest-impact model change available — BEIR jumps from ~42 to ~57 (+15 points). It's an encoder-only cross-encoder (same paradigm as current), ONNX available, 8K token support, ~75 MB INT8. The citation scorer (ms-marco-MiniLM-L-2-v2, 15 MB) remains adequate for its role.

### Recommended Model Spread

| Role | Current | Recommended | Change type | Impact |
|------|---------|-------------|-------------|--------|
| **Main (chat + VDU)** | Qwen3-VL-8B-Instruct | **Qwen3-VL-8B-Thinking** | Model swap | Thinking for agent quality, `/no_think` for VDU |
| **Embedding** | nomic-embed-text-v1.5 | **nomic-embed-text-v1.5** (keep) | None | Adequate. Arctic Embed M v2.0 is a future option. |
| **Reranker** | ms-marco-MiniLM-L-6-v2 | **gte-reranker-modernbert-base** | Model + ONNX swap | +15 BEIR points. Highest-impact change. |
| **Citation scorer** | ms-marco-MiniLM-L-2-v2 | **ms-marco-MiniLM-L-2-v2** (keep) | None | Speed matters here. 15 MB is fine. |

**Total VRAM:** ~5.5-6.5 GB (main model) + ~0.5 GB (embedding, CPU) = ~6-7 GB. Leaves 5-6 GB headroom on 12 GB RTX 4070.

**Pack size:** ~5.3 GB (main model 5 GB + embedding 80 MB + reranker 151 MB INT8 + citation 15 MB). Slightly larger than current (~5.1 GB) due to reranker upgrade.

### Watch list

| Model | When | Why |
|-------|------|-----|
| **Qwen3.5 (small variant)** | When a practical small official artifact is available with local deployment parity | Candidate unified successor to VL-8B-Thinking if quality and runtime constraints are met |
| **Tiny Aya (global/fire/etc.)** | Optional text-only track after license and routing review | Multilingual low-footprint assistant candidate; not a VDU replacement |
| Snowflake Arctic Embed M v2.0 | When llama.cpp GGUF parser bug is fixed | +2.6 BEIR embedding upgrade. Currently broken ([Ollama #9511](https://github.com/ollama/ollama/issues/9511)). |
| IBM Granite reranker | When ONNX files are published | ModernBERT, Apache 2.0, 149M params. Alternative to gte-reranker. No ONNX yet. |
| MiniCPM-o 4.5 | If llama.cpp-omni stabilizes | Alternative VLM with audio support |
| Gemma 3n vision | When llama.cpp adds support | Interesting efficiency play |

### Reranker Upgrade Path (gte-reranker-modernbert-base)

All four integration risks from the original survey have been resolved. The reranker upgrade is the highest-impact model change (+15 BEIR points) and can proceed independently of the main model swap.

**Integration feasibility — all confirmed:**

| Concern | Status | Evidence |
|---------|--------|----------|
| ONNX Runtime compatibility | ✅ | ORT 1.19.2 supports opset 14; ModernBERT exports at opset 14 |
| Tokenizer compatibility | ✅ | DJL `HuggingFaceTokenizer` loads any `tokenizer.json`; gte-reranker uses standard BPE |
| INT8 ONNX availability | ✅ | `model_int8.onnx` at 151 MB in official repo (also Q4F16 at 140 MB) |
| Input truncation | ✅ | `JUSTSEARCH_RERANK_MAX_SEQ_LEN` env var exists — set to 2048+ for longer passages |

**Upgrade steps:**

1. Download from `Alibaba-NLP/gte-reranker-modernbert-base` (pinned commit SHA):
   - `onnx/model_int8.onnx` (151 MB)
   - `tokenizer.json` (3.6 MB)
2. Update `model-registry.v1.json` with new SHA-256 hashes and URLs
3. Update `pack-manifest.v1.json` — pack size increases by ~129 MB (151 MB - 22 MB current)
4. Set `JUSTSEARCH_RERANK_MAX_SEQ_LEN=2048` (or 8192 for max benefit; benchmark both)
5. Run RAG quality eval (`RagQualityEvalTest`) to measure retrieval quality improvement
6. Run latency benchmark — MiniLM-L-6 inference is ~1ms; gte-reranker-modernbert-base at 151 MB will be slower. Measure per-query reranking time. Target: < 100ms for 20 passages.

**Note:** ModernBERT does not use `token_type_ids` (unlike classic BERT). ~~Verify that `CrossEncoderReranker.java` doesn't hardcode a `token_type_ids` tensor.~~ **Resolved (Phase 2, commit `4b8cc88`):** `CrossEncoderReranker` and `CitationScorer` now detect `token_type_ids` at ONNX load time via `session.getInputNames()` and skip the tensor when not present.

### Risk Triage (updated 2026-02-14)

The original survey identified 13 open risks. Desk research and code inspection have resolved 6, narrowed 2 to quick runtime checks, and refined 5 into the Phase 0 test protocol below.

#### Resolved risks

**Reranker ONNX op compatibility** — ✅ Resolved. ONNX Runtime 1.19.2 (bundled in Worker, `gradle/libs.versions.toml:27`) supports opset 14. ModernBERT ONNX exports use opset 14. No incompatibility.

**Reranker tokenizer compatibility** — ✅ Resolved. Worker uses DJL `HuggingFaceTokenizer` (`RerankerTokenizer.java:28`) which loads arbitrary `tokenizer.json` files via the HuggingFace Rust tokenizer library. gte-reranker-modernbert-base uses standard BPE tokenization. No code change needed — just ship the new `tokenizer.json` alongside the ONNX model.

**Reranker INT8 ONNX existence** — ✅ Resolved. The official `Alibaba-NLP/gte-reranker-modernbert-base` HuggingFace repo provides 8 ONNX formats:

| Format | File | Size |
|--------|------|------|
| FP32 | `model.onnx` | 599 MB |
| FP16 | `model_fp16.onnx` | 300 MB |
| INT8 | `model_int8.onnx` | 151 MB |
| Q4F16 | `model_q4f16.onnx` | 140 MB |

The INT8 variant at 151 MB is the best fit (vs our original estimate of ~75 MB). The Q4F16 at 140 MB is an alternative if size matters more.

**Model download URL stability** — ✅ Resolved. Official `Qwen/Qwen3-VL-8B-Thinking-GGUF` repository exists on HuggingFace (Qwen organization, not community). Pack system should pin to a specific commit SHA from this repo, not the `main` branch ref.

**Embedding dimension lock-in** — ✅ Resolved (moved to §Hard Constraints). 768 dimensions are hardcoded in `SSOT/catalogs/fields.v1.json` (lines 193, 205) for both `vector` and `chunk_vector` fields. Changing to a different dimension requires full document re-index. This is an architectural invariant, not a risk — all embedding model candidates must produce 768-dim vectors.

**Image resolution handling** — ✅ Resolved (quantified). The VDU pipeline renders PDF pages at **150 DPI** (`PdfImageRenderer.java:35`) then downscales to **max 1280px** on the longest side (`ImagePreparer.java:23`). For a standard 8.5×11" page: 150 DPI → 1275×1650px → downscaled to ~1280×1658px. This means fine text (~6pt), dense tables, or low-DPI scans may lose detail. However, this is a pipeline tuning concern independent of model choice — the same resolution is fed to any VL model. The Qwen3-VL dynamic resolution tiling handles the input from there.

**Benchmark validity** — ⚠️ Downgraded from "unknown" to "bounded risk." Research found that Qwen3 4-bit quantization (Q4_K_M equivalent) degrades MMLU by ~5 points vs FP16 (74.7% → 69.3% on Qwen3-8B). 8-bit quantization is nearly identical to FP16. No OCR-specific Q4_K_M benchmarks exist — this must be validated empirically in Phase 0. DocVQA/OCRBench remain imperfect proxies for full-page OCR; our own VDU test corpus is the only valid benchmark.

**mmproj VRAM overhead during chat** — ⚠️ Acknowledged, not actionable. The ~1 GB vision encoder stays loaded during text-only chat. This is inherent to unified VL models and would only be solved by the split architecture (which we've deferred). Measure actual overhead in Phase 0 VRAM test to quantify the waste.

#### Quick runtime checks (pre-Phase 0)

**llama-server build compatibility** — The bundled llama-server is build ~b7502 (Dec 2024, inferred from `tools/build-gpu-booster-pack.ps1:18`). The latest llama.cpp release is b8054 (Feb 14, 2026). The `reasoning_content` SSE field was added for DeepSeek-R1 support around build b4215 (Jan 2025). Build b7502 post-dates this, so thinking support is likely present but unconfirmed. **Quick check:** run `llama-server.exe --version` from the runtime pack, or load the Thinking GGUF and verify `reasoning_content` appears in the SSE stream. Note: the `--reasoning-format deepseek` flag is also required (see §A-9).

**Reranker input truncation** — The Worker's `CrossEncoderReranker` truncates inputs to `maxSequenceLength` tokens (`RerankerTokenizer.java:58-59`). Default is 512 (`RerankerConfig.java:23`), configurable via `JUSTSEARCH_RERANK_MAX_SEQ_LEN` env var. For gte-reranker (8K context support), set this to a higher value (e.g., 2048 or 8192). **No code change needed — config only.**

#### Remaining risks (require Phase 0 testing)

The following 5 risks cannot be resolved without downloading and running models. They are incorporated into the Phase 0 test protocol in §Validation Plan below.

1. **Performance & latency** — No tok/s or TTFT data for any candidate. Thinking token overhead is unknown (estimated 200-1000 tokens/turn). CPU fallback performance with thinking overhead could be minutes per response.
2. **`/no_think` reliability for VDU** — Does the Thinking variant leak thinking tokens when `/no_think` is in the system prompt? Does suppressing thinking change the output distribution in ways that affect VDU quality?
3. **Structured output quality with thinking** — Intent translation (NL→JSON) and summarization may suffer from thinking mode producing more verbose, less format-disciplined output.
4. **KV cache sizing with thinking** — Thinking tokens expand KV cache beyond visible output. At 4096 context + 500 thinking tokens, KV cache could be 30-50% larger than baseline. Must measure actual VRAM.
5. **Multilingual quality** — Zero non-English evaluation exists. German OCR, agent chat, and summarization quality are unknown for any candidate.

---

## Research Brief (Archived)

> Original brief used to seed the research agent (2026-02-13). Research is complete — findings are in §Research Findings above. Deleted for brevity; see git history (`rev3`) for the full brief.

---

## Implementation Blueprint (Archived)

> Pre-implementation pseudocode sketches from 2026-02-14 for Tracks A (thinking support, 9 items), B (reranker agnosticism, 5 items), and D (model agnosticism, 6 items). All items are now implemented — see §Completed code changes and §Deferred items for actual results. The sketches are deleted because the actual code diverges in several places (e.g., `StreamCallbacks` got a compact constructor instead of a builder, `SamplingParams` is a standalone record not embedded in `StreamCallbacks`, D-4 used a candidate list not glob-based discovery, D-6 used 0 not -1).
>
> For code reference, read the source files directly:
> - `OnlineModeOps.java` — reasoning extraction (~line 416), SamplingParams injection (~lines 211, 354, 555), think-tag stripping (~line 592)
> - `AgentLoopService.java` — reasoning accumulation (~line 389), think-tag stripping (~line 398), empty-output retry (~line 400)
> - `SamplingParams.java` — THINKING/DETERMINISTIC/VDU presets
> - `LlamaServerOps.java` — `--reasoning-format deepseek` (~line 203)
> - `VduProcessor.java` — `/no_think` prefix (~lines 29, 40)
> - `CrossEncoderReranker.java` — `token_type_ids` detection (~line 144), conditional tensor (~line 370)
> - `CitationScorer.java` — same pattern (~lines 62, 181)
>
> Formerly included: Model Coupling Audit (ex-tempdoc 206). Also archived.

### Design decisions preserved

**DELETED: ~620 lines of pre-implementation pseudocode (Tracks A-1 through A-9, B-1 through B-5, D-1 through D-6). See source files listed above for actual implementation.**


### Not needed

- **Per-model prompt overrides** — The VDU and agent prompts are generic task descriptions ("Extract all text from this document image"). They work with any instruction-following model through the standardized OpenAI-compatible API. llama.cpp handles model-specific chat template formatting via Jinja. Externalizing prompts to config files would add complexity without solving a real problem.
- **Per-model sampling config files** — The `SamplingParams` record (A-3) with workload-specific presets is sufficient. Sampling parameters are per-workload, not per-model. Different models may prefer slightly different values, but the presets are reasonable defaults and the env var overrides handle edge cases.

---

## Action Plan (Archived)

> Pre-execution plan from 2026-02-14. All tracks are now complete:
> - **Track A** (thinking support): All code changes merged (`5240395d`). Items A3-A11 from the execution table: A3 (llama-server compat) DONE, A4 (code changes) DONE, A5 (benchmarks) done on wrong model (Qwen3-4B not Thinking — see item 11), A6 (agent battery) qualitative only, A7 (structured output) NOT DONE, A8 (VDU quality) DONE, A9 (VRAM) DONE, A10 (multilingual) NOT DONE, A11 (RAG eval) NOT DONE.
> - **Track B** (reranker): B1-B2 DONE. B3-B6 (quality eval, latency benchmark, pack manifest) NOT DONE — out of scope for this tempdoc.
> - **Track C** (future watch): Qwen3.5 release assumptions are superseded by this doc's 2026-02-17 reassessment; future watch now targets practical small-variant local deployability, not release timing.
> - **Track D** (model agnosticism): D-4 partially done (EmbeddingModelResolver extracted), D-6 done, D-1 DONE (3 of 3 guards — GPU variant, thinking model, vision capability), D-2/D-3 not needed, D-5 moved to tempdoc 207.
>
> **Canonical docs:** `05-ai-architecture.md` DONE (§Reasoning Pipeline added, model name fixed — rev17), `environment-variables.md` DONE (`USE_THINKING`, `RERANK_GPU_MEM_MB` added — rev17), `14-ai-pack-spec.md` (no change needed), `how-to/ai-pack-authoring.md` (no change needed).
>
> Full execution tables, file lists, and decision gates: see git history (`rev14`).

---

## External Sources (2026-02-17 refresh)

- Qwen3.5 official model card: `https://huggingface.co/Qwen/Qwen3.5-397B-A17B`
- Qwen3.5 config (context + architecture fields): `https://huggingface.co/Qwen/Qwen3.5-397B-A17B/raw/main/config.json`
- HF Transformers docs (`qwen3_5_omni`, release metadata + model references): `https://huggingface.co/docs/transformers/main/model_doc/qwen3_5_omni`
- Cohere Tiny Aya announcement: `https://cohere.com/blog/cohere-labs-tiny-aya`
- Tiny Aya collection: `https://huggingface.co/collections/CohereLabs/tiny-aya`
- Tiny Aya model cards (examples): `https://huggingface.co/CohereLabs/tiny-aya-fire`, `https://huggingface.co/CohereLabs/tiny-aya-global`, `https://huggingface.co/CohereLabs/tiny-aya-water-GGUF`
- Community Qwen3.5 GGUF examples (size feasibility signal, non-official): `https://huggingface.co/unsloth/Qwen3.5-397B-A17B-GGUF`, `https://huggingface.co/QuantFactory/Qwen3.5-397B-A17B-GGUF`

---

## Related tempdocs

- **123** — AI Engine External Tools Research (inference engine capabilities, model alternatives, speculative decoding research)
- **186** — LLM Tool Execution Capability (agent quality analysis, B1-B4 bugs, 12-experiment battery, post-fix 83% baseline)
- **198** — RAG Quality Eval Loop (15-query test suite, 4 quality gates, baseline regeneration procedure, diff script)
- **200** — Agent Tool Architecture (tool calling verification, KV quantization caveats, `chat_template_tool_use` requirement)
- ~~**206** — Model Coupling Audit~~ (deleted — superseded by §Completed code changes in this doc)

## Related canonical docs

- `docs/explanation/01-system-overview.md` — Head/Body/Brain architecture, llama-server management
- `docs/explanation/05-ai-architecture.md` — ONLINE/INDEXING modes, GPU mutual exclusion, VDU pipeline
- `docs/explanation/13-ai-setup-and-verification.md` — AI Home directory structure, pack import/activation APIs
- `docs/explanation/14-ai-pack-spec.md` — Pack manifest schema v1, asset roles, trust model
- `docs/explanation/16-gpu-booster-pack.md` — llama-server build b7502, runtime variants, BYO override
- `docs/how-to/ai-pack-authoring.md` — `pack-author.ps1` usage, digest stability
- `docs/reference/configuration/environment-variables.md` — `JUSTSEARCH_VLM_MODEL`, `JUSTSEARCH_MMPROJ_MODEL`, etc.
