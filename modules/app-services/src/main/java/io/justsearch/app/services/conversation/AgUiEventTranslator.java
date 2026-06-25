/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.conversation.SseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tempdoc 585 §D Phase 3 (C3) — the AG-UI protocol adapter: a SIBLING to {@link
 * AgentEventSseTranslator} that maps each {@link AgentEvent} to an
 * <a href="https://github.com/ag-ui-protocol/ag-ui">AG-UI</a> event (the emerging agent↔UI streaming
 * standard, ~17 event types), so a JustSearch agent run is consumable by any AG-UI client (e.g.
 * CopilotKit) without changing the loop.
 *
 * <p>This is the cleanest demonstration of the 585 seam S1: because every event already has ONE
 * name/payload mapping ({@link AgentEventPayloads}), a SECOND projection of the same stream is a
 * single self-contained file — no loop change, no new event authority. Product value is low for a
 * local-first single-user app (§D.3 C3); the value is the design proof that the vocabulary is
 * standard-aligned.
 *
 * <p>Mapping (our 21-event vocabulary is a superset of AG-UI's 17): the lifecycle / text / tool /
 * state events map to their AG-UI analogues; our richer human-in-the-loop gating + handoff + budget
 * events (which AG-UI does not standardise) fold into the AG-UI {@code CUSTOM} catch-all carrying the
 * original event name + its canonical payload. The {@code default → CUSTOM} arm is deliberate (not an
 * exhaustiveness gap): a future {@link AgentEvent} permit a maintainer does not explicitly map still
 * emits a valid AG-UI {@code CUSTOM} event by construction — the graceful behaviour an external-
 * protocol adapter wants. {@code AgUiEventTranslatorConformanceTest} pins that every permit yields a
 * non-empty AG-UI {@code type}.
 */
public final class AgUiEventTranslator {

  private AgUiEventTranslator() {}

  /** Translate an {@link AgentEvent} to an AG-UI-shaped {@link SseEvent} (event name = AG-UI type). */
  public static SseEvent translate(AgentEvent event) {
    String runId = event.trace() == null ? "" : nz(event.trace().runId());
    return switch (event) {
      case AgentEvent.SessionStarted e ->
          agui("RUN_STARTED", Map.of("threadId", nz(e.sessionId()), "runId", nz(e.sessionId())));
      case AgentEvent.AgentDone e ->
          agui("RUN_FINISHED", Map.of("runId", runId, "result", nz(e.finalResponse())));
      case AgentEvent.AgentError e ->
          agui("RUN_ERROR", Map.of("message", nz(e.error()), "code", nz(e.errorCode())));
      case AgentEvent.TextChunk e ->
          agui("TEXT_MESSAGE_CONTENT", Map.of("messageId", runId, "delta", nz(e.text())));
      case AgentEvent.ReasoningChunk e ->
          agui("THINKING_TEXT_MESSAGE_CONTENT", Map.of("delta", nz(e.text())));
      case AgentEvent.ToolExecutionStarted e ->
          agui("TOOL_CALL_START", Map.of("toolCallId", nz(e.callId()), "toolCallName", nz(e.toolName())));
      case AgentEvent.ToolCallProposed e ->
          agui("TOOL_CALL_ARGS", Map.of("toolCallId", nz(e.call().id()), "delta", nz(e.call().arguments())));
      case AgentEvent.ToolExecutionCompleted e ->
          agui(
              "TOOL_CALL_RESULT",
              Map.of("toolCallId", nz(e.callId()), "content", e.result() == null ? "" : nz(e.result().message())));
      case AgentEvent.StateSnapshot e ->
          agui(
              "STATE_SNAPSHOT",
              Map.of(
                  "snapshot",
                  Map.of(
                      "iteration", e.iteration(),
                      "budgetRemaining", e.budgetRemaining(),
                      "toolCallsExecuted", e.toolCallsExecuted(),
                      "messageCount", e.messageCount(),
                      "activeAgentId", nz(e.activeAgentId()))));
      // Our richer gating/approval/budget/context/handoff/directive/virtual/batch events have no AG-UI
      // lifecycle analogue → the AG-UI CUSTOM catch-all (original name + canonical payload).
      default -> agui("CUSTOM", Map.of("name", AgentEventPayloads.name(event), "value", AgentEventPayloads.base(event)));
    };
  }

  /** Build an AG-UI SseEvent: the wire name is the AG-UI type, also echoed in the payload {@code type}. */
  private static SseEvent agui(String type, Map<String, Object> fields) {
    Map<String, Object> payload = new LinkedHashMap<>(fields);
    payload.put("type", type);
    return new SseEvent(type, payload);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
