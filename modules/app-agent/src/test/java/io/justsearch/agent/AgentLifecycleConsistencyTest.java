package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.lifecycle.AgentLifecycle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-A — the cross-projection consistency invariant. The Activity rail's tool count
 * (projected via {@link AgentLifecycleProjection} → {@link AgentLifecycle#toolCallsExecuted()}) and
 * the unified thread's completed tool events (projected via {@link AgentInteractionMapper}) both
 * derive from the ONE durable {@code AgentRunStore} record for a run. This locks the invariant that
 * they CANNOT DISAGREE on "how many tools the run ran": the lifecycle's authoritative tool total
 * equals the count of completed {@code TOOL_ACTIVITY} events the thread renders. A future change that
 * dropped a mapped event type, or miscounted in the lifecycle, fails here.
 */
final class AgentLifecycleConsistencyTest {

  private static Map<String, Object> event(String type, Map<String, Object> payload) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("timestamp", "2026-01-01T00:00:02Z");
    e.put("eventType", type);
    e.put("payload", payload);
    return e;
  }

  @Test
  @DisplayName("the lifecycle's toolCallsExecuted equals the thread's completed TOOL_ACTIVITY count")
  void lifecycleToolCountAgreesWithThreadEvents() {
    // ONE durable run record: meta (the rail's source) + events.ndjson (the thread's source).
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("sessionId", "run-7");
    meta.put("conversationId", "conv-7");
    meta.put("state", "DONE");
    meta.put("initialBudget", 4000);
    meta.put("totalTokensUsed", 4100);
    meta.put("iterationsUsed", 3);
    meta.put("toolCallsExecuted", 2);
    meta.put(
        "messages",
        List.of(Map.of("role", "user", "content", "find invoices and browse the folder")));

    List<Map<String, Object>> events =
        List.of(
            event("tool_call_pending", Map.of("callId", "c1", "toolName", "core_search_index")),
            event("tool_exec_completed", Map.of("callId", "c1", "success", true)),
            event("tool_call_pending", Map.of("callId", "c2", "toolName", "core_browse_folders")),
            event("tool_exec_completed", Map.of("callId", "c2", "success", true)),
            event("done", Map.of("finalResponse", "found them")));

    // Rail projection: the lifecycle's authoritative tool total.
    AgentLifecycle lc = AgentLifecycleProjection.fromRun(meta);

    // Thread projection: the completed tool events the unified thread renders.
    List<InteractionEvent> threadEvents = new ArrayList<>();
    for (Map<String, Object> e : events) {
      AgentInteractionMapper.fromRunEvent(e, "conv-7").ifPresent(threadEvents::add);
    }
    long completedToolEvents =
        threadEvents.stream()
            .filter(e -> e.kind() == InteractionEventKind.TOOL_ACTIVITY)
            .filter(e -> "completed".equals(e.attributes().get("status")))
            .count();

    // The two projections of the ONE record cannot disagree on the run's tool total.
    assertEquals(2, lc.toolCallsExecuted(), "lifecycle tool total from meta");
    assertEquals(2, completedToolEvents, "thread completed-tool events from events.ndjson");
    assertEquals(
        lc.toolCallsExecuted(),
        completedToolEvents,
        "the rail and the thread project the same record — they cannot disagree on tool count");
  }
}
