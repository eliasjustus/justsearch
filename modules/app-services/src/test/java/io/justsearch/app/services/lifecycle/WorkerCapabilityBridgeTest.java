package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 627 Deliverable 10 — the Worker capability is now ONE shared instance the supervisor writes
 * and every surface reads; the cross-instance mirror (tempdoc 521 T2.5) is gone. This pins the
 * single-instance invariant that replaces the mirror: a listener registered on the capability (the
 * {@code CapabilityHealthBridge}'s contract) observes the supervisor's lifecycle transitions directly,
 * with no second instance to drift out of sync.
 */
final class WorkerCapabilityBridgeTest {

  @Test
  @DisplayName("a listener on the single shared capability observes every supervisor transition")
  void singleInstanceListenerObservesSupervisorTransitions() {
    WorkerCapability cap = new WorkerCapability();
    List<CapabilityHealth> observed = new ArrayList<>();
    cap.addListener((prev, next) -> observed.add(next));

    // Drive the lifecycle the KS bootstrap/supervisor uses; the listener (the bridge) sees each
    // state change directly — there is no separate instance to mirror onto.
    cap.transition(CapabilityHealth.READY, null);
    cap.transition(CapabilityHealth.DEGRADED, "Health check failed");
    cap.transition(CapabilityHealth.READY, null);

    assertEquals(
        List.of(CapabilityHealth.READY, CapabilityHealth.DEGRADED, CapabilityHealth.READY),
        observed,
        "the listener observes every state-change transition on the one shared capability");
    assertEquals(CapabilityHealth.READY, cap.health());
    assertEquals(null, cap.pendingReason(), "READY clears pendingReason (per Capability contract)");
  }

  @Test
  @DisplayName("default state before any transition (read by /api/health and the chat gate)")
  void pendingReasonStartsAtDefaultBeforeAnyTransition() {
    WorkerCapability standalone = new WorkerCapability();
    assertEquals(CapabilityHealth.PENDING, standalone.health());
    assertEquals("Worker not yet connected", standalone.pendingReason());
    assertNotEquals(true, standalone.available(), "PENDING is not available");
  }
}
