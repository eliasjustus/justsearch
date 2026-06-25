/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.ort.GpuArbiter;
import io.justsearch.ort.OrtCudaStatus;
import java.io.Closeable;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for generating text embeddings using ONNX Runtime.
 *
 * <p>By default, this service runs in <b>CPU-only</b> mode to avoid GPU contention
 * with the main application's chat LLM. GPU acceleration can be enabled via
 * {@code JUSTSEARCH_EMBED_GPU_ENABLED=true}.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li><b>GPU Configurable:</b> Set JUSTSEARCH_EMBED_GPU_ENABLED=true for GPU acceleration</li>
 *   <li><b>Single Session:</b> Optimized for sequential batch processing</li>
 *   <li><b>Lazy Loading:</b> Model loaded on first embed call</li>
 *   <li><b>Graceful Degradation:</b> Returns empty embeddings if unavailable</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe for concurrent embed() calls.
 */
public final class EmbeddingService implements EmbeddingProvider, Closeable {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

  /** Default embedding dimension for nomic-embed-text. */
  private static final int DEFAULT_DIMENSION = 768;

  /**
   * Default task instruction prefixes for nomic-embed-text models. Used when no
   * {@code prefix_config.json} file is found in the model directory.
   */
  private static final String DEFAULT_DOCUMENT_PREFIX = "search_document: ";

  private static final String DEFAULT_QUERY_PREFIX = "search_query: ";

  private final Path modelPath;
  private final String documentPrefix;
  private final String queryPrefix;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile AiBackend backend;
  private volatile int dimension = DEFAULT_DIMENSION;
  private volatile boolean available = false;
  private volatile boolean gpuEnabled = false;
  private volatile String resolvedBackendId = "onnx";
  private volatile Supplier<OrtCudaStatus> ortCudaStatusSupplier;
  private final EmbeddingConfig embeddingConfig;
  // Tempdoc 413: zero-dep events seam for embedding.runtime.* metrics. Defaulted to noop;
  // wired in production via createWithBackend(backend, config, events). Final reference, but
  // an empty default keeps tests and pre-bootstrap paths free of telemetry plumbing.
  private final EmbeddingTelemetryEvents events;

  // Query embedding cache (TTL-based, prevents N+1 embedding generation for search→RAG sequences)
  private static final long CACHE_TTL_MS = 5_000;  // 5 second TTL
  private static final long EVICTION_INTERVAL_MS = 1_000;  // Rate-limit eviction to max once per second
  private final ConcurrentHashMap<String, CachedEmbedding> embeddingCache = new ConcurrentHashMap<>();
  private volatile long lastEvictionTime = 0;

  private record CachedEmbedding(ChunkedEmbedding embedding, long expiresAt) {
    boolean isExpired() {
      return System.currentTimeMillis() > expiresAt;
    }
  }

  /**
   * Creates a new EmbeddingService with the specified model path and configuration.
   *
   * <p>The model is not loaded until the first call to {@link #embed(String)}.
   *
   * @param modelPath path to the embedding model directory
   * @param config embedding configuration (GPU, context length, etc.)
   */
  public EmbeddingService(Path modelPath, EmbeddingConfig config) {
    this(modelPath, config, NoopEmbeddingTelemetryEvents.INSTANCE);
  }

  /**
   * Creates a new EmbeddingService with explicit telemetry events. Production callers go through
   * {@link #createWithBackend(AiBackend, EmbeddingConfig, EmbeddingTelemetryEvents)}; this
   * constructor is here for direct construction (test paths) that need to wire telemetry.
   */
  public EmbeddingService(Path modelPath, EmbeddingConfig config, EmbeddingTelemetryEvents events) {
    this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
    this.embeddingConfig = Objects.requireNonNull(config, "config");
    this.events = events != null ? events : NoopEmbeddingTelemetryEvents.INSTANCE;
    String[] prefixes = loadPrefixes(modelPath);
    this.documentPrefix = prefixes[0];
    this.queryPrefix = prefixes[1];
  }

  /** Package-private constructor for tests that don't need full config. */
  EmbeddingService(Path modelPath) {
    this(modelPath, EmbeddingConfig.DISABLED, NoopEmbeddingTelemetryEvents.INSTANCE);
  }

