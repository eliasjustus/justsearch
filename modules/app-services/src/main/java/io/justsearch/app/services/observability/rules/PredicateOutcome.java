/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import java.util.Objects;

/**
 * The result of evaluating a rule's CEL predicate for one tick — a closed tri-state.
 *
 * <p>Tempdoc 600 Design B: a predicate that <em>cannot be evaluated</em> (its metric has no
 * recorded samples) must be representable distinctly from a predicate that evaluated to
 * {@code false}. Before this type, {@link CelEvaluator#evaluatePredicate} collapsed a missing
 * metric to {@code false}, making a <em>blind</em> rule indistinguishable from a <em>healthy</em>
 * one — the rule could never fire and could even spuriously RESOLVE a real firing condition, with
 * no observable trace (the self-monitoring-blindness gap, tempdoc 600 §4.4 / PART III §17).
 *
 * <p>Widening the predicate type — rather than overloading {@code false} — makes "could not
 * evaluate" unrepresentable-as-healthy by construction: the {@link DwellTimeScheduler} freezes on
 * {@link Indeterminate} (no state advance) and {@link RuleRunner} surfaces the blindness as an
 * {@code AssertedCondition(status=UNKNOWN)} observable.
 */
public sealed interface PredicateOutcome permits PredicateOutcome.Evaluated, PredicateOutcome.Indeterminate {

  /** The predicate evaluated to a concrete boolean. */
  record Evaluated(boolean value) implements PredicateOutcome {}

  /**
   * The predicate could not be evaluated this tick (e.g. a referenced metric has no samples). The
   * {@code reason} is a human-readable diagnostic (typically the missing-metric message) surfaced
   * on the blind-monitor condition.
   */
  record Indeterminate(String reason) implements PredicateOutcome {
    public Indeterminate {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Convenience factory for an evaluated outcome. */
  static PredicateOutcome evaluated(boolean value) {
    return new Evaluated(value);
  }

  /** Convenience factory for an indeterminate outcome. */
  static PredicateOutcome indeterminate(String reason) {
    return new Indeterminate(reason);
  }
}
