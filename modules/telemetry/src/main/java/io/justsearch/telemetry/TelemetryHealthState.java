/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe accumulator for telemetry subsystem health signals.
 *
 * <p>Design: All counters are monotonic (only increment). The health endpoint compares current
 * values against thresholds to compute failure rates and detect staleness.
 *
 * <p>This class intentionally does NOT use the telemetry system itself to avoid circular
 * dependencies. It uses plain atomic counters that can be read directly.
 */
public final class TelemetryHealthState {

  /** Tiered disk pressure levels for capacity planning. */
  public enum DiskPressureLevel {
    OK,
    WARNING,
    CRITICAL
  }

  // --- Export failures ---
  private final AtomicLong metricExportFailures = new AtomicLong();
  private final AtomicLong spanExportFailures = new AtomicLong();

  // --- Rotation/retention failures ---
  private final AtomicLong rotationFailures = new AtomicLong();
  private final AtomicLong pruneFailures = new AtomicLong();

  // --- Gauge callback failures ---
  private final AtomicLong gaugeCallbackFailures = new AtomicLong();

  // --- Flush failures ---
  private final AtomicLong flushFailures = new AtomicLong();

  // --- Disk space issues ---
  private final AtomicLong diskSpaceLowEvents = new AtomicLong();
  private volatile DiskPressureLevel diskPressureLevel = DiskPressureLevel.OK;

  // --- Successful operations (for ratio calculation) ---
  private final AtomicLong metricExportSuccesses = new AtomicLong();
  private final AtomicLong spanExportSuccesses = new AtomicLong();
  private final AtomicLong rotationSuccesses = new AtomicLong();

  // --- Last successful timestamps (volatile for visibility) ---
  private volatile Instant lastSuccessfulMetricExport;
  private volatile Instant lastSuccessfulSpanExport;
  private volatile Instant lastSuccessfulRotation;

  // --- Record failures ---

  public void recordMetricExportFailure() {
    metricExportFailures.incrementAndGet();
  }

  public void recordSpanExportFailure() {
    spanExportFailures.incrementAndGet();
  }

  public void recordRotationFailure() {
    rotationFailures.incrementAndGet();
  }

  public void recordPruneFailure() {
    pruneFailures.incrementAndGet();
  }

  public void recordGaugeCallbackFailure() {
    gaugeCallbackFailures.incrementAndGet();
  }

  public void recordFlushFailure() {
    flushFailures.incrementAndGet();
  }

  public void recordDiskSpaceLowEvent() {
    diskSpaceLowEvents.incrementAndGet();
  }

  public void setDiskPressureLevel(DiskPressureLevel level) {
    this.diskPressureLevel = level;
  }

  public DiskPressureLevel getDiskPressureLevel() {
    return diskPressureLevel;
  }

  // --- Record successes ---

  public void recordMetricExportSuccess() {
    metricExportSuccesses.incrementAndGet();
    lastSuccessfulMetricExport = Instant.now();
  }

  public void recordSpanExportSuccess() {
    spanExportSuccesses.incrementAndGet();
    lastSuccessfulSpanExport = Instant.now();
  }

  public void recordRotationSuccess() {
    rotationSuccesses.incrementAndGet();
    lastSuccessfulRotation = Instant.now();
  }

  // --- Snapshot for API ---

  /**
   * Returns an immutable snapshot of the current health state.
   *
   * <p>Thread-safe: reads are atomic for individual counters, but the snapshot may show slight
   * inconsistency between counters if increments happen during snapshot creation. This is
   * acceptable for observability purposes.
   */
  public TelemetryHealthSnapshot snapshot() {
    return new TelemetryHealthSnapshot(
        metricExportFailures.get(),
        spanExportFailures.get(),
        rotationFailures.get(),
        pruneFailures.get(),
        gaugeCallbackFailures.get(),
        flushFailures.get(),
        diskSpaceLowEvents.get(),
        metricExportSuccesses.get(),
        spanExportSuccesses.get(),
        rotationSuccesses.get(),
        lastSuccessfulMetricExport,
        lastSuccessfulSpanExport,
        lastSuccessfulRotation,
        Instant.now());
  }
}
