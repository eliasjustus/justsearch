/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.operations.OperationOutcome;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Source-domain event for operation completion. Consumed by
 * {@link OperationCompletionProjector} to produce an {@link AdvisoryRecord}.
 *
 * <p>Per slice 494 §8.1β: renamed from {@code OperationCompletedAdvisoryEvent}
 * (the old advisory wire type). Same field set; the advisory-specific wire shape
 * is now {@link AdvisoryRecord} (uniform across all advisory classes).
 */
public record OperationCompletionEvent(
    OperationRef operationId,
    OperationOutcome outcome,
    Instant occurredAt,
    Optional<String> diagnosticsLink,
    InvocationProvenance provenance,
    Optional<String> executionId) {

  public OperationCompletionEvent {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(diagnosticsLink, "diagnosticsLink");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(executionId, "executionId");
  }

  public OperationCompletionEvent(
      OperationRef operationId,
      OperationOutcome outcome,
      Instant occurredAt,
      Optional<String> diagnosticsLink,
      InvocationProvenance provenance) {
    this(operationId, outcome, occurredAt, diagnosticsLink, provenance, Optional.empty());
  }
}
