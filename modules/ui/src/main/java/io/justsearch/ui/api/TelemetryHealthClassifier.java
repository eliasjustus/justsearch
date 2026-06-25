/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import java.time.Duration;
import java.time.Instant;

/**
 * Classifies a {@link TelemetryHealthSnapshot} into a lifecycle state + reason code.
 *
 * <p>Tempdoc 419 C3 V2 P1: extracted from {@link TelemetryHealthController#computeResponse} so the
 * same classification feeds both the dedicated {@code /api/telemetry/health} endpoint and the
 * {@code /api/status} readiness envelope (new {@code TELEMETRY} dimension). Single source of
 * truth eliminates threshold-drift between the two surfaces.
 *
 * <p>Stability: stable. Thresholds are hardcoded by design (see {@code OperationalMetrics
 * .ThroughputMonitor} for the prior-art pattern of "pick sensible defaults, ship, iterate").
 */
public final class TelemetryHealthClassifier {

  /** Metrics are considered stale if no successful export in this duration. */
  public static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

  /** Success rate below this threshold triggers DEGRADED state. */
  public static final double FAILURE_RATE_THRESHOLD = 0.9; // 90% success required

  /** Result of classification — state + machine-readable reason code (null when READY). */
  public record Result(LifecycleState state, String reasonCode) {}

  private TelemetryHealthClassifier() {}

  /**
   * Classifies the snapshot. Order of precedence (later overrides earlier):
   *
   * <ol>
   *   <li>Stale export → {@code TELEMETRY_METRICS_STALE}
   *   <li>High failure rate → {@code TELEMETRY_METRICS_HIGH_FAILURE_RATE}
   *   <li>Disk space low → {@code TELEMETRY_DISK_SPACE_LOW}
   * </ol>
   *
   * <p>Returns READY with {@code reasonCode == null} when none of the conditions trigger.
   */
  public static Result classify(TelemetryHealthSnapshot s) {
    LifecycleState state = LifecycleState.LIFECYCLE_STATE_READY;
    String reasonCode = null;

    if (s.lastSuccessfulMetricExport() != null) {
      Duration sinceLastExport = Duration.between(s.lastSuccessfulMetricExport(), Instant.now());
      if (sinceLastExport.compareTo(STALE_THRESHOLD) > 0) {
        state = LifecycleState.LIFECYCLE_STATE_DEGRADED;
        reasonCode = LifecycleReasonCode.TELEMETRY_METRICS_STALE.code();
      }
    }

    if (s.metricExportSuccessRate() < FAILURE_RATE_THRESHOLD) {
      state = LifecycleState.LIFECYCLE_STATE_DEGRADED;
      reasonCode = LifecycleReasonCode.TELEMETRY_METRICS_HIGH_FAILURE_RATE.code();
    }

    if (s.diskSpaceLowEvents() > 0) {
      state = LifecycleState.LIFECYCLE_STATE_DEGRADED;
      reasonCode = LifecycleReasonCode.TELEMETRY_DISK_SPACE_LOW.code();
    }

    return new Result(state, reasonCode);
  }
}
