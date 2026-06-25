/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Centralized registry for all JustSearch environment variables and system properties.
 *
 * <p>This class provides type-safe access to configuration values. The direct resolution
 * order within EnvRegistry is:
 * <ol>
 *   <li>System property (e.g., {@code -Djustsearch.data.dir=/path})</li>
 *   <li>Environment variable (e.g., {@code JUSTSEARCH_DATA_DIR=/path})</li>
 *   <li>Default value (if provided)</li>
 * </ol>
 *
 * <p><strong>Important:</strong> Most application configuration uses YAML-first precedence
 * in {@code RuntimeConfig}, which wraps EnvRegistry:
 * <ol>
 *   <li>YAML configuration (e.g., {@code config.yaml})</li>
 *   <li>System property (via EnvRegistry)</li>
 *   <li>Environment variable (via EnvRegistry)</li>
 *   <li>Default value</li>
 * </ol>
 * This means YAML values take priority over env/sysprop for most settings. EnvRegistry
 * values are used as fallbacks when YAML values are absent.
 *
 * <p>Usage:
 * <pre>{@code
 * Path dataDir = EnvRegistry.DATA_DIR.getPath();
 * int port = EnvRegistry.API_PORT.getInt(8080);
 * }</pre>
 */
public enum EnvRegistry {
    /** Root data directory for all JustSearch artifacts. */
    DATA_DIR("justsearch.data.dir", "JUSTSEARCH_DATA_DIR"),

    /** Explicit base path for Lucene index storage. */
    INDEX_BASE_PATH("justsearch.index.base_path", "JUSTSEARCH_INDEX_BASE_PATH"),

    /** Explicit path to SSOT directory (overrides auto-discovery). */
    SSOT_PATH("justsearch.ssot.path", "JUSTSEARCH_SSOT_PATH"),

    /** Path to field catalog JSON (overrides SSOT lookup). */
    FIELD_CATALOG("justsearch.fieldCatalog", "JUSTSEARCH_FIELD_CATALOG"),

    /** Path to application config YAML. */
    CONFIG_PATH("justsearch.config", "JUSTSEARCH_CONFIG"),

    /** Path to the MCP-host server list JSON (tempdoc 560 §6); unset disables the MCP-host. */
    MCP_HOST_CONFIG("justsearch.mcp.host.config", "JUSTSEARCH_MCP_HOST_CONFIG"),

    /** API server port. */
    API_PORT("justsearch.api.port", "JUSTSEARCH_API_PORT"),

    /** Telemetry flush interval (ms). */
    TELEMETRY_FLUSH_MS("justsearch.telemetry.flushMs", "JUSTSEARCH_TELEMETRY_FLUSH_MS"),

    /** Telemetry metrics file max size (MB). */
    TELEMETRY_METRICS_MAX_MB("justsearch.telemetry.metrics.max_mb", "JUSTSEARCH_TELEMETRY_METRICS_MAX_MB"),

    /** Telemetry metrics retention period (days). */
    TELEMETRY_METRICS_RETENTION_DAYS("justsearch.telemetry.metrics.retention.days", "JUSTSEARCH_TELEMETRY_METRICS_RETENTION_DAYS"),

    /** Telemetry exemplars enabled flag. */
    TELEMETRY_METRICS_EXEMPLARS("justsearch.telemetry.metrics.exemplars", "JUSTSEARCH_TELEMETRY_METRICS_EXEMPLARS"),

    /** Indexer-worker version string (override). */
    INDEXER_WORKER_VERSION("indexer.worker.version", "JUSTSEARCH_INDEXER_WORKER_VERSION"),

    /** Production mode flag. */
    PROD_MODE("justsearch.prod", "JUSTSEARCH_PROD"),

    /** Egress block-all flag for isolated testing. */
    EGRESS_BLOCK_ALL("egress.block_all", "JUSTSEARCH_EGRESS_BLOCK_ALL"),

    /** Query Understanding enabled flag (366 — disabled by default, experimental). */
    QU_ENABLED("justsearch.qu.enabled", "JUSTSEARCH_QU_ENABLED"),

    /** Filter value normalization enabled flag (366 — disabled by default, experimental). */
    FILTER_NORM_ENABLED("justsearch.filter_norm.enabled", "JUSTSEARCH_FILTER_NORM_ENABLED"),

    /** LLM enabled flag. */
    LLM_ENABLED("justsearch.llm.enabled", "JUSTSEARCH_LLM_ENABLED"),

    /** LLM model path. */
    LLM_MODEL_PATH("justsearch.llm.model_path", "JUSTSEARCH_LLM_MODEL_PATH"),

    /** LLM mode (local/remote). */
    LLM_MODE("justsearch.llm.mode", "JUSTSEARCH_LLM_MODE"),

    /** LLM backend selector (auto/stub/etc). */
    LLM_BACKEND("justsearch.llm.backend", "JUSTSEARCH_LLM_BACKEND"),

    /** LLM model file hash (sha256). */
    LLM_MODEL_SHA256("justsearch.llm.model_sha256", "JUSTSEARCH_LLM_MODEL_SHA256"),

    /** LLM GPU layer count. */
    LLM_GPU_LAYERS("justsearch.llm.gpu_layers", "JUSTSEARCH_LLM_GPU_LAYERS"),

    /** LLM request deadline in milliseconds. */
    LLM_DEADLINE_MS("justsearch.llm.deadline_ms", "JUSTSEARCH_LLM_DEADLINE_MS"),

    /** Max in-flight inferences. */
    LLM_MAX_PARALLEL("justsearch.llm.max_parallel", "JUSTSEARCH_LLM_MAX_PARALLEL"),

    /** Session pool size override. */
    LLM_MAX_SESSIONS("justsearch.llm.max_sessions", "JUSTSEARCH_LLM_MAX_SESSIONS"),

    /** Session warmup duration in milliseconds. */
    LLM_SESSION_WARMUP_MS("justsearch.llm.session_warmup_ms", "JUSTSEARCH_LLM_SESSION_WARMUP_MS"),

    /** Inference queue capacity. */
    LLM_QUEUE_CAPACITY("justsearch.llm.queue_capacity", "JUSTSEARCH_LLM_QUEUE_CAPACITY"),

    /** Fractional VRAM hard limit. */
    LLM_VRAM_FRACTION("justsearch.llm.vram_fraction", "JUSTSEARCH_LLM_VRAM_FRACTION"),

    /** Fractional projected VRAM usage. */
    LLM_VRAM_PROJECTED("justsearch.llm.vram_projected", "JUSTSEARCH_LLM_VRAM_PROJECTED"),

    /** Max concurrent backend slots. */
    LLM_MAX_SLOTS("justsearch.llm.max_slots", "JUSTSEARCH_LLM_MAX_SLOTS"),

    /** Absolute VRAM limit in bytes. */
    LLM_VRAM_LIMIT_BYTES("justsearch.llm.vram_limit_bytes", "JUSTSEARCH_LLM_VRAM_LIMIT_BYTES"),

    /** Enables dynamic VRAM autoscaling. */
    LLM_VRAM_AUTO_SCALE("justsearch.llm.vram_auto_scale", "JUSTSEARCH_LLM_VRAM_AUTO_SCALE"),

    /** Simulated backend latency in milliseconds. */
    LLM_SIMULATED_LATENCY_MS(
        "justsearch.llm.simulated_latency_ms", "JUSTSEARCH_LLM_SIMULATED_LATENCY_MS"),

    /** LLM execution thread count. */
    LLM_THREADS("justsearch.llm.threads", "JUSTSEARCH_LLM_THREADS"),

    /** LLM context window. */
    LLM_CONTEXT_LENGTH("justsearch.llm.context_length", "JUSTSEARCH_LLM_CONTEXT_LENGTH"),

    /** Max generated tokens per request. */
    LLM_MAX_NEW_TOKENS("justsearch.llm.max_new_tokens", "JUSTSEARCH_LLM_MAX_NEW_TOKENS"),

    /** Sampling temperature. */
    LLM_TEMPERATURE("justsearch.llm.temperature", "JUSTSEARCH_LLM_TEMPERATURE"),

    /** Top-p sampling parameter. */
    LLM_TOP_P("justsearch.llm.top_p", "JUSTSEARCH_LLM_TOP_P"),

