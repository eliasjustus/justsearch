/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The Head's BOOT-recovery authority (tempdoc 825): a <b>pure</b> function mapping the observed
 * post-boot state to what the one monitor authority must do next. Mirrors {@link SupervisionDecision}
 * deliberately — same shape, same testability posture, no IO / clock / process handles — for the one
 * state supervision structurally cannot cover: {@code KnowledgeServerBootstrap.start()} failed, so
 * there is no client, no spawner, and nothing supervising anything.
 *
 * <p>Law: while no client is bound, a failed boot resolves to a bounded re-{@code ATTEMPT} sequence
 * with exponential backoff and then to exactly one terminal {@code GIVE_UP}
 * ({@code worker.spawn_recovery_exhausted}) — never to silence, and never to an unbounded loop. Two
 * vetoes outrank the budget and stop the sequence WITHOUT a give-up narration, because in both cases
 * another authority's verdict already stands and must not be superseded (825 §D5 decision 2):
 *
 * <ul>
 *   <li>{@link Veto#SUPERVISION_ENGAGED} — the failing attempt's spawner already performed a
 *       supervised restart, so the restart budget is {@link SupervisionPolicy}'s. Re-attempting here
 *       would multiply the declared restart intensity, which is exactly the second-restart-authority
 *       hazard the tempdoc-627 review warned about.
 *   <li>{@link Veto#RESTART_EXHAUSTED} — supervision has already given up
 *       ({@code worker.restart_exhausted}). That verdict is terminal by contract; boot recovery may
 *       not overwrite it with its own.
 * </ul>
 */
public final class BootRecoveryDecision {

  private BootRecoveryDecision() {}

  /** What the boot-recovery arm should do on this tick. */
  public enum Action {
    /** Nothing to do — a client is bound (the health arm owns the worker), or we already gave up. */
    NONE,
    /** An attempt is due but its backoff has not elapsed yet. */
    WAIT,
    /** Re-attempt the bootstrap now. */
    ATTEMPT,
    /** Stop trying. Narrated as the terminal reason code unless a {@link Veto} says otherwise. */
    GIVE_UP
  }

  /** Why the sequence stopped without this authority getting to narrate its own terminal code. */
  public enum Veto {
    /** No veto — the give-up (if any) is this authority's own budget being spent. */
    NONE,
    /** The last attempt left supervision holding the restart budget. */
    SUPERVISION_ENGAGED,
    /** Supervision has already declared the terminal {@code worker.restart_exhausted}. */
    RESTART_EXHAUSTED
  }

  /**
   * Observed inputs. All of them are facts the monitor can read without doing anything: whether a
   * gRPC client is bound, what the capability currently holds, and this arm's own bookkeeping.
   *
   * @param clientBound a {@code RemoteKnowledgeClient} is bound, i.e. the bootstrap is up and the
   *     ordinary health arm owns it
   * @param supervisionEngaged the last failed start left supervision holding the restart budget
   *     ({@code KnowledgeServerBootstrap.supervisionEngagedOnLastAttempt()})
   * @param restartExhaustedHeld the capability currently holds {@code worker.restart_exhausted}
   * @param attemptsMade boot-recovery attempts already made in this arc
   * @param gaveUp this arc has already narrated its terminal state (so it must not narrate twice)
   * @param msSinceLastAttempt elapsed time since the last attempt; {@link Long#MAX_VALUE} when none
   *     has been made yet (the first attempt still waits out the base backoff, so a transient boot
   *     failure is not immediately re-attempted into the same contention that caused it)
   */
  public record Input(
      boolean clientBound,
      boolean supervisionEngaged,
      boolean restartExhaustedHeld,
      int attemptsMade,
      boolean gaveUp,
      long msSinceLastAttempt) {}

  /**
   * The decision. The monitor is intentionally dumb — it executes this verbatim.
   *
   * @param action what to do
   * @param veto which other authority's verdict stopped the sequence ({@link Veto#NONE} otherwise)
   * @param nextAttempt the 1-based attempt number an {@code ATTEMPT} will be (0 otherwise)
   * @param waitMs remaining backoff for a {@code WAIT} (0 otherwise)
   */
  public record Decision(Action action, Veto veto, int nextAttempt, long waitMs) {
    static Decision none() {
      return new Decision(Action.NONE, Veto.NONE, 0, 0);
    }

    static Decision giveUp(Veto veto) {
      return new Decision(Action.GIVE_UP, veto, 0, 0);
    }
  }

  /**
   * Decides the boot-recovery action for {@code in} under {@code policy}. Pure and total.
   *
   * @throws IllegalArgumentException if {@code in} or {@code policy} is null
   */
  public static Decision decide(Input in, BootRecoveryPolicy policy) {
    if (in == null || policy == null) {
      throw new IllegalArgumentException("input and policy must not be null");
    }
    // A bound client means the bootstrap is up: the health arm owns it, and this arm must not touch a
    // live worker. Checked FIRST so a stale gaveUp/attempt count can never act on a recovered worker.
    if (in.clientBound()) {
      return Decision.none();
    }
    // Terminal states are terminal: narrate once, then stay silent.
    if (in.gaveUp()) {
      return Decision.none();
    }
    // Vetoes outrank the budget: another authority's verdict stands, so we stop AND stay quiet.
    if (in.restartExhaustedHeld()) {
      return Decision.giveUp(Veto.RESTART_EXHAUSTED);
    }
    if (in.supervisionEngaged()) {
      return Decision.giveUp(Veto.SUPERVISION_ENGAGED);
    }
    int nextAttempt = in.attemptsMade() + 1;
    if (nextAttempt > policy.maxAttempts()) {
      return Decision.giveUp(Veto.NONE);
    }
    long backoff = backoffMs(nextAttempt, policy);
    long elapsed = in.msSinceLastAttempt();
    if (elapsed < backoff) {
      return new Decision(Action.WAIT, Veto.NONE, nextAttempt, backoff - elapsed);
    }
    return new Decision(Action.ATTEMPT, Veto.NONE, nextAttempt, 0);
  }

  /**
   * Exponential backoff for a 1-based attempt number, capped at the policy ceiling:
   * {@code min(base << (attempt-1), max)}. Same schedule shape as
   * {@link SupervisionDecision#backoffMs} so the two recovery authorities do not drift in feel.
   */
  public static long backoffMs(int nextAttempt, BootRecoveryPolicy policy) {
    long base = policy.baseBackoffMs();
    long max = policy.maxBackoffMs();
    if (nextAttempt <= 1 || base == 0) {
      return Math.min(base, max);
    }
    int shift = nextAttempt - 1;
    // Overflow guard by construction rather than by inspecting the result: `base << shift` wraps
    // SILENTLY and can land on a positive value — or exactly 0, which a `scaled < 0` check misses
    // (1000 << 62 == 0, because 1000 is 8*125 and the 125 shifts clean out of the word). A shift at
    // or past the leading-zero count is precisely the shift that would lose the top bit, and any
    // such value has long since passed the ceiling.
    if (shift >= Long.numberOfLeadingZeros(base)) {
      return max;
    }
    return Math.min(base << shift, max);
  }
}
