package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.health.ThresholdState;
import io.justsearch.app.observability.health.ThresholdPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HealthRecoveryProjector")
final class HealthRecoveryProjectorTest {

  private static final Source SRC = Source.forProcess("head", "instance-1", "1.0");
  private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-05-15T10:01:00Z");
  private static final OperationInvocation RECOVERY =
      new OperationInvocation(new OperationRef("core.reindex"), "{}");

  private final HealthRecoveryProjector projector = new HealthRecoveryProjector();

  private static HealthEventChangeRegistry.HealthChangeEvent change(
      HealthEventChangeRegistry.Kind kind, HealthEvent event) {
    return new HealthEventChangeRegistry.HealthChangeEvent(1L, kind, event);
  }

  private static HealthEvent conditionEvent(
      Severity severity, Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        "test.condition",
        T0,
        SRC,
        severity,
        Optional.of("health-events.test.condition.message"),
        new AssertedCondition(
            "test.subject",
            ConditionStatus.TRUE,
            "TestReason",
            T1,
            Optional.empty(),
            recovery,
            List.of()));
  }

  private static HealthEvent lifecycleEvent(
      Severity severity, Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        "test.lifecycle",
        T0,
        SRC,
        severity,
        Optional.of("health-events.test.lifecycle.message"),
        new LifecycleEvent(Map.of("session_id", "abc"), recovery));
  }

  @Nested
  @DisplayName("AssertedCondition")
  class AssertedConditionTests {

    @Test
    @DisplayName("recoverable WARNING condition → advisory with primaryAction")
    void recoverableWarningCondition() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED,
              conditionEvent(Severity.WARNING, Optional.of(RECOVERY)));

      Optional<AdvisoryProjection> result = projector.project(change);

      assertTrue(result.isPresent());
      AdvisoryProjection p = result.get();
      assertTrue(p.primaryAction().isPresent());
      assertEquals("core.reindex", p.primaryAction().get().target().value());
      assertEquals("test.subject", p.classExtras().get("subject"));
    }

    @Test
    @DisplayName("recoverable ERROR condition → advisory")
    void recoverableErrorCondition() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_MODIFIED,
              conditionEvent(Severity.ERROR, Optional.of(RECOVERY)));

      assertTrue(projector.project(change).isPresent());
    }

    @Test
    @DisplayName("INFO severity → no advisory")
    void infoSeverityFiltered() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED,
              conditionEvent(Severity.INFO, Optional.of(RECOVERY)));

      assertTrue(projector.project(change).isEmpty());
    }

    @Test
    @DisplayName("no recovery → no advisory")
    void noRecoveryFiltered() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED,
              conditionEvent(Severity.WARNING, Optional.empty()));

      assertTrue(projector.project(change).isEmpty());
    }

    @Test
    @DisplayName("CONDITION_REMOVED → no advisory")
    void conditionRemovedFiltered() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_REMOVED,
              conditionEvent(Severity.WARNING, Optional.of(RECOVERY)));

      assertTrue(projector.project(change).isEmpty());
    }

    @Test
    @DisplayName("dedupKey uses lastTransitionTime")
    void dedupKeyUsesLastTransitionTime() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED,
              conditionEvent(Severity.WARNING, Optional.of(RECOVERY)));

      String key = projector.dedupKey(change);

      assertEquals("instance-1#test.condition#" + T1, key);
    }

    @Test
    @DisplayName("dedupKey is idempotent")
    void dedupKeyIdempotent() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED,
              conditionEvent(Severity.WARNING, Optional.of(RECOVERY)));

      assertEquals(projector.dedupKey(change), projector.dedupKey(change));
    }
  }

  @Nested
  @DisplayName("LifecycleEvent")
  class LifecycleEventTests {

    @Test
    @DisplayName("recoverable WARNING lifecycle → advisory")
    void recoverableWarningLifecycle() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED,
              lifecycleEvent(Severity.WARNING, Optional.of(RECOVERY)));

      Optional<AdvisoryProjection> result = projector.project(change);

      assertTrue(result.isPresent());
      assertTrue(result.get().primaryAction().isPresent());
    }

    @Test
    @DisplayName("no recovery → no advisory")
    void noRecoveryFiltered() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED,
              lifecycleEvent(Severity.WARNING, Optional.empty()));

      assertTrue(projector.project(change).isEmpty());
    }

    @Test
    @DisplayName("INFO severity → no advisory")
    void infoSeverityFiltered() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED,
              lifecycleEvent(Severity.INFO, Optional.of(RECOVERY)));

      assertTrue(projector.project(change).isEmpty());
    }

    @Test
    @DisplayName("dedupKey uses event timestamp (no lastTransitionTime)")
    void dedupKeyUsesEventTimestamp() {
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED,
              lifecycleEvent(Severity.WARNING, Optional.of(RECOVERY)));

      String key = projector.dedupKey(change);

      assertEquals("instance-1#test.lifecycle#" + T0, key);
    }
  }

  @Nested
  @DisplayName("ThresholdState + UnknownEventBody")
  class OtherBodyTypes {

    @Test
    @DisplayName("ThresholdState → no advisory (no recovery field)")
    void thresholdStateFiltered() {
      HealthEvent event =
          new HealthEvent(
              "test.threshold",
              T0,
              SRC,
              Severity.WARNING,
              Optional.empty(),
              new ThresholdState(
                  "memory",
                  ThresholdPhase.FIRING,
                  Map.of("ratio_pct", 91),
                  T0,
                  Optional.empty(),
                  Optional.empty(),
                  List.of()));
      var change =
          HealthRecoveryProjectorTest.change(
              HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);

      assertTrue(projector.project(change).isEmpty());
    }
  }
}
