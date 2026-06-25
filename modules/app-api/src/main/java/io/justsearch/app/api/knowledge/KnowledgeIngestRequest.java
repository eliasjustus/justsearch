/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.List;

/**
 * Ingest request for the Knowledge Server-backed HTTP API (e.g., POST /api/knowledge/ingest).
 *
 * <p>Stability: stable (API contract)
 */
public record KnowledgeIngestRequest(List<String> paths) {
  public KnowledgeIngestRequest {
    paths = paths == null ? List.of() : List.copyOf(paths);
  }
}
