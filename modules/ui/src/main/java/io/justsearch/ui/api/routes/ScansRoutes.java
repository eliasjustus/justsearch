/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.ui.api.ScanProgressController;

/**
 * Routes for live scan progress (tempdoc 419 / T4). Clients subscribe via Server-Sent Events
 * to a {@code scanId} returned by {@code POST /api/knowledge/ingest} (the scanId field on
 * {@code KnowledgeIngestResponse}).
 */
public final class ScansRoutes {
  private ScansRoutes() {}

  public static void register(Javalin app, ScanProgressController scanProgressController) {
    app.get("/api/scans/{scanId}/progress", scanProgressController::handleScanProgress);
  }
}
