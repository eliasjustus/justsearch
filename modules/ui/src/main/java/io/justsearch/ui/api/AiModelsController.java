/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.ai.preflight.AiPreflightService;

/**
 * Tempdoc 656 Task 4: {@code GET /api/ai/models/status} — a read-only projection of
 * {@link AiPreflightService}, itself a thin reconciliation of the model registry (declared
 * source of truth) against on-disk presence. Named "models/status" rather than "preflight" to
 * avoid colliding with the existing, differently-scoped {@code /api/ai/packs/preflight}
 * (which validates a specific pack file at a given path, not overall model-registry presence).
 */
public final class AiModelsController {
  private final AiPreflightService service;

  public AiModelsController(AiPreflightService service) {
    this.service = service;
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getPreflight());
  }
}
