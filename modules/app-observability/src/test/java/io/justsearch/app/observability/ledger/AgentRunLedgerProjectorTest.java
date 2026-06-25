package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-A/P-B — the ledger's agent rows are a PROJECTION of the durable {@code AgentRunStore}
 * record, so the unified thread (AgentRunStore) and agent History (ledger) derive from one source and
 * cannot disagree. This proves the projector emits exactly one correctly-shaped {@code Operation} row
 * per tool completion, with the toolName correlated from the earlier proposed/started event.
 */
final class AgentRunLedgerProjectorTest {

  private static Map<String, Object> rec(String type, Map<String, Object> payload) {
    return Map.of("timestamp", "2026-01-01T00:00:02Z", "eventType", type, "payload", payload);
  }

  @Test
  @DisplayName("a tool completion projects ONE Operation row derived from the agent record")
  void projectsOneOperationRow() {
    var registry = new ActionLedgerChangeRegistry();
    var projector = new AgentRunLedgerProjector(registry);

    // The proposed/started event carries the toolName; the completion carries success + executionId.
    projector.onEvent(
        "sess-1", rec("tool_exec_started", Map.of("callId", "c1", "toolName", "core_search_index")));
    projector.onEvent(
        "sess-1",
        rec("tool_exec_completed", Map.of("callId", "c1", "success", true, "executionId", "ex-1")));

    List<ActionEvent> rows = registry.store().recent();
    assertEquals(1, rows.size(), "exactly one ledger row per agent tool execution (no double-count)");
    ActionEvent.Operation op = assertInstanceOf(ActionEvent.Operation.class, rows.get(0));
    assertEquals("core_search_index", op.operationId(), "toolName correlated from the started event");
    assertEquals("SUCCESS", op.outcome());
    assertEquals("agent", op.originator());
    assertEquals("AGENT_LOOP", op.transport());
    assertEquals("ex-1", op.executionId().orElse(""), "executionId carried (undo round-trip survives)");
    assertEquals("sess-1", op.correlationId().orElse(""), "correlationId = run sessionId (History filter)");
  }

  @Test
  @DisplayName("failure maps to FAILURE; non-tool events produce no rows; blank executionId -> empty")
  void failureAndNonToolEvents() {
    var registry = new ActionLedgerChangeRegistry();
    var projector = new AgentRunLedgerProjector(registry);

    projector.onEvent("s", rec("chunk", Map.of("text", "hi"))); // not a tool event → no row
    projector.onEvent(
        "s", rec("tool_call_proposed", Map.of("callId", "c2", "toolName", "core_ingest_files")));
    projector.onEvent(
        "s", rec("tool_exec_completed", Map.of("callId", "c2", "success", false, "executionId", "")));

    List<ActionEvent> rows = registry.store().recent();
    assertEquals(1, rows.size());
    ActionEvent.Operation op = (ActionEvent.Operation) rows.get(0);
    assertEquals("core_ingest_files", op.operationId());
    assertEquals("FAILURE", op.outcome());
    assertTrue(op.executionId().isEmpty(), "blank executionId projects to empty Optional");
  }
}
