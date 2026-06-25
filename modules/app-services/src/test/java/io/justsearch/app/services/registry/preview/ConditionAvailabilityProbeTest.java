package io.justsearch.app.services.registry.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit coverage for the Preview-face {@link ConditionAvailabilityProbe} (tempdoc 550 F2). */
final class ConditionAvailabilityProbeTest {

  private static final Source SRC = Source.forProcess("worker", "instance-1", "1.0");

  private static HealthEvent condition(String id, ConditionStatus status) {
    return new HealthEvent(
        id,
        Instant.parse("2026-05-25T00:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.empty(),
        new AssertedCondition(
            id,
            status,
            "ProbeTest",
            Instant.parse("2026-05-25T00:00:00Z"),
            Optional.empty(),
            Optional.empty(),
            List.of()));
  }

  @Test
  void firingWhenConditionHoldsTrue() {
    ConditionStore store = new ConditionStore();
    store.upsert(condition("index.unavailable", ConditionStatus.TRUE));
    ConditionAvailabilityProbe probe = new ConditionAvailabilityProbe(store);

    assertTrue(probe.isFiring("index.unavailable"), "TRUE-status condition is firing");
    assertFalse(probe.isFiring("some.other.condition"), "absent condition is not firing");
  }

  @Test
  void notFiringWhenConditionIsFalse() {
    ConditionStore store = new ConditionStore();
    store.upsert(condition("index.unavailable", ConditionStatus.FALSE));
    ConditionAvailabilityProbe probe = new ConditionAvailabilityProbe(store);

    assertFalse(probe.isFiring("index.unavailable"), "FALSE-status condition does not hold");
  }

  @Test
  void emptyStoreReportsNothingFiring() {
    ConditionAvailabilityProbe probe = new ConditionAvailabilityProbe(new ConditionStore());
    assertFalse(probe.isFiring("anything"));
    assertFalse(probe.isFiring(null), "null id is handled");
  }
}
