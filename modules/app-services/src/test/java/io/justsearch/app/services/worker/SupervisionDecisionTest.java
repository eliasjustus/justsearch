package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.SupervisionDecision.Action;
import io.justsearch.app.services.worker.SupervisionDecision.Decision;
import io.justsearch.app.services.worker.SupervisionDecision.Input;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fault-matrix guard for the {@link SupervisionDecision} law-bearing seam (tempdoc 627). One test per
 * matrix row: clean-death→restart, hang→graceful-restart, cap→give-up, stability-reset, no-fault, and
 * the backoff schedule. The seam is pure, so each row is an exact assertion (no mocks, no clock).
 */
@DisplayName("SupervisionDecision — recovery-contract fault matrix")
class SupervisionDecisionTest {

  private static final SupervisionPolicy POLICY =
      new SupervisionPolicy(/*max*/ 3, /*base*/ 1000, /*maxCd*/ 30_000, /*window*/ 300_000, /*hang*/ 3);

  // --- Row: healthy worker, no fault ---------------------------------------------------------

  @Test
  @DisplayName("alive + healthy → NONE")
  void aliveHealthy_isNoOp() {
    Decision d = SupervisionDecision.decide(
        new Input(/*alive*/ true, /*unhealthy*/ 0, /*attempts*/ 0, /*known*/ true, /*sinceMs*/ 10_000),
        POLICY);
    assertEquals(Action.NONE, d.action());
  }

  @Test
  @DisplayName("alive + unhealthy but below hang threshold → NONE (transient blip, do not restart)")
  void aliveUnhealthyBelowThreshold_isNoOp() {
    Decision d = SupervisionDecision.decide(
        new Input(true, /*unhealthy*/ 2, 0, true, 10_000), POLICY);
    assertEquals(Action.NONE, d.action(), "2 < threshold 3: a transient failure must not restart");
  }

  // --- Row: clean process death --------------------------------------------------------------

  @Test
  @DisplayName("process dead → RESTART_RESPAWN (no graceful stop; already gone)")
  void death_respawns() {
    Decision d = SupervisionDecision.decide(
        new Input(/*alive*/ false, 0, /*attempts*/ 0, true, 10_000), POLICY);
    assertEquals(Action.RESTART_RESPAWN, d.action());
    assertEquals(1, d.nextAttempt());
    assertEquals(1000, d.backoffMs());
    assertFalse(d.resetBudgetFirst());
  }

  @Test
  @DisplayName("OOM is process death: a dead process restarts regardless of WHY it died")
  void oom_isTreatedAsCleanDeath() {
    // Contract row worker/oom: the decision inspects only processAlive, never the exit code, so OOM
    // (JVM exits non-zero) / segfault / clean exit are one mode to it — all map to RESTART_RESPAWN
    // until the shared cap is hit. A recurring OOM therefore trips the same give-up as any crash loop.
    Decision d = SupervisionDecision.decide(
        new Input(/*alive*/ false, 0, /*attempts*/ 0, true, 10_000), POLICY);
    assertEquals(Action.RESTART_RESPAWN, d.action());
  }

  // --- Row: hang (alive but sustained unhealthy) ---------------------------------------------

  @Test
  @DisplayName("alive + unhealthy at threshold → RESTART_GRACEFUL (the closed loop this tempdoc adds)")
  void hang_gracefulRestart() {
    Decision d = SupervisionDecision.decide(
        new Input(/*alive*/ true, /*unhealthy*/ 3, /*attempts*/ 0, true, 10_000), POLICY);
    assertEquals(Action.RESTART_GRACEFUL, d.action(), "hang must stop gracefully, not hard-kill");
    assertEquals(1, d.nextAttempt());
  }

  // --- Row: shared budget across death and hang ----------------------------------------------

  @Test
  @DisplayName("death and hang draw from the SAME cap (attempt count carries across fault kinds)")
  void deathAndHangShareBudget() {
    // After 2 prior restarts, a death and a hang both compute attempt 3 (still under cap).
    Decision death = SupervisionDecision.decide(new Input(false, 0, /*attempts*/ 2, true, 10_000), POLICY);
    Decision hang = SupervisionDecision.decide(new Input(true, 3, /*attempts*/ 2, true, 10_000), POLICY);
    assertEquals(3, death.nextAttempt());
    assertEquals(3, hang.nextAttempt());
    assertEquals(Action.RESTART_RESPAWN, death.action());
    assertEquals(Action.RESTART_GRACEFUL, hang.action());
  }

  // --- Row: restart cap exceeded → terminal give-up ------------------------------------------

  @Test
  @DisplayName("attempts already at cap → GIVE_UP (terminal)")
  void capExceeded_givesUp() {
    Decision d = SupervisionDecision.decide(
        new Input(false, 0, /*attempts*/ 3, true, 10_000), POLICY);
    assertEquals(Action.GIVE_UP, d.action());
  }

  @Test
  @DisplayName("hang at cap also gives up (cap is fault-kind agnostic)")
  void hangAtCap_givesUp() {
    Decision d = SupervisionDecision.decide(
        new Input(true, 5, /*attempts*/ 3, true, 10_000), POLICY);
    assertEquals(Action.GIVE_UP, d.action());
  }

  // --- Row: stability-window reset -----------------------------------------------------------

  @Test
  @DisplayName("stable beyond window → budget resets, fault restarts as attempt 1")
  void stableBeyondWindow_resetsBudget() {
    Decision d = SupervisionDecision.decide(
        new Input(false, 0, /*attempts*/ 3, /*known*/ true, /*sinceMs*/ 400_000), POLICY);
    assertTrue(d.resetBudgetFirst(), "uptime past the window must reset the counter");
    assertEquals(Action.RESTART_RESPAWN, d.action());
    assertEquals(1, d.nextAttempt());
  }

  @Test
  @DisplayName("stability reset is suppressed when no successful start is recorded yet")
  void noKnownStart_doesNotReset() {
    Decision d = SupervisionDecision.decide(
        new Input(false, 0, /*attempts*/ 3, /*known*/ false, /*sinceMs*/ 999_999), POLICY);
    assertFalse(d.resetBudgetFirst());
    assertEquals(Action.GIVE_UP, d.action());
  }

  // --- Row: backoff schedule -----------------------------------------------------------------

  @Test
  @DisplayName("backoff doubles per attempt and caps at the ceiling")
  void backoffSchedule() {
    assertEquals(1000, SupervisionDecision.backoffMs(1, POLICY));
    assertEquals(2000, SupervisionDecision.backoffMs(2, POLICY));
    assertEquals(4000, SupervisionDecision.backoffMs(3, POLICY));
    assertEquals(8000, SupervisionDecision.backoffMs(4, POLICY));
    assertEquals(30_000, SupervisionDecision.backoffMs(20, POLICY), "capped at maxCooldownMs");
  }

  @Test
  @DisplayName("null args are rejected (total function contract)")
  void nullArgsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> SupervisionDecision.decide(null, POLICY));
    assertThrows(IllegalArgumentException.class,
        () -> SupervisionDecision.decide(new Input(true, 0, 0, true, 0), null));
  }
}
