/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.List;

/**
 * Null Object implementation of {@link EmbeddingProvider}. Always reports unavailable, returns empty
 * results. Used as the initial value for embedding fields before a real model is loaded, eliminating
 * null checks across all consumers.
 */
public final class NoOpEmbeddingProvider implements EmbeddingProvider {

  public static final NoOpEmbeddingProvider INSTANCE = new NoOpEmbeddingProvider();

  private NoOpEmbeddingProvider() {}

  @Override
  public float[] embedDocument(String text) {
    return new float[0];
  }

  @Override
  public float[] embedQuery(String text) {
    return new float[0];
  }

  @Override
  public List<float[]> embedDocumentBatch(List<String> texts) {
    return List.of();
  }

  @Override
  public int dimension() {
    return 0;
  }

  @Override
  public boolean isAvailable() {
    return false;
  }

  @Override
  public boolean isUsingGpu() {
    return false;
  }
}
