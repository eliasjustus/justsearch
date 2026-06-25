/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Grouped chunk vector coverage status for structured /api/status response.
 */
public record ChunkCoverageGroup(
    long observedAtMs,
    long docCount,
    long completedCount,
    long pendingCount,
    long failedCount,
    double coveragePercent,
    boolean ready) {

  public static ChunkCoverageGroup from(WorkerOperationalView w) {
    return new ChunkCoverageGroup(
        System.currentTimeMillis(),
        w.enrichment().chunk().chunkDocCount(),
        w.enrichment().chunk().chunkEmbeddingCompletedCount(),
        w.enrichment().chunk().chunkEmbeddingPendingCount(),
        w.enrichment().chunk().chunkEmbeddingFailedCount(),
        w.enrichment().chunk().chunkVectorCoveragePercent(),
        w.enrichment().chunk().chunkVectorsReady());
  }
}
