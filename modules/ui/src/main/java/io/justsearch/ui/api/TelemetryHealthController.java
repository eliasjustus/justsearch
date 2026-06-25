/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Controller for the {@code /api/telemetry/health} endpoint.
 *
 * <p>This endpoint exposes the health state of the telemetry subsystem, allowing operators to
 * detect when metrics/traces are failing to export.
 */
public final class TelemetryHealthController {

  private final Supplier<TelemetryHealthSnapshot> snapshotSupplier;

  /**
   * Creates a new controller.
   *
   * @param snapshotSupplier supplier that returns the current telemetry health snapshot, or null if
   *     telemetry is unavailable
   */
  public TelemetryHealthController(Supplier<TelemetryHealthSnapshot> snapshotSupplier) {
    this.snapshotSupplier = snapshotSupplier;
  }

  /**
   * Handles GET /api/telemetry/health.
   *
   * <p>Returns a JSON response with schema version 1, following the same patterns as /api/health.
   */
  public void handleGetHealth(Context ctx) {
    TelemetryHealthSnapshot snapshot;
    try {
      snapshot = snapshotSupplier.get();
    } catch (Exception e) {
      snapshot = null;
    }

    if (snapshot == null) {
      ctx.status(503)
          .json(
              Map.of(
                  "schema_version", 1,
                  "observed_at", Instant.now().toString(),
                  "state", LifecycleState.LIFECYCLE_STATE_ERROR,
                  "reason_code", LifecycleReasonCode.TELEMETRY_UNAVAILABLE.code()));
      return;
    }

    TelemetryHealthResponse response = computeResponse(snapshot);
    int httpStatus =
        response.state() == LifecycleState.LIFECYCLE_STATE_READY || response.state() == LifecycleState.LIFECYCLE_STATE_DEGRADED
            ? 200
            : 503;
    ctx.status(httpStatus).json(response);
  }

  private TelemetryHealthResponse computeResponse(TelemetryHealthSnapshot s) {
    TelemetryHealthClassifier.Result classification = TelemetryHealthClassifier.classify(s);

    return new TelemetryHealthResponse(
        1, // schema_version
        s.observedAt().toString(),
        classification.state(),
        classification.reasonCode(),
        new TelemetryHealthResponse.Counters(
            s.metricExportFailures(),
            s.spanExportFailures(),
            s.rotationFailures(),
            s.pruneFailures(),
            s.gaugeCallbackFailures(),
            s.flushFailures(),
            s.diskSpaceLowEvents(),
            s.metricExportSuccesses(),
            s.spanExportSuccesses()),
        new TelemetryHealthResponse.Rates(
            s.metricExportSuccessRate(), s.spanExportSuccessRate()),
        new TelemetryHealthResponse.Timestamps(
            s.lastSuccessfulMetricExport() != null
                ? s.lastSuccessfulMetricExport().toString()
                : null,
            s.lastSuccessfulSpanExport() != null ? s.lastSuccessfulSpanExport().toString() : null,
            s.lastSuccessfulRotation() != null ? s.lastSuccessfulRotation().toString() : null));
  }
}
