/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Wire-format record for a single Operation invocation history entry.
 *
 * <p>Per slice 444b (Operation history HISTORY Resource): one entry per completed
 * dispatch — start time captured before invocation, entry constructed and appended on
 * completion. Append-only; bounded retention via {@link OperationHistoryStore}'s ring
 * buffer.
 *
 * <p>{@code argumentsSummary} is an optional redacted summary of the input payload —
 * never the raw arguments. Per the slice's privacy flag: "the Operation declaration's
 * {@code policy.audit} axis should drive what gets logged. Don't dump raw arguments
 * without consulting the audit policy." For the substrate landing, this is
 * {@link Optional#empty()} until audit-policy plumbing lands; per slice 444b §B.D,
 * forcing a literal {@code "(redacted)"} placeholder was uninformative and pretended
 * to carry data when there was none.
 *
 * <p>{@code diagnosticsLink} is an optional pointer (URL or i18n key) to richer
 * diagnostics for this invocation — typically a HealthEvent id when the failure mapped
 * to a known condition.
 *
 * <p>Slice 447 follow-up (2026-05-08): the {@code affectedResources} field
 * (declared in slice 444b but never populated by any producer) was removed as a phantom
 * field per YAGNI + C-018. Future "Operation→Resource AFFECTS" linkage, if needed,
 * lands as a derived inverse Resource (mirroring impl-D's
 * {@code core.condition-recovery-index} pattern) rather than as another unconsumed
 * substrate field.
 *
 * <p>{@code provenance} (slice 490 §4.B) is REQUIRED — every dispatch path through
 * {@link io.justsearch.agent.api.registry.OperationDispatcher} threads typed
 * invocation-side provenance (transport / executor / initiator / occurredAt). The
 * legacy 2-arg {@code dispatch(op, args)} overload defaults to
 * {@link InvocationProvenance#systemInternal}, so no production callsite produces an
 * entry without provenance. The follow-up commit (Group A4) made the field required
 * rather than {@code Optional} — keeping it optional was theatre when every consumer
 * supplies it. The {@link #actor} field is retained as the legacy literal "head"
 * display string for wire-shape stability.
 *
 * <p>{@code executionId} (tempdoc 550 G6) is the optional backend execution identifier for
 * undo-supported operations — the same value returned to the FE, which links it to the
 * dispatching Effect Journal entry via {@code markUndoableOperation} ({@code journalEntryId}
 * ↔ {@code executionId}). Exposing it on the history entry lets the unified action-ledger
 * projection collapse the FE-Effect row and the backend-Operation row into ONE logical record
 * across the boundary. {@link Optional#empty()} for operations that are not undo-supported
 * (the executor passes no execution id).
 */
public record OperationHistoryEntry(
    OperationRef operationId,
    String actor,
    Optional<String> argumentsSummary,
    Instant startTime,
    Instant endTime,
    OperationOutcome outcome,
    Optional<String> diagnosticsLink,
    InvocationProvenance provenance,
    Optional<String> executionId) {

  public OperationHistoryEntry {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(argumentsSummary, "argumentsSummary");
    Objects.requireNonNull(startTime, "startTime");
    Objects.requireNonNull(endTime, "endTime");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(diagnosticsLink, "diagnosticsLink");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(executionId, "executionId");
    if (actor.isBlank()) {
      throw new IllegalArgumentException("actor must be non-blank");
    }
  }
}
