/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration for {@link BridgedTranslator}.
 *
 * <p>Use {@link #validate()} to check configuration validity before use.
 * Use factory methods like {@link #forDevelopment(Path)} for common presets.
 */
public final class LocalIntentTranslatorConfig {
  private final Path modelPath;
  private final String modelSha256;
  private final String backend;
  private final int gpuLayers;
  private final int gpuDeviceId;
  private final long gpuMemLimitBytes;
  private final long deadlineMs;
  private final int maxParallelInferences;
  private final boolean enableBatching;
  private final int maxSlots;
  private final int maxBatchSize;
  private final int maxSessions;
  private final long sessionWarmupMs;
  private final int queueCapacity;
  private final long simulatedLatencyMs;
  private final int threads;
  private final int contextLength;
  private final int maxNewTokens;
  private final double temperature;
  private final double topP;
  private final double minP;
  private final double repetitionPenalty;
  private final int repetitionPenaltyWindow;
  private final boolean enableJsonGrammarGuard;
  private final long rngSeed;
  private final String backendSelector;
  private final int summaryChunkSizeTokens;
  private final int summaryChunkOverlapTokens;
  private final int embeddingChunkSizeTokens;
  private final int embeddingChunkOverlapTokens;
  private final boolean allowRemoteExecution;
  private final String remoteEndpoint;
  private final String remoteAuthToken;
  private final ContextPolicy contextPolicy;
  private final int pinnedContextTokens;
  private final java.util.List<String> backendTaskOverrides;
  private final java.util.List<String> requiredTasks;
  private final String intentPipelineId;
  private final String embedPipelineId;
  private final String classifyPipelineId;
  private final String summaryPipelineId;
  private final Path templateRoot;
  private final String translateTemplate;
  private final String summaryTemplate;
  private final String summaryReduceTemplate;
  private final boolean enableNativeLogging;
  private final boolean enablePrefixCaching;
  private final long vramLimitBytes;
  private final boolean enableVramAutoScale;
  private final int embeddingQueueCapacity;
  private final long vramActivationOverheadBytes;

  private LocalIntentTranslatorConfig(Builder builder) {
    this.modelPath = builder.modelPath;
    this.modelSha256 = builder.modelSha256;
    this.backend = builder.backend;
    this.gpuLayers = builder.gpuLayers;
    this.gpuDeviceId = builder.gpuDeviceId;
    this.gpuMemLimitBytes = builder.gpuMemLimitBytes;
    this.deadlineMs = builder.deadlineMs;
    this.maxParallelInferences = builder.maxParallelInferences;
    this.enableBatching = builder.enableBatching;
    this.maxSlots = builder.maxSlots;
    this.maxBatchSize = builder.maxBatchSize;
    this.maxSessions = builder.maxSessions;
    this.sessionWarmupMs = builder.sessionWarmupMs;
    this.queueCapacity = builder.queueCapacity;
    this.simulatedLatencyMs = builder.simulatedLatencyMs;
    this.threads = builder.threads;
    this.contextLength = builder.contextLength;
    this.maxNewTokens = builder.maxNewTokens;
    this.temperature = builder.temperature;
    this.topP = builder.topP;
    this.minP = builder.minP;
    this.repetitionPenalty = builder.repetitionPenalty;
    this.repetitionPenaltyWindow = builder.repetitionPenaltyWindow;
    this.enableJsonGrammarGuard = builder.enableJsonGrammarGuard;
    this.rngSeed = builder.rngSeed;
    this.backendSelector = builder.backendSelector;
    this.summaryChunkSizeTokens = builder.summaryChunkSizeTokens;
    this.summaryChunkOverlapTokens = builder.summaryChunkOverlapTokens;
    this.embeddingChunkSizeTokens = builder.embeddingChunkSizeTokens;
    this.embeddingChunkOverlapTokens = builder.embeddingChunkOverlapTokens;
    this.allowRemoteExecution = builder.allowRemoteExecution;
    this.remoteEndpoint = builder.remoteEndpoint;
    this.remoteAuthToken = builder.remoteAuthToken;
    this.contextPolicy = builder.contextPolicy;
    this.pinnedContextTokens = builder.pinnedContextTokens;
    this.backendTaskOverrides = java.util.List.copyOf(builder.backendTaskOverrides);
    this.requiredTasks = java.util.List.copyOf(builder.requiredTasks);
    this.intentPipelineId = builder.intentPipelineId;
    this.embedPipelineId = builder.embedPipelineId;
    this.classifyPipelineId = builder.classifyPipelineId;
    this.summaryPipelineId = builder.summaryPipelineId;
    this.templateRoot = builder.templateRoot;
    this.translateTemplate = builder.translateTemplate;
    this.summaryTemplate = builder.summaryTemplate;
    this.summaryReduceTemplate = builder.summaryReduceTemplate;
    this.enableNativeLogging = builder.enableNativeLogging;
    this.enablePrefixCaching = builder.enablePrefixCaching;
    this.vramLimitBytes = builder.vramLimitBytes;
    this.enableVramAutoScale = builder.enableVramAutoScale;
    this.embeddingQueueCapacity = builder.embeddingQueueCapacity;
    this.vramActivationOverheadBytes = builder.vramActivationOverheadBytes;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  // --- Factory methods for common configurations ---

  /**
   * Creates a development configuration optimized for debugging.
   *
   * <p>Characteristics:
   * <ul>
   *   <li>Single slot (simplified debugging)</li>
   *   <li>Small context (512 tokens)</li>
   *   <li>Deterministic (temperature=0, fixed seed)</li>
   *   <li>Short deadline for fast iteration</li>
   * </ul>
   */
  public static LocalIntentTranslatorConfig forDevelopment(Path modelPath) {
    return newBuilder()
        .modelPath(modelPath)
        .backend("llama")
        .maxSlots(1)
        .maxSessions(1)
        .maxParallelInferences(1)
        .contextLength(512)
        .maxNewTokens(64)
        .temperature(0.0)
        .rngSeed(42L)
        .deadlineMs(30_000)
        .enableBatching(false)
        .build();
  }

  /**
   * Creates a production configuration optimized for throughput.
   *
   * <p>Characteristics:
   * <ul>
   *   <li>Multiple slots for parallel inference</li>
   *   <li>Large context (4096 tokens)</li>
   *   <li>Batching enabled</li>
   *   <li>Context shift policy for long conversations</li>
   *   <li>Prefix caching for zero-latency system prompt ingestion</li>
   * </ul>
   */
  public static LocalIntentTranslatorConfig forProduction(Path modelPath) {
    return newBuilder()
        .modelPath(modelPath)
        .backend("llama")
        .maxSlots(4)
        .maxSessions(4)
        .maxParallelInferences(4)
        .maxBatchSize(4)
        .contextLength(4096)
        .maxNewTokens(256)
        .temperature(0.2)
        .enableBatching(true)
        .contextPolicy(ContextPolicy.SHIFT_OLDEST)
        .enablePrefixCaching(true)
        .deadlineMs(60_000)
        .queueCapacity(32)
        .build();
  }

  /**
   * Creates a test configuration for chaos/stress testing.
   *
   * <p>Characteristics:
   * <ul>
   *   <li>Small slots/context for fast cycles</li>
   *   <li>Short deadline to trigger timeout paths</li>
   *   <li>Deterministic for reproducibility</li>
   * </ul>
   */
  public static LocalIntentTranslatorConfig forTesting(Path modelPath) {
    return newBuilder()
        .modelPath(modelPath)
        .backend("llama")
        .maxSlots(2)
        .maxSessions(4)
        .maxParallelInferences(2)
        .maxBatchSize(2)
        .contextLength(512)
        .maxNewTokens(8)
        .temperature(0.0)
        .rngSeed(42L)
        .deadlineMs(5_000)
        .enableBatching(true)
        .build();
  }

  // --- Validation ---

  /**
   * Validates this configuration, throwing {@link ConfigurationException} if invalid.
   *
   * <p>Checks include:
   * <ul>
   *   <li>Required fields are set (modelPath for native backends)</li>
   *   <li>Numeric ranges are valid</li>
   *   <li>Related fields are consistent (e.g., maxNewTokens &lt; contextLength)</li>
   * </ul>
   *
   * @throws ConfigurationException if validation fails
   */
  public void validate() throws ConfigurationException {
    ConfigurationException.Builder errors = ConfigurationException.builder();

    // Required fields for native backends
    boolean isNativeBackend = "llama".equalsIgnoreCase(backend);
    if (isNativeBackend) {
      if (modelPath == null) {
        errors.addError("modelPath is required for backend '" + backend + "'");
      } else if (!Files.exists(modelPath)) {
        errors.addError("modelPath does not exist: " + modelPath);
      }
    }

    // Numeric range validations
    errors.require(maxSlots > 0, "maxSlots must be positive, got: " + maxSlots);
    errors.require(maxSessions > 0, "maxSessions must be positive, got: " + maxSessions);
    errors.require(contextLength >= 128, "contextLength must be >= 128, got: " + contextLength);
    errors.require(maxNewTokens > 0, "maxNewTokens must be positive, got: " + maxNewTokens);
    errors.require(deadlineMs > 0, "deadlineMs must be positive, got: " + deadlineMs);
    errors.require(threads > 0, "threads must be positive, got: " + threads);

    // Consistency validations
    errors.require(maxNewTokens < contextLength,
        "maxNewTokens (" + maxNewTokens + ") must be less than contextLength (" + contextLength + ")");
    errors.require(maxParallelInferences <= maxSessions,
        "maxParallelInferences (" + maxParallelInferences + ") must be <= maxSessions (" + maxSessions + ")");
    errors.require(maxBatchSize <= maxSlots,
        "maxBatchSize (" + maxBatchSize + ") must be <= maxSlots (" + maxSlots + ")");
    errors.require(summaryChunkOverlapTokens < summaryChunkSizeTokens,
        "summaryChunkOverlapTokens (" + summaryChunkOverlapTokens + ") must be < summaryChunkSizeTokens (" + summaryChunkSizeTokens + ")");

    // Sampling parameter validations
    errors.require(temperature >= 0.0 && temperature <= 2.0,
        "temperature must be in [0.0, 2.0], got: " + temperature);
    errors.require(topP >= 0.0 && topP <= 1.0,
        "topP must be in [0.0, 1.0], got: " + topP);
    errors.require(minP >= 0.0 && minP <= 1.0,
        "minP must be in [0.0, 1.0], got: " + minP);
    errors.require(repetitionPenalty >= 0.5,
        "repetitionPenalty must be >= 0.5, got: " + repetitionPenalty);

    errors.throwIfErrors();
  }

  /**
   * Validates and returns this configuration.
   *
   * @return this configuration if valid
   * @throws ConfigurationException if validation fails
   */
  public LocalIntentTranslatorConfig validated() throws ConfigurationException {
    validate();
    return this;
  }

  /**
   * Returns true if this configuration is valid.
   */
  public boolean isValid() {
    try {
      validate();
      return true;
    } catch (ConfigurationException e) {
      return false;
    }
  }

  public Path modelPath() {
    return modelPath;
  }

  public String modelSha256() {
    return modelSha256;
  }

  public String backend() {
    return backend;
  }

  public int gpuLayers() {
    return gpuLayers;
  }

  /** Returns the CUDA device index for GPU-accelerated inference (default 0). */
  public int gpuDeviceId() {
    return gpuDeviceId;
  }

  /** Returns the GPU memory arena limit in bytes (default 0 = unlimited). */
  public long gpuMemLimitBytes() {
    return gpuMemLimitBytes;
  }

  public long deadlineMs() {
    return deadlineMs;
  }

  public int maxParallelInferences() {
    return maxParallelInferences;
  }

  public boolean enableBatching() {
    return enableBatching;
  }

  public int maxSlots() {
    return maxSlots;
  }

  public int maxBatchSize() {
    return maxBatchSize;
  }

  public int maxSessions() {
    return maxSessions;
  }

  public long sessionWarmupMs() {
    return sessionWarmupMs;
  }

  public int queueCapacity() {
    return queueCapacity;
  }

  public long simulatedLatencyMs() {
    return simulatedLatencyMs;
  }

  public int threads() {
    return threads;
  }

  public int contextLength() {
    return contextLength;
  }

  public int maxNewTokens() {
    return maxNewTokens;
  }

  public double temperature() {
    return temperature;
  }

  public double topP() {
    return topP;
  }

  public double minP() {
    return minP;
  }

  public double repetitionPenalty() {
    return repetitionPenalty;
  }

  public int repetitionPenaltyWindow() {
    return repetitionPenaltyWindow;
  }

  public boolean enableJsonGrammarGuard() {
    return enableJsonGrammarGuard;
  }

  public long rngSeed() {
    return rngSeed;
  }

  public String backendSelector() {
    return backendSelector;
  }

  public int summaryChunkSizeTokens() {
    return summaryChunkSizeTokens;
  }

  public int summaryChunkOverlapTokens() {
    return summaryChunkOverlapTokens;
  }

  /**
   * Returns the chunk size (in tokens) for embedding long documents.
   *
   * <p>Documents exceeding the model's context length will be split into
   * overlapping chunks of this size. Default is 512 tokens.
   */
  public int embeddingChunkSizeTokens() {
    return embeddingChunkSizeTokens;
  }

  /**
   * Returns the overlap (in tokens) between embedding chunks.
   *
   * <p>Adjacent chunks will share this many tokens to preserve context
   * across chunk boundaries. Default is 128 tokens (giving stride = 384).
   */
  public int embeddingChunkOverlapTokens() {
    return embeddingChunkOverlapTokens;
  }

  public boolean allowRemoteExecution() {
    return allowRemoteExecution;
  }

  public String remoteEndpoint() {
    return remoteEndpoint;
  }

  public String remoteAuthToken() {
    return remoteAuthToken;
  }

  public ContextPolicy contextPolicy() {
    return contextPolicy;
  }

  /**
   * Returns the number of tokens to preserve ("pin") at the start of the context window.
   *
   * <p>When using {@link ContextPolicy#SHIFT_AFTER_PINNED}, this specifies how many tokens
   * at the beginning of the context (typically the system prompt) should never be evicted.
   *
   * <p>Default is 64 tokens. Set higher if your system prompts are longer.
   */
  public int pinnedContextTokens() {
    return pinnedContextTokens;
  }

  public java.util.List<String> backendTaskOverrides() {
    return backendTaskOverrides;
  }

  public java.util.List<String> requiredTasks() {
    return requiredTasks;
  }

  public String intentPipelineId() {
    return intentPipelineId;
  }

  public String embedPipelineId() {
    return embedPipelineId;
  }

  public String classifyPipelineId() {
    return classifyPipelineId;
  }

  public String summaryPipelineId() {
    return summaryPipelineId;
  }

  public Path templateRoot() {
    return templateRoot;
  }

  public String translateTemplate() {
    return translateTemplate;
  }

  public String summaryTemplate() {
    return summaryTemplate;
  }

  public String summaryReduceTemplate() {
    return summaryReduceTemplate;
  }

  /**
   * Returns true if native logging should be forwarded to SLF4J.
   * Disabled by default due to threading complexity per spec section 9.
   */
  public boolean enableNativeLogging() {
    return enableNativeLogging;
  }

  /**
   * Returns true if prefix caching is enabled.
   *
   * <p>When enabled, the engine caches system prompt prefixes in the KV cache
   * and copies them to new requests, eliminating redundant computation.
   * This can significantly reduce time-to-first-token for requests with
   * common system prompts.
   *
   * <p>Requires at least 2 slots (one for prefix storage, one for generation).
   * Enabled by default for production configurations.
   */
  public boolean enablePrefixCaching() {
    return enablePrefixCaching;
  }

  /**
   * Returns the VRAM limit in bytes for KV cache allocation.
   *
   * <p>If set to a positive value, the engine will validate that the estimated
   * VRAM usage for KV cache fits within this limit before allocating contexts.
   * This prevents native crashes (GGML_ASSERT, SIGSEGV) from over-allocation.
   *
   * <p>A value of 0 or negative means no limit (use all available VRAM).
   * Default is 0 (no limit).
   *
   * <p>Example: For a 8GB GPU with 2GB reserved for model weights, set to 6GB:
   * {@code .vramLimitBytes(6L * 1024 * 1024 * 1024)}
   */
  public long vramLimitBytes() {
    return vramLimitBytes;
  }

  /**
   * Returns true if VRAM auto-scaling is enabled.
   *
   * <p>When enabled and VRAM estimation exceeds the limit, the engine will
   * automatically reduce slots and/or context length to fit within the limit.
   * When disabled, a {@code BackendException} is thrown instead.
   *
   * <p>Default is true (auto-scale to fit).
   */
  public boolean enableVramAutoScale() {
    return enableVramAutoScale;
  }

  /**
   * Returns the capacity of the embedding queue.
   *
   * <p>This limits how many prepared embedding requests can wait for GPU processing.
   * If the queue fills up, new requests are rejected with a BusyException.
   *
   * <p>Default is 64.
   */
  public int embeddingQueueCapacity() {
    return embeddingQueueCapacity;
  }

  /**
   * Returns the static overhead (in bytes) for activations and scratch buffers.
   *
   * <p>This is used in VRAM estimation to account for memory beyond the KV cache.
   * Increase this value if you see VRAM exhaustion during inference.
   *
   * <p>Default is 512MB (512 * 1024 * 1024).
   */
  public long vramActivationOverheadBytes() {
    return vramActivationOverheadBytes;
  }

  public static final class Builder {
    private Path modelPath;
    private String modelSha256 = "unknown";
    private String backend = "stub";
    private int gpuLayers = 0;
    private int gpuDeviceId = 0;
    private long gpuMemLimitBytes = 0L;
    // Default matches forDevelopment() - 30s is reasonable for most inference tasks.
    // Previous default (900ms) was unrealistic for LLM inference.
    private long deadlineMs = 30_000;
    private int maxParallelInferences = 1;
    private boolean enableBatching = true;
    private int maxSlots = 1;
    private int maxBatchSize = 4;
    private int maxSessions = 1;
    private long sessionWarmupMs = 0L;
    private int queueCapacity = 16;
    private long simulatedLatencyMs = 0;
    private int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private int contextLength = 4096;
    private int maxNewTokens = 128;
    private double temperature = 0.2d;
    private double topP = 0.9d;
    private double minP = 0.0d;
    private double repetitionPenalty = 1.0d;
    private int repetitionPenaltyWindow = 64;
    private boolean enableJsonGrammarGuard = false;
    private long rngSeed = 42L;
    private String backendSelector = "auto";
    private int summaryChunkSizeTokens = 512;
    private int summaryChunkOverlapTokens = 64;
    private int embeddingChunkSizeTokens = 512;
    private int embeddingChunkOverlapTokens = 128;
    private boolean allowRemoteExecution = false;
    private String remoteEndpoint = "";
    private String remoteAuthToken = "";
    private ContextPolicy contextPolicy = ContextPolicy.SHIFT_OLDEST;
    private int pinnedContextTokens = 64;
    private java.util.List<String> backendTaskOverrides = new java.util.ArrayList<>();
    private java.util.List<String> requiredTasks = new java.util.ArrayList<>();
    private String intentPipelineId = "intent_v1";
    private String embedPipelineId = "embed_v1";
    private String classifyPipelineId = "classify_v1";
    private String summaryPipelineId = "summary_mapreduce_v1";
    private Path templateRoot;
    private String translateTemplate = "tinyllama/translate.jinja";
    private String summaryTemplate = "tinyllama/summary_chunk.jinja";
    private String summaryReduceTemplate = "tinyllama/summary_reduce.jinja";
    private boolean enableNativeLogging = false;
    private boolean enablePrefixCaching = false;
    private long vramLimitBytes = 0L;
    private boolean enableVramAutoScale = true;
    private int embeddingQueueCapacity = 64;
    private long vramActivationOverheadBytes = 512L * 1024 * 1024; // 512 MB

    private Builder() {}

    public Builder modelPath(Path modelPath) {
      this.modelPath = modelPath;
      return this;
    }

    public Builder modelSha256(String modelSha256) {
      this.modelSha256 = modelSha256 == null || modelSha256.isBlank() ? "unknown" : modelSha256;
      return this;
    }

    public Builder backend(String backend) {
      this.backend = backend == null || backend.isBlank() ? "stub" : backend;
      return this;
    }

    public Builder gpuLayers(int gpuLayers) {
      this.gpuLayers = Math.max(gpuLayers, 0);
      return this;
    }

    public Builder gpuDeviceId(int gpuDeviceId) {
      this.gpuDeviceId = Math.max(gpuDeviceId, 0);
      return this;
    }

    public Builder gpuMemLimitBytes(long gpuMemLimitBytes) {
      this.gpuMemLimitBytes = Math.max(gpuMemLimitBytes, 0L);
      return this;
    }

    public Builder deadlineMs(long deadlineMs) {
      this.deadlineMs = Math.max(deadlineMs, 1);
      return this;
    }

    public Builder maxParallelInferences(int maxParallelInferences) {
      this.maxParallelInferences = Math.max(maxParallelInferences, 1);
      return this;
    }

    public Builder enableBatching(boolean enableBatching) {
      this.enableBatching = enableBatching;
      return this;
    }

    public Builder maxSlots(int maxSlots) {
      this.maxSlots = Math.max(1, maxSlots);
      return this;
    }

    public Builder maxBatchSize(int maxBatchSize) {
      this.maxBatchSize = Math.max(1, maxBatchSize);
      return this;
    }

    public Builder maxSessions(int maxSessions) {
      this.maxSessions = Math.max(maxSessions, 1);
      return this;
    }

    public Builder sessionWarmupMs(long sessionWarmupMs) {
      this.sessionWarmupMs = Math.max(sessionWarmupMs, 0L);
      return this;
    }

    public Builder queueCapacity(int queueCapacity) {
      this.queueCapacity = Math.max(queueCapacity, 1);
      return this;
    }

    public Builder simulatedLatencyMs(long simulatedLatencyMs) {
      this.simulatedLatencyMs = Math.max(simulatedLatencyMs, 0);
      return this;
    }

    public Builder threads(int threads) {
      this.threads = Math.max(threads, 1);
      return this;
    }

    public Builder contextLength(int contextLength) {
      this.contextLength = Math.max(contextLength, 512);
      return this;
    }

    public Builder maxNewTokens(int maxNewTokens) {
      this.maxNewTokens = Math.max(maxNewTokens, 1);
      return this;
    }

    public Builder temperature(double temperature) {
      this.temperature = clampProbability(temperature, 0.0d, 2.0d);
      return this;
    }

    public Builder topP(double topP) {
      this.topP = clampProbability(topP, 0.0d, 1.0d);
      return this;
    }

    public Builder minP(double minP) {
      this.minP = clampProbability(minP, 0.0d, 1.0d);
      return this;
    }

    public Builder repetitionPenalty(double repetitionPenalty) {
      if (Double.isNaN(repetitionPenalty) || Double.isInfinite(repetitionPenalty)) {
        this.repetitionPenalty = 1.0d;
      } else {
        this.repetitionPenalty = Math.max(0.5d, repetitionPenalty);
      }
      return this;
    }

    public Builder repetitionPenaltyWindow(int window) {
      this.repetitionPenaltyWindow = Math.max(0, window);
      return this;
    }

    public Builder enableJsonGrammarGuard(boolean enableJsonGrammarGuard) {
      this.enableJsonGrammarGuard = enableJsonGrammarGuard;
      return this;
    }

    public Builder rngSeed(long rngSeed) {
      this.rngSeed = rngSeed;
      return this;
    }

    public Builder backendSelector(String backendSelector) {
      this.backendSelector =
          backendSelector == null || backendSelector.isBlank() ? "auto" : backendSelector.strip();
      return this;
    }

    public Builder summaryChunkSizeTokens(int summaryChunkSizeTokens) {
      this.summaryChunkSizeTokens = Math.max(32, summaryChunkSizeTokens);
      return this;
    }

    public Builder summaryChunkOverlapTokens(int summaryChunkOverlapTokens) {
      this.summaryChunkOverlapTokens = Math.max(0, summaryChunkOverlapTokens);
      return this;
    }

    /**
     * Sets the chunk size (in tokens) for embedding long documents.
     *
     * <p>Documents exceeding the model's context length will be split into
     * overlapping chunks of this size. Default is 512 tokens.
     *
     * @param embeddingChunkSizeTokens chunk size in tokens (minimum 32)
     */
    public Builder embeddingChunkSizeTokens(int embeddingChunkSizeTokens) {
      this.embeddingChunkSizeTokens = Math.max(32, embeddingChunkSizeTokens);
      return this;
    }

    /**
     * Sets the overlap (in tokens) between embedding chunks.
     *
     * <p>Adjacent chunks will share this many tokens to preserve context
     * across chunk boundaries. Default is 128 tokens (giving stride = 384).
     *
     * @param embeddingChunkOverlapTokens overlap in tokens (minimum 0)
     */
    public Builder embeddingChunkOverlapTokens(int embeddingChunkOverlapTokens) {
      this.embeddingChunkOverlapTokens = Math.max(0, embeddingChunkOverlapTokens);
      return this;
    }

    public Builder allowRemoteExecution(boolean allowRemoteExecution) {
      this.allowRemoteExecution = allowRemoteExecution;
      return this;
    }

    public Builder remoteEndpoint(String remoteEndpoint) {
      this.remoteEndpoint = remoteEndpoint == null ? "" : remoteEndpoint.trim();
      return this;
    }

    public Builder remoteAuthToken(String remoteAuthToken) {
      this.remoteAuthToken = remoteAuthToken == null ? "" : remoteAuthToken.trim();
      return this;
    }

    public Builder contextPolicy(ContextPolicy contextPolicy) {
      this.contextPolicy = contextPolicy == null ? ContextPolicy.SHIFT_OLDEST : contextPolicy;
      return this;
    }

    /**
     * Sets the number of tokens to preserve ("pin") at the start of the context window.
     *
     * <p>When using {@link ContextPolicy#SHIFT_AFTER_PINNED}, this specifies how many tokens
     * at the beginning of the context (typically the system prompt) should never be evicted.
     *
     * <p>Default is 64 tokens. Adjust based on your system prompt length.
     *
     * @param pinnedContextTokens number of tokens to preserve (minimum 0)
     */
    public Builder pinnedContextTokens(int pinnedContextTokens) {
      this.pinnedContextTokens = Math.max(0, pinnedContextTokens);
      return this;
    }

    public Builder backendTaskOverrides(java.util.Collection<String> overrides) {
      this.backendTaskOverrides = sanitize(overrides);
      return this;
    }

    public Builder requiredTasks(java.util.Collection<String> tasks) {
      this.requiredTasks = sanitize(tasks);
      return this;
    }

    public Builder intentPipelineId(String pipelineId) {
      this.intentPipelineId = sanitizePipelineId(pipelineId, "intent_v1");
      return this;
    }

    public Builder embedPipelineId(String pipelineId) {
      this.embedPipelineId = sanitizePipelineId(pipelineId, "embed_v1");
      return this;
    }

    public Builder classifyPipelineId(String pipelineId) {
      this.classifyPipelineId = sanitizePipelineId(pipelineId, "classify_v1");
      return this;
    }

    public Builder summaryPipelineId(String pipelineId) {
      this.summaryPipelineId = sanitizePipelineId(pipelineId, "summary_mapreduce_v1");
      return this;
    }

    public Builder templateRoot(Path templateRoot) {
      this.templateRoot = templateRoot;
      return this;
    }

    public Builder translateTemplate(String template) {
      this.translateTemplate = sanitizeTemplateName(template, "translate.jinja");
      return this;
    }

    public Builder summaryTemplate(String template) {
      this.summaryTemplate = sanitizeTemplateName(template, "summary_chunk.jinja");
      return this;
    }

    public Builder summaryReduceTemplate(String template) {
      this.summaryReduceTemplate = sanitizeTemplateName(template, "summary_reduce.jinja");
      return this;
    }

    /**
     * Enables forwarding of native llama.cpp logs to SLF4J.
     * Disabled by default due to threading complexity per spec section 9.
     */
    public Builder enableNativeLogging(boolean enable) {
      this.enableNativeLogging = enable;
      return this;
    }

    /**
     * Enables prefix caching for system prompts.
     *
     * <p>When enabled, the engine caches system prompt prefixes in the KV cache
     * and copies them to new requests using {@code llama_kv_cache_seq_cp}.
     * This eliminates redundant computation for requests with common prefixes.
     *
     * <p><b>Requirements:</b>
     * <ul>
     *   <li>At least 2 slots (one for prefix storage)</li>
     *   <li>Works best with consistent system prompts</li>
     * </ul>
     *
     * @param enable true to enable prefix caching
     */
    public Builder enablePrefixCaching(boolean enable) {
      this.enablePrefixCaching = enable;
      return this;
    }

    /**
     * Sets the VRAM limit in bytes for KV cache allocation.
     *
     * <p>If set to a positive value, the engine will validate that the estimated
     * VRAM usage fits within this limit before allocating contexts. This prevents
     * native crashes from over-allocation.
     *
     * <p>Set to 0 or negative to disable the limit (default).
     *
     * @param bytes VRAM limit in bytes (0 = no limit)
     */
    public Builder vramLimitBytes(long bytes) {
      this.vramLimitBytes = Math.max(0, bytes);
      return this;
    }

    /**
     * Enables or disables VRAM auto-scaling.
     *
     * <p>When enabled and VRAM estimation exceeds the limit, the engine will
     * automatically reduce slots and/or context length to fit. When disabled,
     * a {@code BackendException} is thrown instead.
     *
     * @param enable true to enable auto-scaling (default)
     */
    public Builder enableVramAutoScale(boolean enable) {
      this.enableVramAutoScale = enable;
      return this;
    }

    /**
     * Sets the capacity of the embedding queue.
     *
     * <p>This limits how many prepared embedding requests can wait for GPU processing.
     * If the queue fills up, new requests are rejected with a BusyException.
     *
     * @param capacity queue capacity (minimum 1, default 64)
     */
    public Builder embeddingQueueCapacity(int capacity) {
      this.embeddingQueueCapacity = Math.max(1, capacity);
      return this;
    }

    /**
     * Sets the static overhead (in bytes) for activations and scratch buffers.
     *
     * <p>This is used in VRAM estimation to account for memory beyond the KV cache.
     * Increase this value if you see VRAM exhaustion during inference.
     *
     * @param bytes activation overhead in bytes (minimum 0, default 512MB)
     */
    public Builder vramActivationOverheadBytes(long bytes) {
      this.vramActivationOverheadBytes = Math.max(0, bytes);
      return this;
    }

    private static double clampProbability(double value, double min, double max) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        return min;
      }
      return Math.min(Math.max(value, min), max);
    }

    public LocalIntentTranslatorConfig build() {
      Objects.requireNonNull(modelSha256, "modelSha256");
      Objects.requireNonNull(backend, "backend");
      if (maxParallelInferences > maxSessions) {
        throw new IllegalArgumentException(
            "maxParallelInferences ("
                + maxParallelInferences
                + ") exceeds maxSessions ("
                + maxSessions
                + ")");
      }
      if (maxSlots < 1) {
        maxSlots = 1;
      }
      if (maxBatchSize < 1) {
        maxBatchSize = 1;
      }
      if (maxBatchSize > maxSlots) {
        maxBatchSize = maxSlots;
      }
      if (summaryChunkOverlapTokens >= summaryChunkSizeTokens) {
        summaryChunkOverlapTokens = Math.max(0, summaryChunkSizeTokens - 1);
      }
      if (embeddingChunkOverlapTokens >= embeddingChunkSizeTokens) {
        embeddingChunkOverlapTokens = Math.max(0, embeddingChunkSizeTokens - 1);
      }
      return new LocalIntentTranslatorConfig(this);
    }

    private static java.util.List<String> sanitize(java.util.Collection<String> values) {
      java.util.List<String> list = new java.util.ArrayList<>();
      if (values == null) {
        return list;
      }
      for (String value : values) {
        if (value == null || value.isBlank()) {
          continue;
        }
        list.add(value.trim());
      }
      return list;
    }

    private static String sanitizePipelineId(String candidate, String fallback) {
      if (candidate == null || candidate.isBlank()) {
        return fallback;
      }
      return candidate.trim();
    }

    private static String sanitizeTemplateName(String candidate, String fallback) {
      if (candidate == null || candidate.isBlank()) {
        return fallback;
      }
      return candidate.trim();
    }
  }
}
