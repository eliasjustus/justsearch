/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.local.LocalIntentTranslatorV2;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Abstraction over the actual inference provider (llama.cpp, JNI bridge, etc.). Implementations may
 * perform native calls, spawn helper processes, or forward to deterministic fallbacks. The
 * translator interacts with the backend purely through this interface, which keeps the threading,
 * queueing, and degradation logic testable.
 */
public interface AiBackend extends AutoCloseable {

  BackendResponse translate(BackendRequest request) throws BackendException;

  /**
   * Creates a dedicated backend session (for example, a llama_context) that can be reused across
   * multiple requests. Implementations that do not maintain session state may rely on the default
   * implementation, which bridges to {@link #translate(BackendRequest)}.
   */
  default Session createSession() throws BackendException {
    AiBackend backend = this;
    return new Session() {
      @Override
      public BackendResponse translate(BackendRequest request) throws BackendException {
        return backend.translate(request);
      }

      @Override
      public ChunkResponse summarizeChunk(ChunkRequest request) throws BackendException {
        throw new BackendException("summarizeChunk not implemented");
      }

      @Override
      public ReduceResponse reduceChunks(ReduceRequest request) throws BackendException {
        throw new BackendException("reduceChunks not implemented");
      }

      @Override
      public void close() {}
    };
  }

  LocalIntentTranslatorV2.Provenance provenance();

  @Override
  void close() throws BackendException;

  /** Handle representing a reusable backend context. */
  interface Session extends AutoCloseable {

    BackendResponse translate(BackendRequest request) throws BackendException;

    /**
     * Stream a summarized chunk back to the caller using the START/DATA/END contract. The returned
     * {@link ChunkResponse} holds the full text for reduce; the emitter receives incremental
     * {@link io.justsearch.aibackend.local.LocalIntentTranslatorV2.StreamChunk} entries with the
     * provided chunk id.
     */
    default ChunkResponse streamSummary(
        ChunkRequest request,
        java.util.function.Consumer<LocalIntentTranslatorV2.StreamChunk> emitter)
        throws BackendException {
      ChunkResponse resp = summarizeChunk(request);
      if (emitter != null && resp != null) {
        emitter.accept(
            new LocalIntentTranslatorV2.StreamChunk(
                request.chunkId(), resp.summaryText(), true));
      }
      return resp;
    }

    /**
     * Generate an embedding vector for the supplied request. Implementations should prefer the
     * native path where available and return a degraded result with a populated reason code when
     * falling back.
     */
    default LocalIntentTranslatorV2.EmbeddingResult embed(
        LocalIntentTranslatorV2.EmbeddingRequest request) throws BackendException {
      LocalIntentTranslatorV2.EmbeddingRequest normalized =
          request == null
              ? new LocalIntentTranslatorV2.EmbeddingRequest(
                  "", Locale.ENGLISH.toLanguageTag(), 384, java.util.Map.of())
              : request;
      List<Double> values =
          TokenizationResult.deterministicVector(normalized.text(), normalized.dimension());
      return new LocalIntentTranslatorV2.EmbeddingResult(
          values, normalized.dimension(), true, "stub", java.util.Map.of("reason", "stub"));
    }

    /**
     * Returns tokenization metadata for the supplied text. Implementations backed by the native
     * tokenizer should return ids and offsets aligned with llama.cpp; the default uses a simple
     * whitespace heuristic to keep chunking O(n).
     */
    default TokenizationResult tokenize(CharSequence text, String language) {
      return TokenizationResult.fromText(text);
    }

    default ChunkResponse summarizeChunk(ChunkRequest request) throws BackendException {
      throw new BackendException("summarizeChunk not implemented");
    }

    default ReduceResponse reduceChunks(ReduceRequest request) throws BackendException {
      throw new BackendException("reduceChunks not implemented");
    }

    /** Clears any per-session state such as KV cache; default is a no-op for stateless backends. */
    default void reset() throws BackendException {}

    /** Cooperative cancellation hook keyed by request id; default no-op. */
    default void cancel(UUID requestId) throws BackendException {}

    /**
     * Batch-embeds multiple texts in a single inference call. The default implementation falls back
     * to sequential {@link #embed} calls. Backends that support native batching (e.g., ONNX)
     * override this for significantly higher throughput.
     *
     * @param requests the embedding requests to batch
     * @return results in the same order as requests
     */
    default List<LocalIntentTranslatorV2.EmbeddingResult> embedBatch(
        List<LocalIntentTranslatorV2.EmbeddingRequest> requests) throws BackendException {
      List<LocalIntentTranslatorV2.EmbeddingResult> results = new ArrayList<>(requests.size());
      for (LocalIntentTranslatorV2.EmbeddingRequest req : requests) {
        results.add(embed(req));
      }
      return results;
    }

