/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-rule dwell-time state machine implementing Prometheus {@code for} / {@code keep_firing_for}
 * semantics verbatim.
 *
 * <p>Per tempdoc 430 §A.3 + §A.9:
 *
 * <pre>
 *         predicate true
 * INACTIVE -----------------&gt; PENDING (start_time = now)
 *                              |
 *                  predicate continuously true for `for`
 *                              v
 *                            FIRING (emit STARTED_FIRING)
 *                              |
 *                              | predicate false
 *                              v
 *                          KEEP_FIRING (grace_until = now + keep_firing_for)
 *                              |  /
 *                              | / predicate true again
 *                              |/
 *                            FIRING ----loops----^
 *                              |
 *                  predicate stays false until grace_until
 *                              v
 *                          INACTIVE (emit RESOLVED)
 * </pre>
 *
 * <p>Edge cases (§A.9):
 *
 * <ul>
 *   <li><b>Clock skew backward</b>: {@code now &lt; pendingSince} is clamped to {@code pendingSince}
 *       — equivalent to "treat backward jump as zero-time elapsed."
 *   <li><b>{@code for} shorter than evaluator tick</b>: rule fires immediately on the first true
 *       evaluation (no dwell). Documented behavior; not a defect.
 *   <li><b>{@code for == 0}</b>: same as the prior — fires on first true.
 *   <li><b>Predicate evaluation throws</b>: caller treats as predicate-false; the scheduler sees
 *       a false result and does not change rule state on tick (or transitions PENDING→INACTIVE
 *       per the state machine).
 *   <li><b>Cyclic flapping within {@code keep_firing_for}</b>: predicate going false during
 *       KEEP_FIRING and back true keeps the rule in FIRING with no new {@code STARTED_FIRING}
 *       emission. Matches Prometheus semantics.
 * </ul>
 *
 * <p>Thread safety: per-rule state is held in a {@link ConcurrentHashMap}; each {@link #tick}
 * call atomically updates one rule's state via {@code compute}. The scheduler is safe for
 * concurrent ticks across different rules; concurrent ticks for the <em>same</em> rule serialize
 * on the per-key compute lock (effectively single-threaded per rule).
 */
public final class DwellTimeScheduler {

  /** Rule lifecycle state. */
  public enum State {
    INACTIVE,
    PENDING,
    FIRING,
    KEEP_FIRING
  }

  /** Output transition emitted by {@link #tick}. */
  public enum Transition {
    /** Rule entered FIRING from PENDING (Prometheus {@code firing}). */
    STARTED_FIRING,
    /** Rule resolved from KEEP_FIRING after the grace period elapsed. */
    RESOLVED
  }

  /** Per-rule state record. {@code pendingSince} and {@code graceUntil} are nullable. */
  private record RuleState(State state, Instant pendingSince, Instant graceUntil) {
    static RuleState inactive() {
      return new RuleState(State.INACTIVE, null, null);
    }
  }

  private final Clock clock;
  private final ConcurrentMap<String, RuleState> stateByRule = new ConcurrentHashMap<>();

  public DwellTimeScheduler(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the current state for the rule, or INACTIVE if the rule has never been ticked. */
  public State currentState(String ruleName) {
    Objects.requireNonNull(ruleName, "ruleName");
    RuleState s = stateByRule.get(ruleName);
    return s == null ? State.INACTIVE : s.state;
  }

  /**
   * Advances the per-rule state machine with the given predicate outcome and current time.
   *
   * <p>Tempdoc 600 Design B — <b>freeze, don't advance</b>: a {@link PredicateOutcome.Indeterminate}
   * outcome (the metric could not be evaluated this tick) leaves the rule's state <em>untouched</em>
   * and produces no transition. This is the structural fix for the self-monitoring-blindness gap: a
   * blind tick must not be treated as {@code false} (which would reset PENDING, start the
   * KEEP_FIRING grace, or spuriously RESOLVE a real firing condition — making "can't see" look like
   * "healthy") nor as {@code true}. The blindness is surfaced separately by {@link RuleRunner} as an
   * {@code AssertedCondition(status=UNKNOWN)}. Only an {@link PredicateOutcome.Evaluated} outcome
   * advances the Prometheus dwell machine.
   *
   * @param rule the rule whose state to update
   * @param outcome the predicate result for this tick (evaluated-boolean, or indeterminate)
   * @return the transition produced this tick, or {@link Optional#empty()} if no observable
   *     transition occurred (and always empty for an indeterminate outcome — the machine is frozen)
   */
  public Optional<Transition> tick(Rule rule, PredicateOutcome outcome) {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(outcome, "outcome");
    // Freeze on indeterminate: leave state untouched (no reset, no grace, no resolve).
    if (!(outcome instanceof PredicateOutcome.Evaluated evaluated)) {
      return Optional.empty();
    }
    boolean predicateTrue = evaluated.value();
    Instant now = clock.instant();
    Optional<Transition>[] result = new Optional[] {Optional.empty()};
    stateByRule.compute(
        rule.name(),
        (k, prior) -> {
          RuleState current = prior == null ? RuleState.inactive() : prior;
          RuleState next = advance(current, rule, predicateTrue, now, result);
          return next;
        });
    return result[0];
  }

  /** Resets the rule to INACTIVE. Used by tests; never called from production. */
  void reset(String ruleName) {
    stateByRule.remove(ruleName);
  }

  // ============================================================
  // State machine — pure function
  // ============================================================

  private static RuleState advance(
      RuleState current,
      Rule rule,
      boolean predicateTrue,
      Instant now,
      Optional<Transition>[] outcome) {
    return switch (current.state) {
      case INACTIVE -> advanceFromInactive(rule, predicateTrue, now, outcome);
      case PENDING -> advanceFromPending(current, rule, predicateTrue, now, outcome);
      case FIRING -> advanceFromFiring(rule, predicateTrue, now);
      case KEEP_FIRING -> advanceFromKeepFiring(current, predicateTrue, now, outcome);
    };
  }

  private static RuleState advanceFromInactive(
      Rule rule, boolean predicateTrue, Instant now, Optional<Transition>[] outcome) {
    if (!predicateTrue) {
      return RuleState.inactive();
    }
    // For == 0 means fire-immediately: skip PENDING, go straight to FIRING.
    if (rule.forDuration().isZero()) {
      outcome[0] = Optional.of(Transition.STARTED_FIRING);
      return new RuleState(State.FIRING, now, null);
    }
    return new RuleState(State.PENDING, now, null);
  }

  private static RuleState advanceFromPending(
      RuleState current,
      Rule rule,
      boolean predicateTrue,
      Instant now,
      Optional<Transition>[] outcome) {
    if (!predicateTrue) {
      // Predicate flipped false before `for` elapsed → reset.
      return RuleState.inactive();
    }
    Instant pendingSince = current.pendingSince;
    // Clock skew backward: clamp.
    Duration elapsed = Duration.between(pendingSince, now);
    if (elapsed.isNegative()) {
      elapsed = Duration.ZERO;
    }
    if (elapsed.compareTo(rule.forDuration()) >= 0) {
      // `for` elapsed → fire.
      outcome[0] = Optional.of(Transition.STARTED_FIRING);
      return new RuleState(State.FIRING, pendingSince, null);
    }
    // Still pending.
    return current;
  }

  private static RuleState advanceFromFiring(Rule rule, boolean predicateTrue, Instant now) {
    if (predicateTrue) {
      return new RuleState(State.FIRING, null, null);
    }
    // Predicate went false → start grace period.
    Instant graceUntil = now.plus(rule.keepFiringFor());
    return new RuleState(State.KEEP_FIRING, null, graceUntil);
  }

  private static RuleState advanceFromKeepFiring(
      RuleState current,
      boolean predicateTrue,
      Instant now,
      Optional<Transition>[] outcome) {
    if (predicateTrue) {
      // Flapped back to true → return to FIRING with no new emission.
      return new RuleState(State.FIRING, null, null);
    }
    Instant graceUntil = current.graceUntil;
    // Clock skew: if now < graceUntil, hold position.
    if (now.isBefore(graceUntil)) {
      return current;
    }
    // Grace expired → resolve.
    outcome[0] = Optional.of(Transition.RESOLVED);
    return RuleState.inactive();
  }
}
