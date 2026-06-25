package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelemetryHealthStateTest {

  @Test
  void snapshotIsImmutable() {
    var state = new TelemetryHealthState();
    state.recordMetricExportSuccess();
    var snapshot1 = state.snapshot();

    state.recordMetricExportFailure();
    state.recordMetricExportFailure();
    var snapshot2 = state.snapshot();

    // First snapshot should not be affected by subsequent mutations
    assertEquals(1, snapshot1.metricExportSuccesses());
    assertEquals(0, snapshot1.metricExportFailures());

    // Second snapshot should reflect the new state
    assertEquals(1, snapshot2.metricExportSuccesses());
    assertEquals(2, snapshot2.metricExportFailures());
  }

  @Test
  void successRateCalculation() {
    var state = new TelemetryHealthState();

    // No operations: 100% success rate
    var snapshot = state.snapshot();
    assertEquals(1.0, snapshot.metricExportSuccessRate(), 0.001);
    assertEquals(1.0, snapshot.spanExportSuccessRate(), 0.001);

    // 3 successes, 1 failure = 75% success rate
    state.recordMetricExportSuccess();
    state.recordMetricExportSuccess();
    state.recordMetricExportSuccess();
    state.recordMetricExportFailure();

    snapshot = state.snapshot();
    assertEquals(0.75, snapshot.metricExportSuccessRate(), 0.001);

    // All failures = 0% success rate
    for (int i = 0; i < 10; i++) {
      state.recordSpanExportFailure();
    }
    snapshot = state.snapshot();
    assertEquals(0.0, snapshot.spanExportSuccessRate(), 0.001);
  }

  @Test
  void timestampsAreRecorded() {
    var state = new TelemetryHealthState();

    // Initially null
    var snapshot = state.snapshot();
    assertNull(snapshot.lastSuccessfulMetricExport());
    assertNull(snapshot.lastSuccessfulSpanExport());
    assertNull(snapshot.lastSuccessfulRotation());

    // After success, timestamp is populated
    Instant before = Instant.now();
    state.recordMetricExportSuccess();
    Instant after = Instant.now();

    snapshot = state.snapshot();
    assertNotNull(snapshot.lastSuccessfulMetricExport());
    assertTrue(
        !snapshot.lastSuccessfulMetricExport().isBefore(before),
        "Timestamp should be >= before");
    assertTrue(
        !snapshot.lastSuccessfulMetricExport().isAfter(after), "Timestamp should be <= after");
  }

  @Test
  void allCountersIncrement() {
    var state = new TelemetryHealthState();

    state.recordMetricExportFailure();
    state.recordSpanExportFailure();
    state.recordRotationFailure();
    state.recordPruneFailure();
    state.recordGaugeCallbackFailure();
    state.recordFlushFailure();
    state.recordDiskSpaceLowEvent();
    state.recordMetricExportSuccess();
    state.recordSpanExportSuccess();
    state.recordRotationSuccess();

    var snapshot = state.snapshot();
    assertEquals(1, snapshot.metricExportFailures());
    assertEquals(1, snapshot.spanExportFailures());
    assertEquals(1, snapshot.rotationFailures());
    assertEquals(1, snapshot.pruneFailures());
    assertEquals(1, snapshot.gaugeCallbackFailures());
    assertEquals(1, snapshot.flushFailures());
    assertEquals(1, snapshot.diskSpaceLowEvents());
    assertEquals(1, snapshot.metricExportSuccesses());
    assertEquals(1, snapshot.spanExportSuccesses());
    assertEquals(1, snapshot.rotationSuccesses());
  }

  @Test
  void hasFailuresDetectsAnyFailure() {
    var state = new TelemetryHealthState();
    assertFalse(state.snapshot().hasFailures());

    state.recordDiskSpaceLowEvent();
    assertTrue(state.snapshot().hasFailures());
  }

  @Test
  void totalFailuresAggregates() {
    var state = new TelemetryHealthState();
    state.recordMetricExportFailure();
    state.recordSpanExportFailure();
    state.recordRotationFailure();

    assertEquals(3, state.snapshot().totalFailures());
  }

  @Test
  void concurrentIncrementsAreThreadSafe() throws InterruptedException {
    var state = new TelemetryHealthState();
    int numThreads = 10;
    int incrementsPerThread = 1000;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
      var unused = executor.submit(
          () -> {
            try {
              for (int j = 0; j < incrementsPerThread; j++) {
                state.recordMetricExportSuccess();
                state.recordMetricExportFailure();
              }
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent increments should complete");
    executor.shutdown();

    var snapshot = state.snapshot();
    assertEquals(
        numThreads * incrementsPerThread,
        snapshot.metricExportSuccesses(),
        "All success increments should be counted");
    assertEquals(
        numThreads * incrementsPerThread,
        snapshot.metricExportFailures(),
        "All failure increments should be counted");
  }

  @Test
  void observedAtIsPopulated() {
    var state = new TelemetryHealthState();
    Instant before = Instant.now();
    var snapshot = state.snapshot();
    Instant after = Instant.now();

    assertNotNull(snapshot.observedAt());
    assertTrue(!snapshot.observedAt().isBefore(before));
    assertTrue(!snapshot.observedAt().isAfter(after));
  }
}
