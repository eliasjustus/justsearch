package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 627 Deliverable 10: with one shared {@link WorkerCapability} the async worker-start can drive
 * it past PENDING <em>before</em> {@code CapabilityHealthBridge.wireListeners} adds its listener — the
 * deleted {@code HeadAssembly} mirror's synchronous initial-copy used to cover that. The bridge now
 * <b>replays the current condition on wire</b>, so a worker that booted straight to DEGRADED is seeded
 * immediately; a READY or PENDING capability seeds no worker condition.
 */
@DisplayName("CapabilityHealthBridge — replay current state on wire")
final class CapabilityHealthBridgeReplayTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  private static boolean hasWorkerCondition(ConditionStore store) {
    return store.currentSnapshot().stream().map(HealthEvent::id).anyMatch("worker.capability"::equals);
  }

  private static void wire(WorkerCapability worker, ConditionStore conditions) {
    CapabilityHealthBridge.wireListeners(
        worker,
        new InferenceCapability(false),
        conditions,
        new HealthEventChangeRegistry(),
        HEAD,
        new OccurrenceLog());
  }

  @Test
  @DisplayName("wiring to an already-DEGRADED capability seeds the worker condition immediately")
  void replaysDegradedOnWire() {
    ConditionStore conditions = new ConditionStore();
    WorkerCapability worker = new WorkerCapability();
    worker.transition(CapabilityHealth.DEGRADED, "Health check failed"); // moved BEFORE the bridge wires
    wire(worker, conditions);
    assertTrue(
        hasWorkerCondition(conditions),
        "a DEGRADED-before-wire worker must be seeded by replay-on-wire (the S1 race fix)");
  }

  @Test
  @DisplayName("wiring to a READY capability seeds no worker condition (replay clears)")
  void noConditionForReadyOnWire() {
    ConditionStore conditions = new ConditionStore();
    WorkerCapability worker = new WorkerCapability();
    worker.transition(CapabilityHealth.READY, null);
    wire(worker, conditions);
    assertFalse(hasWorkerCondition(conditions), "READY seeds no condition");
  }

  @Test
  @DisplayName("wiring to a PENDING (booting) capability seeds no worker condition")
  void noConditionForPendingOnWire() {
    ConditionStore conditions = new ConditionStore();
    wire(new WorkerCapability(), conditions); // default state is PENDING
    assertFalse(hasWorkerCondition(conditions), "PENDING (normal boot) seeds no condition");
  }
}
