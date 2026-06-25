/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.preview;

import io.justsearch.agent.api.registry.AvailabilityExpression;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure evaluator for the bounded {@link AvailabilityExpression} predicate AST — tempdoc
 * 550 Slice F2 (Preview face).
 *
 * <p>Before this, {@code Operation.availability} was a <b>dead channel</b>: the only
 * reader was {@code UIOperationEmitter} (which serializes the AST to the wire for
 * display), and nothing actually <i>evaluated</i> it (verified in 550's diagnosis;
 * mirrors {@code KeybindingEntry.when}). The Preview face needs one evaluation that
 * answers "can this action run right now?" — this is that evaluation.
 *
 * <p>The evaluator is a pure recursive function over the sealed AST, parameterised by a
 * {@code conditionFiring} predicate (true when an asserted condition with the given id is
 * currently FIRING). It has no dependency on any store, so it is trivially testable and
 * reusable by every Preview consumer (the preview endpoint, and — once the Authorize
 * cutover lands — the gate's "requires-consent?" check and agent-tool-emitter filtering).
 *
 * <p><b>Structural-first (550 D4):</b> this evaluation is fully answerable with the Brain
 * offline; it consults only declared structure + live condition state, never the LLM.
 *
 * <p>Semantics (match {@link AvailabilityExpression}'s contract):
 *
 * <ul>
 *   <li>{@code Always} → true.
 *   <li>{@code ConditionMatches(id)} → {@code conditionFiring.test(id)}.
 *   <li>{@code AllOf(children)} → conjunction; empty → true (vacuous).
 *   <li>{@code AnyOf(children)} → disjunction; empty → false (vacuous).
 *   <li>{@code Not(child)} → negation.
 * </ul>
 */
public final class AvailabilityEvaluator {

  private AvailabilityEvaluator() {}

  /**
   * Evaluates {@code expr} against the current condition state.
   *
   * @param expr the availability predicate AST (never null)
   * @param conditionFiring true when the named condition id is currently FIRING
   * @return whether the Operation is currently available
   */
  public static boolean evaluate(
      AvailabilityExpression expr, Predicate<String> conditionFiring) {
    Objects.requireNonNull(expr, "expr");
    Objects.requireNonNull(conditionFiring, "conditionFiring");
    return switch (expr) {
      case AvailabilityExpression.Always ignored -> true;
      case AvailabilityExpression.ConditionMatches cm -> conditionFiring.test(cm.conditionId());
      case AvailabilityExpression.AllOf allOf ->
          allOf.children().stream().allMatch(child -> evaluate(child, conditionFiring));
      case AvailabilityExpression.AnyOf anyOf ->
          anyOf.children().stream().anyMatch(child -> evaluate(child, conditionFiring));
      case AvailabilityExpression.Not not -> !evaluate(not.child(), conditionFiring);
    };
  }
}