    /**
     * Estimates how many backend tokens {@code text} would consume. Implementations may rely on the
     * backend tokenizer; the default implementation approximates tokens by counting contiguous
     * non-whitespace groups.
     */
    default int estimateTokens(CharSequence text) {
      return tokenize(text, "").tokenCount();
    }

    /** Optional warm-up hook invoked immediately after session creation. */
    default void warmup() throws BackendException {}

    @Override
    default void close() throws BackendException {}
  }

  record ChunkRequest(
      UUID requestId, int chunkId, String text, int startToken, int endToken, String language) {
    public ChunkRequest {
      requestId = requestId == null ? UUID.randomUUID() : requestId;
      text = text == null ? "" : text;
      language = language == null ? "" : language;
    }
  }

  record ChunkResponse(int chunkId, String summaryText, boolean included) {
    public ChunkResponse {
      summaryText = summaryText == null ? "" : summaryText;
    }
  }

  record ReduceRequest(
      UUID requestId, String language, String originalContent, List<ChunkResponse> chunks) {
    public ReduceRequest {
      requestId = requestId == null ? UUID.randomUUID() : requestId;
      language = language == null ? "" : language;
      originalContent = originalContent == null ? "" : originalContent;
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
  }

  record ReduceResponse(String summaryText, String reasonCode) {
    public ReduceResponse {
      summaryText = summaryText == null ? "" : summaryText;
      reasonCode = reasonCode == null || reasonCode.isBlank() ? "ok" : reasonCode;
    }
  }

  /** Captures tokenization metadata for reuse across chunking operations. */
  @SuppressWarnings("ArrayRecordComponent")
  record TokenizationResult(String text, int[] tokenOffsets, int[] prefixCounts, int[] tokenIds) {
    public TokenizationResult {
      text = text == null ? "" : text;
      tokenOffsets = tokenOffsets == null ? new int[0] : tokenOffsets;
      prefixCounts = prefixCounts == null ? new int[text.length() + 1] : prefixCounts;
      tokenIds = tokenIds == null ? new int[0] : tokenIds;
    }

    public int tokenCount() {
      return prefixCounts.length == 0 ? 0 : prefixCounts[prefixCounts.length - 1];
    }

    public int tokensUntil(int endIndex) {
      if (prefixCounts.length == 0) {
        return 0;
      }
      int clamped = Math.max(0, Math.min(endIndex, prefixCounts.length - 1));
      return Math.max(0, prefixCounts[clamped]);
    }

    public int indexForToken(int target) {
      if (tokenOffsets.length == 0 || target <= 0) {
        return 0;
      }
      if (target >= tokenOffsets.length) {
        return text.length();
      }
      return Math.max(0, Math.min(text.length(), tokenOffsets[target]));
    }

    public static TokenizationResult fromText(CharSequence text) {
      String value = text == null ? "" : text.toString();
      int length = value.length();
      int[] prefixCounts = new int[length + 1];
      List<Integer> offsets = new ArrayList<>();
      boolean inToken = false;
      int tokens = 0;
      for (int i = 0; i < length; i++) {
        char c = value.charAt(i);
        if (Character.isWhitespace(c)) {
          if (inToken) {
            inToken = false;
          }
        } else {
          if (!inToken) {
            offsets.add(i);
            tokens++;
            inToken = true;
          }
        }
        prefixCounts[i + 1] = tokens;
      }
      int[] tokenOffsets = offsets.stream().mapToInt(Integer::intValue).toArray();
      return new TokenizationResult(value, tokenOffsets, prefixCounts, new int[0]);
    }

    public static List<Double> deterministicVector(String text, int dimension) {
      int resolvedDimension = Math.max(1, dimension);
      List<Double> vector = new ArrayList<>(resolvedDimension);
      if (text == null || text.isBlank()) {
        for (int i = 0; i < resolvedDimension; i++) {
          vector.add(0.0d);
        }
        return vector;
      }
      byte[] bytes =
          text.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      double[] values = new double[resolvedDimension];
      double norm = 0.0d;
      for (int i = 0; i < values.length; i++) {
        int b = Byte.toUnsignedInt(bytes[i % bytes.length]);
        int c = Byte.toUnsignedInt(bytes[(i * 3 + 7) % bytes.length]);
        double v = Math.sin((b + c + i) * 0.03125d);
        values[i] = v;
        norm += v * v;
      }
      double magnitude = Math.sqrt(norm);
      for (double v : values) {
        vector.add(magnitude == 0.0d ? 0.0d : v / magnitude);
      }
      return vector;
    }
  }
}
