/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal translator contract for the out-of-process AI worker during Phase 3.1.
 *
 * <p>The implementation in this phase is intentionally lightweight; Phase 6 replaces it with the
 * full llama.cpp-backed translator.</p>
 */
public interface LocalLlmTranslator extends AutoCloseable {

  /**
   * Translate the free-form query text into a SearchIntent JSON payload.
   *
   * @param text raw user query (never {@code null}, but may be blank)
   * @param locale optional locale hint (BCP-47); implementations may ignore this in the stub phase
   * @return JSON string that validates against {@code search-intent.schema.json}
   * @throws TranslatorException when translation fails
   */
  String translateIntent(String text, String locale) throws TranslatorException;

  /**
   * Produce a short textual summary for the supplied content along with metadata.
   *
   * @param content source text to summarise
   * @param language optional ISO 639-1 language; implementations may ignore this in the stub phase
   * @param requestId caller-provided correlation id
   * @return structured summary result
   * @throws TranslatorException when summarisation fails
   */
  default SummaryResult summarizeWithMeta(String content, String language, UUID requestId)
      throws TranslatorException {
    return new SummaryResult(
        requestId == null ? UUID.randomUUID() : requestId,
        summarize(content, language),
        true,
        false,
        "ok",
        List.of(),
        Map.of());
  }

  /**
   * Produce a short textual summary for the supplied content.
   *
   * @param content source text to summarise
   * @param language optional ISO 639-1 language; implementations may ignore this in the stub phase
   * @return summary text placeholder used during Phase 3.1 before the real integration
   * @throws TranslatorException when summarisation fails
   */
  default String summarize(String content, String language) throws TranslatorException {
    return summarizeWithMeta(content, language, UUID.randomUUID()).summaryText();
  }

  /** Returns {@code true} when the translator is ready to serve requests. */
  default boolean isReady() {
    return true;
  }

  @Override
  default void close() throws TranslatorException {
    // no-op for stub implementations
  }

  /** Summary metadata returned by {@link #summarizeWithMeta(String, String, UUID)}. */
  record SummaryResult(
      UUID requestId,
      String summaryText,
      boolean grounded,
      boolean degraded,
      String reasonCode,
      List<ChunkSummary> chunks,
      Map<String, Object> attributes) {
    public SummaryResult {
      requestId = requestId == null ? UUID.randomUUID() : requestId;
      summaryText = summaryText == null ? "" : summaryText;
      degraded = degraded || (!grounded && !"ok".equalsIgnoreCase(reasonCode));
      reasonCode = reasonCode == null || reasonCode.isBlank() ? (grounded ? "ok" : "unknown") : reasonCode;
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public SummaryResult(
        UUID requestId, String summaryText, boolean grounded, String reasonCode, List<ChunkSummary> chunks) {
      this(requestId, summaryText, grounded, !"ok".equalsIgnoreCase(reasonCode) || !grounded, reasonCode, chunks, Map.of());
    }

    public SummaryResult(
        UUID requestId,
        String summaryText,
        boolean grounded,
        String reasonCode,
        List<ChunkSummary> chunks,
        Map<String, Object> attributes) {
      this(requestId, summaryText, grounded, !"ok".equalsIgnoreCase(reasonCode) || !grounded, reasonCode, chunks, attributes);
    }
  }

  /** Describes how a content chunk contributed to the final summary. */
  record ChunkSummary(int id, int startToken, int endToken, boolean included) {}

  final class TranslatorException extends Exception {
    public TranslatorException(String message, Throwable cause) {
      super(message, cause);
    }

    public TranslatorException(String message) {
      super(message);
    }
  }
}