    /** Min-p sampling parameter. */
    LLM_MIN_P("justsearch.llm.min_p", "JUSTSEARCH_LLM_MIN_P"),

    /** Repetition penalty value. */
    LLM_REP_PENALTY("justsearch.llm.rep_penalty", "JUSTSEARCH_LLM_REP_PENALTY"),

    /** Repetition penalty sliding window. */
    LLM_REP_WINDOW("justsearch.llm.rep_window", "JUSTSEARCH_LLM_REP_WINDOW"),

    /** Enables JSON grammar guard. */
    LLM_ENABLE_JSON_GUARD("justsearch.llm.enable_json_guard", "JUSTSEARCH_LLM_ENABLE_JSON_GUARD"),

    /** Enables thinking mode (reasoning_content parsing, --reasoning-format deepseek). Default true. */
    USE_THINKING("justsearch.llm.use_thinking", "JUSTSEARCH_USE_THINKING"),

    /** Reasoning token budget for llama-server (--reasoning-budget). Default 0 (disabled). -1 = unlimited. */
    REASONING_BUDGET("justsearch.llm.reasoning_budget", "JUSTSEARCH_REASONING_BUDGET"),

    /** Enable deterministic context compression for older agent tool outputs. */
    AGENT_CONTEXT_COMPRESSION_ENABLED(
        "justsearch.agent.context_compression.enabled",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED"),

    /** Minimum tool output size (characters) before compression is applied. */
    AGENT_CONTEXT_COMPRESSION_MIN_CHARS(
        "justsearch.agent.context_compression.min_chars",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS"),

    /** Number of most-recent tool outputs to keep uncompressed in conversation context. */
    AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS(
        "justsearch.agent.context_compression.keep_last_results",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS"),

    /** Default search result limit for the agent search tool. */
    AGENT_SEARCH_DEFAULT_LIMIT(
        "justsearch.agent.search.default_limit", "JUSTSEARCH_AGENT_SEARCH_DEFAULT_LIMIT"),

    /** Default search mode for the agent search tool (text, hybrid, vector). Empty = text. */
    AGENT_SEARCH_DEFAULT_MODE(
        "justsearch.agent.search.default_mode", "JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE"),

    /** Default max folders for the agent browse tool. */
    AGENT_BROWSE_DEFAULT_MAX_FOLDERS(
        "justsearch.agent.browse.default_max_folders",
        "JUSTSEARCH_AGENT_BROWSE_DEFAULT_MAX_FOLDERS"),

    /** Maximum characters preserved per tool result before truncation. */
    AGENT_MAX_TOOL_RESULT_CHARS(
        "justsearch.agent.max_tool_result_chars", "JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS"),

    /** Maximum completion tokens per agent LLM call (-1 = use llama-server default). */
    AGENT_MAX_COMPLETION_TOKENS(
        "justsearch.agent.max_completion_tokens", "JUSTSEARCH_AGENT_MAX_COMPLETION_TOKENS"),

    /** Template root directory. */
    LLM_TEMPLATE_ROOT("justsearch.llm.template_root", "JUSTSEARCH_LLM_TEMPLATE_ROOT"),

    /** Intent translation template filename. */
    LLM_TEMPLATE_TRANSLATE(
        "justsearch.llm.template_translate", "JUSTSEARCH_LLM_TEMPLATE_TRANSLATE"),

    /** Summary map template filename. */
    LLM_TEMPLATE_SUMMARY("justsearch.llm.template_summary", "JUSTSEARCH_LLM_TEMPLATE_SUMMARY"),

    /** Summary reduce template filename. */
    LLM_TEMPLATE_REDUCE("justsearch.llm.template_reduce", "JUSTSEARCH_LLM_TEMPLATE_REDUCE"),

    /** RNG seed used by deterministic test/dev paths. */
    LLM_RNG_SEED("justsearch.llm.rng_seed", "JUSTSEARCH_LLM_RNG_SEED"),

    /** Backend selector profile. */
    LLM_BACKEND_SELECTOR("justsearch.llm.backend_selector", "JUSTSEARCH_LLM_BACKEND_SELECTOR"),

    /** Summary chunk size in tokens. */
    LLM_SUMMARY_CHUNK_TOKENS(
        "justsearch.llm.summary_chunk_tokens", "JUSTSEARCH_LLM_SUMMARY_CHUNK_TOKENS"),

    /** Summary chunk overlap in tokens. */
    LLM_SUMMARY_CHUNK_OVERLAP(
        "justsearch.llm.summary_chunk_overlap", "JUSTSEARCH_LLM_SUMMARY_CHUNK_OVERLAP"),

    /** Allows remote LLM backend usage. */
    LLM_ALLOW_REMOTE("justsearch.llm.allow_remote", "JUSTSEARCH_LLM_ALLOW_REMOTE"),

    /** Remote backend endpoint. */
    LLM_REMOTE_ENDPOINT("justsearch.llm.remote_endpoint", "JUSTSEARCH_LLM_REMOTE_ENDPOINT"),

    /** Remote backend auth token. */
    LLM_REMOTE_AUTH_TOKEN("justsearch.llm.remote_auth_token", "JUSTSEARCH_LLM_REMOTE_AUTH_TOKEN"),

    /** Comma-separated backend task capability overrides. */
    LLM_BACKEND_SUPPORTS("justsearch.llm.backend_supports", "JUSTSEARCH_LLM_BACKEND_SUPPORTS"),

    /** Translator intent pipeline identifier. */
    TRANSLATOR_PIPELINE_INTENT(
        "justsearch.translator.pipeline.intent", "JUSTSEARCH_TRANSLATOR_PIPELINE_INTENT"),

    /** Translator embedding pipeline identifier. */
    TRANSLATOR_PIPELINE_EMBED(
        "justsearch.translator.pipeline.embed", "JUSTSEARCH_TRANSLATOR_PIPELINE_EMBED"),

    /** Translator classify pipeline identifier. */
    TRANSLATOR_PIPELINE_CLASSIFY(
        "justsearch.translator.pipeline.classify", "JUSTSEARCH_TRANSLATOR_PIPELINE_CLASSIFY"),

    /** Summary pipeline identifier. */
    SUMMARY_PIPELINE("justsearch.summary.pipeline", "JUSTSEARCH_SUMMARY_PIPELINE"),

    /** Summary max input characters before rejection. */
    SUMMARY_MAX_CHARACTERS("justsearch.summary.max_characters", "JUSTSEARCH_SUMMARY_MAX_CHARACTERS"),

    /** Summary max estimated tokens before rejection. */
    SUMMARY_MAX_TOKENS("justsearch.summary.max_tokens", "JUSTSEARCH_SUMMARY_MAX_TOKENS"),

    /** Summary too-large message key. */
    SUMMARY_MESSAGE_KEY("justsearch.summary.message_key", "JUSTSEARCH_SUMMARY_MESSAGE_KEY"),

    /** Summary queue-full message key. */
    SUMMARY_QUEUE_FULL_MESSAGE_KEY(
        "justsearch.summary.queue_full_message_key", "JUSTSEARCH_SUMMARY_QUEUE_FULL_MESSAGE_KEY"),

    /** Summary execution threads. */
    SUMMARY_EXECUTION_THREADS(
        "justsearch.summary.execution_threads", "JUSTSEARCH_SUMMARY_EXECUTION_THREADS"),

    /** Summary execution queue capacity. */
    SUMMARY_EXECUTION_QUEUE_CAPACITY(
        "justsearch.summary.execution_queue_capacity",
        "JUSTSEARCH_SUMMARY_EXECUTION_QUEUE_CAPACITY"),

    /** Embedding dimension override for worker/runtime compatibility. */
    EMBED_DIMENSION_OVERRIDE("justsearch.embed.dimension", "JUSTSEARCH_EMBED_DIM"),

    /** Embedding backend to use: "auto" (default) or "onnx". "llama" was removed in March 2026. */
    EMBED_BACKEND("justsearch.embed.backend", "JUSTSEARCH_EMBED_BACKEND"),

    /**
     * Explicit path to ONNX embedding model directory. When unset, discovery
     * resolves via {@code OnnxModelDiscovery}: {@code <modelRoot>/onnx/<modelName>/}
     * (modelName defaults to {@code gte-multilingual-base}). Set this env var
     * to override discovery and force a specific path.
     */
    EMBED_ONNX_MODEL_PATH("justsearch.embed.onnx.model_path", "JUSTSEARCH_EMBED_ONNX_MODEL_PATH"),

    /** Enable GPU acceleration for ONNX embedding inference (default false). */
    EMBED_GPU_ENABLED("justsearch.embed.gpu.enabled", "JUSTSEARCH_EMBED_GPU_ENABLED"),

    /** CUDA device ID for ONNX embedding sessions (default 0). */
    EMBED_GPU_DEVICE_ID("justsearch.embed.gpu.device_id", "JUSTSEARCH_EMBED_GPU_DEVICE_ID"),

    /** GPU memory arena limit for ONNX embedding sessions (MB, default 3072). */
    EMBED_GPU_MEM_MB("justsearch.embed.gpu_mem_mb", "JUSTSEARCH_EMBED_GPU_MEM_MB"),

    /** Enable GPU acceleration for SPLADE inference (default false). */
    SPLADE_GPU_ENABLED("justsearch.splade.gpu_enabled", "JUSTSEARCH_SPLADE_GPU_ENABLED"),

    /** CUDA device ID for SPLADE inference (default 0). */
    SPLADE_GPU_DEVICE_ID("justsearch.splade.gpu_device_id", "JUSTSEARCH_SPLADE_GPU_DEVICE_ID"),

    /** GPU memory arena limit for SPLADE sessions (MB, default 4096). */
    SPLADE_GPU_MEM_MB("justsearch.splade.gpu_mem_mb", "JUSTSEARCH_SPLADE_GPU_MEM_MB"),

    /** GPU memory arena limit for ONNX reranker sessions (MB, default 2048). */
    RERANK_GPU_MEM_MB("justsearch.rerank.gpu_mem_mb", "JUSTSEARCH_RERANK_GPU_MEM_MB", "2048"),

    /** Explicit path to the reranker model directory. */
    RERANK_MODEL_PATH("justsearch.rerank.model_path", "JUSTSEARCH_RERANK_MODEL_PATH"),

    /** Enable GPU acceleration for reranker inference (default true — reranker runs in Worker with GPU ORT JAR). */
    RERANK_GPU_ENABLED("justsearch.rerank.gpu.enabled", "JUSTSEARCH_RERANK_GPU_ENABLED", "true"),

    /** CUDA device ID for reranker inference (default 0). */
    RERANK_GPU_DEVICE_ID("justsearch.rerank.gpu.device_id", "JUSTSEARCH_RERANK_GPU_DEVICE_ID", "0"),

    /** Explicit path to the chunk reranker model directory. */
    RERANK_CHUNKS_MODEL_PATH(
        "justsearch.rerank.chunks.model_path", "JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH"),

    /** Enable GPU acceleration for chunk reranker inference (default false). */
    RERANK_CHUNKS_GPU_ENABLED(
        "justsearch.rerank.chunks.gpu.enabled", "JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED", "false"),

    /** CUDA device ID for chunk reranker inference (default 0). */
    RERANK_CHUNKS_GPU_DEVICE_ID(
        "justsearch.rerank.chunks.gpu.device_id", "JUSTSEARCH_RERANK_CHUNKS_GPU_DEVICE_ID", "0"),

    /** Search pipeline profile. */
    SEARCH_PROFILE("justsearch.search.pipeline.profile", "JUSTSEARCH_SEARCH_PROFILE"),

    /** Primary index collection override (legacy escape hatch; prefer YAML). */
    INDEX_COLLECTION("justsearch.index.collection", "JUSTSEARCH_INDEX_COLLECTION"),

    /** Index parity guard escape hatch (allow opening read-only on mismatch). */
    INDEX_PARITY_ALLOW_MISMATCH(
        "justsearch.index.parity.allow_mismatch", "JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH"),

    /** Schema mismatch policy (e.g., blue_green_migrate, rebuild_backup_first). */
    INDEX_SCHEMA_MISMATCH_POLICY(
        "index.schema_mismatch.policy", "JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY"),

    /** Migration cutover: max tolerable failed jobs before aborting (default -1 = unlimited). */
    INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS(
        "index.migration.cutover.max_failed_jobs",
        "JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS"),

    /** Explicit repository root path (overrides auto-discovery). */
    REPO_ROOT("justsearch.repo.root", "JUSTSEARCH_REPO_ROOT"),

    // ==================== AI/Inference Configuration (Fix #7) ====================

    /** JustSearch home/base directory. */
    HOME("justsearch.home", "JUSTSEARCH_HOME"),

    /** Explicit path to the Tesseract executable or containing directory for OCR extraction. */
    TESSERACT_PATH("justsearch.tesseract.path", "JUSTSEARCH_TESSERACT_PATH"),

    /** Explicit path to tessdata for OCR language packs. */
    TESSDATA_PATH("justsearch.tessdata.path", "JUSTSEARCH_TESSDATA_PATH"),

    /** Path to llama-server executable. */
    SERVER_EXE("justsearch.server.exe", "JUSTSEARCH_SERVER_EXE"),

    /** Models directory for AI model files. */
    MODELS_DIR("justsearch.models.dir", "JUSTSEARCH_MODELS_DIR"),

    /** Vision-Language Model filename. */
    VLM_MODEL("justsearch.vlm.model", "JUSTSEARCH_VLM_MODEL"),

    /** Vision projector model filename. */
    MMPROJ_MODEL("justsearch.mmproj.model", "JUSTSEARCH_MMPROJ_MODEL"),

    /**
     * VLM extraction profile name (tempdoc 580 Track D / F-009). Atomically selects the
     * (vlm-model, mmproj) pair for document extraction so a half-swap is unrepresentable:
     * {@code qwen-vl} (default — today's behavior) or {@code paddle-ocr-vl} (the F-009 pilot).
     * The per-file {@link #VLM_MODEL}/{@link #MMPROJ_MODEL} overrides still win when set.
     */
    VLM_PROFILE("justsearch.vlm.profile", "JUSTSEARCH_VLM_PROFILE"),

    /** HTTP port for llama-server. */
    SERVER_PORT("justsearch.server.port", "JUSTSEARCH_SERVER_PORT"),

    /** LLM context window size. */
    CONTEXT_SIZE("justsearch.context.size", "JUSTSEARCH_CONTEXT_SIZE"),

    /** Number of GPU layers to offload. */
    GPU_LAYERS("justsearch.gpu.layers", "JUSTSEARCH_GPU_LAYERS"),

    /** Master GPU switch for ONNX models (auto-set when CUDA detected). Per-model overrides win. */
    GPU_ENABLED("justsearch.gpu.enabled", "JUSTSEARCH_GPU_ENABLED"),

    /** GPU acceleration policy gate (true = allow GPU, false = CPU-only). */
    POLICY_GPU_ACCELERATION_ENABLED(
        "policy.gpu_acceleration_enabled",
        "JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED"),

    /** Enable/disable embedding feature independently (escape hatch; default from YAML/SSOT). */
    AI_EMBED_ENABLED("justsearch.ai.embed.enabled", "JUSTSEARCH_AI_EMBED_ENABLED"),

    /** Enable/disable classification feature independently (escape hatch; default from YAML/SSOT). */
    AI_CLASSIFY_ENABLED("justsearch.ai.classify.enabled", "JUSTSEARCH_AI_CLASSIFY_ENABLED"),

    /** Disable all AI features. */
    AI_DISABLED("justsearch.ai.disabled", "JUSTSEARCH_AI_DISABLED"),

    // ==================== RAG Configuration ====================

    /** Number of chunks to retrieve for RAG context (default 5). */
    RAG_TOP_K("justsearch.rag.top_k", "JUSTSEARCH_RAG_TOP_K"),

    /** VRAM threshold for 12GB-tier classification in bytes. */
    VRAM_THRESHOLD_12GB("justsearch.vram.threshold.12gb", "JUSTSEARCH_VRAM_THRESHOLD_12GB"),

    /** VRAM threshold for 8GB-tier classification in bytes. */
    VRAM_THRESHOLD_8GB("justsearch.vram.threshold.8gb", "JUSTSEARCH_VRAM_THRESHOLD_8GB"),

    /** VRAM threshold for 4GB-tier classification in bytes. */
    VRAM_THRESHOLD_4GB("justsearch.vram.threshold.4gb", "JUSTSEARCH_VRAM_THRESHOLD_4GB"),

    /** Cosine similarity threshold for post-hoc citation matching (default 0.5). */
    CITATION_MATCH_THRESHOLD(
        "justsearch.citation.match_threshold", "JUSTSEARCH_CITATION_MATCH_THRESHOLD"),

    /** OnnxRuntime native variant ID override. */
    ONNXRUNTIME_VARIANT_ID("justsearch.onnxruntime.variantId", "JUSTSEARCH_ONNXRUNTIME_VARIANT_ID"),

    /** ONNX Runtime native library path for CUDA provider. */
    ORT_NATIVE_PATH("justsearch.onnxruntime.native_path", "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH"),

    /** ORT per-session profile directory (diagnostic, tempdoc 397 §14.24 FB). */
    ORT_PROFILING_DIR("justsearch.ort.profiling_dir", "JUSTSEARCH_ORT_PROFILING_DIR"),

    /** ORT VERBOSE-level session logging toggle (diagnostic, tempdoc 397 §14.24 FB). */
    ORT_VERBOSE_LOGGING("justsearch.ort.verbose", "JUSTSEARCH_ORT_VERBOSE"),

    /** Search pipeline definition file path (full path override). */
    SEARCH_PIPELINE("justsearch.search.pipeline", "JUSTSEARCH_SEARCH_PIPELINE"),

    /** 306: enable/disable query classification for A/B eval (default: true via builder). */
    SEARCH_QUERY_CLASSIFICATION_ENABLED(
        "justsearch.search.query_classification.enabled",
        "JUSTSEARCH_SEARCH_QUERY_CLASSIFICATION_ENABLED"),
    /** 306: title field boost in BM25 DisjunctionMaxQuery (default: 3.0 via builder, 0 to disable). */
    SEARCH_TITLE_BOOST("justsearch.search.title_boost", "JUSTSEARCH_SEARCH_TITLE_BOOST"),

    /** 326: NER entity field boost in BM25 DisjunctionMaxQuery (default: 2.0, 0 to disable). */
    SEARCH_ENTITY_BOOST("justsearch.search.entity_boost", "JUSTSEARCH_SEARCH_ENTITY_BOOST"),

    /** 343: enable/disable chunk-aware merge in search (default: true via builder). */
    SEARCH_CHUNK_AWARE_ENABLED(
        "search.chunk_aware.enabled", "JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED"),

    /** Translator repo root override (for local/dev model assets). */
    TRANSLATOR_REPO_ROOT("justsearch.translator.repoRoot", "JUSTSEARCH_TRANSLATOR_REPO_ROOT"),

    /** UI automation mode enabled flag. */
    UI_AUTOMATION_ENABLED("justsearch.ui.automation.enabled", "JUSTSEARCH_UI_AUTOMATION"),

    /** UI automation: require translator even in automation mode. */
    UI_AUTOMATION_REQUIRE_TRANSLATOR(
        "justsearch.ui.automation.requireTranslator",
        "JUSTSEARCH_UI_AUTOMATION_REQUIRE_TRANSLATOR"),

    /** UI automation: force infra diagnostics overrides. */
    UI_AUTOMATION_FORCE_DIAGNOSTICS(
        "justsearch.ui.automation.forceDiagnostics",
        "JUSTSEARCH_UI_AUTOMATION_FORCE_DIAGNOSTICS"),

    /** UI settings persistence mode (read-only, in-memory, etc.). */
    UI_SETTINGS_MODE("justsearch.ui.settings.mode", "JUSTSEARCH_UI_SETTINGS_MODE"),

    /** Source of server executable selection (environment_variable / operator / etc.). */
    SERVER_EXE_SOURCE("justsearch.server.exe.source", "JUSTSEARCH_SERVER_EXE_SOURCE"),

    /** LambdaMART reranker enabled flag (default: true when model file exists). */
    LAMBDAMART_ENABLED("justsearch.lambdamart.enabled", "JUSTSEARCH_LAMBDAMART_ENABLED"),

    // ==================== NER Configuration ====================

    /** Enable NER extraction (auto if model found). */
    NER_ENABLED("justsearch.ner.enabled", "JUSTSEARCH_NER_ENABLED"),

    /** Explicit path to NER model directory. */
    NER_MODEL_PATH("justsearch.ner.model_path", "JUSTSEARCH_NER_MODEL_PATH"),

    /** Max token sequence length for NER inference (default 512). */
    NER_MAX_SEQ_LEN("justsearch.ner.max_seq_len", "JUSTSEARCH_NER_MAX_SEQ_LEN"),

    /** NER confidence threshold (default 0.5). */
    NER_CONFIDENCE_THRESHOLD(
        "justsearch.ner.confidence_threshold", "JUSTSEARCH_NER_CONFIDENCE_THRESHOLD"),

    /** Enable GPU acceleration for NER inference. */
    NER_GPU_ENABLED("justsearch.ner.gpu_enabled", "JUSTSEARCH_NER_GPU_ENABLED"),

    /** CUDA device index for NER GPU inference (default 0). */
    NER_GPU_DEVICE_ID("justsearch.ner.gpu_device_id", "JUSTSEARCH_NER_GPU_DEVICE_ID"),

    /** GPU memory arena limit in MB for NER inference (default 512). */
    NER_GPU_MEM_MB("justsearch.ner.gpu_mem_mb", "JUSTSEARCH_NER_GPU_MEM_MB"),

    // ==================== Extraction Sandbox Configuration ====================

    /**
     * Worker extraction sandbox mode. Values: {@code in_process} (default) or {@code process}.
     * In {@code process} mode the Worker requires a non-blank {@link
     * #EXTRACTION_SANDBOX_COMMAND} listing the child JVM command to spawn (whitespace-split
     * argv). See tempdoc 410 for the failure-domain design.
     */
    EXTRACTION_SANDBOX_MODE(
        "justsearch.extraction.sandbox.mode", "JUSTSEARCH_EXTRACTION_SANDBOX_MODE"),

    /**
     * Whitespace-separated command used by the {@code process} sandbox mode to spawn the
     * extraction child JVM. Required when {@link #EXTRACTION_SANDBOX_MODE} is {@code process};
     * ignored otherwise.
     */
    EXTRACTION_SANDBOX_COMMAND(
        "justsearch.extraction.sandbox.command", "JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND"),

    // ==================== Ingestion Skip Policy (tempdoc 410 §13) ====================

    /**
     * Comma-separated lowercase file-name fragments treated as skip patterns by
     * {@code IngestionSkipPolicy}. Operator override; defaults to the built-in system/junk set
     * ({@code thumbs.db}, {@code .ds_store}, {@code desktop.ini}, {@code .git}, {@code .svn},
     * {@code $recycle.bin}). The default applies when this key is unset; setting it replaces the
     * defaults wholesale.
     */
    INGESTION_SKIP_PATTERNS(
        "justsearch.ingestion.skip.patterns", "JUSTSEARCH_INGESTION_SKIP_PATTERNS"),

    /**
     * Comma-separated lowercase file extensions (no leading dot) treated as build/cache output by
     * {@code IngestionSkipPolicy}. Defaults to {@code pyc,pyo,class,o,obj}.
     */
    INGESTION_SKIP_EXTENSIONS(
        "justsearch.ingestion.skip.extensions", "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS"),

    /**
     * Comma-separated lowercase directory basenames a tree walk should never descend into.
     * Defaults to the standard VCS / cache / recycle-bin set ({@code .git}, {@code .svn},
     * {@code .hg}, {@code .bzr}, {@code cvs}, {@code node_modules}, {@code bower_components},
     * {@code __pycache__}, {@code .tox}, {@code .pytest_cache}, {@code .mypy_cache},
     * {@code $recycle.bin}, {@code system volume information}).
     */
    INGESTION_SKIP_DIRECTORY_NAMES(
        "justsearch.ingestion.skip.directory_names",
        "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES"),

    /**
     * Lite mode for ingestion-only test scenarios (tempdoc 419 T6.1). When {@code true} the
     * Head process skips InferenceLifecycleManager initialization (the AI stack), cascading
     * through the existing {@code OnlineAiService.unavailable()} fallback in
     * {@code HeadAssembly}. Equivalent in effect to {@code JUSTSEARCH_AI_DISABLED=true}
     * but named for the test-harness use case so future test-mode skips have an obvious home.
     *
     * <p>Saves ~3-8s of startup time depending on hardware (avoids llama-server probe and
     * model-file checks). Used by the per-class isolated test backend fixture
     * ({@code IsolatedBackendFixture}, T6.2) so integration tests can spawn a backend in
     * single-digit seconds.
     */
    LITE_MODE("justsearch.lite.mode", "JUSTSEARCH_LITE_MODE", "false"),

    /**
     * Retention window (in days) for entries in the {@code path_resolution} table after a
     * file's deletion has been observed. ADR-0028 / tempdoc 419 T5.1. After this window
     * elapses, rows with non-null {@code removed_at} are pruned by the periodic job-cleanup
     * task. The default of 90 days lets the activity panel still answer "this file was
     * deleted on X" for recently-removed entries while keeping the table size bounded.
     * Removing a watched root immediately prunes everything under it regardless of retention.
     */
    PATH_RESOLUTION_RETENTION_DAYS(
        "justsearch.path_resolution.retention_days",
        "JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS",
        "90"),

    // ==================== VDU Configuration ====================

    /**
     * Quality score threshold for VDU routing. PDFs with extraction quality
     * below this threshold are queued for VLM re-extraction. Range: 0.0–1.0.
     * Default 0.3 (conservative). Experiments showed VLM improves pages up to 0.7.
     */
    VDU_QUALITY_THRESHOLD(
        "justsearch.vdu.quality_threshold", "JUSTSEARCH_VDU_QUALITY_THRESHOLD"),

    // ==================== SPLADE Configuration ====================

    /** Enable SPLADE sparse retrieval (auto if model found). */
    SPLADE_ENABLED("justsearch.splade.enabled", "JUSTSEARCH_SPLADE_ENABLED"),

    /** Explicit path to SPLADE model directory. */
    SPLADE_MODEL_PATH("justsearch.splade.model_path", "JUSTSEARCH_SPLADE_MODEL_PATH"),

    /** Max token sequence length for SPLADE inference (default 512). */
    SPLADE_MAX_SEQ_LEN("justsearch.splade.max_seq_len", "JUSTSEARCH_SPLADE_MAX_SEQ_LEN"),

    /** SPLADE query encoding mode: "onnx" (neural) or "idf" (IDF-weighted lookup). */
    SPLADE_QUERY_MODE("justsearch.splade.query_mode", "JUSTSEARCH_SPLADE_QUERY_MODE"),

    /** SPLADE post-processing activation: "log1p" or "double_log1p". */
    SPLADE_ACTIVATION("justsearch.splade.activation", "JUSTSEARCH_SPLADE_ACTIVATION"),

    /** Path to SPLADE truncation evidence directory. */
    SPLADE_EVIDENCE_PATH("justsearch.splade.evidence_path", "JUSTSEARCH_SPLADE_EVIDENCE_PATH"),

    // ==================== BGE-M3 Configuration ====================

    /** Sparse retrieval model selection: "splade" (default) or "bge-m3". */
    SPARSE_MODEL("justsearch.sparse_model", "JUSTSEARCH_SPARSE_MODEL"),

    /** Enable BGE-M3 sparse+dense retrieval (auto if model found). */
    BGE_M3_ENABLED("justsearch.bgem3.enabled", "JUSTSEARCH_BGE_M3_ENABLED"),

    /** Explicit path to BGE-M3 model directory. */
    BGE_M3_MODEL_PATH("justsearch.bgem3.model_path", "JUSTSEARCH_BGE_M3_MODEL_PATH"),

    /** Max token sequence length for BGE-M3 inference (default 8192). */
    BGE_M3_MAX_SEQ_LEN("justsearch.bgem3.max_seq_len", "JUSTSEARCH_BGE_M3_MAX_SEQ_LEN"),

    /** Enable GPU acceleration for BGE-M3 inference (default false). */
    BGE_M3_GPU_ENABLED("justsearch.bgem3.gpu_enabled", "JUSTSEARCH_BGE_M3_GPU_ENABLED"),

    /** CUDA device ID for BGE-M3 inference (default 0). */
    BGE_M3_GPU_DEVICE_ID("justsearch.bgem3.gpu_device_id", "JUSTSEARCH_BGE_M3_GPU_DEVICE_ID"),

    /** GPU memory arena limit for BGE-M3 sessions (MB, default 3072). */
    BGE_M3_GPU_MEM_MB("justsearch.bgem3.gpu_mem_mb", "JUSTSEARCH_BGE_M3_GPU_MEM_MB"),

    // ==================== Reranker Configuration ====================

    /** Enable document reranking (auto if model found). */
    RERANK_ENABLED("justsearch.rerank.enabled", "JUSTSEARCH_RERANK_ENABLED"),

    /** Top-K documents to rerank (default 20). */
    RERANK_TOP_K("justsearch.rerank.top_k", "JUSTSEARCH_RERANK_TOP_K", "20"),

    /** Reranker deadline in milliseconds (default 200). */
    RERANK_DEADLINE_MS("justsearch.rerank.deadline_ms", "JUSTSEARCH_RERANK_DEADLINE_MS", "200"),

    /** Minimum hits before reranking is attempted (default 5). */
    RERANK_MIN_HITS("justsearch.rerank.min_hits", "JUSTSEARCH_RERANK_MIN_HITS", "5"),

    /** Max token sequence length for reranker inference (default 512; model supports 8192 but O(n²) attention cost and GPU VRAM make that impractical). */
    RERANK_MAX_SEQ_LEN("justsearch.rerank.max_seq_len", "JUSTSEARCH_RERANK_MAX_SEQ_LEN", "512"),

    /** Max average document length in chars for reranker eligibility (default 16000). */
    RERANK_MAX_AVG_DOC_LENGTH_CHARS(
        "justsearch.rerank.max_avg_doc_length_chars",
        "JUSTSEARCH_RERANK_MAX_AVG_DOC_LENGTH_CHARS",
        "16000"),

    /** Enable chunk reranking (auto if model found). */
    RERANK_CHUNKS_ENABLED(
        "justsearch.rerank.chunks.enabled", "JUSTSEARCH_RERANK_CHUNKS_ENABLED"),

    /** Top-K chunks to rerank (default 10). */
    RERANK_CHUNKS_TOP_K("justsearch.rerank.chunks.top_k", "JUSTSEARCH_RERANK_CHUNKS_TOP_K", "10"),

    /** Max GPU candidates for chunk reranker (default 50). */
    RERANK_CHUNKS_MAX_GPU_CANDIDATES(
        "justsearch.rerank.chunks.max_gpu_candidates",
        "JUSTSEARCH_RERANK_CHUNKS_MAX_GPU_CANDIDATES",
        "50"),

    /** Chunk reranker deadline in milliseconds (default 150). */
    RERANK_CHUNKS_DEADLINE_MS(
        "justsearch.rerank.chunks.deadline_ms", "JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS", "150"),

    /** Minimum hits before chunk reranking is attempted (default 3). */
    RERANK_CHUNKS_MIN_HITS(
        "justsearch.rerank.chunks.min_hits", "JUSTSEARCH_RERANK_CHUNKS_MIN_HITS", "3"),

    /** Max token sequence length for chunk reranker (default 512). */
    RERANK_CHUNKS_MAX_SEQ_LEN(
        "justsearch.rerank.chunks.max_seq_len", "JUSTSEARCH_RERANK_CHUNKS_MAX_SEQ_LEN", "512"),

    /** Chunk reranker result ordering: "auto", "score", or "position" (default "auto"). */
    RERANK_CHUNKS_ORDER("justsearch.rerank.chunks.order", "JUSTSEARCH_RERANK_CHUNKS_ORDER", "auto"),

    // ==================== Citation Scorer Configuration ====================

    /** Enable citation scorer (auto if model found). */
    CITATION_SCORER_ENABLED(
        "justsearch.citation.scorer.enabled", "JUSTSEARCH_CITATION_SCORER_ENABLED"),

    /** Explicit path to citation scorer model directory. */
    CITATION_SCORER_MODEL_PATH(
        "justsearch.citation.scorer.model_path", "JUSTSEARCH_CITATION_SCORER_MODEL_PATH"),

    /** Citation scorer confidence threshold (default 0.5). */
    CITATION_SCORER_THRESHOLD(
        "justsearch.citation.scorer.threshold", "JUSTSEARCH_CITATION_SCORER_THRESHOLD"),

    /** Max token sequence length for citation scorer (default 512). */
    CITATION_SCORER_MAX_SEQ_LEN(
        "justsearch.citation.scorer.max_seq_len", "JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN"),

    /** Citation scorer deadline in milliseconds (default 2000). */
    CITATION_SCORER_DEADLINE_MS(
        "justsearch.citation.scorer.deadline_ms", "JUSTSEARCH_CITATION_SCORER_DEADLINE_MS"),

    // ==================== Misc Subsystem Configuration ====================

    /** Embedding context length override (default 2048). */
    EMBED_CONTEXT_LENGTH(
        "justsearch.embed.context_length", "JUSTSEARCH_EMBED_CONTEXT_LENGTH"),

    /** GPL revalidation size factor (default 2.0). */
    GPL_REEVAL_SIZE_FACTOR(
        "justsearch.gpl.reeval_size_factor", "JUSTSEARCH_GPL_REEVAL_SIZE_FACTOR"),

    // ==================== Worker AI Connection (tempdoc 314 C1) ====================

    /** Whether the AI worker gRPC client is enabled. */
    AI_ENABLED("workers.ai.enabled", "JUSTSEARCH_AI_ENABLED"),

    /** AI worker client host (gRPC connection from Head). */
    AI_HOST("justsearch.ai.host", "JUSTSEARCH_AI_HOST"),

    /** AI worker client port (gRPC connection from Head). */
    AI_PORT("justsearch.ai.port", "JUSTSEARCH_AI_PORT"),

    /** AI worker client deadline (ms). */
    AI_DEADLINE_MS("justsearch.ai.deadlineMs", "JUSTSEARCH_AI_DEADLINE_MS"),

    // ==================== Worker Indexer Connection (tempdoc 314 C1) ====================

    /** Whether the indexer worker gRPC client is enabled. */
    INDEXER_ENABLED("workers.indexer.enabled", "JUSTSEARCH_INDEXER_ENABLED"),

    /** Indexer worker client host (gRPC connection from Head). */
    INDEXER_HOST("justsearch.indexer.host", "JUSTSEARCH_INDEXER_HOST"),

    /** Indexer worker client port (gRPC connection from Head). */
    INDEXER_PORT("justsearch.indexer.port", "JUSTSEARCH_INDEXER_PORT"),

    /** Indexer worker client deadline (ms). */
    INDEXER_DEADLINE_MS("justsearch.indexer.deadlineMs", "JUSTSEARCH_INDEXER_DEADLINE_MS"),

    /** Indexer worker ingest queue size. */
    INDEXER_QUEUE_SIZE("justsearch.indexer.queueSize", "JUSTSEARCH_INDEXER_QUEUE_SIZE"),

    /** Indexer worker max in-flight bytes. */
    INDEXER_MAX_INFLIGHT_BYTES(
        "justsearch.indexer.maxInFlightBytes", "JUSTSEARCH_INDEXER_MAX_INFLIGHT_BYTES"),

    // ==================== Translator Health (tempdoc 314 C1) ====================

    /** Translator health check refresh interval (ms). */
    TRANSLATOR_REFRESH_INTERVAL_MS(
        "translator.health.refreshIntervalMs", "JUSTSEARCH_TRANSLATOR_REFRESH_INTERVAL_MS"),

    /** Translator health check max backoff (ms). */
    TRANSLATOR_MAX_BACKOFF_MS(
        "translator.health.maxBackoffMs", "JUSTSEARCH_TRANSLATOR_MAX_BACKOFF_MS"),

    /** Translator health check staleness alert threshold (seconds). */
    TRANSLATOR_STALENESS_ALERT_SECONDS(
        "translator.health.stalenessAlertSeconds", "JUSTSEARCH_TRANSLATOR_STALENESS_ALERT_SECONDS"),

    // ==================== Infra Health (tempdoc 314 Phase F) ====================

    /** Infra health gRPC server host. */
    INFRA_HEALTH_HOST("justsearch.infra.health.host", "JUSTSEARCH_INFRA_HEALTH_HOST"),
    /** Infra health gRPC server port. */
    INFRA_HEALTH_PORT("justsearch.infra.health.port", "JUSTSEARCH_INFRA_HEALTH_PORT"),

    // ==================== Language Configuration (tempdoc 314 Phase F) ====================

    /** Default index language (BCP-47 tag). */
    INDEX_DEFAULT_LANGUAGE("justsearch.index.default_language", "JUSTSEARCH_INDEX_DEFAULT_LANGUAGE"),

    // ==================== Indexing Tracing (tempdoc 312 Phase 0) ====================

    /** Indexing pipeline tracing level: none (default), sample (1%), detailed (100%). */
    INDEX_TRACING_LEVEL("justsearch.index.tracing_level", "JUSTSEARCH_INDEX_TRACING_LEVEL"),

    /**
     * Head process tracing level: none (default), sample (1%), detailed (100%). Tempdoc 518
     * Appendix G W4.2 — when non-none, the head's {@code HeadlessApp} initializes a
     * {@link io.justsearch.telemetry.TracingBootstrap} so the existing span-authoring code
     * ({@code AgentLoopService}, {@code KnowledgeHttpApiAdapter}) emits to the NDJSON
     * exporter + optional OTLP fan-out. Required for tempdoc 518's
     * {@code justsearch.inference.generation} span attribute to attach to exported spans.
     */
    HEAD_TRACING_LEVEL("justsearch.head.tracing_level", "JUSTSEARCH_HEAD_TRACING_LEVEL"),
    /** Search language filter policy. */
    SEARCH_LANGUAGE_POLICY(
        "justsearch.search.default_language_policy", "JUSTSEARCH_SEARCH_DEFAULT_LANGUAGE_POLICY"),

    // ==================== Dev Hot-Reload (tempdoc 305 Phase 2) ====================

    /** Enables dev hot-reload service restart on recompile (default false). */
    DEV_HOTRELOAD("justsearch.dev.hotreload", "JUSTSEARCH_DEV_HOTRELOAD"),

    /** Path to worker-services classes directory (dev only). */
    DEV_HOTRELOAD_CLASSES_DIR(
        "justsearch.dev.hotreload.classesDir", "JUSTSEARCH_DEV_HOTRELOAD_CLASSES_DIR"),

    /** JDWP debug port for HotSwapPush bytecode updates (default 5005). */
    DEV_DEBUG_PORT("justsearch.dev.debug.port", "JUSTSEARCH_DEV_DEBUG_PORT"),

    /** 371: Content hash of the Worker distribution (stale-JVM detection). */
    BUILD_STAMP("justsearch.build.stamp", "JUSTSEARCH_BUILD_STAMP"),

    /**
     * Tempdoc 606 Piece 2b: content hash of the Head distribution the dev-runner launched.
     * Injected by the dev-runner ({@code -Djustsearch.head.stamp}); echoed on
     * {@code /api/runtime/manifest} (HeadInfo.buildStamp) so a dev tool can detect a stale
     * Head answering on a reused port. Dev-only; absent in production.
     */
    HEAD_BUILD_STAMP("justsearch.head.stamp", "JUSTSEARCH_HEAD_BUILD_STAMP"),

    // ==================== Worker Bootstrap (tempdoc 329) ====================

    /** Path to worker config snapshot JSON (set by HeadlessApp at runtime). */
    WORKER_CONFIG_SNAPSHOT(
        "justsearch.worker.config_snapshot", "JUSTSEARCH_WORKER_CONFIG_SNAPSHOT"),

    /**
     * Main (Head) process PID, forwarded Head→Worker so the Worker can probe Head liveness and
     * distinguish a real Head death from a benign OS-resume stale heartbeat (tempdoc 630). Absent
     * on standalone worker runs, where the Worker falls back to heartbeat-only suicide.
     */
    HEAD_PID("justsearch.head.pid", "JUSTSEARCH_HEAD_PID"),

    /**
     * Dev/test override for the OS energy-intent poll (tempdoc 630): {@code reduced} or {@code full}
     * forces the energy state, bypassing the {@code GetSystemPowerStatus} probe so the throttle +
     * "Paused" UI can be exercised on AC / without toggling Windows Energy Saver. Empty = probe.
     */
    POWER_FORCE_ENERGY_STATE("justsearch.power.force_energy_state", "JUSTSEARCH_POWER_FORCE_ENERGY_STATE"),

    // ==================== Index Vector HNSW (tempdoc 347 D2: sysProp = configKey) ====================

    /** HNSW M parameter (number of connections per node). */
    INDEX_VECTOR_HNSW_M("index.vector.hnsw.m", "JUSTSEARCH_INDEX_VECTOR_HNSW_M"),
    /** HNSW ef_construction parameter. */
    INDEX_VECTOR_HNSW_EF_CONSTRUCTION("index.vector.hnsw.ef_construction",
        "JUSTSEARCH_INDEX_VECTOR_HNSW_EF_CONSTRUCTION"),
    /** HNSW ef_search parameter for query-time. */
    INDEX_VECTOR_EF_SEARCH("index.vector.ef_search", "JUSTSEARCH_INDEX_VECTOR_EF_SEARCH"),
    /** Enable vector quantization for index storage. */
    INDEX_VECTOR_QUANTIZATION_ENABLED("index.vector.quantization.enabled",
        "JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED"),

    // ==================== RAG Pipeline (tempdoc 347 D2: sysProp = configKey) ====================

    /** RAG retrieval mode (hybrid, vector, text). */
    RAG_RETRIEVE_MODE("rag.retrieve.mode", "JUSTSEARCH_RAG_RETRIEVE_MODE"),
    /** RAG retrieval top-K candidates. */
    RAG_RETRIEVE_TOP_K("rag.retrieve.top_k", "JUSTSEARCH_RAG_RETRIEVE_TOP_K"),
    /** RAG over-retrieval factor for diverse sampling. */
    RAG_OVERRETRIEVE_FACTOR("rag.retrieve.overretrieve_factor", "JUSTSEARCH_RAG_OVERRETRIEVE_FACTOR"),
    /** RAG diversification mode (none, mmr). */
    RAG_DIVERSIFY_MODE("rag.diversify.mode", "JUSTSEARCH_RAG_DIVERSIFY_MODE"),
    /** MMR lambda parameter for diversity-relevance trade-off. */
    RAG_MMR_LAMBDA("rag.mmr.lambda", "JUSTSEARCH_RAG_MMR_LAMBDA"),
    /** MMR max candidate pool size. */
    RAG_MMR_MAX_CANDIDATES("rag.mmr.max_candidates", "JUSTSEARCH_RAG_MMR_MAX_CANDIDATES"),
    /** Include surrounding document context in RAG chunks. */
    RAG_INCLUDE_SURROUNDING("rag.context.include_surrounding",
        "JUSTSEARCH_RAG_INCLUDE_SURROUNDING_CONTEXT"),
    /** Enable chunk-level vector retrieval for RAG. */
    RAG_CHUNK_VECTORS_ENABLED("rag.chunk_vectors.enabled", "JUSTSEARCH_RAG_CHUNK_VECTORS_ENABLED"),

    // ==================== Worker Limits (tempdoc 347 D2: sysProp = configKey) ====================

    /** Max batch size for indexing operations. */
    WORKER_MAX_BATCH_SIZE("worker.limits.max_batch_size", "JUSTSEARCH_WORKER_MAX_BATCH_SIZE"),
    /** Max queue depth for pending indexing jobs. */
    WORKER_MAX_QUEUE_DEPTH("worker.limits.max_queue_depth", "JUSTSEARCH_WORKER_MAX_QUEUE_DEPTH"),
    /** Max content length per document (characters). */
    WORKER_MAX_CONTENT_LENGTH("worker.limits.max_content_length",
        "JUSTSEARCH_WORKER_MAX_CONTENT_LENGTH"),
    /** Max file size for ingestion (bytes). */
    WORKER_MAX_FILE_SIZE("worker.limits.max_file_size", "JUSTSEARCH_WORKER_MAX_FILE_SIZE"),

    // ==================== Hybrid Search (tempdoc 347 D2: sysProp = configKey) ====================

    /** RRF K constant for reciprocal rank fusion. */
    HYBRID_RRF_K("index.hybrid.rrf_k", "JUSTSEARCH_INDEX_RRF_K"),
    /** Min chars before vector search is skipped (short-query optimization). */
    HYBRID_VECTOR_SKIP_MIN_CHARS("index.hybrid.vector_skip_min_chars",
        "JUSTSEARCH_INDEX_VECTOR_SKIP_MIN_CHARS"),
    /** Max candidate limit for hybrid search results. */
    HYBRID_CANDIDATE_LIMIT_MAX("index.hybrid.candidate_limit_max",
        "JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX"),
    /** Text candidate multiplier for over-retrieval. */
    HYBRID_TEXT_CANDIDATE_MULTIPLIER("index.hybrid.text_candidate_multiplier",
        "JUSTSEARCH_INDEX_HYBRID_TEXT_CANDIDATE_MULTIPLIER"),
    /** Vector candidate multiplier for over-retrieval. */
    HYBRID_VECTOR_CANDIDATE_MULTIPLIER("index.hybrid.vector_candidate_multiplier",
        "JUSTSEARCH_INDEX_HYBRID_VECTOR_CANDIDATE_MULTIPLIER"),
    /** Vector RRF weight in normal-signal fusion. */
    HYBRID_VECTOR_RRF_WEIGHT("index.hybrid.vector_rrf_weight",
        "JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT"),
    /** BM25 score boost weight. */
    HYBRID_BM25_SCORE_BOOST_WEIGHT("index.hybrid.bm25_score_boost_weight",
        "JUSTSEARCH_INDEX_BM25_SCORE_BOOST_WEIGHT"),
    /** Vector low-signal top score threshold. */
    HYBRID_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD("index.hybrid.vector_low_signal_top_score_threshold",
        "JUSTSEARCH_INDEX_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD"),
    /** BM25 low-signal top score threshold. */
    HYBRID_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD("index.hybrid.bm25_low_signal_top_score_threshold",
        "JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD"),
    /** BM25 low-signal total hits threshold. */
    HYBRID_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD("index.hybrid.bm25_low_signal_total_hits_threshold",
        "JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD"),
    /** Vector-only cap for low-signal scenarios. */
    HYBRID_VECTOR_ONLY_CAP_LOW_SIGNAL("index.hybrid.vector_only_cap_low_signal",
        "JUSTSEARCH_INDEX_VECTOR_ONLY_CAP_LOW_SIGNAL"),
    /** Vector RRF weight in low-signal scenarios. */
    HYBRID_VECTOR_RRF_WEIGHT_LOW_SIGNAL("index.hybrid.vector_rrf_weight_low_signal",
        "JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT_LOW_SIGNAL"),
    /** Fusion strategy (rrf, cc). */
    HYBRID_FUSION_STRATEGY("index.hybrid.fusion_strategy", "JUSTSEARCH_HYBRID_FUSION_STRATEGY"),
    /** CC fusion alpha parameter. */
    HYBRID_CC_ALPHA("index.hybrid.cc_alpha", "JUSTSEARCH_HYBRID_CC_ALPHA"),
    /** CC fusion: exclude zero-score channels. */
    HYBRID_CC_ZERO_EXCLUDE("index.hybrid.cc_zero_exclude", "JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE"),
    /** CC fusion: sparse channel weight. */
    HYBRID_CC_WEIGHT_SPARSE("index.hybrid.cc_weight_sparse", "JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE"),
    /** CC fusion: dense channel weight. */
    HYBRID_CC_WEIGHT_DENSE("index.hybrid.cc_weight_dense", "JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE"),
    /** CC fusion: SPLADE channel weight. */
    HYBRID_CC_WEIGHT_SPLADE("index.hybrid.cc_weight_splade", "JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE"),
    /** Tempdoc 580 §13.3: per-query adaptive CC-weight selection (default off). */
    HYBRID_ADAPTIVE_WEIGHTS_ENABLED(
        "index.hybrid.adaptive_weights_enabled", "JUSTSEARCH_HYBRID_ADAPTIVE_WEIGHTS_ENABLED"),
    /**
     * Tempdoc 636 Design v2: per-query leg arbitration — raise the 2-way CC dense weight (alpha)
     * when dense is bounded-confident AND the legs diverge (low cross-leg rank overlap), so the
     * lexical leg cannot suppress a confident dense answer on grep-defeating paraphrase queries
     * (default off; static alpha wins).
     */
    HYBRID_LEG_ARBITRATION_ENABLED(
        "index.hybrid.leg_arbitration_enabled", "JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED"),
    /** Tempdoc 636 Design v2: dense weight (CC alpha) applied when leg arbitration fires. */
    HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE(
        "index.hybrid.leg_arbitration_alpha_diverge",
        "JUSTSEARCH_HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE"),
    /**
     * Tempdoc 636 review fix: BM25 top2/top1 ratio at/above which BM25 counts as "incoherent" (flat
     * top, no clear lexical winner) — leg arbitration only fires when BM25 is incoherent, so a
     * peaked BM25 winner on BM25-dominant corpora (legal/email) is not down-weighted.
     */
    HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN(
        "index.hybrid.leg_arbitration_bm25_incoherence_min",
        "JUSTSEARCH_HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN"),
    /**
     * Tempdoc 636 Design v3: recall-complete rerank pool — guarantee each retrieval leg's top-N
     * candidates survive into the returned list (the cross-encoder's rerank window), so a confident
     * dense answer that fused-score truncation would bury still reaches the relevance model. Unlike
     * leg arbitration (v2) it never down-weights a leg, so it is keyword-neutral by construction
     * (default off).
     */
    HYBRID_RERANK_POOL_RECALL_COMPLETE(
        "index.hybrid.leg_recall_complete_enabled",
        "JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE"),
    /** Tempdoc 636 Design v3: per-leg top-N guaranteed into the recall-complete rerank pool. */
    HYBRID_RERANK_POOL_TOP_N(
        "index.hybrid.leg_recall_complete_top_n", "JUSTSEARCH_HYBRID_RERANK_POOL_TOP_N"),
    /** Branch-level fusion strategy. */
    HYBRID_BRANCH_FUSION_STRATEGY("index.hybrid.branch_fusion_strategy",
        "JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY"),
    /** Branch CC fusion: exclude zero-score branches. */
    HYBRID_BRANCH_CC_ZERO_EXCLUDE("index.hybrid.branch_cc_zero_exclude",
        "JUSTSEARCH_HYBRID_BRANCH_CC_ZERO_EXCLUDE"),
    /** Branch CC fusion: whole-doc weight. */
    HYBRID_BRANCH_CC_WEIGHT_WHOLE("index.hybrid.branch_cc_weight_whole",
        "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE"),
    /** Branch CC fusion: chunk weight. */
    HYBRID_BRANCH_CC_WEIGHT_CHUNK("index.hybrid.branch_cc_weight_chunk",
        "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK"),
    /** Branch chunk minimum weight multiplier. */
    HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER("index.hybrid.branch_chunk_min_weight_multiplier",
        "JUSTSEARCH_HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER"),
    /**
     * UI exclude patterns (JSON array string). User-side preference, written by the SettingsController
     * Apply Excludes flow and read by IndexingController.applyExcludes + KnowledgeSearchController
     * for search-time exclusion. Tempdoc 519 §9 Block B3.0.b promoted this from raw
     * System.getProperty to EnvRegistry to satisfy the checkNoDirectJustsearchSysProp build gate
     * after ExcludeGlobs moved to app-services.
     */
    UI_EXCLUDE_PATTERNS("justsearch.ui.exclude_patterns", "JUSTSEARCH_UI_EXCLUDE_PATTERNS"),
    /**
     * UI settings read-only flag. When true, the UiSettingsStore operates in IN_MEMORY mode
     * regardless of other settings (no disk persistence). Tempdoc 519 §9 Block B3.0.d:
     * promoted from raw System.getenv access after UiSettingsStore moved to app-services.
     */
    UI_SETTINGS_READONLY("justsearch.ui.settings.readOnly", "JUSTSEARCH_UI_SETTINGS_READONLY"),
    /**
     * Rule-engine tick interval (milliseconds). Controls how often the RuleRunner evaluates
     * rules. Tempdoc 519 §10 endpoint: extracted from the bootstrap's
     * RuleRunnerBuilder helper to satisfy the checkNoDirectJustsearchSysProp build gate
     * after the rule-engine wiring was lifted into a phase-helper class.
     */
    RULE_TICK_MS("justsearch.rule.tick.ms", "JUSTSEARCH_RULE_TICK_MS");

    // YAML-only keys moved to ConfigKey.java (tempdoc 347 D1).

    private final String sysProp;
    private final String envVar;
    private final String defaultValue;

    EnvRegistry(String sysProp, String envVar) {
        this(sysProp, envVar, null);
    }

    EnvRegistry(String sysProp, String envVar, String defaultValue) {
        this.sysProp = Objects.requireNonNull(sysProp);
        this.envVar = Objects.requireNonNull(envVar);
        this.defaultValue = defaultValue;
    }

    /** Returns the system property name. */
    public String sysProp() {
        return sysProp;
    }

    /** Returns the environment variable name. */
    public String envVar() {
        return envVar;
    }

    /**
     * Returns the default value for this entry, or null if no default is defined.
     *
     * <p>Used by {@code ResolvedConfigBuilder} to register a programmatic default at ordinal 100.
     * Defaults defined here centralize values that were previously scattered across call sites.
     */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * Returns the config key used in the {@code ResolvedConfigBuilder} ordinal chain.
     *
     * <p>Always equals {@code sysProp()}. The separate {@code configKey} field was removed in
     * tempdoc 347 D2 by standardizing all sysProp names to match the ordinal chain key.
     */
    public String configKey() {
        return sysProp;
    }

    /**
     * Gets the raw string value, or empty if not set.
     *
     * @return the configured value, or empty
     */
    public Optional<String> get() {
        String val = System.getProperty(sysProp);
        if (val != null && !val.isBlank()) {
            return Optional.of(val);
        }
        val = System.getenv(envVar);
        if (val != null && !val.isBlank()) {
            return Optional.of(val);
        }
        return Optional.empty();
    }

    /**
     * Gets the string value, or the provided default.
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default value
     */
    public String getString(String defaultValue) {
        return get().orElse(defaultValue);
    }

    /**
     * Gets the value as an integer, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public int getInt(int defaultValue) {
        return get().map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a long, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public long getLong(long defaultValue) {
        return get().map(s -> {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a double, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public double getDouble(double defaultValue) {
        return get().map(s -> {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a boolean.
     *
     * <p>Recognized true values: "true", "1", "yes" (case-insensitive).
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default value
     */
    public boolean getBoolean(boolean defaultValue) {
        return get().map(s -> {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a Path, or the provided default.
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default path
     */
    public Path getPath(Path defaultValue) {
        return get().map(Path::of).orElse(defaultValue);
    }

    /**
     * Gets the value as a Path, or null if not set.
     *
     * @return the configured path, or null
     */
    public Path getPath() {
        return get().map(Path::of).orElse(null);
    }

    /**
     * Checks if this configuration is explicitly set.
     *
     * @return true if set via system property or environment variable
     */
    public boolean isSet() {
        return get().isPresent();
    }

    /**
     * Config keys checked for Head→Worker divergence after gRPC handshake (tempdoc 329).
     *
     * <p>If the Head's {@link #get()} value for any of these keys differs from the Worker's value,
     * a WARN is logged. This turns silent misconfiguration (tempdoc 312 item 20) into a visible
     * signal. The set focuses on keys that caused actual bugs or are critical for correctness.
     */
    public static final Set<EnvRegistry> CONFIG_DIVERGENCE_CHECK_KEYS = EnumSet.of(
        DATA_DIR,
        CONFIG_PATH,
        REPO_ROOT,
        SSOT_PATH,
        EMBED_ONNX_MODEL_PATH,
        ORT_NATIVE_PATH,
        INDEX_BASE_PATH,
        INDEX_SCHEMA_MISMATCH_POLICY,
        INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS
        // POLICY_GPU_ACCELERATION_ENABLED removed (347): EnterprisePolicyService writes
        // System.setProperty() on Head, causing expected divergence with Worker's env var.
        // The Worker gets the correct value from the config snapshot.
    );
}
