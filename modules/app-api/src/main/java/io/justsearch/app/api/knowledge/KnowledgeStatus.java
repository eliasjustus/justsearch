/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.Map;

/**
 * Status snapshot for the Knowledge Server-backed HTTP API (e.g., GET /api/knowledge/status).
 *
 * <p>Stability: stable (API contract)
 */
public record KnowledgeStatus(
    String state,
    boolean ready,
    long queueDepth,
    long docCount,
    long activeDocCount,
    long buildingDocCount,
    String servingSearchGenerationId,
    String servingIngestGenerationId,
    long switchBufferDepth,
    long pendingJobsCount,
    long processingJobsCount,
    long pendingReadyJobsCount,
    long pendingBackoffJobsCount,
    long migrationSwitchingAgeMs,
    long migrationSwitchingMaxDurationMs,
    boolean migrationPaused,
    String migrationPauseReason,
    long migrationPausedAtMs,
    boolean healthy,
    String indexState,
    Map<String, Object> extras) {

  public KnowledgeStatus {
    state = state == null ? "" : state;
    servingSearchGenerationId = servingSearchGenerationId == null ? "" : servingSearchGenerationId;
    servingIngestGenerationId = servingIngestGenerationId == null ? "" : servingIngestGenerationId;
    migrationPauseReason = migrationPauseReason == null ? "" : migrationPauseReason;
    indexState = indexState == null ? "" : indexState;
    extras = extras == null ? Map.of() : Map.copyOf(extras);
  }
}
