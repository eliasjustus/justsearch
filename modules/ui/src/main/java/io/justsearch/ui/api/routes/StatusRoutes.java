/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.DiagnosticsController;
import io.justsearch.ui.api.PolicyController;
import io.justsearch.ui.api.SettingsController;
import io.justsearch.ui.api.TelemetryHealthController;
import io.justsearch.ui.api.UiReadyController;

public final class StatusRoutes {
  private StatusRoutes() {}

  public static void register(
      Javalin app,
      Telemetry telemetry,
      Handler statusHandler,
      Handler healthHandler,
      UiReadyController uiReadyController,
      SettingsController settingsController,
      PolicyController policyController,
      DiagnosticsController diagnosticsController) {
    app.get("/api/status", statusHandler);
    app.get("/api/health", healthHandler);

    if (telemetry instanceof LocalTelemetry lt) {
      var telemetryHealthController = new TelemetryHealthController(lt::getHealthSnapshot);
      app.get("/api/telemetry/health", telemetryHealthController::handleGetHealth);
    }

    app.post("/api/ui/ready", uiReadyController::handlePost);
    app.get("/api/ui/ready", uiReadyController::handleGet);
    app.get("/api/settings/v2", settingsController::handleGetSettingsV2);
    app.post("/api/settings/v2", settingsController::handleUpdateSettingsV2);
    app.get("/api/policy/effective", policyController::handleGetEffectivePolicy);
    app.get("/api/policy/validate", policyController::handleValidatePolicy);
    app.post("/api/policy/user/create", policyController::handleCreateUserPolicy);
    app.post(
        "/api/policy/user/allowlist/pack-manifest/add",
        policyController::handleAddPackManifestShaToUserAllowlist);
    app.post("/api/diagnostics/export", diagnosticsController::handleExport);
    // Tempdoc 518 Appendix G Wave D.1: in-product trace explorer endpoint.
    app.get("/api/diagnostics/traces", diagnosticsController::handleRecentTraces);
  }
}
