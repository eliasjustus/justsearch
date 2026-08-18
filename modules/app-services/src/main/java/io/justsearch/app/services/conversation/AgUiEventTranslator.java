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
      // Tempdoc 834 §6.3.4 — the AG-UI snapshot carries the new act-on-the-run facts too, and is a
      // LinkedHashMap for the same reason base() is: `park` is ABSENT when the run is not parked.
      case AgentEvent.StateSnapshot e -> agui("STATE_SNAPSHOT", Map.of("snapshot", snapshotOf(e)));
      // Our richer gating/approval/budget/context/handoff/directive/virtual/batch events have no AG-UI
      // lifecycle analogue → the AG-UI CUSTOM catch-all (original name + canonical payload).
      default -> agui("CUSTOM", Map.of("name", AgentEventPayloads.name(event), "value", AgentEventPayloads.base(event)));
    };
  }

  /**
   * Translate an ALREADY-PROJECTED {@code (name, payload)} pair — the shape a run journal carries
   * (tempdoc 834 §1.3.2) — to the same AG-UI event {@link #translate(AgentEvent)} would produce.
   *
   * <p><strong>This is a SECOND hand-written switch, and the design says so out loud</strong>
   * (§6.5). It is not a mechanical projection: the typed switch RENAMES fields on the way out
   * ({@code ToolExecutionCompleted.result().message()} becomes {@code "content"}), so this version
   * has to re-derive each mapping from the payload keys. That is a real drift surface — which is
   * exactly what makes the equivalence gate in {@code AgUiEventTranslatorConformanceTest}
   * load-bearing rather than ceremonial. If the gate is ever weakened, the drift returns.
   *
   * <p>{@code payload} may be the bare {@code AgentEventPayloads.base} map or the wire form with
   * its {@code trace} envelope appended; the trace is read for {@code runId} and then EXCLUDED from
   * the {@code CUSTOM} passthrough body, so both inputs yield the same output the typed form does.
   */
  public static SseEvent translateFromMap(String name, Map<String, Object> payload) {
    Map<String, Object> body = payload == null ? Map.of() : payload;
    String runId = str(traceOf(body).get("runId"));
    return switch (name) {
      case "session_started" ->
          agui("RUN_STARTED", Map.of("threadId", str(body.get("sessionId")), "runId", str(body.get("sessionId"))));
      case "done" -> agui("RUN_FINISHED", Map.of("runId", runId, "result", str(body.get("finalResponse"))));
      case "error" ->
          agui("RUN_ERROR", Map.of("message", str(body.get("error")), "code", str(body.get("errorCode"))));
      case "chunk" -> agui("TEXT_MESSAGE_CONTENT", Map.of("messageId", runId, "delta", str(body.get("text"))));
      case "reasoning_chunk" -> agui("THINKING_TEXT_MESSAGE_CONTENT", Map.of("delta", str(body.get("text"))));
      case "tool_exec_started" ->
          agui("TOOL_CALL_START", Map.of("toolCallId", str(body.get("callId")), "toolCallName", str(body.get("toolName"))));
      case "tool_call_proposed" ->
          agui("TOOL_CALL_ARGS", Map.of("toolCallId", str(body.get("callId")), "delta", str(body.get("arguments"))));
      // The rename §6.5 names: the canonical payload key is `output`, the AG-UI field is `content`.
      case "tool_exec_completed" ->
          agui("TOOL_CALL_RESULT", Map.of("toolCallId", str(body.get("callId")), "content", str(body.get("output"))));
      case "state_snapshot" -> agui("STATE_SNAPSHOT", Map.of("snapshot", withoutTrace(body)));
      default -> agui("CUSTOM", Map.of("name", name, "value", withoutTrace(body)));
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> traceOf(Map<String, Object> payload) {
    Object trace = payload.get("trace");
    return trace instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  /**
   * The payload minus its trace envelope. The typed translator builds {@code CUSTOM.value} and the
   * snapshot body from {@code base(event)}, which never carries the trace, so a wire payload has to
   * be reduced to the same thing or the two forms would differ by exactly one key.
   */
  private static Map<String, Object> withoutTrace(Map<String, Object> payload) {
    if (!payload.containsKey("trace")) {
      return payload;
    }
    Map<String, Object> out = new LinkedHashMap<>(payload);
    out.remove("trace");
    return out;
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  /**
   * The AG-UI {@code STATE_SNAPSHOT} body. Delegates the two nested shapes to {@link
   * AgentEventPayloads} so the AG-UI projection cannot drift from the canonical one.
   */
  private static Map<String, Object> snapshotOf(AgentEvent.StateSnapshot e) {
    // base() already null-guards activeAgentId to "" exactly as nz() does, so this is the same five
    // keys it emitted before 834, plus the three new ones.
    return new LinkedHashMap<>(AgentEventPayloads.base(e));
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
