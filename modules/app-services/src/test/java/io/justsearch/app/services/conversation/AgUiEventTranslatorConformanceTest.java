package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.conversation.SseEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 585 §D Phase 3 (C3) — the AG-UI adapter conformance: every {@link AgentEvent} permit must
 * translate to a VALID AG-UI event type, and the wire name must equal the payload {@code type} (the
 * AG-UI envelope invariant). This is the test that makes the {@code default → CUSTOM} arm safe: a new
 * permit a maintainer forgets to map still emits a valid {@code CUSTOM} event, and this test proves it.
 */
class AgUiEventTranslatorConformanceTest {

  /** The AG-UI event types this adapter emits (the standard's lifecycle/text/tool/state + CUSTOM). */
  private static final Set<String> AG_UI_TYPES =
      Set.of(
          "RUN_STARTED",
          "RUN_FINISHED",
          "RUN_ERROR",
          "TEXT_MESSAGE_CONTENT",
          "THINKING_TEXT_MESSAGE_CONTENT",
          "TOOL_CALL_START",
          "TOOL_CALL_ARGS",
          "TOOL_CALL_RESULT",
          "STATE_SNAPSHOT",
          "CUSTOM");

  /** One representative of every {@link AgentEvent} sealed permit. */
  private static final List<AgentEvent> ALL_VARIANTS =
      List.of(
          new AgentEvent.TextChunk("hi"),
          new AgentEvent.ReasoningChunk("thinking"),
          new AgentEvent.ToolCallProposed(new io.justsearch.agent.api.ToolCallRequest("id", "tool", "args"), RiskTier.LOW),
          new AgentEvent.ToolBatchProposed(List.of()),
          new AgentEvent.ToolCallPendingApproval("id", "tool", "args", RiskTier.LOW),
          new AgentEvent.ToolCallApproved("id"),
          new AgentEvent.DirectiveAcknowledged("focus"),
          new AgentEvent.ToolExecutionStarted("id", "tool"),
          new AgentEvent.ToolExecutionCompleted("id", OperationResult.success("ok")),
          new AgentEvent.ToolCallRejected("id", "no"),
          new AgentEvent.ToolCallVirtual("id", "wire", "args"),
          new AgentEvent.AgentDone("answer", 1, 0, 0),
          new AgentEvent.AgentError("boom", "CODE"),
          new AgentEvent.AgentProgress("phase", "msg", 1, 8),
          new AgentEvent.AgentBudgetUpdate("phase", 0, 0),
          new AgentEvent.BudgetGatePending(1, 0, 0),
          new AgentEvent.ContextGatePending(1, 2),
          new AgentEvent.ContextCompacted(1),
          new AgentEvent.SessionStarted("sid"),
          new AgentEvent.HandoffProposed("a", "b", "why"),
          new AgentEvent.HandoffExecuted("a", "b"),
          new AgentEvent.StateSnapshot(2, 1500, 3, 7, "primary"));

  @Test
  @DisplayName("ALL_VARIANTS covers every AgentEvent permit")
  void coversEveryPermit() {
    assertEquals(
        AgentEvent.class.getPermittedSubclasses().length,
        ALL_VARIANTS.size(),
        "ALL_VARIANTS must list every AgentEvent permit (a new variant was added — add it here)");
  }

  @Test
  @DisplayName("every AgentEvent maps to a valid AG-UI type with a matching payload envelope")
  void everyEventMapsToAValidAgUiType() {
    for (AgentEvent event : ALL_VARIANTS) {
      SseEvent sse = AgUiEventTranslator.translate(event);
      assertTrue(
          AG_UI_TYPES.contains(sse.name()),
          () -> event.getClass().getSimpleName() + " mapped to unknown AG-UI type: " + sse.name());
      assertEquals(
          sse.name(),
          sse.payload().get("type"),
          () -> "AG-UI payload.type must equal the event type for " + event.getClass().getSimpleName());
    }
  }

  @Test
  @DisplayName("the lifecycle/state events map to their specific AG-UI analogues, not CUSTOM")
  void specificMappingsAreHonoured() {
    assertEquals("RUN_STARTED", AgUiEventTranslator.translate(new AgentEvent.SessionStarted("s")).name());
    assertEquals("RUN_FINISHED", AgUiEventTranslator.translate(new AgentEvent.AgentDone("a", 1, 0, 0)).name());
    assertEquals("RUN_ERROR", AgUiEventTranslator.translate(new AgentEvent.AgentError("e", "C")).name());
    assertEquals("TEXT_MESSAGE_CONTENT", AgUiEventTranslator.translate(new AgentEvent.TextChunk("t")).name());
    assertEquals(
        "STATE_SNAPSHOT",
        AgUiEventTranslator.translate(new AgentEvent.StateSnapshot(1, 0, 0, 0, "p")).name());
    // A human-in-the-loop gating event with no AG-UI analogue folds into CUSTOM carrying the name.
    SseEvent custom = AgUiEventTranslator.translate(new AgentEvent.BudgetGatePending(1, 0, 0));
    assertEquals("CUSTOM", custom.name());
    assertEquals("budget_gate", custom.payload().get("name"));
  }
}
