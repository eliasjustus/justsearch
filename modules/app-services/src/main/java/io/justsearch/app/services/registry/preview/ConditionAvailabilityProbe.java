/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.preview;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.ThresholdPhase;
import io.justsearch.app.observability.health.ThresholdState;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Derives the "is this condition currently firing?" predicate the {@link
 * AvailabilityEvaluator} needs, from the live {@link ConditionStore} — tempdoc 550 Slice
 * F2 (Preview face).
 *
 * <p>"Firing" matches {@link io.justsearch.agent.api.registry.AvailabilityExpression.ConditionMatches}'s
 * contract — a named condition <i>holds</i>:
 *
 * <ul>
 *   <li>An {@link AssertedCondition} with {@link ConditionStatus#TRUE} (k8s convention:
 *       status TRUE = "the named condition holds").
 *   <li>A {@link ThresholdState} in {@link ThresholdPhase#FIRING}.
 * </ul>
 *
 * <p>Pure read over the store's current snapshot; structural-first (550 D4), Brain not
 * required. Exposed as a {@link Predicate} so the evaluator stays decoupled from the
 * health substrate.
 */
public final class ConditionAvailabilityProbe {

  private final ConditionStore conditionStore;

  public ConditionAvailabilityProbe(ConditionStore conditionStore) {
    this.conditionStore = Objects.requireNonNull(conditionStore, "conditionStore");
  }

  /** True when a condition with {@code conditionId} is currently firing. */
  public boolean isFiring(String conditionId) {
    if (conditionId == null) {
      return false;
    }
    return conditionStore.currentSnapshot().stream()
        .filter(event -> conditionId.equals(event.id()))
        .anyMatch(
            event ->
                switch (event.body()) {
                  case AssertedCondition ac -> ac.status() == ConditionStatus.TRUE;
                  case ThresholdState ts -> ts.phase() == ThresholdPhase.FIRING;
                  default -> false;
                });
  }

  /** The firing predicate, for {@link AvailabilityEvaluator#evaluate}. */
  public Predicate<String> asPredicate() {
    return this::isFiring;
  }
}
