---
title: Environment Variables & System Properties
type: reference
status: stable
description: "Canonical runtime knobs (ports, paths, AI toggles)."
---

# Environment Variables & System Properties

This document lists configuration keys supported by JustSearch.

Scope:
- **EnvRegistry keys (complete list):** keys read via `modules/configuration` `EnvRegistry`.
- **Additional runtime keys (selected):** high-signal keys used by other runtime components but not yet in `EnvRegistry`.
- **YAML + ownership contract:** see `docs/reference/configuration/runtime-config-ownership-matrix.md` for `ResolvedConfig` YAML keys, env/sysprop fallbacks, owning module, and precedence notes.
- **Perf harness knobs:** env vars consumed by performance/benchmark scripts.

## Precedence Rule

1. **System property** (`-D...`) (highest priority)
2. **Environment variable** (`JUSTSEARCH_...`)
3. **Default value** (implementation fallback)

## EnvRegistry Keys (Complete)

| Env Variable | System Property | Type | Description |
| :--- | :--- | :--- | :--- |
| **Paths & Discovery** | | | |
| `JUSTSEARCH_DATA_DIR` | `justsearch.data.dir` | Path | Root directory for logs, index, and durable state (e.g., `jobs.db`). |
| `JUSTSEARCH_HOME` | `justsearch.home` | Path | JustSearch “home” directory (desktop: AI Home root used for models/packs/runtime restore state). |
| `JUSTSEARCH_SSOT_PATH` | `justsearch.ssot.path` | Path | Overrides auto-discovery of the `SSOT/` directory. |
| `JUSTSEARCH_FIELD_CATALOG` | `justsearch.fieldCatalog` | Path | Explicit field catalog JSON path (overrides SSOT lookup). |
| `JUSTSEARCH_CONFIG` | `justsearch.config` | Path | Explicit application config YAML path. |
| `JUSTSEARCH_REPO_ROOT` | `justsearch.repo.root` | Path | Explicit repo root (useful in split-repo layouts). |
| `JUSTSEARCH_STAGE_PLUGIN_MANIFEST` | `justsearch.plugins.manifest` | Path | Explicit stage plugin manifest path. |
| `JUSTSEARCH_MCP_HOST_CONFIG` | `justsearch.mcp.host.config` | Path | Path to the MCP-host server list JSON (tempdoc 560 §6). Each entry `{id, command:[...], env:{...}}` is connected at startup; its tools become consent-gated EXECUTABLE operations callable by the local LLM. Unset ⇒ MCP-host disabled. |
| `JUSTSEARCH_TESSERACT_PATH` | `justsearch.tesseract.path` | Path | Explicit Tesseract executable or runtime directory for Worker Tika OCR. Overrides app-owned runtime discovery. |
| `JUSTSEARCH_TESSDATA_PATH` | `justsearch.tessdata.path` | Path | Explicit tessdata directory for Worker Tika OCR. Overrides packaged/runtime-adjacent tessdata discovery. |
| **API & Runtime** | | | |
| `JUSTSEARCH_API_PORT` | `justsearch.api.port` | Int | Port for the local loopback API. `0` means “ephemeral”. |
| `JUSTSEARCH_PROD` | `justsearch.prod` | Bool | Enables desktop/prod posture (e.g., session token enforcement for non-GET endpoints). |
| `JUSTSEARCH_EGRESS_BLOCK_ALL` | `egress.block_all` | Bool | Blocks outgoing network calls (tests/air-gapped posture). |
| **Telemetry** | | | |
| `JUSTSEARCH_TELEMETRY_FLUSH_MS` | `justsearch.telemetry.flushMs` | Long | Telemetry flush interval in milliseconds (NDJSON exporter). |
| **Indexing & Search** | | | |
| `JUSTSEARCH_INDEX_COLLECTION` | `justsearch.index.collection` | String | Index “collection” name (legacy escape hatch; prefer YAML). |
| `JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH` | `justsearch.index.parity.allow_mismatch` | Bool | Dev escape hatch to allow opening read-only on schema parity mismatch. |
| `JUSTSEARCH_INDEX_TRACING_LEVEL` | `justsearch.index.tracing_level` | String | Indexing pipeline OTel tracing level: `none` (default, no spans), `sample` (1% ratio sampling), `detailed` (100% — all batches/docs). Requires Worker restart. **Cost guidance:** `none` has validated sub-10µs overhead (tempdoc 312 item 7). `detailed` produced ~7,400 spans on a 15-query scifact eval (tempdoc 400 §23.8); production use should stay on `none` and only enable `detailed` for the nightly observability workflow or explicit debug sessions. See `docs/explanation/08-observability.md` for the span tree and `traces.ndjson` rotation limits (10 MB / 7-day default). |
| `JUSTSEARCH_HEAD_TRACING_LEVEL` | `justsearch.head.tracing_level` | String | Head process OTel tracing level: `none` (default, no spans), `sample` (1% ratio), `detailed` (100%). Tempdoc 518 Appendix G W4.2 — when non-`none`, the head's `HeadlessApp` initializes a `TracingBootstrap` so the existing span-authoring code (`AgentLoopService.invoke_agent`, `KnowledgeHttpApiAdapter.search`, etc.) emits to `traces.ndjson` + optional OTLP fan-out. Spans automatically carry the `justsearch.inference.generation` attribute (per W2.2) when an inference runtime is registered. Cost profile mirrors the indexing tracing key. |
| `JUSTSEARCH_SEARCH_PROFILE` | `justsearch.search.pipeline.profile` | String | Selects a search pipeline profile (e.g., `default`, `semantic`, `hybrid`). |
| **Worker / Build Info** | | | |
| `JUSTSEARCH_INDEXER_WORKER_VERSION` | `indexer.worker.version` | String | Overrides the Worker version string (primarily for build/debug). |
| `JUSTSEARCH_BUILD_STAMP` | `justsearch.build.stamp` | String | SHA-256 content hash of Worker distribution (16 hex chars). Injected by `WorkerSpawner` from `build-stamp.txt`. Used for stale-JVM detection — jseval compares the running stamp against the on-disk stamp to warn of mismatches. (371) |
| **AI (inference + embeddings)** | | | |
| `JUSTSEARCH_AI_AUTOSTART_ENABLED` | `justsearch.ai.autostart.enabled` | Bool | Auto-start llama-server on backend startup. Used by eval runs (`-Pllm=true`). (369) |
| `JUSTSEARCH_AI_AUTOSTART_DISABLED` | `justsearch.ai.autostart.disabled` | Bool | Explicitly disable LLM auto-start, overriding `AI_AUTOSTART_ENABLED`. (369) |
| | `justsearch.inference.health_check_timeout_ms` | Int | llama-server health check timeout in milliseconds (default `120000`). Eval runs override to `180000`. Default was `30000` pre-alpha.17 — raised because Qwen3.5-9B Q4_K_M + multimodal cold-load legitimately exceeds 30s on first launch (374 alpha.17 R1). Honored by both `LlamaServerOps` (smoke test / mode transition) and `RuntimeActivationService` (activation self-test). Sysprop only — static init in both classes. (369, 374) |
| `JUSTSEARCH_AI_DISABLED` | `justsearch.ai.disabled` | Bool | Disables all AI features (forces keyword-only flows where applicable). |
| `JUSTSEARCH_LLM_ENABLED` | `justsearch.llm.enabled` | Bool | Enables/disables LLM features (escape hatch; policy/UI may override). |
| `JUSTSEARCH_AI_EMBED_ENABLED` | `justsearch.ai.embed.enabled` | Bool | Enables/disables embeddings independently (escape hatch). |
| `JUSTSEARCH_AI_CLASSIFY_ENABLED` | `justsearch.ai.classify.enabled` | Bool | Enables/disables classification independently (escape hatch). |
| `JUSTSEARCH_LLM_MODEL_PATH` | `justsearch.llm.model_path` | Path | Path to the main chat/VLM GGUF model used by `llama-server`. |
| `JUSTSEARCH_LLM_MODE` | `justsearch.llm.mode` | String | LLM mode selector (implementation-defined; e.g., local/remote). |
| `JUSTSEARCH_LLM_BACKEND` | `justsearch.llm.backend` | String | Backend selector (e.g., `auto`, `stub`). `stub` disables vector embeddings (used by indexing benches). |
| `JUSTSEARCH_EMBED_BACKEND` | `justsearch.embed.backend` | String | Embedding backend selector: `auto` (default) or `onnx`. Does NOT control GPU/CPU — use `JUSTSEARCH_EMBED_GPU_ENABLED` for GPU offload. |
| `JUSTSEARCH_MODEL_PATH` | `justsearch.model.path` | Path | Legacy embedding model path. Propagated to Worker via env but not consumed by ONNX embedding discovery (use `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` for explicit model override). |
| `JUSTSEARCH_EMBED_ONNX_MODEL_PATH` | `justsearch.embed.onnx.model_path` | Path | Explicit ONNX embedding model directory (overrides auto-discovery). When unset, `EmbeddingOnnxModelDiscovery` tries `embeddinggemma-300m/` then `embedding/`. |
| `JUSTSEARCH_EMBED_GPU_ENABLED` | `justsearch.embed.gpu.enabled` | Bool | Enable GPU acceleration for ONNX embedding inference (default `false`). Preferred over legacy `JUSTSEARCH_EMBED_GPU_LAYERS`. |
| `JUSTSEARCH_EMBED_GPU_MEM_MB` | `justsearch.embed.gpu_mem_mb` | Int | GPU arena size in MB for ONNX embedding CUDA sessions (default `2048`). EmbeddingGemma at batch=8 needs ~470MB; batch=16+ OOMs at 2048MB. |
| `JUSTSEARCH_EMBED_GPU_LAYERS` | `justsearch.embed.gpu.layers` | Int | Legacy alias for `EMBED_GPU_ENABLED`: any value > 0 is treated as `EMBED_GPU_ENABLED=true`. Use `EMBED_GPU_ENABLED` for new configurations. |
| `JUSTSEARCH_EMBED_GPU_DEVICE_ID` | `justsearch.embed.gpu.device_id` | Int | CUDA device ID for ONNX embedding sessions (default 0). |
| `JUSTSEARCH_MODELS_DIR` | `justsearch.models.dir` | Path | Models directory (used to resolve model filenames in some flows). |
| `JUSTSEARCH_SERVER_EXE` | `justsearch.server.exe` | Path | Path to `llama-server` executable (BYO runtime override). |
| `JUSTSEARCH_SERVER_PORT` | `justsearch.server.port` | Int | HTTP port for `llama-server`. |
| `JUSTSEARCH_CONTEXT_SIZE` | `justsearch.context.size` | Int | LLM context window size (`n_ctx` request target). |
| `JUSTSEARCH_GPU_ENABLED` | `justsearch.gpu.enabled` | Bool | Master ONNX GPU switch. Auto-set to `true` when CUDA DLLs are detected. Per-model overrides (`EMBED_GPU_ENABLED`, `SPLADE_GPU_ENABLED`, etc.) take precedence when explicitly set. Does not affect reranker or chunk reranker (those remain opt-in). |
| `JUSTSEARCH_GPU_LAYERS` | `justsearch.gpu.layers` | Int | GPU offload layers for `llama-server` (`-ngl`). Does not affect ONNX GPU — use `JUSTSEARCH_GPU_ENABLED` or per-model overrides. |
| `JUSTSEARCH_VLM_MODEL` | `justsearch.vlm.model` | String | Vision-language model filename (resolved under `JUSTSEARCH_MODELS_DIR`). |
| `JUSTSEARCH_MMPROJ_MODEL` | `justsearch.mmproj.model` | String | Vision projector filename (resolved under `JUSTSEARCH_MODELS_DIR`). |
| `JUSTSEARCH_USE_THINKING` | `justsearch.llm.use_thinking` | Bool | Enables reasoning stream formatting for llama-server (`--reasoning-format deepseek`) and reasoning-channel extraction. Default `true`. Requires server restart to apply flag change. See `docs/explanation/05-ai-architecture.md` §Reasoning Pipeline. |
| `JUSTSEARCH_REASONING_BUDGET` | `justsearch.llm.reasoning_budget` | Int | llama-server reasoning token budget (`--reasoning-budget`). Default `0` (disabled). Set `-1` for unlimited reasoning budget. Requires server restart to apply flag change. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED` | `justsearch.agent.context_compression.enabled` | Bool | Enables deterministic compression of older agent tool outputs in conversation history. Default `true`. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS` | `justsearch.agent.context_compression.min_chars` | Int | Minimum tool output length before compression is applied. Default `200`. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS` | `justsearch.agent.context_compression.keep_last_results` | Int | Number of most recent tool outputs kept uncompressed in context. Default `0`. |
| `JUSTSEARCH_AGENT_SEARCH_DEFAULT_LIMIT` | `justsearch.agent.search.default_limit` | Int | Default search result limit for agent search tool (1-20). Default `5`. |
| `JUSTSEARCH_AGENT_BROWSE_DEFAULT_MAX_FOLDERS` | `justsearch.agent.browse.default_max_folders` | Int | Default max folders for agent browse tool (1-200). Default `20`. |
| `JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS` | `justsearch.agent.max_tool_result_chars` | Int | Maximum characters preserved per tool result before truncation. Default `900`, min `100`. |
| `JUSTSEARCH_RERANK_GPU_MEM_MB` | `justsearch.rerank.gpu_mem_mb` | Int | GPU memory arena size (MB) for the Worker-side ONNX reranker CUDA execution provider. Default `2048`. Minimum for GTE-ModernBERT at seq=512. See `docs/explanation/05-ai-architecture.md` §Reranker GPU Coordination. |
| **Summary runtime** | | | |
| `JUSTSEARCH_SUMMARY_MAX_CHARACTERS` | `justsearch.summary.max_characters` | Int | Max summary input characters before rejection (default `200000`, clamped `>= 1`). |
| `JUSTSEARCH_SUMMARY_MAX_TOKENS` | `justsearch.summary.max_tokens` | Int | Max summary estimated tokens before rejection (default `20000`, clamped `>= 1`). |
| `JUSTSEARCH_SUMMARY_MESSAGE_KEY` | `justsearch.summary.message_key` | String | i18n key for the summary-too-large toast. |
| `JUSTSEARCH_SUMMARY_QUEUE_FULL_MESSAGE_KEY` | `justsearch.summary.queue_full_message_key` | String | i18n key for summary queue saturation toast. |
| `JUSTSEARCH_SUMMARY_EXECUTION_THREADS` | `justsearch.summary.execution_threads` | Int | Summary execution thread override (default from `llm.max_sessions`, clamped `>= 1`). |
| `JUSTSEARCH_SUMMARY_EXECUTION_QUEUE_CAPACITY` | `justsearch.summary.execution_queue_capacity` | Int | Summary execution queue capacity (default `max(llm.queue_capacity, threads)`, clamped `>= 1`). |
| `JUSTSEARCH_SUMMARY_PIPELINE` | `justsearch.summary.pipeline` | String | Summary pipeline id (default `summary_mapreduce_v1` after sanitize). |
| **LLM runtime tuning** | | | |
| `JUSTSEARCH_LLM_MODEL_SHA256` | `justsearch.llm.model_sha256` | String | Expected model SHA-256 metadata (default `unknown`). |
| `JUSTSEARCH_LLM_GPU_LAYERS` | `justsearch.llm.gpu_layers` | Int | LLM GPU layers override. |
| `JUSTSEARCH_LLM_DEADLINE_MS` | `justsearch.llm.deadline_ms` | Long | LLM request deadline in milliseconds. |
| `JUSTSEARCH_LLM_MAX_PARALLEL` | `justsearch.llm.max_parallel` | Int | Max concurrent inferences. |
| `JUSTSEARCH_LLM_MAX_SESSIONS` | `justsearch.llm.max_sessions` | Int | Session pool size override. |
| `JUSTSEARCH_LLM_SESSION_WARMUP_MS` | `justsearch.llm.session_warmup_ms` | Long | Session warmup delay in milliseconds. |
| `JUSTSEARCH_LLM_QUEUE_CAPACITY` | `justsearch.llm.queue_capacity` | Int | Inference queue capacity. |
| `JUSTSEARCH_LLM_VRAM_FRACTION` | `justsearch.llm.vram_fraction` | Double | VRAM fraction hard limit (finite, non-negative). |
| `JUSTSEARCH_LLM_VRAM_PROJECTED` | `justsearch.llm.vram_projected` | Double | Projected VRAM fraction (finite, non-negative). |
| `JUSTSEARCH_LLM_MAX_SLOTS` | `justsearch.llm.max_slots` | Int | Max backend slots (clamped `>= 1`). |
| `JUSTSEARCH_LLM_VRAM_LIMIT_BYTES` | `justsearch.llm.vram_limit_bytes` | Long | Absolute VRAM limit in bytes (clamped `>= 0`). |
| `JUSTSEARCH_LLM_VRAM_AUTO_SCALE` | `justsearch.llm.vram_auto_scale` | Bool | Enable VRAM autoscale logic. |
| `JUSTSEARCH_LLM_SIMULATED_LATENCY_MS` | `justsearch.llm.simulated_latency_ms` | Long | Simulated latency for test/dev. |
| `JUSTSEARCH_LLM_THREADS` | `justsearch.llm.threads` | Int | LLM worker threads override. |
| `JUSTSEARCH_LLM_CONTEXT_LENGTH` | `justsearch.llm.context_length` | Int | LLM context length. |
| `JUSTSEARCH_LLM_MAX_NEW_TOKENS` | `justsearch.llm.max_new_tokens` | Int | Max generated tokens per request. |
| `JUSTSEARCH_LLM_TEMPERATURE` | `justsearch.llm.temperature` | Double | Sampling temperature (finite, non-negative). |
| `JUSTSEARCH_LLM_TOP_P` | `justsearch.llm.top_p` | Double | Top-p sampling parameter. |
| `JUSTSEARCH_LLM_MIN_P` | `justsearch.llm.min_p` | Double | Min-p sampling parameter. |
| `JUSTSEARCH_LLM_REP_PENALTY` | `justsearch.llm.rep_penalty` | Double | Repetition penalty value. |
| `JUSTSEARCH_LLM_REP_WINDOW` | `justsearch.llm.rep_window` | Int | Repetition penalty window. |
| `JUSTSEARCH_LLM_ENABLE_JSON_GUARD` | `justsearch.llm.enable_json_guard` | Bool | Enable JSON grammar guard. |
| `JUSTSEARCH_LLM_TEMPLATE_ROOT` | `justsearch.llm.template_root` | Path | Template root directory (invalid path -> `null`). |
| `JUSTSEARCH_LLM_TEMPLATE_TRANSLATE` | `justsearch.llm.template_translate` | String | Translation template filename. |
| `JUSTSEARCH_LLM_TEMPLATE_SUMMARY` | `justsearch.llm.template_summary` | String | Summary map template filename. |
| `JUSTSEARCH_LLM_TEMPLATE_REDUCE` | `justsearch.llm.template_reduce` | String | Summary reduce template filename. |
| `JUSTSEARCH_LLM_RNG_SEED` | `justsearch.llm.rng_seed` | Long | RNG seed override. |
| `JUSTSEARCH_LLM_BACKEND_SELECTOR` | `justsearch.llm.backend_selector` | String | Backend selector profile (`auto` by default). |
| `JUSTSEARCH_LLM_SUMMARY_CHUNK_TOKENS` | `justsearch.llm.summary_chunk_tokens` | Int | Summary chunk size (clamped `>= 32`). |
| `JUSTSEARCH_LLM_SUMMARY_CHUNK_OVERLAP` | `justsearch.llm.summary_chunk_overlap` | Int | Summary overlap, bounded to `[0, chunk-1]`. |
| `JUSTSEARCH_LLM_ALLOW_REMOTE` | `justsearch.llm.allow_remote` | Bool | Allows remote backend use when enabled. |
| `JUSTSEARCH_LLM_REMOTE_ENDPOINT` | `justsearch.llm.remote_endpoint` | String | Remote backend endpoint URL/base. |
| `JUSTSEARCH_LLM_REMOTE_AUTH_TOKEN` | `justsearch.llm.remote_auth_token` | String | Remote backend auth token. |
| `JUSTSEARCH_LLM_BACKEND_SUPPORTS` | `justsearch.llm.backend_supports` | CSV List | Backend capability override list (comma-separated). |
| **Pipeline ids** | | | |
| `JUSTSEARCH_TRANSLATOR_PIPELINE_INTENT` | `justsearch.translator.pipeline.intent` | String | Intent pipeline id (default `intent_v1`). |
| `JUSTSEARCH_TRANSLATOR_PIPELINE_EMBED` | `justsearch.translator.pipeline.embed` | String | Embed pipeline id (default `embed_v1`). |
| `JUSTSEARCH_TRANSLATOR_PIPELINE_CLASSIFY` | `justsearch.translator.pipeline.classify` | String | Classify pipeline id (default `classify_v1`). |
| **RAG** | | | |
| `JUSTSEARCH_RAG_TOP_K` | `justsearch.rag.top_k` | Int | Number of chunks to retrieve for RAG context (default 5). |
| **GPU / VRAM thresholds** | | | |
| `JUSTSEARCH_VRAM_THRESHOLD_12GB` | `justsearch.vram.threshold.12gb` | Long | VRAM tier threshold for "12GB+" classification in bytes (default 11500000000). Affects UI tier display only; does not change VramDetector's llama-server flag selection. |
| `JUSTSEARCH_VRAM_THRESHOLD_8GB` | `justsearch.vram.threshold.8gb` | Long | VRAM tier threshold for "8GB" classification in bytes (default 7500000000). |
| `JUSTSEARCH_VRAM_THRESHOLD_4GB` | `justsearch.vram.threshold.4gb` | Long | VRAM tier threshold for "4GB" classification in bytes (default 3500000000). |
| **Hybrid Fusion (CC / Branch)** | | | |
| `JUSTSEARCH_HYBRID_FUSION_STRATEGY` | `index.hybrid.fusion_strategy` | String | Whole-doc fusion algorithm: `cc` (default) or `rrf`. CC uses min-max normalized convex combination; RRF uses reciprocal rank fusion. |
| `JUSTSEARCH_HYBRID_CC_ALPHA` | `index.hybrid.cc_alpha` | Double | CC dense-vs-sparse tradeoff (2-leg CC only). Default 0.5. |
| `JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED` | `index.hybrid.leg_arbitration_enabled` | Bool | Tempdoc 636 Design v2: per-query leg arbitration — raise the 2-leg CC alpha toward dense when dense is bounded-confident AND the legs diverge (low cross-leg top-K doc-id overlap), so the lexical leg cannot suppress a confident dense answer on grep-defeating paraphrase queries. **Default true** (graded 2026-06-24: combined with recall-complete, +195% nDCG@10 on the buried-fact target; accepted −3.22% on real email — tempdoc 636; set to false to disable). |
| `JUSTSEARCH_HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE` | `index.hybrid.leg_arbitration_alpha_diverge` | Double | Dense weight (CC alpha) applied when leg arbitration fires. Gentler values keep more lexical weight so a BM25-correct answer is not zeroed on factoid queries where dense is confident-but-wrong. Default 0.7. |
| `JUSTSEARCH_HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN` | `index.hybrid.leg_arbitration_bm25_incoherence_min` | Double | The discriminator: leg arbitration fires only when BM25's own `top2/top1` score ratio is ≥ this (a flat top = no clear lexical winner = "incoherent"). A peaked BM25 winner (BM25-dominant corpora like legal/email) stays below the threshold so its leg is NOT down-weighted. Higher = stricter. Default 0.9 (calibrated: needle win preserved, courtlistener regression cut from −23% to ~−2%). |
| `JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE` | `index.hybrid.leg_recall_complete_enabled` | Bool | Tempdoc 636 Design v3: recall-complete rerank pool — guarantee each retrieval leg's top-N candidates survive fused-score truncation into the returned list (the cross-encoder's rerank window), so a confident dense answer the lexical leg would bury still reaches the relevance model. Keyword-neutral (never down-weights a leg), unlike leg arbitration. **Default true** (graded 2026-06-24: +98% nDCG@10 on the buried-fact target, neutral on real email — tempdoc 636; set to false to disable). |
| `JUSTSEARCH_HYBRID_RERANK_POOL_TOP_N` | `index.hybrid.leg_recall_complete_top_n` | Int | Per-leg top-N guaranteed into the recall-complete rerank pool. Default 10. |
| `JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE` | `index.hybrid.cc_zero_exclude` | Bool | Exclude zero-scored docs from CC normalization (default true). |
| `JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE` | `index.hybrid.cc_weight_sparse` | Double | CC weight for BM25 leg in 3-way fusion (default 0.60). |
| `JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE` | `index.hybrid.cc_weight_dense` | Double | CC weight for KNN leg in 3-way fusion (default 0.20). |
| `JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE` | `index.hybrid.cc_weight_splade` | Double | CC weight for SPLADE leg in 3-way fusion (default 0.20). SPLADE weight is further modulated by `parent_token_count` (full weight ≤1,024 tokens, zero ≥4,096 tokens). |
| `JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY` | `index.hybrid.branch_fusion_strategy` | String | Branch fusion algorithm: `cc` (default) or `rrf`. Controls how the whole-doc branch and chunk branch are merged. |
| `JUSTSEARCH_HYBRID_BRANCH_CC_ZERO_EXCLUDE` | `index.hybrid.branch_cc_zero_exclude` | Bool | Exclude zero-scored docs from branch CC normalization (default true). |
| `JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE` | `index.hybrid.branch_cc_weight_whole` | Double | CC weight for whole-doc branch (default 0.50). |
| `JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK` | `index.hybrid.branch_cc_weight_chunk` | Double | CC weight for chunk branch (default 0.50). Effective weight is modulated by parent document length. |
| `JUSTSEARCH_HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER` | `index.hybrid.branch_chunk_min_weight_multiplier` | Double | Minimum chunk branch weight multiplier for short documents (default 0.25). Short docs (≤1,024 tokens) get this multiplier; long docs (≥4,096 tokens) get 1.0. |
| **Search Pipeline** | | | |
| `JUSTSEARCH_QU_ENABLED` | `justsearch.qu.enabled` | Bool | Enable Query Understanding preprocessing (disabled by default). When enabled, an LLM-based QU layer extracts filters/boosts from natural language queries before search execution. Gated behind disabled default due to LLM scheduling contention (QU + expansion compete for same llama-server slot). (363) |
| `JUSTSEARCH_FILTER_NORM_ENABLED` | `justsearch.filter_norm.enabled` | Bool | Enable filter value normalization service (disabled by default). Hybrid deterministic + LLM architecture: exact/prefix/substring matching first (0ms), LLM fallback for semantic gaps only. Fires async on both search and answer paths. (366) |
| `JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED` | `search.chunk_aware.enabled` | Bool | Enable chunk-aware merge in search (default true). When enabled, long-doc corpora get a chunk branch fused with the whole-doc branch. Gated by `isShortCorpus()` — short corpora skip chunk merge regardless. |
| `JUSTSEARCH_SEARCH_ENTITY_BOOST` | `justsearch.search.entity_boost` | Double | Entity text field boost in DisjunctionMaxQuery (default 0.0 = disabled). Values > 0 boost documents with NER-extracted entity matches. |
| `JUSTSEARCH_SEARCH_QUERY_CLASSIFICATION_ENABLED` | `search.query_classification.enabled` | Bool | Enable rule-based query classification (default true). |
| **SPLADE GPU** | | | |
| `JUSTSEARCH_SPLADE_GPU_ENABLED` | `justsearch.splade.gpu_enabled` | Bool | Enable GPU acceleration for SPLADE inference. Falls back to `JUSTSEARCH_GPU_ENABLED` when unset. |
| `JUSTSEARCH_SPLADE_GPU_DEVICE_ID` | `justsearch.splade.gpu_device_id` | Int | CUDA device ID for SPLADE inference (default 0). |
| `JUSTSEARCH_SPLADE_GPU_MEM_MB` | `justsearch.splade.gpu_mem_mb` | Int | GPU memory arena limit for SPLADE sessions in MB (default 2048). |
| **NER GPU** | | | |
| `JUSTSEARCH_NER_GPU_ENABLED` | `justsearch.ner.gpu_enabled` | Bool | Enable GPU acceleration for NER inference. Falls back to `JUSTSEARCH_GPU_ENABLED` when unset. |
| `JUSTSEARCH_NER_GPU_DEVICE_ID` | `justsearch.ner.gpu_device_id` | Int | CUDA device ID for NER inference (default 0). |
| `JUSTSEARCH_NER_GPU_MEM_MB` | `justsearch.ner.gpu_mem_mb` | Int | GPU memory arena limit for NER sessions in MB (default 512). |
| **BGE-M3 GPU** | | | |
| `JUSTSEARCH_BGE_M3_GPU_ENABLED` | `justsearch.bgem3.gpu_enabled` | Bool | Enable GPU acceleration for BGE-M3 inference. Falls back to `JUSTSEARCH_GPU_ENABLED` when unset. |
| `JUSTSEARCH_BGE_M3_GPU_DEVICE_ID` | `justsearch.bgem3.gpu_device_id` | Int | CUDA device ID for BGE-M3 inference (default 0). |
| `JUSTSEARCH_BGE_M3_GPU_MEM_MB` | `justsearch.bgem3.gpu_mem_mb` | Int | GPU memory arena limit for BGE-M3 sessions in MB (default 3072). |
| **ONNX Runtime** | | | |
| `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` | `justsearch.onnxruntime.native_path` | Path | ORT native runtime directory for CUDA EP (SPLADE, embedding, reranker). First-class resolved config path; propagated to Worker via config snapshot. Supersedes legacy `onnxruntime.native.path`. |
| `JUSTSEARCH_ORT_PROFILING_DIR` | `justsearch.ort.profiling_dir` | Path | Diagnostic only. When set, each ORT GPU session writes a per-session profile file to this directory (5–15% inference overhead). Typed via `RuntimePolicy.Profiling.ortProfilingDir` since tempdoc 397 §14.24 FB; appears in `/api/debug/session-policies` under `runtime.profiling.ortProfilingDir`. |
| `JUSTSEARCH_ORT_VERBOSE` | `justsearch.ort.verbose` | Bool | Diagnostic only. Enables ORT VERBOSE-level session logging for EP routing diagnostics (significant log volume). Typed via `RuntimePolicy.Profiling.verboseLogging` since tempdoc 397 §14.24 FB; appears in `/api/debug/session-policies` under `runtime.profiling.verboseLogging`. |
| **Extraction Sandbox (tempdoc 410 §5)** | | | |
| `JUSTSEARCH_EXTRACTION_SANDBOX_MODE` | `justsearch.extraction.sandbox.mode` | String | Worker extraction sandbox mode. Values: `in_process` (default — current behaviour) or `process` (out-of-process child JVM). Selecting `process` requires `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND`; the Worker fails fast at startup otherwise. The factory seam is operator-selectable; the protocol is tested in `ProcessExtractionSandboxTest`. |
| `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` | `justsearch.extraction.sandbox.command` | String | Whitespace-separated argv used to spawn the extraction child JVM when `EXTRACTION_SANDBOX_MODE=process`. Operator must construct a command that launches `io.justsearch.indexerworker.extract.ExtractionSandboxChild` with the Worker's classpath. Ignored unless `process` mode is selected. |
| **Ingestion Skip Policy (tempdoc 410 §13)** | | | |
| `JUSTSEARCH_INGESTION_SKIP_PATTERNS` | `justsearch.ingestion.skip.patterns` | String | Comma-separated lowercase file-name fragments treated as skip patterns by `IngestionSkipPolicy`. Defaults to `thumbs.db,.ds_store,desktop.ini,.git,.svn,$recycle.bin`. Set replaces the defaults wholesale; resolved at Worker boot. Operator override only — there is no per-root form yet (gated on the SourceRoot capability model). **Empty-string semantics:** the canonical `EnvRegistry` resolution treats unset and blank-set as the same (Optional.empty), so setting the key to `""` or whitespace falls back to the defaults — there is no "explicit empty = disable" form. To effectively disable the skip-pattern category, set the key to a sentinel value that won't match any real path (e.g., `__never_match_anything__`). Disabling these defaults causes junk files (Thumbs.db, .DS_Store, .git contents, etc.) to be indexed; the defaults exist for a reason. |
| `JUSTSEARCH_INGESTION_SKIP_EXTENSIONS` | `justsearch.ingestion.skip.extensions` | String | Comma-separated lowercase file extensions (no leading dot) treated as build/cache output. Defaults to `pyc,pyo,class,o,obj`. Set replaces the defaults wholesale. Same empty-string semantics + sentinel-disable pattern as `JUSTSEARCH_INGESTION_SKIP_PATTERNS`. |
| `JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES` | `justsearch.ingestion.skip.directory_names` | String | Comma-separated lowercase directory basenames a tree walk should never descend into. Defaults to `.git,.svn,.hg,.bzr,cvs,node_modules,bower_components,__pycache__,.tox,.pytest_cache,.mypy_cache,$recycle.bin,system volume information`. Set replaces the defaults wholesale. Same empty-string semantics + sentinel-disable pattern as `JUSTSEARCH_INGESTION_SKIP_PATTERNS`. Disabling causes the walker to descend into `.git/objects/`, `node_modules/`, etc. — orders of magnitude more files than typical user content. |
| **Path Resolution + Test Mode (tempdoc 419 T5/T6, ADR-0028)** | | | |
| `JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS` | `justsearch.path_resolution.retention_days` | Int | Retention window (in days) for entries in the `path_resolution` table after a file's deletion has been observed. Default `90`. Rows with non-null `removed_at` are pruned by the periodic job-cleanup task once `removed_at + retention < now`. The 90-day default lets the activity panel still answer "this file was deleted on X" for recently-removed entries without unbounded table growth. Removing a watched root immediately prunes everything under it regardless of retention (ADR-0028). Lower values reduce table size but shorten the "recently deleted" UX window. |
| `JUSTSEARCH_LITE_MODE` | `justsearch.lite.mode` | Bool | Lite mode for ingestion-only test scenarios. When `true` the Head process skips InferenceLifecycleManager initialization (the AI stack), cascading through the `OnlineAiService.unavailable()` fallback. Equivalent in effect to `JUSTSEARCH_AI_DISABLED=true` but namespaced for the test-harness use case so future test-mode skips have an obvious home. Saves ~3-8s of startup time. Used by the per-class `IsolatedBackendFixture` (see `docs/how-to/spawn-isolated-test-backend.md`). Default `false`. |

## Additional Runtime Keys (Selected)

| Env Variable | System Property | Type | Description |
| :--- | :--- | :--- | :--- |
| **JVM & Worker** | | | |
| `JUSTSEARCH_WORKER_HEAP` | `justsearch.worker.heap` | String | Worker JVM max heap size (default `512m`). Example: `1g`, `2048m`. |
| `JUSTSEARCH_JVM_OPTS` | N/A | String | Custom JVM options passed to worker process. Useful for GC logging (`-Xlog:gc*`), NMT (`-XX:NativeMemoryTracking=summary`), or profiling. Multiple options separated by whitespace. **Limitation:** Options are split on whitespace; file paths with spaces are not supported. |
| **Dev Hot-Reload** | | | |
| `JUSTSEARCH_DEV_HOTRELOAD` | `justsearch.dev.hotreload` | Bool | Enables dev hot-reload: JDWP agent on Worker + `DevReloadManager` for service reconstruction on signal. Use with MCP `start(hotReload: true)` or set env var before starting the dev stack. Default `false`. |
| `JUSTSEARCH_DEV_DEBUG_PORT` | `justsearch.dev.debug.port` | Int | JDWP debug port for Worker (default `5005`). HotSwapPush connects here to push bytecode updates. Auto-enabled when `DEV_HOTRELOAD=true`. |
| `JUSTSEARCH_DEV_HOTRELOAD_CLASSES_DIR` | `justsearch.dev.hotreload.classesDir` | Path | Worker-services classes directory for DevReloadManager. Auto-set by `WorkerSpawner` when hot-reload is enabled. Override for non-standard layouts. |
| **Indexing & Storage** | | | |
| `JUSTSEARCH_INDEX_BASE_PATH` | `justsearch.index.base_path` | Path | Overrides the effective index root (contains `state.json` and `indices/`). |
| `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` | `index.schema_mismatch.policy` | String | Policy for schema incompatibility (`FAIL_CLOSED`, `REBUILD_BACKUP_FIRST`, `BLUE_GREEN_MIGRATE`). |
| `JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS` | `index.migration.cutover.max_failed_jobs` | Int | Optional failure budget to block auto-cutover during migration. |
| `JUSTSEARCH_LLM_ACCEL` |  | String | Selects the native llama backend variant for in-process llama.cpp (embeddings). Values: `cuda`, `metal`, `vulkan`, `cpu`. If unset, JustSearch chooses based on detected hardware (prefers CUDA, then Metal, else CPU). |
| `JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED` | `policy.gpu_acceleration_enabled` | Bool | System-wide GPU policy gate (forces CPU-only behavior when false). |
| **RAG retrieval** | | | |
| `JUSTSEARCH_RAG_RETRIEVE_MODE` | `rag.retrieve.mode` | String | Retrieval mode: `auto` (prefer hybrid), `hybrid`, or `bm25`. |
| `JUSTSEARCH_RAG_OVERRETRIEVE_FACTOR` | `rag.retrieve.overretrieve_factor` | Int | Candidate multiplier for coverage-aware diversification (default 3). |
| `JUSTSEARCH_RAG_DIVERSIFY_MODE` | `rag.diversify.mode` | String | Diversification mode: `position` (default) or `mmr`. |
| `JUSTSEARCH_RAG_MMR_LAMBDA` | `rag.mmr.lambda` | Double | MMR relevance-vs-novelty tradeoff (0..1; default 0.5). |
| `JUSTSEARCH_RAG_MMR_MAX_CANDIDATES` | `rag.mmr.max_candidates` | Int | Max candidate chunks to embed for MMR (default 20). |
| `JUSTSEARCH_RAG_CHUNK_VECTORS_ENABLED` | `rag.chunk_vectors.enabled` | Bool | Enables chunk-level hybrid retrieval with `chunk_vector` when coverage is ready (default true; falls back when coverage < 95%). |
| **ONNX Runtime (reranker GPU)** | | | |
|  | `onnxruntime.native.path` | Path | **Legacy.** Optional native ORT runtime directory (CUDA EP) for reranker GPU acceleration (Worker); forwarded/derived by `WorkerSpawner`. Superseded by `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` (in EnvRegistry) which is resolved via `ResolvedConfig` and propagated via worker config snapshot. The legacy sysprop is still checked as a fallback by `OrtCudaHelper`. |
| `JUSTSEARCH_ONNXRUNTIME_VARIANT_ID` | `justsearch.onnxruntime.variantId` | String | Optional ORT variant id (defaults to the active llama-server runtime variant id when using v3 runtime variants). |
| **Search reranker** | | | |
| `JUSTSEARCH_RERANK_ENABLED` | `justsearch.rerank.enabled` | Bool | Enables cross-encoder reranking for interactive search. Auto-enabled when a model is discovered (see below). Set explicitly to `false` to force-disable. |
| `JUSTSEARCH_RERANK_MODEL_PATH` | `justsearch.rerank.model_path` | Path | Path to the reranker model directory. Propagated to Worker via config snapshot. If unset, auto-discovery checks (via `ResolvedPathResolver`): `<modelsDir>/onnx/reranker/`, `<dataDir>/models/onnx/reranker/`, `<repoRoot>/models/onnx/reranker/`, then dev fallback. |
| `JUSTSEARCH_RERANK_TOP_K` | `justsearch.rerank.top_k` | Int | Documents to rerank (default 20). |
| `JUSTSEARCH_RERANK_DEADLINE_MS` | `justsearch.rerank.deadline_ms` | Long | Time budget for reranking (default 200ms). |
| `JUSTSEARCH_RERANK_MIN_HITS` | `justsearch.rerank.min_hits` | Int | Minimum hits to trigger reranking (default 5). |
| `JUSTSEARCH_RERANK_MAX_SEQ_LEN` | `justsearch.rerank.max_seq_len` | Int | Max sequence length (default 512). |
| `JUSTSEARCH_RERANK_GPU_ENABLED` | `justsearch.rerank.gpu.enabled` | Bool | Enables ORT GPU session for Worker-side reranking when a CUDA-capable ORT native runtime is available (default true). Set to `false` to force CPU-only reranking. |
| `JUSTSEARCH_RERANK_GPU_DEVICE_ID` | `justsearch.rerank.gpu.device_id` | Int | GPU device id for reranking (default 0). |
| **RAG chunk reranker** | | | |
| `JUSTSEARCH_RERANK_CHUNKS_ENABLED` | `justsearch.rerank.chunks.enabled` | Bool | Enables chunk reranking for RAG. Auto-enabled when a model is discovered. Set explicitly to `false` to force-disable. |
| `JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH` | `justsearch.rerank.chunks.model_path` | Path | Chunk reranker model path (falls back to `JUSTSEARCH_RERANK_MODEL_PATH`, then auto-discovery). |
| `JUSTSEARCH_RERANK_CHUNKS_TOP_K` | `justsearch.rerank.chunks.top_k` | Int | Chunks to rerank on CPU (default 10). |
| `JUSTSEARCH_RERANK_CHUNKS_MAX_GPU_CANDIDATES` | `justsearch.rerank.chunks.max_gpu_candidates` | Int | Max chunk candidates when GPU is available (default 50). |
| `JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS` | `justsearch.rerank.chunks.deadline_ms` | Long | Time budget for chunk reranking (default 150ms). |
| `JUSTSEARCH_RERANK_CHUNKS_MIN_HITS` | `justsearch.rerank.chunks.min_hits` | Int | Minimum chunks to trigger reranking (default 3). |
| `JUSTSEARCH_RERANK_CHUNKS_ORDER` | `justsearch.rerank.chunks.order` | String | Rerank order: `auto` (default), `before_diversify`, `after_diversify`. |
| `JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED` | `justsearch.rerank.chunks.gpu.enabled` | Bool | Enables ORT GPU session for chunk reranking when available (default false). |
| `JUSTSEARCH_RERANK_CHUNKS_GPU_DEVICE_ID` | `justsearch.rerank.chunks.gpu.device_id` | Int | GPU device id for chunk reranking (default 0). |
| **Citation scorer** | | | |
| `JUSTSEARCH_CITATION_SCORER_ENABLED` | `justsearch.citation.scorer.enabled` | Bool | Enables CPU-based cross-encoder citation scoring after Q&A/summarization. Auto-enabled when a model is discovered. Set explicitly to `false` to force-disable. |
| `JUSTSEARCH_CITATION_SCORER_MODEL_PATH` | `justsearch.citation.scorer.model_path` | Path | Path to the citation scorer ONNX model directory (must contain `model.onnx` + `tokenizer.json`). If unset, auto-discovery checks: `<dataDir>/models/onnx/citation-scorer/`, `<cwd>/models/onnx/citation-scorer/`, then `<cwd>/models/citation-scorer/ms-marco-MiniLM-L2-v2/` (dev). |
| `JUSTSEARCH_CITATION_SCORER_THRESHOLD` | `justsearch.citation.scorer.threshold` | Double | Minimum sigmoid-normalized similarity score for a citation match (default 0.5, clamped to [0.01, 1.0]). |
| `JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN` | `justsearch.citation.scorer.max_seq_len` | Int | Maximum token sequence length for cross-encoder input (default 512). |
| `JUSTSEARCH_CITATION_SCORER_DEADLINE_MS` | `justsearch.citation.scorer.deadline_ms` | Long | Time budget for citation scoring in milliseconds (default 2000). Partial results returned on timeout. |
| **Evaluation (jseval)** | | | |
| `JUSTSEARCH_SKIP_PROJECTIONS` | *(n/a — env only)* | String (comma-separated) | Skip specific projections at `jseval run` end-of-run dispatch (tempdoc 400 Phase 6 / 6.1). Example: `JUSTSEARCH_SKIP_PROJECTIONS=encoder_drift,rank_diff`. Used when iterating on a flaky projection without losing other signals. Equivalent to `--skip-projection=<name>` CLI flag. |
| `JUSTSEARCH_MANIFEST_OVERRIDE` | *(n/a — env only)* | Path | Path to a JSON file whose contents overwrite the computed `manifest.json` at `jseval run` end (tempdoc 400 Phase 6 / 6.5, LR5-d synthetic executor). Consumer filter: override manifests carry `"synthetic": true` so downstream projections can ignore them. **Requires `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS=1`** — otherwise raises. Only use from `jseval bisect --synthesize`. |
| `JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS` | *(n/a — env only)* | Bool | Safety gate for `JUSTSEARCH_MANIFEST_OVERRIDE`. When `1`, manifest overriding is permitted. Any other value (or unset) raises. Named `_DANGEROUS` because overriding the manifest invalidates cohort identity — downstream consumers (envelopes, drift detection, history queries) that assume manifest stability are compromised. Never set in production. |

## YAML-Only Keys (No Env Var Override)

These keys are configurable only via `application.yaml`. They do not have env var or system property override paths.

| YAML Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `search.chunk_aware.enabled` | Bool | `true` | Enables chunk-aware merge in the search pipeline (stages 13a–13c). When enabled and chunk documents exist in the index, a parallel chunk branch retrieves and fuses chunk-level evidence before merging with whole-doc results. |

Legacy note:
- Some bundled runs also set `justsearch.data_dir` and/or `app.data_dir` as back-compat aliases for the data directory (primarily for older logback templates).

Ownership notes:
- LLM runtime keys (`WorkerConfig`/`LlmSettings`) are backed by `EnvRegistry` (with `PlatformPaths.resolveDataDir()` for canonical data-dir precedence).
- Runtime key forwarding is owned by `modules/app-services` (`WorkerSpawner`) and runtime consumption is split across `modules/app-inference`, Worker-side configuration, and `modules/gpu-bridge`.
  Remaining direct platform/native probes such as `llama.lib.path`, `os.*`, and `app.home` are intentionally outside this runtime key catalog.

## Perf Harness Knobs

These env vars were consumed by the EBv1 capture harness (removed in tempdoc 638) and were never part
of the long-lived app runtime configuration contract. They are retained here as historical reference only.

| Env Variable | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `JUSTSEARCH_C_EARLY_DOC_COUNT` | Int | 10 | Scenario C: number of “early” docs used to measure time-to-first-hit. |
| `JUSTSEARCH_C_LOAD_DOC_COUNT` | Int | 50 | Scenario C: number of background “load” docs used to keep indexing active. |
| `JUSTSEARCH_C_ITERATIONS` | Int | 5 | Scenario C: UI interaction iterations (keystroke-to-paint samples). |

## Examples

### 1) Mode 0 (lexical-only; disable embeddings)

```powershell
$env:JUSTSEARCH_LLM_BACKEND = "stub"
powershell -ExecutionPolicy Bypass -File scripts/bench/run-claim-b-suite-win.ps1
```

### 2) Mode 1 (CPU embeddings)

```powershell
$env:JUSTSEARCH_MODEL_PATH = "C:\\AI\\models\\nomic-embed-text-v1.5.Q4_K_M.gguf"
$env:JUSTSEARCH_EMBED_GPU_LAYERS = "0"
powershell -ExecutionPolicy Bypass -File scripts/bench/run-claim-b-suite-win.ps1
```

### 3) Online inference GPU offload (llama-server)

```powershell
$env:JUSTSEARCH_LLM_MODEL_PATH = "C:\\AI\\models\\Qwen_Qwen3.5-9B-Q4_K_M.gguf"
$env:JUSTSEARCH_GPU_LAYERS = "99"
./gradlew.bat --no-daemon :modules:app-launcher:run
```

Notes:
- GPU offload requires a GPU-capable runtime (e.g., a v3 runtime variant under `<AI_HOME>/native-bin/llama-server/variants/<variantId>/llama-server.exe`).
