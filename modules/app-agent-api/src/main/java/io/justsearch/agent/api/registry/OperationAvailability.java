/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * Discovery-axis sub-record on {@link Operation}: when (under what backend state) the
 * Operation is suggested as available, and what default arguments to apply when invoked
 * as a recovery from an upstream context.
 *
 * <p>Per slice 447 §X.3.1 + §X.11.5 follow-up Phase 3: this is one of the three
 * sub-records produced by partitioning the formerly-monolithic OperationPolicy along
 * consumer-model lines. {@link OperationPolicy} keeps invocation-time axes (risk,
 * confirm, audit, retry, rateLimit, capabilities, undo); {@code OperationAvailability}
 * holds discovery-time axes; {@link OperationLineage} holds post-execution axes.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code expression}: bounded predicate AST evaluated against current backend
 *       state (typically {@code ConditionStore}). When empty, the Operation is always
 *       available (no state-dependent gating); equivalent to
 *       {@link AvailabilityExpression.Always}. Future discovery-layer slices read this
 *       to filter Operations during recovery suggestion.
 *   <li>{@code argumentDefaultsJson}: JSON object source text for default arguments
 *       applied when this Operation is invoked from an {@link OperationInvocation}
 *       reference. Mirrors the existing {@code Interface.inputs} convention (no
 *       Jackson databind dependency on app-agent-api per §E.5).
 * </ul>
 *
 * <p>Empty default per §X.11.2: every existing Operation construction site passes
 * {@link #empty()} during the initial partition rollout. Non-empty values land per
 * declaration when consumer slices ship.
 */
public record OperationAvailability(
    Optional<AvailabilityExpression> expression, Optional<String> argumentDefaultsJson) {

  public OperationAvailability {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(argumentDefaultsJson, "argumentDefaultsJson");
    argumentDefaultsJson.ifPresent(
        json -> {
          if (json.isBlank()) {
            throw new IllegalArgumentException(
                "argumentDefaultsJson present but blank — pass Optional.empty() for the no-defaults case");
          }
        });
  }

  /** Empty discovery axis: no expression, no argument defaults. */
  public static OperationAvailability empty() {
    return new OperationAvailability(Optional.empty(), Optional.empty());
  }
}
