/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

import io.justsearch.app.api.stream.ChunkFormat;
import io.justsearch.app.api.stream.ChunkFormat.Event;
import io.justsearch.aibackend.local.LocalLlmTranslator.SummaryResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 successor to {@link LocalLlmTranslator}. Adds explicit readiness and provenance hooks so
 * the worker can expose detailed health and pairing information to the application.
 */
public interface LocalIntentTranslatorV2 extends LocalLlmTranslator {

  /**
   * Immutable metadata describing the currently loaded model/runtime.
   *
   * <p>This record is used for health checks, startup validation, and telemetry.
   *
   * @param modelFileSha256 SHA256 hash of the model file (for integrity/provenance tracking)
   * @param backend Backend identifier (e.g., "llama", "stub", "openai")
   * @param gpuLayers Number of layers offloaded to GPU
   * @param embeddingDimension Vector dimension produced by this model (e.g., 768 for nomic-embed)
   * @param maxContextLength Maximum context length supported by this model
   */
  record Provenance(
      String modelFileSha256,
      String backend,
      int gpuLayers,
      int embeddingDimension,
      int maxContextLength) {
    public Provenance {
      modelFileSha256 = modelFileSha256 == null ? "" : modelFileSha256;
      backend = backend == null ? "" : backend;
      gpuLayers = Math.max(gpuLayers, 0);
      embeddingDimension = Math.max(embeddingDimension, 0);
      maxContextLength = Math.max(maxContextLength, 0);
    }

    /** Backward-compatible constructor for legacy code. */
    public Provenance(String modelFileSha256, String backend, int gpuLayers) {
      this(modelFileSha256, backend, gpuLayers, 0, 0);
    }

    public static Provenance empty() {
      return new Provenance("unknown", "unknown", 0, 0, 0);
    }
  }

  /**
   * Returns {@code true} when the translator is ready to accept requests. Implementations SHOULD
   * keep this {@code false} until warm-up probes succeed.
   */
  default boolean ready() {
    return LocalLlmTranslator.super.isReady();
  }

  /** Returns a human-readable readiness reason when {@link #ready()} is {@code false}. */
  default String readinessReason() {
    return null;
  }

  /** Marks the translator as ready; implementations may override to update internal flags. */
  default void markReady() {}

  /**
   * Marks the translator as unready with the supplied reason. Implementations SHOULD sanitize the
   * reason code (e.g., lower-case snake case) for telemetry.
   */
  default void markUnready(String reason) {}

  /**
   * Translates intent for startup probes without enforcing {@link #ready()} gating. Default
   * implementation delegates to {@link #translateIntent(String, String)}.
   */
  default String translateForCanary(String text, String locale) throws TranslatorException {
    return translateIntent(text, locale);
  }

  /**
   * Returns provenance metadata aligned with the handshake payload. Implementations MUST return the
   * same values they surface via {@code HealthService.Version}.
   */
  default Provenance provenance() {
    return Provenance.empty();
  }

  /** Current executor queue depth; implementations may override to expose telemetry. */
  default int queueDepth() {
    return 0;
  }

  /** Queue saturation ratio (0-1). */
  default double queueSaturation() {
    return 0.0d;
  }

  /** Session utilization ratio (0-1). */
  default double sessionUtilization() {
    return 0.0d;
  }

  /**
   * Registers a cancellation handle for the supplied request. The worker invokes {@link
   * CancellationHandle#cancel()} when the client cancels so implementations can interrupt any
   * backend work. Implementations should tolerate repeated registrations for the same request id.
   */
  default CancellationHandle registerSummaryCancellation(UUID requestId) {
    return CancellationHandle.NOOP;
  }

  /**
   * Estimate how many backend tokens a payload would consume. Implementations SHOULD delegate to the
   * backend tokenizer; the default implementation falls back to a simple character heuristic.
   */
  default int estimateTokens(CharSequence text) {
    if (text == null) {
      return 0;
    }
    double tokens = Math.ceil(text.length() / 4.0d);
    if (tokens <= 0) {
      return 0;
    }
    if (tokens >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) tokens;
  }

