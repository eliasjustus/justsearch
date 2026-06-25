package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 C3 V2 P1 — pure-function classification rules. The same logic feeds {@code
 * /api/telemetry/health} and the {@code /api/status} readiness envelope's {@code TELEMETRY}
 * dim, so the cases here are the contract boundary between both surfaces.
 */
@DisplayName("TelemetryHealthClassifier")
final class TelemetryHealthClassifierTest {

  private static final Instant NOW = Instant.now();

  @Test
  @DisplayName("READY when all counters healthy and exports recent")
  void readyWhenHealthy() {
    var snap = snapshot(NOW.minusSeconds(30), 100, 0, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_READY, result.state());
    assertNull(result.reasonCode());
  }

  @Test
  @DisplayName("DEGRADED + telemetry.metrics.stale when last export older than STALE_THRESHOLD")
  void degradedWhenStale() {
    var stale = NOW.minus(TelemetryHealthClassifier.STALE_THRESHOLD).minusSeconds(60);
    var snap = snapshot(stale, 100, 0, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, result.state());
    assertEquals(LifecycleReasonCode.TELEMETRY_METRICS_STALE.code(), result.reasonCode());
  }

  @Test
  @DisplayName(
      "DEGRADED + telemetry.metrics.high_failure_rate when success rate below threshold")
  void degradedOnHighFailureRate() {
    // 80% success → below 0.9 threshold
    var snap = snapshot(NOW.minusSeconds(30), 80, 20, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, result.state());
    assertEquals(
        LifecycleReasonCode.TELEMETRY_METRICS_HIGH_FAILURE_RATE.code(), result.reasonCode());
  }

  @Test
  @DisplayName("Failure rate overrides stale precedence (later check wins)")
  void failureRateOverridesStale() {
    var stale = NOW.minus(TelemetryHealthClassifier.STALE_THRESHOLD).minusSeconds(60);
    // Stale + low success rate; classifier should report HIGH_FAILURE_RATE.
    var snap = snapshot(stale, 80, 20, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, result.state());
    assertEquals(
        LifecycleReasonCode.TELEMETRY_METRICS_HIGH_FAILURE_RATE.code(), result.reasonCode());
  }

  @Test
  @DisplayName("Disk space low overrides everything (final precedence rung)")
  void diskSpaceLowOverridesAll() {
    var stale = NOW.minus(TelemetryHealthClassifier.STALE_THRESHOLD).minusSeconds(60);
    var snap = snapshot(stale, 80, 20, 5);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_DEGRADED, result.state());
    assertEquals(LifecycleReasonCode.TELEMETRY_DISK_SPACE_LOW.code(), result.reasonCode());
  }

  @Test
  @DisplayName("Null lastSuccessfulMetricExport bypasses stale check (READY when no exports yet)")
  void nullLastExportSkipsStaleCheck() {
    var snap = snapshot(null, 0, 0, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_READY, result.state());
    assertNull(result.reasonCode());
  }

  @Test
  @DisplayName("Boundary: success rate exactly at 0.9 is healthy (strict less-than)")
  void successRateAtBoundaryHealthy() {
    var snap = snapshot(NOW.minusSeconds(30), 90, 10, 0);
    var result = TelemetryHealthClassifier.classify(snap);
    assertEquals(LifecycleState.LIFECYCLE_STATE_READY, result.state());
    assertNull(result.reasonCode());
  }

  private static TelemetryHealthSnapshot snapshot(
      Instant lastExport, long successes, long failures, long diskLow) {
    return new TelemetryHealthSnapshot(
        failures, // metricExportFailures
        0L, // spanExportFailures
        0L, // rotationFailures
        0L, // pruneFailures
        0L, // gaugeCallbackFailures
        0L, // flushFailures
        diskLow, // diskSpaceLowEvents
        successes, // metricExportSuccesses
        0L, // spanExportSuccesses
        0L, // rotationSuccesses
        lastExport, // lastSuccessfulMetricExport
        null, // lastSuccessfulSpanExport
        null, // lastSuccessfulRotation
        NOW); // observedAt
  }

}