  /**
   * Creates an EmbeddingService with a pre-created backend, bypassing discovery and the provider
   * registry. Used by the composition root when the install contract provides the model variant.
   *
   * @param backend pre-created AI backend (typically OnnxEmbeddingBackend wrapping an encoder with
   *     an injected SessionHandle)
   * @param config embedding configuration (for model path, context length, GPU flags)
   * @return initialized EmbeddingService
   */
  public static EmbeddingService createWithBackend(AiBackend backend, EmbeddingConfig config) {
    return createWithBackend(backend, config, NoopEmbeddingTelemetryEvents.INSTANCE);
  }

  /**
   * Creates an EmbeddingService with a pre-created backend and telemetry events. Production
   * callers (KnowledgeServer) supply a real {@code EmbeddingTelemetryEvents}; tests / pre-bootstrap
   * paths use {@link NoopEmbeddingTelemetryEvents#INSTANCE}.
   *
   * @param backend pre-created AI backend
   * @param config embedding configuration
   * @param events events sink for {@code embedding.runtime.*} metric emissions (tempdoc 413)
   * @return initialized EmbeddingService
   */
  public static EmbeddingService createWithBackend(
      AiBackend backend, EmbeddingConfig config, EmbeddingTelemetryEvents events) {
    EmbeddingService svc = new EmbeddingService(config.modelPath(), config, events);
    svc.resolvedBackendId = "onnx";
    svc.backend = backend;
    svc.gpuEnabled = config.gpuEnabled();
    svc.available = true;
    svc.initialized.set(true);
    if (backend instanceof io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend ob) {
      svc.ortCudaStatusSupplier = () -> ob.encoder().getOrtCudaStatus();
    }
    log.info(
        "EmbeddingService created with pre-built backend: modelPath={}, gpuEnabled={}",
        config.modelPath(),
        config.gpuEnabled());
    return svc;
  }

  /**
   * Reports whether the embedding backend is ready. Tempdoc 397 §14.11 Stage 4b: the
   * SPI-registry initialisation path is deleted; {@code EmbeddingService} is always constructed
   * via {@link #createWithBackend} (composition root) with the backend pre-wired. This method
   * now just reports the backend-readiness state that was set at construction.
   *
   * <p>Retained for call-site compatibility ({@code IndexingLoop} and {@code KnowledgeServer}
   * expect it). Idempotent.
   */
  public boolean initialize() {
    return available;
  }

  /** Returns the {@link EmbeddingConfig} used by this service, or null if not configured. */
  public EmbeddingConfig embeddingConfig() {
    return embeddingConfig;
  }

  /**
   * Generates an embedding vector for the given text.
   *
   * <p>For short texts, returns a single vector. For long texts that exceed
   * the model's context window, the text is chunked and a mean-pooled vector
   * is returned (see {@link #embedWithChunks} for fine-grained access).
   *
   * @param text The text to embed
   * @return The embedding vector as a float array, or null if unavailable
   */
  public float[] embed(String text) {
    ChunkedEmbedding result = embedWithChunks(text);
    return result == null ? null : result.primaryVector();
  }

  /**
   * Generates an embedding for a document, prepending the task instruction prefix.
   *
   * @param text The document text to embed
   * @return The embedding vector, or null if unavailable
   */
  public float[] embedDocument(String text) {
    return embed(documentPrefix + text);
  }

  /**
   * Generates an embedding for a search query, prepending the task instruction prefix.
   *
   * @param text The query text to embed
   * @return The embedding vector, or null if unavailable
   */
  public float[] embedQuery(String text) {
    return embed(queryPrefix + text);
  }

