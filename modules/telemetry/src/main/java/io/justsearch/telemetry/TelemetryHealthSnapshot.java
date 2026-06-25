/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import java.time.Instant;

/**
 * Immutable snapshot of telemetry health counters for API serialization.
 *
 * <p>This record captures a point-in-time view of the telemetry subsystem's health state. It is
 * safe to serialize to JSON and return via the health endpoint.
 */
public record TelemetryHealthSnapshot(
    long metricExportFailures,
    long spanExportFailures,
    long rotationFailures,
    long pruneFailures,
    long gaugeCallbackFailures,
    long flushFailures,
    long diskSpaceLowEvents,
    long metricExportSuccesses,
    long spanExportSuccesses,
    long rotationSuccesses,
    Instant lastSuccessfulMetricExport,
    Instant lastSuccessfulSpanExport,
    Instant lastSuccessfulRotation,
    Instant observedAt) {

  /**
   * Calculates the metric export success rate.
   *
   * @return success rate in range [0.0, 1.0], or 1.0 if no exports have been attempted
   */
  public double metricExportSuccessRate() {
    long total = metricExportSuccesses + metricExportFailures;
    return total == 0 ? 1.0 : (double) metricExportSuccesses / total;
  }

  /**
   * Calculates the span export success rate.
   *
   * @return success rate in range [0.0, 1.0], or 1.0 if no exports have been attempted
   */
  public double spanExportSuccessRate() {
    long total = spanExportSuccesses + spanExportFailures;
    return total == 0 ? 1.0 : (double) spanExportSuccesses / total;
  }

  /**
   * Calculates the rotation success rate.
   *
   * @return success rate in range [0.0, 1.0], or 1.0 if no rotations have been attempted
   */
  public double rotationSuccessRate() {
    long total = rotationSuccesses + rotationFailures;
    return total == 0 ? 1.0 : (double) rotationSuccesses / total;
  }

  /** Returns total failure count across all categories. */
  public long totalFailures() {
    return metricExportFailures
        + spanExportFailures
        + rotationFailures
        + pruneFailures
        + gaugeCallbackFailures
        + flushFailures;
  }

  /** Returns true if any failures have been recorded. */
  public boolean hasFailures() {
    return totalFailures() > 0 || diskSpaceLowEvents > 0;
  }
}
