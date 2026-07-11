/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.List;

/**
 * Operational interface for embedding generation. Consumers hold this type instead of the concrete
 * {@link EmbeddingService}, enabling the Null Object pattern ({@link NoOpEmbeddingProvider}) for
 * graceful degradation when no embedding model is loaded.
 *
 * <p>Lifecycle methods (initialize, close, factory) remain on {@link EmbeddingService}. This
 * interface covers only the methods consumers call during normal operation.
 */
public interface EmbeddingProvider {

  /** Embeds a document text for indexing. */
  float[] embedDocument(String text);

  /** Embeds a query text for search. */
  float[] embedQuery(String text);

  /** Batch-embeds multiple document texts. */
  List<float[]> embedDocumentBatch(List<String> texts);

  /**
   * Late chunking (tempdoc 691 Phase 1): embeds {@code content} once and derives the whole-document
   * vector plus one vector per character span from the same forward pass, instead of embedding the
   * parent and each chunk independently. Callers must gate on the {@code
   * justsearch.embed.late_chunking_enabled} flag themselves — this method is unconditional.
   *
   * @param content the full document text (unprefixed)
   * @param charSpans {@code [startCharInclusive, endCharExclusive)} ranges into {@code content}
   * @return the doc vector plus one chunk vector per span, or {@code null} if late chunking is
   *     unsupported by this provider/backend, or {@code content} exceeds the model's context window
   * @throws RuntimeException if the underlying inference call fails — treat like any other
   *     embedding failure (do not mark complete; let the doc retry/escalate)
   */
  EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans);

  /** Returns the embedding dimension (e.g., 768 for nomic-embed-text). */
  int dimension();

  /** Returns true if the embedding model is loaded and ready for inference. */
  boolean isAvailable();

  /** Returns true if the embedding model is using GPU acceleration. */
  boolean isUsingGpu();
}