  /**
   * Generates embeddings for the given text, with support for chunked documents.
   *
   * <p>For short texts that fit within the model's context window:
   * <ul>
   *   <li>{@code primaryVector}: The single embedding vector</li>
   *   <li>{@code chunkVectors}: Empty list</li>
   *   <li>{@code chunkCount}: 1</li>
   * </ul>
   *
   * <p>For long texts that exceed the context window:
   * <ul>
   *   <li>{@code primaryVector}: Mean-pooled vector across all chunks</li>
   *   <li>{@code chunkVectors}: Individual vectors per chunk for fine-grained retrieval</li>
   *   <li>{@code chunkCount}: Number of chunks</li>
   * </ul>
   *
   * @param text The text to embed
   * @return Chunked embedding result, or null if unavailable
   */
  public ChunkedEmbedding embedWithChunks(String text) {
    if (closed.get()) {
      events.onInvokeFailure(
          EmbeddingTelemetryEvents.Operation.SINGLE,
          EmbeddingTelemetryEvents.InvokeFailureReason.CLOSED);
      return null;
    }

    // Rate-limited eviction: prevent memory accumulation without O(n) overhead on every call
    // Run before availability check so cache is cleaned even when backend is temporarily unavailable
    long now = System.currentTimeMillis();
    if (now - lastEvictionTime > EVICTION_INTERVAL_MS) {
      evictExpiredEntries();
      lastEvictionTime = now;
    }

    if (!initialized.get()) {
      initialize();
    }

    if (!available || backend == null) {
      return null;
    }

    if (text == null || text.isBlank()) {
      events.onInvokeFailure(
          EmbeddingTelemetryEvents.Operation.SINGLE,
          EmbeddingTelemetryEvents.InvokeFailureReason.NULL_TEXT);
      return null;
    }

    // Check cache first (prevents N+1 embedding generation for search→RAG sequences)
    String cacheKey = text.strip();
    CachedEmbedding cached = embeddingCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      events.onCacheHit();
      log.debug("Embedding cache hit for query ({} chars)", text.length());
      return cached.embedding();
    }
    events.onCacheMiss();

