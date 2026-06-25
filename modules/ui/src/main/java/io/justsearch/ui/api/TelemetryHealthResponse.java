/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.justsearch.contract.wire.LifecycleState;

/**
 * Response record for the {@code /api/telemetry/health} endpoint.
 *
 * <p>This follows the same schema versioning pattern as {@code /api/health} and {@code /api/status}.
 *
 * @param schemaVersion always 1 for this schema version
 * @param observedAt ISO-8601 timestamp when the snapshot was taken
 * @param state overall health state (READY, DEGRADED, or ERROR)
 * @param reasonCode reason code if state is not READY (follows LifecycleReasonCode pattern)
 * @param counters failure and success counters
 * @param rates success rates for export operations
 * @param timestamps last successful operation timestamps
 */
public record TelemetryHealthResponse(
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("observed_at") String observedAt,
    @JsonProperty("state") LifecycleState state,
    @JsonProperty("reason_code") String reasonCode,
    @JsonProperty("counters") Counters counters,
    @JsonProperty("rates") Rates rates,
    @JsonProperty("timestamps") Timestamps timestamps) {

  /**
   * Failure and success counters for telemetry operations.
   *
   * <p>All counters are monotonically increasing since process start.
   */
  public record Counters(
      @JsonProperty("metric_export_failures") long metricExportFailures,
      @JsonProperty("span_export_failures") long spanExportFailures,
      @JsonProperty("rotation_failures") long rotationFailures,
      @JsonProperty("prune_failures") long pruneFailures,
      @JsonProperty("gauge_callback_failures") long gaugeCallbackFailures,
      @JsonProperty("flush_failures") long flushFailures,
      @JsonProperty("disk_space_low_events") long diskSpaceLowEvents,
      @JsonProperty("metric_export_successes") long metricExportSuccesses,
      @JsonProperty("span_export_successes") long spanExportSuccesses) {}

  /**
   * Success rates for export operations.
   *
   * <p>Values are in the range [0.0, 1.0], where 1.0 means no failures.
   */
  public record Rates(
      @JsonProperty("metric_export_success_rate") double metricExportSuccessRate,
      @JsonProperty("span_export_success_rate") double spanExportSuccessRate) {}

  /**
   * Timestamps of last successful operations.
   *
   * <p>Values are ISO-8601 timestamps or null if no successful operation has occurred.
   */
  public record Timestamps(
      @JsonProperty("last_successful_metric_export") String lastSuccessfulMetricExport,
      @JsonProperty("last_successful_span_export") String lastSuccessfulSpanExport,
      @JsonProperty("last_successful_rotation") String lastSuccessfulRotation) {}
}
