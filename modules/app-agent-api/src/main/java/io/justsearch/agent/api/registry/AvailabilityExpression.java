/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Bounded predicate AST for Operation availability evaluation.
 *
 * <p>Per slice 447 §X.3.1 + §X.11.5 follow-up Phase 3: declared on
 * {@link OperationAvailability#expression()}; consumed by the discovery layer
 * (slice 447 §X.3.4 + the future condition-recovery-index consumer pattern) to decide
 * whether an Operation is currently suggested.
 *
 * <p><strong>Decision (impl-E + Phase 3 reinstatement)</strong>: typed AST chosen over
 * free-form String, JsonLogic, and CEL. Rationale (slice 447-impl-E "Decision:
 * AvailabilityExpression shape"):
 *
 * <ul>
 *   <li>Zero external dependencies.
 *   <li>Compile-time exhaustiveness on pattern-matching consumers (sealed interface).
 *   <li>Closed expressivity is a feature — plugins cannot smuggle eval-style extensions.
 *   <li>Bounded AST is the simplest evaluator (pattern match; no parser).
 *   <li>Future expressivity (e.g., magnitude comparisons) adds a new sealed variant —
 *       discoverable + exhaustive at compile time.
 * </ul>
 *
 * <p>Variants:
 *
 * <ul>
 *   <li>{@link Always} — predicate is always true. Default for Operations with no
 *       state-dependent gating.
 *   <li>{@link ConditionMatches} — true when an AssertedCondition with the named
 *       {@code conditionId} is currently FIRING in {@code ConditionStore}.
 *   <li>{@link AllOf} — boolean conjunction of children. {@code AllOf(empty)}
 *       evaluates to true (vacuous truth).
 *   <li>{@link AnyOf} — boolean disjunction of children. {@code AnyOf(empty)}
 *       evaluates to false (vacuous falsity).
 *   <li>{@link Not} — boolean negation of a single child.
 * </ul>
 */
public sealed interface AvailabilityExpression
    permits AvailabilityExpression.Always,
        AvailabilityExpression.ConditionMatches,
        AvailabilityExpression.AllOf,
        AvailabilityExpression.AnyOf,
        AvailabilityExpression.Not {

  /** Always-true predicate. */
  record Always() implements AvailabilityExpression {}

  /** True when an AssertedCondition with the named {@code conditionId} is currently FIRING. */
  record ConditionMatches(String conditionId) implements AvailabilityExpression {
    public ConditionMatches {
      Objects.requireNonNull(conditionId, "conditionId");
      if (conditionId.isBlank()) {
        throw new IllegalArgumentException("conditionId must be non-blank");
      }
    }
  }

  /** Conjunction (AND) of children. Empty list trivially evaluates to true. */
  record AllOf(List<AvailabilityExpression> children) implements AvailabilityExpression {
    public AllOf {
      Objects.requireNonNull(children, "children");
      children = List.copyOf(children);
    }
  }

  /** Disjunction (OR) of children. Empty list trivially evaluates to false. */
  record AnyOf(List<AvailabilityExpression> children) implements AvailabilityExpression {
    public AnyOf {
      Objects.requireNonNull(children, "children");
      children = List.copyOf(children);
    }
  }

  /** Negation of a single child. */
  record Not(AvailabilityExpression child) implements AvailabilityExpression {
    public Not {
      Objects.requireNonNull(child, "child");
    }
  }
}
