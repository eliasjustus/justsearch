package io.justsearch.app.services.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.inference.TargetPhase;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.inference.InferenceTags.HealthSeverity;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 412 Phase 4: validates that every {@link InferenceTelemetryAdapter} method emits
 * to the expected {@code inference.*} metric via the typed catalog. Mirrors the
 * {@code WorkerLuceneTelemetryAdapterTest} shape from tempdoc 417 (~25 tests for ~12 events).
 */
@DisplayName("InferenceTelemetryAdapter — events bridge")
final class InferenceTelemetryAdapterTest {

  private TestMetricRegistry registry;
  private InferenceMetricCatalog catalog;
  private InferenceTelemetryAdapter adapter;

  private static final InferenceConfig FAKE_SCHEMA =
      new InferenceConfig(
          Path.of("/fake/llama-server.exe"),
          Path.of("/fake/model.gguf"),
          null,
          9999,
          4096,
          0,
          false);

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(InferenceMetricCatalog.DEFINITIONS);
    catalog = new InferenceMetricCatalog(registry);
    adapter = new InferenceTelemetryAdapter(catalog);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  // ==================== Transition ====================

  @Test
  void onTransitionEmitsCounterAndDuration() {
    adapter.onTransition("OFFLINE", "ONLINE", TransitionReason.AUTO_START, Duration.ofMillis(500));
    var tags = new InferenceTags.TransitionTags("OFFLINE", "ONLINE", TransitionReason.AUTO_START);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.TRANSITION_TOTAL, tags));
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.TRANSITION_DURATION_MS, tags));
  }

  @Test
  void onTransitionWithNegativeDurationSkipsHistogram() {
    adapter.onTransition("OFFLINE", "OFFLINE", TransitionReason.UNKNOWN, Duration.ofMillis(-1));
    var tags = new InferenceTags.TransitionTags("OFFLINE", "OFFLINE", TransitionReason.UNKNOWN);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.TRANSITION_TOTAL, tags));
    assertEquals(0L, registry.histogramCount(InferenceMetricCatalog.TRANSITION_DURATION_MS, tags));
  }

  @Test
  void onTransitionWithNullDurationSkipsHistogram() {
    adapter.onTransition("ONLINE", "OFFLINE", TransitionReason.SHUTDOWN, null);
    var tags = new InferenceTags.TransitionTags("ONLINE", "OFFLINE", TransitionReason.SHUTDOWN);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.TRANSITION_TOTAL, tags));
    assertEquals(0L, registry.histogramCount(InferenceMetricCatalog.TRANSITION_DURATION_MS, tags));
  }

  @Test
  void onTransitionWithNullPhasesDefaultsToOffline() {
    adapter.onTransition(null, null, null, Duration.ofMillis(10));
    var tags = new InferenceTags.TransitionTags("OFFLINE", "OFFLINE", TransitionReason.UNKNOWN);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.TRANSITION_TOTAL, tags));
  }

  // ==================== Startup ====================

  @Test
  void onStartupAttemptEmitsCounter() {
    adapter.onStartupAttempt(FAKE_SCHEMA, StartupReason.COLD_START, TargetPhase.ONLINE);
    var tags = new InferenceTags.StartupAttemptTags(TargetPhase.ONLINE, StartupReason.COLD_START);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.STARTUP_ATTEMPT_TOTAL, tags));
  }

  @Test
  void onStartupAttemptIgnoresNullArgs() {
    adapter.onStartupAttempt(FAKE_SCHEMA, null, TargetPhase.ONLINE);
    adapter.onStartupAttempt(FAKE_SCHEMA, StartupReason.COLD_START, null);
    assertTrue(registry.emittedNames().isEmpty());
  }

  @Test
  void onStartupCompleteRecordsHistogramWithExplicitTargetPhase() {
    var identity = new RuntimeIdentity(1L, "model", 8080, 1L);
    adapter.onStartupComplete(FAKE_SCHEMA, Duration.ofMillis(2500), identity, TargetPhase.ONLINE);
    var tags = new InferenceTags.StartupDurationTags(TargetPhase.ONLINE);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.STARTUP_DURATION_MS, tags));
  }

  @Test
  void onStartupCompleteIndexingPhaseRecordsSeparately() {
    var identity = RuntimeIdentity.nonProcess(1L);
    adapter.onStartupComplete(FAKE_SCHEMA, Duration.ofMillis(50), identity, TargetPhase.INDEXING);
    var indexingTags = new InferenceTags.StartupDurationTags(TargetPhase.INDEXING);
    assertEquals(
        1L, registry.histogramCount(InferenceMetricCatalog.STARTUP_DURATION_MS, indexingTags));
  }

  @Test
  void onStartupFailureEmitsCounter() {
    adapter.onStartupFailure(
        new InferenceFailure.StartupFailure(StartupCode.MISSING_DLL, "no DLL", null));
    var tags = InferenceTags.StartupFailureTags.of(TargetPhase.ONLINE, StartupCode.MISSING_DLL);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.STARTUP_FAILURE_TOTAL, tags));
  }

  @Test
  void onStartupFailureNullIsNoOp() {
    adapter.onStartupFailure(null);
    assertTrue(registry.emittedNames().isEmpty());
  }

  // ==================== Health ====================

  @Test
  void onHealthFailureEmitsWithSingleSeverity() {
    adapter.onHealthFailure(
        new InferenceFailure.HealthFailure(HealthCode.HEALTH_TIMEOUT, "x", null), 1, false);
    var tags = InferenceTags.HealthFailureTags.of(HealthCode.HEALTH_TIMEOUT, HealthSeverity.SINGLE);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.HEALTH_FAILURE_TOTAL, tags));
  }

  @Test
  void onHealthFailureEmitsWithRestartTriggeredSeverity() {
    adapter.onHealthFailure(
        new InferenceFailure.HealthFailure(HealthCode.PROCESS_DIED, "x", null), 3, true);
    var tags =
        InferenceTags.HealthFailureTags.of(HealthCode.PROCESS_DIED, HealthSeverity.RESTART_TRIGGERED);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.HEALTH_FAILURE_TOTAL, tags));
  }

  @Test
  void onHealthRecoveredEmitsCounter() {
    adapter.onHealthRecovered(2);
    assertEquals(
        1L,
        registry.counterValue(InferenceMetricCatalog.HEALTH_RECOVERED_TOTAL, EmptyTags.INSTANCE));
  }

  // ==================== Config apply ====================

  @Test
  void onConfigApplyAttemptEmitsWithRestartTrue() {
    adapter.onConfigApplyAttempt(FAKE_SCHEMA, FAKE_SCHEMA, true);
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_TOTAL, new InferenceTags.ConfigApplyTags(true)));
  }

  @Test
  void onConfigApplyAttemptEmitsWithRestartFalse() {
    adapter.onConfigApplyAttempt(FAKE_SCHEMA, FAKE_SCHEMA, false);
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_TOTAL, new InferenceTags.ConfigApplyTags(false)));
  }

  @Test
  void onConfigApplyFailureWithConfigCodeRoutesViaConfigCode() {
    adapter.onConfigApplyFailure(
        new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, "bad"));
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_FAILURE_TOTAL,
            InferenceTags.ConfigFailureTags.of(ConfigCode.INVALID_CONFIG)));
  }

  @Test
  void onConfigApplyFailureWithTransitionCodeRoutesViaTransitionCode() {
    adapter.onConfigApplyFailure(
        new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED, "x", null));
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_FAILURE_TOTAL,
            InferenceTags.ConfigFailureTags.of(TransitionCode.CONFIG_APPLY_FAILED)));
  }

  // ==================== Request ====================

  @Test
  void onRequestStartedRecordsQueueWait() {
    adapter.onRequestStarted(RequestKind.CHAT, Duration.ofMillis(75));
    var tags = new InferenceTags.RequestQueueTags(RequestKind.CHAT);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.REQUEST_QUEUE_WAIT_MS, tags));
  }

  @Test
  void onRequestStartedSkipsWhenWaitNegativeOrNull() {
    adapter.onRequestStarted(RequestKind.STREAM, Duration.ofMillis(-1));
    adapter.onRequestStarted(RequestKind.STREAM, null);
    adapter.onRequestStarted(null, Duration.ofMillis(0));
    assertTrue(registry.emittedNames().isEmpty());
  }

  @Test
  void onRequestCompletedRecordsDuration() {
    adapter.onRequestCompleted(RequestKind.SUMMARY, Duration.ofMillis(450), RequestOutcome.OK);
    var tags = new InferenceTags.RequestDurationTags(RequestKind.SUMMARY, RequestOutcome.OK);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.REQUEST_DURATION_MS, tags));
  }

  @Test
  void onRequestCompletedDistinguishesOutcome() {
    adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(10), RequestOutcome.OK);
    adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(20), RequestOutcome.ERROR);
    assertEquals(
        1L,
        registry.histogramCount(
            InferenceMetricCatalog.REQUEST_DURATION_MS,
            new InferenceTags.RequestDurationTags(RequestKind.CHAT, RequestOutcome.OK)));
    assertEquals(
        1L,
        registry.histogramCount(
            InferenceMetricCatalog.REQUEST_DURATION_MS,
            new InferenceTags.RequestDurationTags(RequestKind.CHAT, RequestOutcome.ERROR)));
  }

  @Test
  void onRequestCompletedSkipsWhenDurationInvalid() {
    adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(-1), RequestOutcome.OK);
    adapter.onRequestCompleted(RequestKind.CHAT, null, RequestOutcome.OK);
    adapter.onRequestCompleted(null, Duration.ofMillis(10), RequestOutcome.OK);
    adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(10), null);
    assertTrue(registry.emittedNames().isEmpty());
  }

  @Test
  void onRequestEnqueuedIsNoOp() {
    adapter.onRequestEnqueued(RequestKind.CHAT);
    assertTrue(registry.emittedNames().isEmpty());
  }

  // ==================== End-to-end ====================

  @Test
  void allElevenMetricNamesEmittedAtLeastOnce() {
    adapter.onTransition("OFFLINE", "ONLINE", TransitionReason.UNKNOWN, Duration.ofMillis(1));
    adapter.onStartupAttempt(FAKE_SCHEMA, StartupReason.COLD_START, TargetPhase.ONLINE);
    adapter.onStartupComplete(
        FAKE_SCHEMA, Duration.ofMillis(1), RuntimeIdentity.nonProcess(1L), TargetPhase.ONLINE);
    adapter.onStartupFailure(new InferenceFailure.StartupFailure(StartupCode.UNKNOWN, "x", null));
    adapter.onConfigApplyAttempt(FAKE_SCHEMA, FAKE_SCHEMA, true);
    adapter.onConfigApplyFailure(new InferenceFailure.ConfigFailure(ConfigCode.UNKNOWN, "x"));
    adapter.onHealthFailure(new InferenceFailure.HealthFailure(HealthCode.UNKNOWN, "x", null), 1, false);
    adapter.onHealthRecovered(0);
    adapter.onRequestStarted(RequestKind.CHAT, Duration.ofMillis(1));
    adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(1), RequestOutcome.OK);

    var emitted = registry.emittedNames();
    String[] names = {
      InferenceMetricCatalog.TRANSITION_TOTAL,
      InferenceMetricCatalog.TRANSITION_DURATION_MS,
      InferenceMetricCatalog.STARTUP_ATTEMPT_TOTAL,
      InferenceMetricCatalog.STARTUP_DURATION_MS,
      InferenceMetricCatalog.STARTUP_FAILURE_TOTAL,
      InferenceMetricCatalog.CONFIG_APPLY_TOTAL,
      InferenceMetricCatalog.CONFIG_APPLY_FAILURE_TOTAL,
      InferenceMetricCatalog.HEALTH_FAILURE_TOTAL,
      InferenceMetricCatalog.HEALTH_RECOVERED_TOTAL,
      InferenceMetricCatalog.REQUEST_QUEUE_WAIT_MS,
      InferenceMetricCatalog.REQUEST_DURATION_MS,
    };
    for (String name : names) {
      assertTrue(emitted.contains(name), "expected " + name + "; got " + emitted);
    }
  }
}
