package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 577 §2.14 Root I (#13) — the run-observer eviction seam.
 *
 * <p>A run's SSE writers observe the run's channel, whose fan-out evicts any observer whose
 * {@code accept} THROWS (verified in {@code RunChannelRegistryTest}), and the posture-graded
 * zero-observer park fires only once {@code observerCount()} reaches 0 (verified in
 * {@code AgentSessionBudgetTest}). The missing link this pins: an SSE write to a DISCONNECTED client
 * — which {@link SseWriter#writeEvent} reports by returning {@code false} (not by throwing) — must be
 * turned into a throw at the observer boundary, so the run evicts it. Before this seam, {@code
 * writeAgentEvent}/the engine consumer ignored the {@code false} return, the dead socket lingered in
 * the observer set, {@code observerCount()} never dropped, and a Watch run proceeded UNWATCHED.
 *
 * <p><strong>Kept through tempdoc 834's hub deletion.</strong> §7-S3b's sweep table calls this seam
 * obsolete "once {@code onClose} owns disconnect" — which holds only for the MANAGED run streams.
 * The raw {@code Context}-based attach routes have no {@code onClose}, so a failed write is still
 * their only disconnect signal; deleting the seam and this test would have removed a live guard,
 * not residue. It retires when those routes do.
 */
final class AgentControllerSseEvictionTest {

  @Test
  @DisplayName("CLIENT_GONE throws so the run evicts the observer")
  void clientGoneThrowsSoTheRunEvicts() {
    // A genuine disconnect ⇒ the observer must throw (RuntimeException), which the channel's evict-on-throw fan-out
    // catches — dropping observerCount toward the zero-observer park.
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
    // reattach (the event sits in the replay ring). So no throw.
    assertDoesNotThrow(() -> AgentSseWriter.evictIfGone(SseWriter.SseWriteOutcome.SERIALIZATION_FAILED));
  }
}
