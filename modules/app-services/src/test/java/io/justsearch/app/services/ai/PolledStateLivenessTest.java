package io.justsearch.app.services.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tempdoc 575 §17 Face C — the polled-state liveness law (the install/pack backstop decision). Pins
 * the law that AiInstallService/AiPackImportService's lazy reaper consumes, with an injected clock so
 * no service construction is needed (the backend analogue of the FE inFlightLiveness tests).
 */
class PolledStateLivenessTest {

  private static final long STALE_MS = 5 * 60_000L;
  private static final long NOW = 1_000_000_000L;

  @Test
  void runningWithAFreshHeartbeatIsNotStale() {
    assertFalse(PolledStateLiveness.isStaleRunning("running", NOW - 1000, NOW, STALE_MS));
    assertFalse(PolledStateLiveness.isStaleRunning("running", NOW - (STALE_MS - 1), NOW, STALE_MS));
  }

  @Test
  void runningPastTheStaleWindowIsStale() {
    assertTrue(PolledStateLiveness.isStaleRunning("running", NOW - (STALE_MS + 1), NOW, STALE_MS));
    assertTrue(PolledStateLiveness.isStaleRunning("running", NOW - (10 * STALE_MS), NOW, STALE_MS));
  }

  @Test
  void aNonRunningStateIsNeverStaleRunning() {
    assertFalse(PolledStateLiveness.isStaleRunning("completed", NOW - (STALE_MS + 1), NOW, STALE_MS));
    assertFalse(PolledStateLiveness.isStaleRunning("failed", 1, NOW, STALE_MS));
    assertFalse(PolledStateLiveness.isStaleRunning("idle", 0, NOW, STALE_MS));
  }

  @Test
  void aNeverTouchedRunningStatusIsNotReaped() {
    // updatedAtEpochMs <= 0 means the owner never beat once — do not reclaim a just-started "running"
    // that hasn't had a chance to write its first progress timestamp yet.
    assertFalse(PolledStateLiveness.isStaleRunning("running", 0, NOW, STALE_MS));
    assertFalse(PolledStateLiveness.isStaleRunning("running", -1, NOW, STALE_MS));
  }
}
