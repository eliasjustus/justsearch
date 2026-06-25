/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Grouped embedding status for structured /api/status response.
 *
 * <p>Contains embedding compatibility state, fingerprints, and doc-level coverage.
 * Derived from {@code WorkerOperationalView.compatibility} and {@code .enrichment} sub-records.
 */
public record EmbeddingStatusGroup(
    long observedAtMs,
    String compatState,
    String compatReason,
    String fingerprintCurrent,
    String fingerprintStored,
    boolean ready,
    long docCount,
    long completedCount,
    long pendingCount,
    long failedCount,
    double coveragePercent) {

  /** Creates from a WorkerOperationalView + top-level embeddingReady. */
  public static EmbeddingStatusGroup from(WorkerOperationalView w, boolean embeddingReady) {
    return new EmbeddingStatusGroup(
        System.currentTimeMillis(),
        w.compatibility().embeddingCompatState(),
        w.compatibility().embeddingCompatReason(),
        w.compatibility().embeddingFingerprintCurrent(),
        w.compatibility().embeddingFingerprintStored(),
        embeddingReady,
        w.enrichment().embeddingDocCount(),
        w.enrichment().embeddingCompletedCount(),
        w.enrichment().embeddingPendingCount(),
        w.enrichment().embeddingFailedCount(),
        w.enrichment().embeddingCoveragePercent());
  }
}
