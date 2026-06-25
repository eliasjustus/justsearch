/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The Worker's recovery-contract authority (tempdoc 627): a <b>pure</b> function mapping an observed
 * supervision state to the action a supervisor must take. This is the seam that closes the Worker's
 * observation→actuation loop — both Body detection sources (clean process death and sustained
 * gRPC-unhealth / "hang") feed this one budgeted decision, so they share a single restart cap rather
 * than two independent counters.
 *
 * <p>Law (registered in {@code governance/logic-seams.v1.json}): a recoverable fault never dead-ends
 * at a status flag — it resolves to RESTART or, once the shared cap is exceeded, a terminal GIVE_UP;
 * the cap is shared across death and hang faults; a worker that has run stably for the policy window
 * resets the budget. No IO, no clock, no process handles — every input is a parameter so the full
 * fault matrix is unit-testable. Mirrors the Brain's crash/health decision in {@code LlamaServerOps}
 * (which is itself closed against {@code InferenceCapability}); see tempdoc 627 Reach
 * (Observation-Actuation Closure).
 */
public final class SupervisionDecision {

  private SupervisionDecision() {}

  /** What the supervisor should do in response to the observed state. */
  public enum Action {
    /** No fault observed (process alive and not in a sustained-unhealthy hang). */
    NONE,
    /** Process is dead; respawn directly (no graceful stop needed — it is already gone). */
    RESTART_RESPAWN,
    /** Process alive but hung; stop it gracefully (signal-bus flush) then respawn. */
    RESTART_GRACEFUL,
    /** Restart cap exceeded; declare the terminal give-up state. */
    GIVE_UP
  }

  /**
   * Observed supervision inputs. {@code lastStartKnown} guards the stability-reset branch so a worker
   * that has never recorded a successful start cannot spuriously reset its budget.
   */
  public record Input(
      boolean processAlive,
      int consecutiveUnhealthy,
      int restartAttempts,
      boolean lastStartKnown,
      long msSinceLastStart) {}

  /**
   * The decision: the action, whether the shell must reset the restart counter to zero first (the
   * stability-window reset), the attempt number this restart will be (1-based), and the backoff to
   * sleep before it. The shell is intentionally dumb — it executes this verbatim.
   */
  public record Decision(Action action, boolean resetBudgetFirst, int nextAttempt, long backoffMs) {
    static Decision none() {
      return new Decision(Action.NONE, false, 0, 0);
    }

    static Decision giveUp() {
      return new Decision(Action.GIVE_UP, false, 0, 0);
    }
  }

  /**
   * Decides the supervisor action for {@code in} under {@code policy}. Pure and total.
   *
   * @throws IllegalArgumentException if {@code in} or {@code policy} is null
   */
  public static Decision decide(Input in, SupervisionPolicy policy) {
    if (in == null || policy == null) {
      throw new IllegalArgumentException("input and policy must not be null");
    }

    boolean death = !in.processAlive();
    boolean hang = in.processAlive() && in.consecutiveUnhealthy() >= policy.hangUnhealthyThreshold();
    if (!death && !hang) {
      return Decision.none();
    }

    // Stability reset (OTP rolling window): a worker that ran longer than the window resets the
    // shared budget, so an isolated late failure starts fresh rather than tripping the cap.
    boolean reset =
        in.lastStartKnown() && in.msSinceLastStart() >= policy.stabilityWindowMs();
    int effectiveAttempts = reset ? 0 : in.restartAttempts();
    int nextAttempt = effectiveAttempts + 1;

    if (nextAttempt > policy.maxRestartAttempts()) {
      return Decision.giveUp();
    }

    long backoff = backoffMs(nextAttempt, policy);
    Action action = death ? Action.RESTART_RESPAWN : Action.RESTART_GRACEFUL;
    return new Decision(action, reset, nextAttempt, backoff);
  }

  /**
   * Exponential backoff for a 1-based attempt number, capped at the policy ceiling:
   * {@code min(base << (attempt-1), max)}. Mirrors the historical {@code WorkerSpawner} schedule
   * (1s, 2s, 4s, … capped at 30s). Pure; exposed for direct test of the law.
   */
  public static long backoffMs(int nextAttempt, SupervisionPolicy policy) {
    if (nextAttempt <= 1) {
      return Math.min(policy.baseCooldownMs(), policy.maxCooldownMs());
    }
    int shift = Math.min(nextAttempt - 1, 62); // guard against long overflow on absurd inputs
    long scaled = policy.baseCooldownMs() << shift;
    if (scaled < 0) { // overflowed
      return policy.maxCooldownMs();
    }
    return Math.min(scaled, policy.maxCooldownMs());
  }
}
