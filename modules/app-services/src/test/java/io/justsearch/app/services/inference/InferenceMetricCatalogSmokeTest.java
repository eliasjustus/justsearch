package io.justsearch.app.services.inference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.inference.TargetPhase;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.inference.InferenceTags.HealthSeverity;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 412 Phase 4 smoke test for {@link InferenceMetricCatalog}: definitions are
 * consistent, instruments build, and emit calls resolve to the expected metric series.
 */
@DisplayName("InferenceMetricCatalog — smoke")
final class InferenceMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private InferenceMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(InferenceMetricCatalog.DEFINITIONS);
    catalog = new InferenceMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  @DisplayName("instrument fields are non-null after construction")
  void instrumentsBuild() {
    assertNotNull(catalog.transitionTotal);
    assertNotNull(catalog.transitionDurationMs);
    assertNotNull(catalog.startupAttemptTotal);
    assertNotNull(catalog.startupDurationMs);
    assertNotNull(catalog.startupFailureTotal);
    assertNotNull(catalog.configApplyTotal);
    assertNotNull(catalog.configApplyFailureTotal);
    assertNotNull(catalog.healthFailureTotal);
    assertNotNull(catalog.healthRecoveredTotal);
    assertNotNull(catalog.requestQueueWaitMs);
    assertNotNull(catalog.requestDurationMs);
  }

  @Test
  @DisplayName("namespace prefix matches every metric name")
  void namespaceConsistency() {
    String prefix = InferenceMetricCatalog.NAMESPACE + ".";
    for (var def : InferenceMetricCatalog.DEFINITIONS) {
      assertTrue(def.name().startsWith(prefix), "wrong namespace: " + def.name());
    }
  }

  @Test
  @DisplayName("transitionTotal emits with TransitionTags")
  void transitionTotalEmits() {
    var tags = new InferenceTags.TransitionTags("OFFLINE", "ONLINE", TransitionReason.AUTO_START);
    catalog.transitionTotal.increment(tags);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.TRANSITION_TOTAL, tags));
  }

  @Test
  @DisplayName("startupAttemptTotal emits with StartupAttemptTags")
  void startupAttemptEmits() {
    var tags = new InferenceTags.StartupAttemptTags(TargetPhase.ONLINE, StartupReason.COLD_START);
    catalog.startupAttemptTotal.increment(tags);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.STARTUP_ATTEMPT_TOTAL, tags));
  }

  @Test
  @DisplayName("startupFailureTotal emits with StartupFailureTags")
  void startupFailureEmits() {
    var tags = InferenceTags.StartupFailureTags.of(TargetPhase.ONLINE, StartupCode.MISSING_DLL);
    catalog.startupFailureTotal.increment(tags);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.STARTUP_FAILURE_TOTAL, tags));
  }

  @Test
  @DisplayName("healthFailureTotal emits with HealthFailureTags")
  void healthFailureEmits() {
    var tags = InferenceTags.HealthFailureTags.of(HealthCode.HEALTH_TIMEOUT, HealthSeverity.SINGLE);
    catalog.healthFailureTotal.increment(tags);
    assertEquals(1L, registry.counterValue(InferenceMetricCatalog.HEALTH_FAILURE_TOTAL, tags));
  }

  @Test
  @DisplayName("healthRecoveredTotal emits with EmptyTags")
  void healthRecoveredEmits() {
    catalog.healthRecoveredTotal.increment(EmptyTags.INSTANCE);
    assertEquals(
        1L,
        registry.counterValue(InferenceMetricCatalog.HEALTH_RECOVERED_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  @DisplayName("transitionDurationMs records histogram sample")
  void transitionDurationEmits() {
    var tags = new InferenceTags.TransitionTags("ONLINE", "ONLINE", TransitionReason.ADMIN_TRIGGERED);
    catalog.transitionDurationMs.record(123L, tags);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.TRANSITION_DURATION_MS, tags));
  }

  @Test
  @DisplayName("requestQueueWaitMs records histogram sample")
  void requestQueueWaitEmits() {
    var tags = new InferenceTags.RequestQueueTags(RequestKind.CHAT);
    catalog.requestQueueWaitMs.record(50L, tags);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.REQUEST_QUEUE_WAIT_MS, tags));
  }

  @Test
  @DisplayName("requestDurationMs records histogram sample")
  void requestDurationEmits() {
    var tags = new InferenceTags.RequestDurationTags(RequestKind.CHAT, RequestOutcome.OK);
    catalog.requestDurationMs.record(200L, tags);
    assertEquals(1L, registry.histogramCount(InferenceMetricCatalog.REQUEST_DURATION_MS, tags));
  }

  @Test
  @DisplayName("configApplyTotal differentiates by restart_required tag")
  void configApplyEmits() {
    catalog.configApplyTotal.increment(new InferenceTags.ConfigApplyTags(true));
    catalog.configApplyTotal.increment(new InferenceTags.ConfigApplyTags(false));
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_TOTAL, new InferenceTags.ConfigApplyTags(true)));
    assertEquals(
        1L,
        registry.counterValue(
            InferenceMetricCatalog.CONFIG_APPLY_TOTAL, new InferenceTags.ConfigApplyTags(false)));
  }

  @Test
  @DisplayName("noop catalog does not throw")
  void noopCatalogDoesNotThrow() {
    var noop = InferenceMetricCatalog.noop();
    assertDoesNotThrow(
        () ->
            noop.transitionTotal.increment(
                new InferenceTags.TransitionTags("OFFLINE", "ONLINE", TransitionReason.UNKNOWN)));
  }
}