    // Note: We no longer truncate text - the EmbeddingActor handles chunking internally
    try (AiBackend.Session session = backend.createSession()) {
      EmbeddingRequest request = new EmbeddingRequest(
          text,
          "",  // locale
          dimension,
          Map.of()
      );

      EmbeddingResult result = session.embed(request);

      if (result.degraded()) {
        log.debug("Embedding degraded: {}", result.reason());
      }

      // Convert List<Double> to float[]
      List<Double> vector = result.vector();
      if (vector.isEmpty()) {
        return null;
      }

      // Update actual dimension from result
      if (result.dimension() > 0) {
        this.dimension = result.dimension();
      }

      float[] primaryVector = toFloatArray(vector);

      // Convert chunk vectors if present
      List<float[]> chunkVectors;
      if (result.isChunked()) {
        events.onChunked(result.chunkCount());
        chunkVectors = new ArrayList<>(result.chunkVectors().size());
        for (List<Double> chunkVec : result.chunkVectors()) {
          chunkVectors.add(toFloatArray(chunkVec));
        }
        log.info("Chunked embedding: {} chunks for {} chars of text",
            result.chunkCount(), text.length());
      } else {
        chunkVectors = List.of();
      }

      ChunkedEmbedding embedding = new ChunkedEmbedding(primaryVector, chunkVectors, result.chunkCount());

      // Cache the result for subsequent lookups (e.g., search→RAG sequence)
      long expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
      embeddingCache.put(cacheKey, new CachedEmbedding(embedding, expiresAt));

      return embedding;

    } catch (BackendException e) {
      events.onInvokeFailure(
          EmbeddingTelemetryEvents.Operation.SINGLE,
          EmbeddingTelemetryEvents.InvokeFailureReason.BACKEND_EXCEPTION);
      log.warn("Embedding failed: {}", e.getMessage());
      log.debug("Embedding failed (stack trace)", e);
      return null;
    }
  }

  /**
   * Batch-embeds multiple document texts in a single inference call when possible.
   *
   * <p>Uses the backend's native batch embedding when available (ONNX), falling back to sequential
   * embedding otherwise. Each text is prefixed with the document task instruction.
   *
   * @param texts the document texts to embed (without prefix)
   * @return vectors in the same order as inputs; null entries for texts that failed
   */
  public List<float[]> embedDocumentBatch(List<String> texts) {
    if (closed.get()) {
      events.onInvokeFailure(
          EmbeddingTelemetryEvents.Operation.BATCH,
          EmbeddingTelemetryEvents.InvokeFailureReason.CLOSED);
      return null;
    }
    if (!initialized.get()) {
      initialize();
    }
    if (!available || backend == null) {
      return null;
    }
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }

    try (AiBackend.Session session = backend.createSession()) {
      List<EmbeddingRequest> requests = new ArrayList<>(texts.size());
      for (String text : texts) {
        requests.add(new EmbeddingRequest(documentPrefix + text, "", dimension, Map.of()));
      }

      List<EmbeddingResult> results = session.embedBatch(requests);

      List<float[]> vectors = new ArrayList<>(results.size());
      int detectedDim = 0;
      for (EmbeddingResult result : results) {
        if (result.degraded() || result.vector().isEmpty()) {
          vectors.add(null);
        } else {
          if (detectedDim == 0 && result.dimension() > 0) {
            detectedDim = result.dimension();
          }
          // Tempdoc 413 followup: emit chunked event for batch path too — most chunking
          // happens during indexing, which goes through this batch method exclusively.
          if (result.isChunked()) {
            events.onChunked(result.chunkCount());
          }
          vectors.add(toFloatArray(result.vector()));
        }
      }
      if (detectedDim > 0) {
        this.dimension = detectedDim;
      }
      log.debug("Batch embedding: {} texts, {} vectors returned", texts.size(), vectors.size());
      return vectors;
    } catch (BackendException e) {
      events.onInvokeFailure(
          EmbeddingTelemetryEvents.Operation.BATCH,
          EmbeddingTelemetryEvents.InvokeFailureReason.BACKEND_EXCEPTION);
      log.warn("Batch embedding failed: {}", e.getMessage());
      log.debug("Batch embedding failed (stack trace)", e);
      return null;
    }
  }

  /**
   * Loads task prefixes from a {@code prefix_config.json} in the model directory. Expected format:
   * {@code {"document_prefix": "search_document: ", "query_prefix": "search_query: "}}. Returns
   * defaults if no config file exists.
   */
  private static String[] loadPrefixes(Path modelPath) {
    if (modelPath == null) {
      return new String[] {DEFAULT_DOCUMENT_PREFIX, DEFAULT_QUERY_PREFIX};
    }
    // modelPath may be a file (GGUF) or directory (ONNX); get the directory
    Path dir = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
    if (dir == null) {
      return new String[] {DEFAULT_DOCUMENT_PREFIX, DEFAULT_QUERY_PREFIX};
    }
    Path configFile = dir.resolve("prefix_config.json");
    if (Files.exists(configFile)) {
      try {
        String content = Files.readString(configFile);
        String docPrefix = extractJsonString(content, "document_prefix");
        String queryPrefix = extractJsonString(content, "query_prefix");
        log.info(
            "Embedding prefixes from prefix_config.json: doc='{}', query='{}'",
            docPrefix,
            queryPrefix);
        return new String[] {docPrefix, queryPrefix};
      } catch (java.io.IOException e) {
        log.debug("Failed to read prefix_config.json, using defaults: {}", e.getMessage());
      }
    }
    return new String[] {DEFAULT_DOCUMENT_PREFIX, DEFAULT_QUERY_PREFIX};
  }

  /** Simple JSON string value extraction (avoids adding a JSON library dependency). */
  private static String extractJsonString(String json, String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return "";
    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return "";
    int startQuote = json.indexOf('"', colonIdx + 1);
    if (startQuote < 0) return "";
    int endQuote = json.indexOf('"', startQuote + 1);
    if (endQuote < 0) return "";
    return json.substring(startQuote + 1, endQuote);
  }

  private static float[] toFloatArray(List<Double> vector) {
    float[] arr = new float[vector.size()];
    for (int i = 0; i < vector.size(); i++) {
      arr[i] = vector.get(i).floatValue();
    }
    return arr;
  }

  /** Remove expired entries from the embedding cache. */
  private void evictExpiredEntries() {
    long now = System.currentTimeMillis();
    embeddingCache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
  }

  // NOTE: keep env/sysprop access centralized in modules/configuration (ConfigStore / PlatformPaths).

  /**
   * Result of embedding a text with potential chunking.
   *
   * @param primaryVector Mean-pooled vector (or single vector for short texts)
   * @param chunkVectors Individual chunk vectors (empty for short texts)
   * @param chunkCount Number of chunks (1 for short texts)
   */
  public record ChunkedEmbedding(float[] primaryVector, List<float[]> chunkVectors, int chunkCount) {
    public ChunkedEmbedding {
      primaryVector = primaryVector == null ? new float[0] : primaryVector;
      chunkVectors = chunkVectors == null ? List.of() : List.copyOf(chunkVectors);
      chunkCount = Math.max(1, chunkCount);
    }

    /** Returns true if this embedding was chunked (multiple vectors). */
    public boolean isChunked() {
      return chunkCount > 1 && !chunkVectors.isEmpty();
    }
  }

  /**
   * Returns the embedding dimension.
   *
   * @return The dimension of embedding vectors
   */
  public int dimension() {
    return dimension;
  }

  /**
   * Returns true if the embedding service is available and initialized.
   */
  public boolean isAvailable() {
    return available && !closed.get();
  }

  /**
   * Returns true if the embedding service is using GPU acceleration.
   *
   * @return true if GPU is enabled for embeddings
   */
  public boolean isUsingGpu() {
    return gpuEnabled;
  }

  /**
   * Returns the resolved backend identifier (always "onnx" since the llama backend was removed).
   */
  public String resolvedBackendId() {
    return resolvedBackendId;
  }

  /** Sets the ORT CUDA status supplier for this embedding service. */
  public void setOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    this.ortCudaStatusSupplier = supplier;
  }

  /** Returns the ORT CUDA status, or null if not an ONNX backend. */
  public OrtCudaStatus getOrtCudaStatus() {
    Supplier<OrtCudaStatus> supplier = this.ortCudaStatusSupplier;
    return supplier != null ? supplier.get() : null;
  }

  /**
   * Returns the GPU layers value for status reporting.
   *
   * <p>Reports {@code 1} when GPU is enabled, {@code 0} otherwise. The proto field (108) stays
   * {@code int32} for backward compatibility.
   */
  public int gpuLayers() {
    return gpuEnabled ? 1 : 0;
  }

  /**
   * Returns the model path.
   */
  public Path modelPath() {
    return modelPath;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test Hooks (package-private)
  // ─────────────────────────────────────────────────────────────────────────────

  /** Returns the current query-embedding cache size (number of cached query results). */
  public int cacheSize() {
    return embeddingCache.size();
  }

  /** Returns the current cache size. Package-private alias for legacy test callsites. */
  int getCacheSizeForTesting() {
    return cacheSize();
  }

  /** Sets the last eviction time. Package-private for testing. */
  void setLastEvictionTimeForTesting(long time) {
    this.lastEvictionTime = time;
  }

  /** Adds a test entry to the cache. Package-private for testing. */
  void putCacheEntryForTesting(String key, ChunkedEmbedding embedding, long expiresAt) {
    embeddingCache.put(key, new CachedEmbedding(embedding, expiresAt));
  }

  /**
   * Releases the GPU embedding session (yielding VRAM) while keeping the service alive so query
   * embedding continues on the CPU fallback session.
   *
   * <p>Tempdoc 598 R4: on the ADR-0004 GPU handoff the worker must free embedding VRAM for the
   * Online chat model, but — unlike {@link #close()} — query embedding must survive so semantic
   * search and RAG do not collapse to keyword the moment chat comes Online. This does not tear
   * down the backend or flip {@code available}; the deferred CPU session ({@code BASIC_OPT}, fp16,
   * same weights as the indexed document vectors) is created lazily on the next {@code embedQuery}.
   * No-op when closed or when the backend is not the ONNX encoder.
   */
  public void releaseGpuSession() {
    if (closed.get()) {
      return;
    }
    if (backend
        instanceof io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend ob) {
      ob.encoder().releaseGpuSession();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      log.info("Closing EmbeddingService...");
      if (backend != null) {
        try {
          backend.close();
        } catch (BackendException e) {
          log.warn("Error closing embedding backend: {}", e.getMessage());
        }
        backend = null;
      }
      // Clear embedding cache to release memory
      embeddingCache.clear();
      available = false;
      log.info("EmbeddingService closed.");
    }
  }
}
