/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Projector for operation-completed advisories. Consumes {@link OperationCompletionEvent}
 * (previously the wire type; now the source-domain type) and projects into the uniform
 * {@link AdvisoryRecord} shape via {@link AdvisoryProjection}.
 *
 * <p>Per slice 494 §8: inline-bound in {@code OperationExecutorImpl} via the existing
 * advisory-consumer slot. Every Operation with {@code OperationPolicy.advisoryClass}
 * present produces an {@link OperationCompletionEvent} that this projector transforms.
 *
 * <p>All source events pass through (no filtering — the opt-in gate is at the executor
 * level via {@code OperationPolicy.emitAdvisoryOnCompletion}). The projector is pure
 * transformation.
 */
public final class OperationCompletionProjector
    implements AdvisoryProjector<OperationCompletionEvent> {

  public static final AdvisoryClassId CLASS_ID = AdvisoryClassId.of("operation.completed");

  private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(1);

  @Override
  public AdvisoryClassId classId() {
    return CLASS_ID;
  }

  @Override
  public EmissionPolicy emissionPolicy() {
    return EmissionPolicy.persisted().withDedupeWindow(DEDUPE_WINDOW);
  }

  @Override
  public Optional<AdvisoryProjection> project(OperationCompletionEvent event) {
    Optional<OperationInvocation> undoAction = event.executionId()
        .map(eid -> new OperationInvocation(
            event.operationId(),
            "{\"executionId\":\"" + eid.replace("\"", "\\\"") + "\"}"));
    Optional<String> actionKind = undoAction.isPresent()
        ? Optional.of("undo") : Optional.empty();
    return Optional.of(
        new AdvisoryProjection(
            event.occurredAt(),
            event.diagnosticsLink(),
            Optional.of(event.provenance()),
            undoAction,
            actionKind,
            Optional.of(
                "advisory.operation-completed."
                    + event.outcome().name().toLowerCase(java.util.Locale.ROOT)),
            Map.of(
                "operationId", event.operationId().value(),
                "outcome", event.outcome().name())));
  }

  @Override
  public String dedupKey(OperationCompletionEvent event) {
    return event.operationId().value() + ":" + event.outcome().name();
  }
}
