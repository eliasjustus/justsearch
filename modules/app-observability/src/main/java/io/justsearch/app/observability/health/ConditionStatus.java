/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

/**
 * Tri-state status for an asserted condition.
 *
 * <p>Per tempdoc 430 §A.1 + k8s {@code metav1.ConditionStatus}: TRUE means the condition
 * is asserted (the named property holds), FALSE means asserted-not-holding, UNKNOWN
 * means observation-failed-or-not-yet-available. Re-emitting the same status is a no-op
 * per k8s {@code SetStatusCondition} semantics; transitions update
 * {@link AssertedCondition#lastTransitionTime()}.
 */
public enum ConditionStatus {
  TRUE,
  FALSE,
  UNKNOWN
}
