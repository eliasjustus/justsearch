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

  /** Returns the embedding dimension (e.g., 768 for nomic-embed-text). */
  int dimension();

  /** Returns true if the embedding model is loaded and ready for inference. */
  boolean isAvailable();

  /** Returns true if the embedding model is using GPU acceleration. */
  boolean isUsingGpu();
}
