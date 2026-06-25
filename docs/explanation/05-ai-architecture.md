---
title: AI Architecture
type: explanation
status: stable
description: "Hybrid Inference (CPU/GPU) and VRAM management."
---

# 05. AI Architecture (The "Brain")

JustSearch implements a **Hybrid Inference Architecture** to provide advanced AI features (RAG, Vision, Summarization) on consumer hardware with limited VRAM (e.g., 8GB).

## The Problem: VRAM Contention
Modern local AI requires two distinct types of models:
1.  **Embedding Model:** ONNX Runtime encoder assets selected from the model manifest. High-throughput, used for vector search and chunk embeddings in the Worker process.
2.  **Generative LLM:** (e.g., `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, the current packaged default). Latency-sensitive, used for Chat, Q&A, Summarization, and VDU (served by `llama-server.exe`). Models that emit `reasoning_content` support chain-of-thought reasoning (see §Reasoning Pipeline below).

On an 8GB GPU, loading both simultaneously (or leaving both GPU-enabled) can cause OOM (Out Of Memory) errors or fallback to ultra-slow system RAM.

## The Solution: Mutual Exclusion

JustSearch enforces a strict **Single-tenant GPU Policy** across processes:
* The **Main Process** owns Online inference (`llama-server.exe`) via `modules/app-inference` and `InferenceLifecycleManager`.
* The **Worker Process** owns indexing + Worker-side ONNX Runtime encoders, and cooperates via the MMF `main_gpu_active` flag (offset `24`, `MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE`).

### Modes

| Mode | Active Model | Purpose | Process |
| :--- | :--- | :--- | :--- |
| **Indexing Mode** | Embedding Model | Vectorizing documents in background | Worker |
| **Online Mode** | Generative LLM | Interactive Chat, Q&A, Summarization, Vision | Main (llama-server) |
| **Offline Mode** | none | No GPU work; background queues can accumulate | Main + Worker |

### Transition Protocol

When the user opens the "Chat" tab:
1.  **Main:** Begins a mode transition via `ModeStateMachine` (validates not already transitioning, stores previous mode for rollback).
2.  **Main:** Signals Worker via MMF (`main_gpu_active = 1`).
3.  **Worker:** Unloads/suspends GPU-backed ORT encoder work as needed and skips embedding work while the flag is set.
4.  **Main:** Starts `llama-server.exe` (or **adopts** an already-running instance on the configured port).
5.  **Main:** Polls `GET /health` until 200 OK (timeout configurable via `justsearch.inference.health_check_timeout_ms` system property, default 30000ms; progress logged every 10s during wait — tempdoc 369), then reads `GET /props` (best-effort) to learn the effective `n_ctx` and `model_alias`.
6.  **Main:** Completes the transition to ONLINE via `ModeStateMachine`. On failure at any step, rolls back to the previous mode.

When the user closes Chat or minimizes the app:
1.  **Main:** Kills `llama-server.exe`.
2.  **Main:** Signals Worker (`OFFSET_GPU_ACTIVE = 0`).
3.  **Worker:** Reloads Worker-side ORT encoders as needed and resumes backfill.

## Components

### 1. `llama-server` (The Engine)
We use the compiled binary from `llama.cpp` as a separate process (`llama-server.exe`) for maximum performance and isolation.

**v1 note (current shipping posture):** v1 Simple Mode bundles a **CPU-only** `llama-server` runtime by default (pinned upstream build).
GPU-accelerated runtimes (NVIDIA CUDA) are **deferred to v3 hardware-awareness** and are expected to be distributed via an offline **GPU Booster Pack** (runtime variant) rather than downloaded as arbitrary executables.
The control plane and flags (e.g., `-ngl`) exist today, but GPU acceleration only applies when a GPU-capable runtime is used.
*   **Protocol:** OpenAI-compatible API (`/v1/chat/completions`).
*   **Diagnostics:** `GET /health` and `GET /props` (includes `n_ctx` + `model_alias`).
*   **Binary discovery:** `InferenceConfig.findServerExecutable()` searches canonical paths and `variants/` subdirectories. When GPU is configured (`gpuLayers > 0`), prefers `variants/cuda12/` for CUDA-optimized binary. Falls back to baseline binary. **Dev-layout path** (active only when `justsearch.repo.root` system property is set): searches `{repoRoot}/modules/shell/src-tauri/resources/headless/` (Tauri resource bundle). Added in tempdoc 369 for eval backend LLM support. (The former `{repoRoot}/third_party/llama.cpp/build/` local source-build path was removed with the vendored llama.cpp tree — tempdoc 632; the runtime is the pinned upstream prebuilt download.)
*   **Crash diagnostics:** `waitForServerHealth()` parses llama-server stderr for known failure patterns (e.g., `unknown model architecture`) and surfaces user-facing error messages instead of opaque "failed to load model" errors.
*   **Arguments:**
    *   `-m <model_path>`: Main GGUF model file.
    *   `-c <ctx_size>`: Context window (critical for RAG).
    *   `-ngl <layers>`: Number of GPU layers (offload).
    *   `--mmproj`: Vision adapter path (for Qwen/Llava).
    *   `--port <port>`: HTTP port.
*   **VDU mode flags** (applied only during VDU batch processing, not global):
    *   `-np 1`: Single slot (multi-slot causes alternating 500s on vision)
    *   `--cache-ram 0`: Disable prompt cache (prevents silent crashes after ~7 pages)
    *   `chat_template_kwargs: {"enable_thinking": false}`: Ensures VLM output goes to `content` field

### 2. `InferenceLifecycleManager` (The Manager)

Delegates to package-private collaborators: **`LlamaServerOps`** (process spawn/kill, health checks, zombie protection), **`OnlineModeOps`** (chat/vision completion requests, streaming, lock acquisition), **`TokenEndpointOps`** (tokenize/apply-template probing with caching), **`ServerPropsOps`** (/props parsing, model ID extraction), and **`ModeStateMachine`** (validated mode transitions).

*   **Responsibilities:**
    *   Spawning/Killing `llama-server`.
    *   **Zombie Protection:** Uses `taskkill /F /PID` on Windows to ensure VRAM is released.
    *   **Health Checks:** Waits for `/health` during startup; runs periodic health checks for hung detection.
    *   **External Instance Adoption:** If the configured port is already serving a healthy `llama-server`, it can adopt it instead of starting a duplicate process (prevents restart loops after a forced kill).
        * By default, adoption is verified via `GET /props` (not just `GET /health`) to avoid accidentally adopting an unrelated HTTP service.
        * Dev escape hatch: `-Djustsearch.inference.external.allow_health_only_adoption=true` (allows health-only adoption when `/props` is missing/unparseable).
        * Adopted servers are still monitored; if the external server becomes unhealthy mid-session, inference transitions to Offline (no process handle to restart).
    *   **Crash Recovery:** If the owned server crashes while in Online mode, it stops and restarts it (with cleanup first). Health checks and crash recovery run on independent schedulers (`healthScheduler`, `recoveryScheduler`), preventing a slow health probe from blocking recovery.
    *   **Mode State Machine:** `ModeStateMachine` validates all mode transitions (`beginTransition` → `complete`/`rollback`, `forceOffline` for emergencies). No raw state assignments — all transitions go through validated operations with precondition checks.
    *   **Effective Runtime Info:** Reads `/props` to capture best-effort runtime `n_ctx` and `model_alias`, which is surfaced via `/api/inference/status` and used as the request `model` id.
    *   **Hot-apply (current):** The Local API exposes `POST /api/inference/reload`, which re-reads persisted `/api/settings/v2` values and calls `OnlineAiRuntimeControl.applyRuntimeOverrides(...)` with `RESTART_IF_ONLINE`.
        * This updates model/context/gpuLayers without a full backend restart.
        * It **must not** auto-start `llama-server` when the system is Offline; it only restarts when already Online.
        * If Online AI adopted an external `llama-server` instance (no process handle), restart is rejected; use `POST /api/inference/detach` to switch to a managed server on a new port.
    *   **Server-Model Compatibility Warnings:** Two runtime warnings help detect common misconfiguration:
        1. **GPU Variant Mismatch** (`LlamaServerOps.startLlamaServer()`): Warns when `gpuLayers > 0` but server executable not under `variants/` subdirectory (indicating CPU variant). Does not block startup.
        2. **Thinking Model Mismatch** (`ServerPropsOps.warnIfThinkingMismatch()`): Warns when `USE_THINKING=true` but loaded model name lacks "Thinking" substring. Does not block startup.

        These are non-fatal warnings logged at WARN level to aid troubleshooting. System continues with potentially degraded behavior.

### 3. Embedding Backend (Worker, ONNX Runtime)
*   **Class:** `io.justsearch.indexerworker.embed.EmbeddingService`
*   **Backend:** ONNX Runtime; sessions built via the Worker composition root (`InferenceCompositionRoot.compose(...)`) and applied by `OrtSessionAssembler` in `modules/ort-common` — see the composition subsection below and register entry D-007.
*   **Default:** CPU-only (GPU offload is opt-in via `JUSTSEARCH_EMBED_GPU_ENABLED`).
*   **GPU Coordination:** `IndexingLoop` unloads/reloads the embedding backend based on `WorkerSignalBus.isMainGpuActive()`.
*   **Model File Selection:** `ModelManifest.loadOrDefault()` reads `model_manifest.json` from the model directory to determine which `.onnx` file to use for CPU vs GPU. External directories without a manifest fall back to convention (`model.onnx` CPU, `model_fp16.onnx` GPU).

### ONNX Runtime Infrastructure (`ort-common`)

All ORT consumers (embedding, SPLADE, NER, BGE-M3, cross-encoder reranker, citation scorer) share a single session-construction pipeline in `modules/ort-common` (`io.justsearch.ort`). Tempdoc 397 collapsed six divergent construction paths onto the typed pipeline below; see [24-worker-inference-composition.md](24-worker-inference-composition.md) for the full explainer and register entry D-007 in `docs/reference/inference-runtime-register.md` for the decision rationale.

**Construction pipeline** (single production path, no customiser lambdas, no SPI discovery):

1. `RuntimePolicyResolver.resolve(cfg, hardware)` → `RuntimePolicy` — JVM-wide session settings (arena, CUDA provider, session, profiling).
2. `ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant)` → `ModelSessionPolicy` — per-encoder GPU / CPU / lifecycle / RunOptions.
3. `InferenceCompositionRoot.compose(cfg, hardware, contract, modelsDir, arbiter)` → `InferenceSurface`. Resolves each encoder's `VariantSelection` (via `VariantSelector`, or `DevModeVariantProbe` when the install contract is absent), calls `OrtSessionAssembler.buildManager(Composition, arbiter)`, wraps sessions as `SessionHandle`, constructs encoders with pre-built `<Role>Assembly` (shape + tokenizer + vocabulary / label-mapping), returns the typed surface.
4. Encoders consume `SessionHandle` only — they do zero filesystem I/O in constructors. `ClosurePropertyTest` (ArchUnit) enforces this.

**Key classes:**

| Class | Purpose |
|-------|---------|
| `InferenceCompositionRoot.compose(...)` | Single production entry point; returns `InferenceSurface` |
| `InferenceSurface` | Typed bundle of ready-to-use encoders + `PolicySnapshot` + `List<SessionHandle>` for lifecycle management |
| `OrtSessionAssembler` | The only caller of ORT setters in production. Entries: `buildManager(Composition, GpuArbiter)`, `verifyModelSession(...)` (Gradle verify-model task), `probeModelNames(...)` |
| `SessionOptionsApplier` | Walks `RuntimePolicy` + `ModelSessionPolicy` fields → ORT setters. Every option value flows from a policy field (§6 closure property) |
| `RuntimePolicy`, `ModelSessionPolicy` | Typed policy records consumed by the applier |
| `SessionHandle` | Interface encoders consume: `acquire()`, `acquireCpu()`, `status()`, `releaseGpu()`, `reportCpuSessionFailure()`, `setLifecycleCallback(...)` |
| `NativeSessionHandle` | Concrete `SessionHandle` impl. Package-private `Builder`; external callers reach it only through the assembler |
| `GpuArbiter` | Typed replacement for `BooleanSupplier shouldUseGpu` |
| `ModelManifest` | Reads `model_manifest.json` for CPU/GPU model file selection (moved from worker-core, 359) |
| `GpuSessionConfig` | Record: `(gpuDeviceId, gpuMemLimitBytes)` |
| `OrtCudaHelper` | Windows DLL preloading, native path resolution, DLL presence checks |
| `OrtCudaStatus` | Structured CUDA observability record (`ready()`, `missingDlls()`, `providerFailed()`, `released()`) |
| `OnnxSessionCache` | Session creation with per-machine graph-optimisation caching (uses `BASIC_OPT` for FP16 models, `EXTENDED_OPT` for others) |

**Diagnostics:** `GET /api/debug/session-policies` returns the resolved `RuntimePolicy` and every `ModelSessionPolicy` as JSON, proxied from the Worker's live `InferenceSurface` via the `GetSessionPolicies` gRPC rpc (§14.28 U4). Diffing two runs is diffing two records; no log archaeology.

**Encoder runtime state:** `GET /api/inference/encoders` (tempdoc 422) returns a derived per-encoder explainer that correlates the policy snapshot with the runtime `OrtCudaView` probe to answer "why is encoder X currently on CPU/GPU/unavailable?" with one structured response. Keys are `EncoderRole.consumerName()` (`embed`, `bgem3`, `splade`, `ner`, `reranker`, `citation`) so operators can correlate the response with `ort.session.*` metric lines in `metrics-worker.ndjson`. Read-only and user/agent-facing (not under `/api/debug/`) by design — the underlying `/api/debug/session-policies` is dev-namespaced and exposes the raw policy snapshot.

Production session option values are driven by `RuntimePolicy` + `ModelSessionPolicy` — `SessionOptionsApplier` is the single setter site:
- `arena_extend_strategy = kSameAsRequested` (exact allocation; two sessions share GPU)
- `enable_cuda_graph = 0` (allows arena shrinkage between calls)
- `use_device_allocator_for_initializers = 1` (weights bypass arena)
- `setMemoryPatternOptimization(false)` (variable-length sequences)
- `setInterOpNumThreads(1)`, `allow_spinning = 0` (reduce CPU contention)

**FP16 CPU optimization caveat:** `OnnxSessionCache.optimizeAndCache()` uses `BASIC_OPT` (instead of `EXTENDED_OPT`) for FP16 models on CPU. This reduces first-run graph optimization from 30-60+ minutes to ~5-10 minutes. FP16 embedding on CPU is still broken/unsupported — ORT CPU EP has no native FP16 support and inserts Cast (FP16->FP32) nodes before every operation, causing severe runtime overhead. The correct solution is to ship FP32 model variants for CPU (SPLADE already does this correctly; embedding does not yet — see model-inventory.md `gte-multilingual-base` entry). See [ADR-0019](../decisions/0019-cpu-gpu-model-selection-strategy.md) for the full CPU/GPU model selection decision.

Model file verification: `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true`

### 4. Reranker GPU Coordination (Worker-side, default enabled)

The cross-encoder reranker runs in the **Worker process** (360), sharing
GPU arbitration with embedding, SPLADE, and NER via the signal bus.
GPU is enabled by default (`JUSTSEARCH_RERANK_GPU_ENABLED=true`).

GPU arbitration:
- **Startup initialization**: GPU session is created in `initDeferredModels()` with a warm-up inference to compile the ORT execution plan.
- **Signal bus arbitration**: `selectSession()` checks `!signalBus.isMainGpuActive()` — same as all other Worker ORT consumers.
- **VRAM release**: `releaseGpuSession()` frees the GPU session when Main process claims GPU (e.g., `llama-server` going online).
- **Fallback**: Reranking continues on CPU session while GPU is released or unavailable.
- **Head-side invocation**: The Head calls the Worker's `Rerank` gRPC RPC, sending pre-built document texts (title + snippet). The Head has no ORT sessions.

Defaults: `gpu_mem_mb=2048`, `max_seq_len=512`. At seq=512, GPU inference
for 20 docs takes ~2.2s (vs ~42s on CPU at seq=2048).

Observability: `OrtCudaStatus` record tracks GPU state, visible in `/api/status` under `rerankerOrtCuda`.

## Reasoning Pipeline

JustSearch supports **chain-of-thought reasoning** via the configured chat model — any model that emits `reasoning_content` in OpenAI-compatible SSE streams. This is gated by the `JUSTSEARCH_USE_THINKING` environment variable (default: `true`).

### Activation

When `USE_THINKING=true`, `LlamaServerOps` adds `--reasoning-format deepseek` to the `llama-server` command line. This tells `llama-server` to emit reasoning tokens as a separate `reasoning_content` field in SSE deltas, instead of inline `<think>` tags in the `content` field.

### Streaming Architecture

`OnlineModeOps` parses SSE deltas from `/v1/chat/completions` and routes content to `StreamCallbacks` — a record with 6 callbacks defined in `OnlineAiService`:

| Callback | Purpose |
|----------|---------|
| `onChunk` | Response text content |
| `onReasoningChunk` | Chain-of-thought reasoning (separate from content) |
| `onToolCallDelta` | Tool call JSON deltas (agent loop) |
| `onUsage` | Token usage metadata |
| `onComplete` | Stream finished |
| `onError` | Stream error |

`AgentLlmCaller` (the agent loop's LLM-caller collaborator) accumulates reasoning chunks into a `StringBuilder` and logs the full reasoning at DEBUG level after each agent turn. Reasoning is not exposed in the UI response.

### Sampling Parameters

`SamplingParams` (`modules/app-api`) defines per-workload presets injected into HTTP request bodies at 3 injection points in `OnlineModeOps` (`streamChatWithTools`, `streamChat`, `sendChatRequest`):

| Preset | Temperature | Top-P | Used by |
|--------|-------------|-------|---------|
| `THINKING` | 0.6 | 0.95 | Reserved preset for explicit reasoning-heavy calls (not currently wired in production paths) |
| `AGENT` | 0.2 | 0.9 | Agent chat (`AgentLoopService`) |
| `DETERMINISTIC` | 0.1 | 0.9 | Summarization, Q&A |
| `VDU` | 0.0 | 0.9 | Vision document understanding (deterministic OCR output) |

When `SamplingParams` is null, no sampling parameters are sent (server defaults apply).

### Think-Tag Handling

Despite `--reasoning-format deepseek`, `<think>` tags can leak into content in edge cases (llama.cpp bug [#13189](https://github.com/ggml-org/llama.cpp/issues/13189), non-streaming responses, or chunk boundaries in streaming). Two defenses:

1. **`OnlineModeOps.sendChatRequest()`** — Strips `<think>...</think>` via regex from non-streaming responses before returning.
2. **`AgentLlmCaller.callLlmWithTools()`** — Strips `<think>` tags from accumulated streaming responses before adding to conversation history.

### VDU Reasoning Suppression

`VduProcessor` prepends `/no_think` to both VDU prompt constants (Pass 1: OCR extraction, Pass 2: metadata enrichment). This is a Qwen3 soft switch — the model was trained to recognize `/no_think` in system or user messages and suppress extended reasoning.

Suppression avoids the "long-wrong trajectory" problem where thinking chains degrade perception quality on pure OCR tasks, and reduces latency and token consumption.

**Known limitation:** `/no_think` in a user message mid-conversation does NOT suppress reasoning (Qwen3 Jinja template checks the system prompt only). The VDU pipeline always sends it as the first message, so this limitation does not apply.

### Empty Output Recovery

`AgentLlmCaller.callLlmWithRetries` retries on empty content (no text and no tool calls) with bounded backoff per `AgentRetryPolicy` (the `EMPTY_RESPONSE` decision). This handles transient empty responses — e.g. when reasoning tokens consume the context window, leaving no budget for the answer. (There is no `/no_think` injection on this path.)

### Reasoning Budget

`llama-server` accepts `--reasoning-budget N` to control reasoning token generation at the template level. Default: **0** (disabled via `JUSTSEARCH_REASONING_BUDGET` env var / `-Djustsearch.llm.reasoning_budget`).

With `--reasoning-budget 0`, the server injects a `/no_think` equivalent at the chat template level, which is more reliable than prompt-level suppression alone. The system prompt's `/no_think` directive is kept as defense-in-depth.

**Why reasoning is disabled by default for agent workloads**: A thinking-capable chat model can generate 2000-2500 reasoning tokens for complex queries (measured on the prior `Qwen3VL-8B-Thinking` default). With a shared `max_tokens` budget (OpenAI-style, no separate reasoning budget), this exhausts the generation budget before producing any content — causing empty responses (the "B6" failure mode). Setting `--reasoning-budget 0` eliminates this class of failure entirely (0/12 empty responses vs 4/12 without it).

**Re-enabling reasoning**: Set `JUSTSEARCH_REASONING_BUDGET=-1` for unlimited reasoning. Use prompt-level `/think`/`/no_think` for per-step control within the same session. The most recent directive wins in multi-turn Qwen3 conversations.

### Operational Findings (Tested)

These findings were validated through controlled experiments (12-16 scenario batteries, A/B testing) and are reflected in the current defaults:

1. **System prompt phrasing matters dramatically for Thinking models.** Imperative phrasing ("Call browse_folders first") causes the model to follow it literally on every query. Conditional phrasing ("Use browse_folders when you need to discover folder structure") allows contextual tool selection. This single change was the most impactful improvement in agent quality.

2. **`/no_think` works in system prompts but is ineffective mid-conversation.** The Qwen3 Jinja template checks the system prompt for `/think`/`/no_think` directives. A `/no_think` user message mid-conversation does not suppress reasoning — the model continues generating reasoning tokens (verified: 8605 chars vs 8738 chars initial). The reliable suppression mechanism is `--reasoning-budget 0` at the server level.

3. **Budget-aware prompts are counterproductive for 8B models.** Injecting remaining token budget ("Budget: X/8192 tokens used") into the system prompt caused a V4 regression (66.7% → 58.3%). The suffix "Be concise. Avoid redundant tool calls." made the agent too conservative — it stopped exploring after a single negative result instead of following up. 8B models follow concrete instructions ("use absolute paths") better than abstract meta-cognitive ones ("be concise").

4. **AGENT sampling preset (temp=0.2) is within consensus range but not A/B validated.** Industry consensus is temp 0.0-0.5 for tool-calling agents. The current temp=0.2 has not been tested against temp=0.0 or temp=0.1 for this model.

### Rollback

Set `JUSTSEARCH_USE_THINKING=false` to disable reasoning stream formatting. This omits `--reasoning-format deepseek` from the server command line. Sampling parameters are still sent when callers explicitly provide a preset. Think-tag stripping still runs as a safety net. Server restart is needed for the `--reasoning-format` flag change.

## Vision Capability Detection

JustSearch detects vision capabilities at two levels to ensure VDU features are only enabled when the runtime supports them:

### Config-Level Detection

`InferenceLifecycleManager.hasVisionCapability()` performs static capability detection based on configuration:

- **Check**: `mmprojPath != null` (vision projector model is configured)
- **When**: Configuration load time
- **Purpose**: Prevents startup when VDU is required but unconfigured

### Runtime Detection

`ServerPropsOps.extractServerProps()` performs dynamic capability detection from the running `llama-server`:

1. Fetches `GET /props` after server health check passes
2. Extracts `modalities.vision` boolean from response JSON
3. Caches result in `ServerProps` record
4. Exposes via `/api/inference/status` `hasVisionCapability` field

### VDU Processing Guard

`VduProcessor.process()` enforces runtime guard before processing vision documents:

- **Precondition**: `inferenceLifecycleManager.hasVisionCapability()` must return `true`
- **Failure mode**: Returns `VduResult.skipped()` with reason "Vision capability not available"
- **Telemetry**: `vdu.outcome_total{outcome=skipped}` counter

This dual-layer detection ensures:
1. Early failure detection (config level) before server startup
2. Runtime verification (actual server capabilities) for external server adoption
3. Graceful degradation when vision features unavailable

## RAG Summarization Architecture

To handle documents of any size, JustSearch implements a two-path summarization strategy. The entry point is `SummaryController`, which delegates to decomposed collaborators: `FullCoverageSummarizer` (paged content loading + orchestration), `MapReducePipeline` (hierarchical map/reduce), `ContentLoadingOps` (gRPC document fetching), and `SectionProcessingOps` (section splitting + token estimation):

### 1. Full Coverage (default for UI workflows)
*   **Goal:** summarize the *entire* extracted content (not just top-k chunks).
*   **Approach:** load content in pages (guard-railed), then either:
    * stream a direct summary for small inputs, or
    * run a hierarchical map/reduce when content would exceed the context window.

### 2. Quick Summary (RAG representative chunks)
*   **Goal:** fast “good enough” summary when full coverage is disabled (or as a fallback).
*   **Approach:** use Knowledge Server retrieval to pull representative chunks (top-k), then summarize those.

### Retrieval modes + degradation (current)

RAG retrieval (`SearchService.retrieveContext`) returns explicit metadata so clients can distinguish "semantic", "keyword-only", and fallback behavior:
- `retrieval_mode`: `BM25` | `HYBRID` | `CHUNK_HYBRID` | `FULLTEXT_FALLBACK`
- `retrieval_mode_reason`: allowlisted reason code explaining why a mode was chosen (or blocked); see `docs/reference/contracts/search-and-rag-reason-codes.md`
- `context_truncated`: true when the Worker hit the retrieval budget

Chunk-level hybrid (`CHUNK_HYBRID`) uses the `chunk_vector` field and is coverage-gated: it is only used when chunk vectors are sufficiently backfilled (>= 95%). Readiness is surfaced via `/api/status` (`chunkVectorCoveragePercent`, `chunkVectorsReady`, etc.). A kill switch exists via `rag.chunk_vectors.enabled` (default true).

Optional quality boost (disabled by default): a cross-encoder chunk reranker can rerank BM25 chunk hits under a tight time budget. GPU acceleration requires an ONNX Runtime CUDA-capable native runtime (see `docs/explanation/16-gpu-booster-pack.md`).

### Token budgets (current)
`SummaryController` uses the configured `maxTokens` (persisted via `/api/settings/v2`) as the **output** budget for summarize/Q&A/chat. It also computes a safe **input** budget from the effective context window (`n_ctx`) to avoid llama-server 400s when input + output would exceed the server limit.

The Head passes this input token budget to the Worker (`RetrieveContextRequest.max_context_tokens`) so the Worker can budget context during retrieval (avoids "Worker fetches 200K chars, Head truncates to 3K tokens" waste). The Head still keeps a safety-net truncation step and filters citations based on the returned `sections[]` to avoid "citations for dropped chunks" after truncation.

## Q&A (multi-file “Ask”)

Q&A uses the Worker's retrieval path (`DocumentService.retrieveContextWithMeta(...)` → gRPC `SearchService.retrieveContext`) to get relevant context, then streams an answer via `OnlineAiService`.

Important correctness/UX detail (current):

- RAG retrieval can legitimately return an **empty** context (no chunks indexed + BM25 finds no matches).
- In that case, `SummaryController.handleAskStream` falls back to `documents().fetchBatch(...)` (full docs) instead of hard-failing with `NO_CONTENT`.

### Context size guardrails (strict char budgeting)
Token-aware budgeting is preferred when available (`max_context_tokens > 0`). Character budgeting remains a fallback safety net.
JustSearch enforces a strict **character cap** on retrieved context strings (default **200,000 chars**) to prevent oversized prompts and “soft cap” drift.

Implementation:

- **Token-aware budgeter:** `TokenAwareBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/TokenAwareBudgeter.java`) is used when the Head provides `max_context_tokens > 0`.
- **Budgeter:** `ContextBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/ContextBudgeter.java`) counts **all** overhead (section headers + separators), not just raw document content.
- **Worker retrieval:** `GrpcSearchService` uses `ContextBudgeter` when building the context returned by `SearchService.retrieveContext` (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java`).
- **Fallback retrieval:** when RAG returns empty/insufficient context, the fallback full-doc path is also budgeted via `ContextBudgeter` (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`).

Regression coverage:

- `modules/indexing/src/test/java/io/justsearch/indexing/rag/ContextBudgeterTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/GrpcSearchServiceRetrieveContextTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceContextBudgetTest.java`

### Stable Intermediate Format: `SECTION_SUMMARY_V1`
To reduce long-run hallucinations in hierarchical runs, all map/reduce steps use a strict intermediate schema:

```text
<SECTION_SUMMARY_V1>
CLAIMS:
- <short claim> (evidence: "<very short quote>")
ENTITIES:
- <entity>
DATES_NUMBERS:
- <date/number> — <context> (evidence: "<very short quote>")
UNKNOWNS:
- <important missing/unclear info, or "none">
</SECTION_SUMMARY_V1>
```

* **Map:** summarize each section into exactly one `SECTION_SUMMARY_V1` block.
* **Reduce:** merge multiple blocks into exactly one smaller `SECTION_SUMMARY_V1` block (dedupe, preserve evidence).
* **Synthesis:** produce the final user-facing summary from the blocks (do not mention the tags).

All hierarchical steps use `OnlineAiService.streamChat(...)` so they share the same streaming primitive and error handling.

## Citation Pipeline

JustSearch uses a **two-pronged citation strategy** (see ADR-0006) to attribute AI-generated answers to source documents:

### Prong 1: LLM-generated citations (primary)

RAG prompts instruct the LLM to place `[N]` markers inline. Source chunks are wrapped in numbered `<passage id="N" source="file">` XML (Q&A) or prefixed with `[N]` (summarization). The `meta` SSE event delivers `ContextCitation[]` with rich metadata:

- `parentDocId`, `chunkIndex`, `chunkTotal` — chunk identity
- `startChar`, `endChar` — character offsets for click-to-jump
- `startLine`, `endLine`, `headingText` — section navigation
- `score` — BM25 retrieval score
- `excerpt` — source chunk text

This path works with any model capable of following citation instructions. No embedding service or ONNX models required.

### Prong 2: Post-hoc cross-encoder matching (supplementary)

After the LLM finishes streaming, `GrpcSearchService.matchCitations()` runs a CPU-only ONNX cross-encoder (`CitationScorer`) to score each answer sentence against source chunks:

1. Answer text is split into sentences via `BreakIterator`
2. Each sentence is scored against all source chunks via `CitationScorer.scoreAll()` (ms-marco-MiniLM-L-6-v2, ~22 MB INT8 ONNX)
3. Scores are sigmoid-normalized to [0,1], filtered by threshold (default 0.5)
4. Results are sent as a `citation_matches` SSE event (after `done`)

The cross-encoder runs on CPU, eliminating the GPU contention that blocked the original embedding-based approach (embedding model and LLM compete for VRAM on single-GPU systems).

Fallback chain in `matchCitations()`:
1. Cross-encoder (CPU, no GPU needed) → preferred
2. Embedding cosine similarity (requires `EmbeddingService`) → blocked during Q&A on single-GPU
3. `EMBEDDING_UNAVAILABLE` → no post-hoc matching

### Frontend rendering

The frontend (`useAppAI.ts`) handles both prongs:

- `onCitationMatches` **enriches** existing RAG citations with cross-encoder scores — preserves excerpts, offsets, and headings from the `meta` event, only updates `score`
- `injectCitationMarkers` strips any LLM-generated `[N]` markers before injecting cross-encoder markers (prevents duplication)
- `CitationHoverCard` displays document name, excerpt preview, score badge (hidden for BM25 scores >1.0), and section metadata
- `MarkdownRenderer` parses `[N]` syntax into clickable citation buttons

### Citation Parsing and Attribution Contract (RAG Eval)

For automated RAG evaluation, citation handling uses a permissive parser with strict attribution rules:

- Accepted marker formats: `[1]`, `[Document1]`, `[Document 1]`, and truncated `[1` (stream cutoff tolerance).
- Attribution rule is strict: a parsed marker only counts as correct when the marker number resolves to the expected source document for that claim/query.
- This keeps format tolerance high while preventing false credit from wrong-source citations.

### Configuration

The citation scorer is opt-in via environment variables:

| Env Variable | Default | Description |
|:---|:---|:---|
| `JUSTSEARCH_CITATION_SCORER_ENABLED` | `false` | Enable cross-encoder citation scoring |
| `JUSTSEARCH_CITATION_SCORER_MODEL_PATH` | — | Path to ONNX model directory (`model.onnx` + `tokenizer.json`) |
| `JUSTSEARCH_CITATION_SCORER_THRESHOLD` | `0.5` | Minimum similarity score for a match |
| `JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN` | `512` | Maximum token sequence length |
| `JUSTSEARCH_CITATION_SCORER_DEADLINE_MS` | `2000` | Time budget for scoring |

### ONNX Model Distribution

| Feature | Model | Size | Notes |
|---------|-------|------|-------|
| Search reranker | `gte-multilingual-reranker-base` | ~340 MB (FP16 GPU) | 306M params, 70+ langs; default `maxSeqLen=512`; 175ms/20 docs on GPU (343, 359, 360) |
| Citation scorer | `ms-marco-MiniLM-L-6-v2` | ~22 MB (INT8) | CPU-only by design; upgraded from L-2 (343) |

**Auto-discovery resolution order** (implemented in `OnnxModelDiscovery` via `ResolvedPathResolver`):
1. Explicit env var override (no validation)
2. `<modelsDir>/onnx/<modelName>/` (validated, auto-enable)
3. `<dataDir>/models/onnx/<modelName>/` (validated, auto-enable)
4. `<repoRoot>/models/onnx/<modelName>/` (validated, auto-enable)
5. Dev fallback (requires `ENABLED=true` env var)

Models are bundled in the installer as flat assets (~40 MB total) with a post-download arrangement step.

### Model Identity & Swap Detection

Silent model swaps (e.g., user replaces a GGUF file between restarts) can cause subtle quality regressions without any signal. Two mechanisms detect this:

1. **ONNX model fingerprinting** — `CitationMatchOps` computes SHA-256 of `model.onnx` on scorer initialization and stores the fingerprint in a volatile field. On re-initialization, a fingerprint mismatch triggers a warning log. This covers the citation scorer and reranker ONNX models.

2. **Chat model swap detection** — `InferenceLifecycleManager` persists the active model ID (learned from `llama-server /props`) to `<dataDir>/inference-model-id.txt`. On startup, if the persisted ID differs from the newly reported model ID, a warning is logged. This covers the generative LLM served by `llama-server`.

Both mechanisms are warn-only (no blocking behavior) since legitimate model upgrades are a normal operation.

## Vision Support (VDU)
Vision Document Understanding (VDU) enriches visual documents beyond baseline text extraction.
Baseline scanned/image-text searchability is Worker-owned: structured Tika runs first, then bounded
Tika/Tesseract OCR can produce `extraction_method=OCR_TIKA` when the text layer is missing or weak.
Successful OCR also records compact `visual_extraction_evidence`, including OCR language, optional
Tesseract TSV confidence summary, fallback route, truncation, and OCR skip/guard reason when relevant.
That evidence can queue VDU enrichment when baseline text exists but OCR/layout signals suggest richer
visual understanding would help.
*   **Flow:**
    1.  Worker extracts with structured Tika. If extracted text is empty/garbage and the file is OCR-eligible, Worker extraction attempts bounded Tika/Tesseract OCR before VDU is considered.
    2.  User goes idle (Offline Mode) and/or triggers offline processing.
    3.  Head/app-services selects pending docs and runs `VduBatchProcessor` → `VduProcessor`.
    4.  `VduProcessor` calls a Vision-capable model via `llama-server` (e.g., the configured chat model + `--mmproj`) with: “Transcribe the text in this image.”
    5.  Worker persists successful non-empty VDU by overwriting `content`, re-deriving `content_preview` and `language`, regenerating chunks, and recording `extraction_method=VDU`.
    6.  Failed or completed-empty VDU preserves the best baseline text. The UI surfaces per-doc `vduStatus` + `textProvenance` in the Inspector Panel so users can see whether the current text came from Tika, OCR, or VDU.

Worker status splits visual demand into `visualTextNeededCount` for missing baseline readable text and
`visualEnrichmentNeededCount` for documents where VDU is useful after baseline text exists. OCR blockers
therefore degrade retrieval only when baseline text is still missing; VDU enrichment-only blockers degrade
AI features instead.

Verification lanes:

- Hermetic eligibility fixtures (no llama-server): `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VduEligibilityPdfFixturesTest.java`
- Tier-2 OCR (requires llama-server): `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/vdu/VduBatchProcessorE2ETest.java` (`processesScannedPdfWithRealLlm`, fixture `modules/system-tests/src/systemTest/resources/fixtures/pdf/scanned-alpha.pdf`)

### VDU Resilience & Observability

*   **Timeout Protection:** `VduProcessor` enforces strict timeouts on LLM operations to prevent single-threaded VDU queue blocking:
    *   Pass 1 (vision completion): 120 seconds
    *   Pass 2 (chat completion): 60 seconds
    *   Timeout telemetry: `vdu.timeout_total` counter increments on timeout
*   **Circuit Breaker:** `VduBatchProcessor` uses a circuit breaker (5 failures, 1 minute recovery) to fast-fail remaining documents when the LLM is repeatedly failing. This prevents hammering a dead inference engine during batch processing.
*   **Latency Metrics:** Timer metrics (`vdu.pass1.duration_ms`, `vdu.pass2.duration_ms`, `vdu.total.duration_ms`) track pipeline performance. See `docs/explanation/08-observability.md` for the full metrics list.
*   **Debug Trace Logging:** Enable TRACE logging for `io.justsearch.app.services.vdu.VduProcessor` to see truncated text samples (first 500 chars of Pass 1, first 300 chars of Pass 2). JVM property: `-Dlogging.level.io.justsearch.app.services.vdu.VduProcessor=TRACE`.
