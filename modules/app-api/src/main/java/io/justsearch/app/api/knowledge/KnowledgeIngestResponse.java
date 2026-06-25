/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

/**
 * Ingest response for the Knowledge Server-backed HTTP API (e.g., POST /api/knowledge/ingest).
 *
 * <p>Stability: stable (API contract)
 *
 * <p>Tempdoc 419 / T4: {@code scanId} is the worker-allocated UUID that callers can use to
 * subscribe to live progress via {@code GET /api/scans/{scanId}/progress} (SSE). Empty when
 * the scan didn't produce any progress event (e.g., the input wasn't a directory).
 */
public record KnowledgeIngestResponse(int accepted, String error, String scanId) {
  public KnowledgeIngestResponse {
    error = error == null ? "" : error;
    scanId = scanId == null ? "" : scanId;
  }

  /** Back-compat constructor for callers that don't yet emit a scanId. */
  public KnowledgeIngestResponse(int accepted, String error) {
    this(accepted, error, "");
  }
}
