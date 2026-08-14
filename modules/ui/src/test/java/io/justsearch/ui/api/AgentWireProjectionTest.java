package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.agent.AgentBatchSummary;
import io.justsearch.app.api.agent.AgentHistoryResponse;
import io.justsearch.app.api.agent.AgentSessionSummary;
import io.justsearch.app.api.agent.AgentSessionsResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 564 Phase 3: guards the {@code Map → record} projection AgentController performs at the
 * {@code /api/chat/sessions} and {@code /api/chat/agent/history} boundaries. The records exist so the
 * FE can validate the surface against a generated JSON-Schema → Zod projection — but only if the
 * record serializes to <em>exactly</em> the JSON the agent layer's untyped {@code Map} produced. This
 * test proves that round-trip fidelity (no wire change) for representative shapes, including the
 * nested {@code terminationReason}. Without it, the projection is an unverified audit claim
 * (static-green ≠ live-working); the deferred live batch is the second tier.
 */
@DisplayName("Agent wire-record projection fidelity")
final class AgentWireProjectionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Mirrors {@code AgentRunStore.toSessionSummary} output for a terminated session. */
  private static Map<String, Object> sessionSummaryMap() {
    var termination = new LinkedHashMap<String, Object>();
    termination.put("disposition", "COMPLETED");
    termination.put("errorCode", null);
    termination.put("cancelTrigger", null);
    var m = new LinkedHashMap<String, Object>();
    m.put("sessionId", "s-1");
    m.put("startedAt", "2026-04-28T10:00:00Z");
    m.put("updatedAt", "2026-04-28T10:01:00Z");
    m.put("state", "COMPLETED");
    m.put("resumable", false);
    m.put("iterationsUsed", 2);
    m.put("toolCallsExecuted", 1);
    m.put("totalTokensUsed", 120);
    m.put("activeAgentId", "planner");
    m.put("terminationReason", termination);
    // Tempdoc 834 §5.3 — toSessionSummary ALWAYS emits the key (null for a run that was never
    // interrupted), so the record projection must too or the wire JSON changes.
    m.put("interruptedAt", null);
    m.put("preview", "find files");
    return m;
  }

  /** Mirrors {@code AgentRunStore.toSessionSummary} for a run a restart found mid-flight. */
  private static Map<String, Object> interruptedSessionSummaryMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("sessionId", "s-2");
    m.put("startedAt", "2026-04-28T10:00:00Z");
    m.put("updatedAt", "2026-04-28T10:01:00Z");
    m.put("state", "WAITING_APPROVAL");
    m.put("resumable", true);
    m.put("iterationsUsed", 2);
    m.put("toolCallsExecuted", 1);
    m.put("totalTokensUsed", 120);
    m.put("activeAgentId", "planner");
    m.put("terminationReason", null);
    m.put("interruptedAt", "2026-04-28T10:05:00Z");
    m.put("preview", "find files");
    return m;
  }

  /** Mirrors {@code AgentRunQueryService.toBatchSummary} output. */
  private static Map<String, Object> batchSummaryMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("batchId", "b-1");
    m.put("timestamp", "2026-04-28T10:00:00Z");
    m.put("explanation", "move files");
    m.put("operationCount", 3);
    m.put("successCount", 1L);
    m.put("failedCount", 1L);
    m.put("skippedCount", 1L);
    m.put("finalized", true);
    return m;
  }

  @Test
  @DisplayName("sessions envelope: record serializes identically to the untyped Map wire")
  void sessionsRoundTrip() {
    Map<String, Object> map = sessionSummaryMap();
    AgentSessionSummary record = MAPPER.convertValue(map, AgentSessionSummary.class);
    JsonNode fromRecord = MAPPER.valueToTree(new AgentSessionsResponse(List.of(record)));
    JsonNode fromMap = MAPPER.valueToTree(Map.of("sessions", List.of(map)));
    assertEquals(fromMap, fromRecord, "AgentSessionSummary projection must not change the wire JSON");
  }

  @Test
  @DisplayName("interrupted row: interruptedAt survives the Map → record projection")
  void interruptedRowRoundTrip() {
    Map<String, Object> map = interruptedSessionSummaryMap();
    AgentSessionSummary record = MAPPER.convertValue(map, AgentSessionSummary.class);
    assertEquals("2026-04-28T10:05:00Z", record.interruptedAt());
    // 834 §5.2: the marker is ADDITIVE — the resume seed and the resumable flag are untouched, so
    // the run can still be resumed from exactly where it stopped.
    assertEquals("WAITING_APPROVAL", record.state());
    assertEquals(Boolean.TRUE, record.resumable());
    JsonNode fromRecord = MAPPER.valueToTree(new AgentSessionsResponse(List.of(record)));
    JsonNode fromMap = MAPPER.valueToTree(Map.of("sessions", List.of(map)));
    assertEquals(fromMap, fromRecord, "AgentSessionSummary projection must not change the wire JSON");
  }

  @Test
  @DisplayName("history envelope: record serializes identically to the untyped Map wire")
  void historyRoundTrip() {
    Map<String, Object> map = batchSummaryMap();
    AgentBatchSummary record = MAPPER.convertValue(map, AgentBatchSummary.class);
    JsonNode fromRecord = MAPPER.valueToTree(new AgentHistoryResponse(List.of(record)));
    JsonNode fromMap = MAPPER.valueToTree(Map.of("batches", List.of(map)));
    assertEquals(fromMap, fromRecord, "AgentBatchSummary projection must not change the wire JSON");
  }
}
