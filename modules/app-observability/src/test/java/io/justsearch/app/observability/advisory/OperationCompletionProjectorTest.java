package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.operations.OperationOutcome;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationCompletionProjector")
final class OperationCompletionProjectorTest {

  private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

  private final OperationCompletionProjector projector = new OperationCompletionProjector();

  private static OperationCompletionEvent event(
      String operationId, OperationOutcome outcome) {
    return new OperationCompletionEvent(
        new OperationRef(operationId),
        outcome,
        T0,
        Optional.empty(),
        InvocationProvenance.systemInternal(T0));
  }

  @Test
  @DisplayName("classId is operation.completed")
  void classId() {
    assertEquals(AdvisoryClassId.of("operation.completed"), projector.classId());
  }

  @Test
  @DisplayName("SUCCESS event projects with correct classExtras")
  void successEventProjects() {
    var result = projector.project(event("core.ping-backend", OperationOutcome.SUCCESS));

    assertTrue(result.isPresent());
    AdvisoryProjection p = result.get();
    assertEquals(T0, p.occurredAt());
    assertEquals("core.ping-backend", p.classExtras().get("operationId"));
    assertEquals("SUCCESS", p.classExtras().get("outcome"));
    assertTrue(p.provenance().isPresent());
  }

  @Test
  @DisplayName("FAILURE event projects with FAILURE in classExtras")
  void failureEventProjects() {
    var result = projector.project(event("core.reindex", OperationOutcome.FAILURE));

    assertTrue(result.isPresent());
    assertEquals("FAILURE", result.get().classExtras().get("outcome"));
  }

  @Test
  @DisplayName("diagnosticsLink passes through")
  void diagnosticsLinkPassthrough() {
    var ev =
        new OperationCompletionEvent(
            new OperationRef("core.ping-backend"),
            OperationOutcome.SUCCESS,
            T0,
            Optional.of("https://example.test/diag"),
            InvocationProvenance.systemInternal(T0));

    var result = projector.project(ev);

    assertTrue(result.isPresent());
    assertEquals(Optional.of("https://example.test/diag"), result.get().diagnosticsLink());
  }

  @Test
  @DisplayName("dedupKey matches operationId:outcome pattern")
  void dedupKeyPattern() {
    assertEquals(
        "core.ping-backend:SUCCESS",
        projector.dedupKey(event("core.ping-backend", OperationOutcome.SUCCESS)));
    assertEquals(
        "core.reindex:FAILURE",
        projector.dedupKey(event("core.reindex", OperationOutcome.FAILURE)));
  }

  @Test
  @DisplayName("dedupKey is idempotent")
  void dedupKeyIdempotent() {
    var ev = event("core.ping-backend", OperationOutcome.SUCCESS);
    assertEquals(projector.dedupKey(ev), projector.dedupKey(ev));
  }

  @Test
  @DisplayName("event with executionId projects undo primaryAction + primaryActionKind")
  void executionIdProducesUndoPrimaryAction() {
    var ev =
        new OperationCompletionEvent(
            new OperationRef("core.file-operations"),
            OperationOutcome.SUCCESS,
            T0,
            Optional.empty(),
            InvocationProvenance.systemInternal(T0),
            Optional.of("batch-abc-123"));

    var result = projector.project(ev);

    assertTrue(result.isPresent());
    AdvisoryProjection p = result.get();
    assertTrue(p.primaryAction().isPresent());
    assertEquals("core.file-operations", p.primaryAction().get().target().value());
    assertTrue(p.primaryAction().get().defaultArgsJson().contains("batch-abc-123"));
    assertTrue(p.primaryActionKind().isPresent());
    assertEquals("undo", p.primaryActionKind().get());
  }

  @Test
  @DisplayName("event without executionId has empty primaryAction (existing behavior)")
  void noExecutionIdMeansNoPrimaryAction() {
    var result = projector.project(event("core.ping-backend", OperationOutcome.SUCCESS));

    assertTrue(result.isPresent());
    assertTrue(result.get().primaryAction().isEmpty());
    assertTrue(result.get().primaryActionKind().isEmpty());
  }

  @Test
  @DisplayName("different outcomes produce different dedupKeys")
  void differentOutcomesDifferentKeys() {
    String successKey =
        projector.dedupKey(event("core.ping-backend", OperationOutcome.SUCCESS));
    String failureKey =
        projector.dedupKey(event("core.ping-backend", OperationOutcome.FAILURE));
    assertTrue(
        !successKey.equals(failureKey),
        "SUCCESS and FAILURE for same operation should have different dedup keys");
  }
}
