package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 577 §2.14 Root I (#13) — the hub-observer eviction seam.
 *
 * <p>A run's SSE writers subscribe to the session's {@code RunEventHub}; the hub's {@code deliver}
 * evicts any observer whose {@code accept} THROWS (verified in {@code RunEventHubTest}), and the
 * posture-graded zero-observer park fires only once {@code observerCount()} reaches 0 (verified in
 * {@code AgentSessionBudgetTest}). The missing link this pins: an SSE write to a DISCONNECTED client
 * — which {@link SseWriter#writeEvent} reports by returning {@code false} (not by throwing) — must be
 * turned into a throw at the observer boundary, so the hub evicts it. Before this seam, {@code
 * writeAgentEvent}/the engine consumer ignored the {@code false} return, the dead socket lingered in
 * the subscriber set, {@code observerCount()} never dropped, and a Watch run proceeded UNWATCHED.
 */
final class AgentControllerSseEvictionTest {

  @Test
  @DisplayName("CLIENT_GONE throws so the hub evicts the observer")
  void clientGoneThrowsSoTheHubEvicts() {
    // A genuine disconnect ⇒ the observer must throw (RuntimeException), which the hub's deliver()
    // catches to evict it — dropping observerCount toward the zero-observer park.
    var thrown =
        assertThrows(
            AgentSseWriter.SseObserverGoneException.class,
            () -> AgentSseWriter.evictIfGone(SseWriter.SseWriteOutcome.CLIENT_GONE));
    assertTrue(thrown instanceof RuntimeException);
  }

  @Test
  @DisplayName("WRITTEN does not throw — a live observer stays subscribed")
  void writtenDoesNotThrow() {
    assertDoesNotThrow(() -> AgentSseWriter.evictIfGone(SseWriter.SseWriteOutcome.WRITTEN));
  }

  @Test
  @DisplayName("SERIALIZATION_FAILED does NOT evict — a bad payload is skipped, the observer kept")
  void serializationFailureDoesNotEvictTheObserver() {
    // The regression guard: a non-serializable event (e.g. a tool's structuredData) must NOT be
    // mistaken for a disconnect — evicting on it would kill a live stream AND re-poison every
    // reattach (the event sits in the hub's replay buffer). So no throw.
    assertDoesNotThrow(() -> AgentSseWriter.evictIfGone(SseWriter.SseWriteOutcome.SERIALIZATION_FAILED));
  }
}
