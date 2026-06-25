/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/** Chunk vector coverage metrics for RAG readiness. */
public record ChunkCoverageView(
    long chunkDocCount,
    long chunkEmbeddingCompletedCount,
    long chunkEmbeddingPendingCount,
    long chunkEmbeddingFailedCount,
    double chunkVectorCoveragePercent,
    boolean chunkVectorsReady) {
  public static ChunkCoverageView empty() {
    return new ChunkCoverageView(0, 0, 0, 0, 0.0, false);
  }
}