  /**
   * Streams a summary using the canonical chunk contract. Implementations MAY override for
   * fine-grained streaming; the default behavior buffers the result from {@link
   * LocalLlmTranslator#summarizeWithMeta(String, String, UUID)} and replays it in chunks.
   */
  default SummaryResult summarizeWithMeta(
      String content, String language, UUID requestId, ChunkEmitter emitter) throws TranslatorException {
    SummaryResult result = summarizeWithMeta(content, language, requestId);
    if (emitter != null) {
      emitBuffered(result, emitter);
    }
    return result;
  }

  /**
   * Generate an embedding vector for the supplied text. Implementations SHOULD override to invoke
   * the real pipeline; the default implementation returns a deterministic stub vector so callers
   * can exercise degrade paths.
   *
   * @param request the embedding request (must not be null, must have positive dimension)
   * @throws TranslatorException if embedding fails
   * @throws IllegalArgumentException if request is null or has invalid dimension
   */
  default EmbeddingResult embed(EmbeddingRequest request) throws TranslatorException {
    if (request == null) {
      throw new IllegalArgumentException("EmbeddingRequest must not be null");
    }
    if (request.dimension() <= 0) {
      throw new IllegalArgumentException("EmbeddingRequest.dimension must be positive (got " + request.dimension() + "). "
          + "Load from FieldCatalogDef.vectorDimension() instead of using hardcoded defaults.");
    }
    List<Double> values = deterministicEmbedding(request.text(), request.dimension());
    return new EmbeddingResult(values, request.dimension(), true, "stub", Map.of("reason", "stub"));
  }

  /**
   * Run the classifier pipeline for the supplied text. Implementations SHOULD override to invoke
   * the real pipeline; the default implementation returns a degraded placeholder payload.
   */
  default ClassificationResult classify(ClassificationRequest request) throws TranslatorException {
    ClassificationRequest normalized =
        request == null ? new ClassificationRequest("", "", Map.of()) : request;
    String payload =
        """
        {
          "labels": [],
          "reason": "stub",
          "text": "%s"
        }
        """
            .formatted(escapeJson(normalized.text()));
    return new ClassificationResult(payload, true, "unsupported", Map.of("reason", "stub"));
  }

  private static void emitBuffered(SummaryResult result, ChunkEmitter emitter) {
    emitter.emit(Event.START, null);
    List<String> chunks = ChunkFormat.chunkText(result.summaryText());
    if (chunks.isEmpty()) {
      emitter.emit(Event.END, new StreamChunk(0, "", true));
      return;
    }
    for (int i = 0; i < chunks.size(); i++) {
      boolean finalChunk = i == chunks.size() - 1;
      Event event = finalChunk ? Event.END : Event.DATA;
      emitter.emit(event, new StreamChunk(i, chunks.get(i), finalChunk));
    }
  }

  /** Streaming sink fed by {@link #summarizeWithMeta(String, String, UUID, ChunkEmitter)}. */
  interface ChunkEmitter {
    void emit(ChunkFormat.Event event, StreamChunk chunk);
  }

  /**
   * Translate intent text and return metadata indicating whether the translator degraded the result.
   */
  default IntentTranslation translateIntentWithMeta(String text, String locale) throws TranslatorException {
    String intentJson = translateIntent(text, locale);
    return new IntentTranslation(intentJson, false, "ok", Map.of());
  }

