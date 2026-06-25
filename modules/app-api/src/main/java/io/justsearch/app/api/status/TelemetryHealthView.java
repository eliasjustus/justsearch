/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Telemetry-subsystem health summary for the /api/status endpoint.
 *
 * <p>Tempdoc 419 C3 V1 (2026-04-26): exposes monotonic failure counters maintained by
 * {@link io.justsearch.telemetry.TelemetryHealthState} so frontend Health explanations can
 * surface evidence for future telemetry-degraded events. Distinct from {@link
 * TelemetryMetricsView} (which carries content-length / throughput stats), this record carries
 * subsystem-health signals.
 *
 * <p>Stability: stable (API contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryHealthView(long flushFailureCount, long gaugeCallbackFailureCount) {

  public static TelemetryHealthView empty() {
    return new TelemetryHealthView(0L, 0L);
  }
}