  /** Structured intent translation result. */
  record IntentTranslation(String intentJson, boolean degraded, String reason, Map<String, Object> attributes) {
    public IntentTranslation {
      intentJson = intentJson == null ? "" : intentJson;
      reason = reason == null || reason.isBlank() ? (degraded ? "unknown" : "ok") : reason;
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  /** Canonical chunk artifact shared with worker/client streaming. */
  record StreamChunk(int ordinal, String text, boolean finalChunk) {
    public StreamChunk {
      ordinal = Math.max(0, ordinal);
      text = text == null ? "" : text;
    }
  }

  /** Cancellation token returned to the worker. */
  interface CancellationHandle extends AutoCloseable {
    CancellationHandle NOOP =
        new CancellationHandle() {
          @Override
          public void cancel() {}

          @Override
          public void close() {}
        };

    void cancel();

    @Override
    default void close() {}
  }

  /** Parameters for embedding requests. */
  record EmbeddingRequest(String text, String locale, int dimension, Map<String, Object> metadata) {
    public EmbeddingRequest {
      text = text == null ? "" : text;
      locale = locale == null ? "" : locale;
      dimension = Math.max(1, dimension);
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /**
   * Embedding vector response from the translator.
   *
   * <p>For short texts that fit within the model's context window, {@code vector} contains
   * the single embedding and {@code chunkVectors} is empty.
   *
   * <p>For long texts that exceed the context window, the text is split into overlapping
   * chunks using a sliding window. Each chunk is embedded separately:
   * <ul>
   *   <li>{@code vector}: Mean-pooled vector across all chunks (for simple similarity search)</li>
   *   <li>{@code chunkVectors}: Individual vectors per chunk (for fine-grained retrieval)</li>
   * </ul>
   *
   * @param vector Primary embedding vector (single vector or mean-pooled if chunked)
   * @param chunkVectors Individual chunk vectors for long documents (empty if not chunked)
   * @param dimension Embedding dimension
   * @param chunkCount Number of chunks (1 for short texts, >1 for chunked texts)
   * @param degraded True if result is degraded (e.g., stub or fallback)
   * @param reason Reason code (e.g., "ok", "chunked", "stub")
   * @param metadata Additional metadata (e.g., chunk boundaries)
   */
  record EmbeddingResult(
      List<Double> vector,
      List<List<Double>> chunkVectors,
      int dimension,
      int chunkCount,
      boolean degraded,
      String reason,
      Map<String, Object> metadata) {
    public EmbeddingResult {
      vector = vector == null ? List.of() : List.copyOf(vector);
      chunkVectors = chunkVectors == null ? List.of() : chunkVectors.stream()
          .map(v -> v == null ? List.<Double>of() : List.copyOf(v))
          .toList();
      dimension = Math.max(0, dimension);
      chunkCount = Math.max(1, chunkCount);
      reason = reason == null || reason.isBlank() ? (degraded ? "unknown" : "ok") : reason;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Backward-compatible constructor for single-vector results. */
    public EmbeddingResult(List<Double> vector, int dimension, boolean degraded, String reason, Map<String, Object> metadata) {
      this(vector, List.of(), dimension, 1, degraded, reason, metadata);
    }

    /** Backward-compatible constructor for non-degraded single-vector results. */
    public EmbeddingResult(List<Double> vector, int dimension, Map<String, Object> metadata) {
      this(vector, List.of(), dimension, 1, false, "ok", metadata);
    }

    /** Returns true if this result contains multiple chunk vectors. */
    public boolean isChunked() {
      return chunkCount > 1 && !chunkVectors.isEmpty();
    }
  }

  /** Parameters for classification requests. */
  record ClassificationRequest(String text, String locale, Map<String, Object> metadata) {
    public ClassificationRequest {
      text = text == null ? "" : text;
      locale = locale == null ? "" : locale;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /** Classification payload result. */
  record ClassificationResult(String payloadJson, boolean degraded, String reason, Map<String, Object> metadata) {
    public ClassificationResult {
      payloadJson = payloadJson == null ? "{}" : payloadJson;
      reason = reason == null || reason.isBlank() ? (degraded ? "unknown" : "ok") : reason;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  private static List<Double> deterministicEmbedding(String text, int dimension) {
    int resolvedDimension = Math.max(1, dimension);
    List<Double> values = new ArrayList<>(resolvedDimension);
    if (text == null || text.isBlank()) {
      for (int i = 0; i < resolvedDimension; i++) {
        values.add(0.0d);
      }
      return values;
    }
    String normalized = text.toLowerCase(Locale.ROOT);
    byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
    double[] vector = new double[resolvedDimension];
    double norm = 0.0d;
    for (int i = 0; i < vector.length; i++) {
      int b = Byte.toUnsignedInt(bytes[i % bytes.length]);
      int c = Byte.toUnsignedInt(bytes[(i * 3 + 7) % bytes.length]);
      double value = Math.sin((b + c + i) * 0.03125d);
      vector[i] = value;
      norm += value * value;
    }
    double magnitude = Math.sqrt(norm);
    for (double v : vector) {
      values.add(magnitude == 0.0d ? 0.0d : v / magnitude);
    }
    return values;
  }

  private static String escapeJson(String text) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        builder.append('\\');
      }
      builder.append(c);
    }
    return builder.toString();
  }
}
