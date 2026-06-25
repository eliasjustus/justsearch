/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tempdoc 561 P-A/P-B (correction) — the READ-TIME projection of a persisted {@code AgentRunStore}
 * event into a plane-neutral {@link InteractionEvent} for the unified thread.
 *
 * <p>This is a projection, not a producer: the agent's activity is already durable in
 * {@code AgentRunStore.events.ndjson} (§10: "the live thread is reconstructable from events.ndjson").
 * The unified thread reads those records and maps them here — it does NOT write a second store. Only
 * the events that constitute the durable thread become interaction events; transient/streaming events
 * (chunks, proposed/approved/started, progress, budget, session_started) map to empty.
 *
 * <p>Input is one {@code events.ndjson} record: {@code {timestamp: ISO, eventType: String, payload:
 * {…}}} (the shape {@code AgentRunStore.appendEvent} writes via {@code toPayload}).
 */
public final class AgentInteractionMapper {

  private AgentInteractionMapper() {}

  /**
   * Project one persisted run event to its thread event, or empty if it is not durable thread
   * content.
   *
   * @param record one {@code events.ndjson} record ({@code timestamp}/{@code eventType}/{@code
   *     payload})
   * @param conversationId the chat conversation this run belongs to
   */
  public static Optional<InteractionEvent> fromRunEvent(
      Map<String, Object> record, String conversationId) {
    if (!(record.get("eventType") instanceof String eventType)) {
      return Optional.empty();
    }
    Map<String, Object> payload =
        record.get("payload") instanceof Map<?, ?> m ? castMap(m) : Map.of();
    Instant at = parseTs(record.get("timestamp"));
    String stamp = String.valueOf(at.toEpochMilli());
    return switch (eventType) {
      case "done" -> {
        // Tempdoc 565 §26.I (Fix A) — a WORKFLOW terminal `done` (it carries `nodesExecuted`) is NOT an
        // answer bubble: the workflow's content lives in the per-node `node_output` events that bracket
        // inside each node, and the done's `finalResponse` merely repeats the LAST node's output.
        // Skipping it here prevents the last node rendering twice on a reloaded workflow run. An AGENT
        // `done` (no `nodesExecuted`) IS the answer and falls through.
        if (payload.containsKey("nodesExecuted")) {
          yield Optional.empty();
        }
        // Tempdoc 565 §3.A/persistence — carry the answer's grounding sources + per-sentence
        // citations on the persisted assistant message so a reloaded thread renders the same Sources
        // pane + inline marks from the record (mirrors the RAG path at
        // ConversationEngine.persistedAssistant, which attaches citations/claimMatches).
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (payload.get("sources") instanceof List<?> srcs && !srcs.isEmpty()) {
          attributes.put("sources", srcs);
        }
        if (payload.get("citations") instanceof List<?> cites && !cites.isEmpty()) {
          attributes.put("citations", cites);
        }
        yield Optional.of(
            new InteractionEvent(
                conversationId + ":assistant:" + stamp,
                conversationId,
                at,
                InteractionEventKind.ASSISTANT_MESSAGE,
                "agent",
                str(payload.get("finalResponse")),
                attributes));
      }
      // Tempdoc 565 §12.3.B — `tool_call_proposed` fires for EVERY tool (incl. auto-run ones that
      // never reach `pending`), carrying the tool's identity (toolName + arguments + risk). The FE
      // projection merges all TOOL_ACTIVITY events for a callId, so this supplies the verb+target the
      // compact tool row needs on the record (reload) — the terminal completed/rejected events add the
      // outcome/evidence but carry no identity.
      case "tool_call_proposed" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":proposed",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "arguments", payload.get("arguments"),
                      "status", "proposed",
                      "risk", payload.get("risk"))));
      case "tool_call_pending" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":pending",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "arguments", payload.get("arguments"),
                      "status", "pending",
                      "risk", payload.get("risk"))));
      // Tempdoc 565 §15.C — the workflow run (now projected through this ONE thread mapper) carries a
      // tool's identity on `tool_exec_started` for auto-run steps that never reach `pending` (the agent
      // path supplies identity via `tool_call_proposed`). The FE merges all TOOL_ACTIVITY by callId, so
      // this adds the verb+target to the same card the terminal `tool_exec_completed` fills out.
      case "tool_exec_started" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":started",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "toolName", payload.get("toolName"),
                      "status", "executing")));
      case "tool_exec_completed" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":completed",
                  conversationId,
                  at,
                  attrs(
                      "callId", payload.get("callId"),
                      "status", "completed",
                      "success", payload.get("success"),
                      "output", payload.get("output"),
                      // Tempdoc 561 #6: carry the producer evidence onto the record event so the
                      // record render shows the same evidence cards as the live overlay.
                      "structuredData", payload.get("structuredData"))));
      case "tool_call_rejected" ->
          Optional.of(
              toolActivity(
                  str(payload.get("callId")) + ":rejected",
                  conversationId,
                  at,
                  attrs("callId", payload.get("callId"), "status", "rejected", "reason",
                      payload.get("reason"))));
      case "error" ->
          Optional.of(
              new InteractionEvent(
                  conversationId + ":error:" + stamp,
                  conversationId,
                  at,
                  InteractionEventKind.ERROR,
                  "agent",
                  str(payload.get("error")),
                  attrs("errorCode", payload.get("errorCode"))));
      case "handoff_executed" ->
          Optional.of(
              new InteractionEvent(
                  conversationId + ":handoff:" + stamp,
                  conversationId,
                  at,
                  InteractionEventKind.HANDOFF,
                  "agent",
                  "",
                  attrs("fromAgentId", payload.get("fromAgentId"), "toAgentId",
                      payload.get("toAgentId"))));
      // Tempdoc 565 §26.A/§26.B — the workflow run's STRUCTURE: a node boundary surfaces as a
      // PROGRESS event carrying `nodeBoundary`/`nodeId`/`nodeKind`, so the record-side projection
      // brackets a node's steps into a run segment (the FE `assignRunSegments` pass) exactly as the
      // live side does. Before §26 these were dropped here (the `default` no-op), so a reloaded
      // workflow run lost its node grouping. The nodeId doubles as the segment label.
      case "node_started" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 1, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.PROGRESS,
                  "agent",
                  "",
                  attrs(
                      "nodeBoundary", "start",
                      "nodeId", payload.get("nodeId"),
                      "nodeKind", payload.get("kind"),
                      "label", payload.get("nodeId"))));
      case "node_completed" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 3, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.PROGRESS,
                  "agent",
                  "",
                  attrs(
                      "nodeBoundary", "end",
                      "nodeId", payload.get("nodeId"),
                      "nodeKind", payload.get("kind"),
                      "label", payload.get("nodeId"))));
      // Tempdoc 565 §26.I (Fix A) — a workflow LlmStep's full output, persisted as the node's durable
      // ASSISTANT_MESSAGE. Its id sorts BETWEEN node_started (role 1) and node_completed (role 3) even on
      // a same-millisecond timestamp tie (role 2), so the reloaded projection brackets it INSIDE the node
      // segment — making reload identical to the live render (which builds the same text from the chunks).
      case "node_output" ->
          Optional.of(
              new InteractionEvent(
                  nodeEventId(conversationId, payload.get("index"), 2, payload.get("nodeId"), stamp),
                  conversationId,
                  at,
                  InteractionEventKind.ASSISTANT_MESSAGE,
                  "agent",
                  str(payload.get("output")),
                  Map.of()));
      default -> Optional.empty();
    };
  }

  private static InteractionEvent toolActivity(
      String id, String conversationId, Instant at, Map<String, Object> attributes) {
    return new InteractionEvent(
        id, conversationId, at, InteractionEventKind.TOOL_ACTIVITY, "agent", "", attributes);
  }

  /** Build an attribute map, skipping null values (Map.copyOf rejects nulls). */
  private static Map<String, Object> attrs(Object... kv) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      Object value = kv[i + 1];
      if (value != null) {
        m.put((String) kv[i], value);
      }
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : o == null ? "" : String.valueOf(o);
  }

  /**
   * Tempdoc 565 §26.I — a workflow node event's stable id, built so LEXICAL order == TEMPORAL order on a
   * same-millisecond timestamp tie: {@code …:node:<5-digit index>:<role 1=start|2=output|3=end>:<nodeId>:<ms>}.
   * The FE sort tiebreaker is {@code id.localeCompare}, so without the index+role ordering a tie between
   * {@code node_output} and {@code node_completed} (emitted back-to-back) would sort the {@code end}
   * boundary first and render the node's output OUTSIDE its segment (the reload defect Fix A targets); the
   * index keeps node N's {@code end} ahead of node N+1's {@code start} on the cross-node tie.
   */
  private static String nodeEventId(
      String conversationId, Object indexObj, int role, Object nodeId, String stamp) {
    int idx = indexObj instanceof Number n ? n.intValue() : 0;
    return conversationId
        + ":node:"
        + String.format(java.util.Locale.ROOT, "%05d", idx)
        + ":"
        + role
        + ":"
        + str(nodeId)
        + ":"
        + stamp;
  }

  static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    if (raw instanceof Number n) {
      return Instant.ofEpochMilli(n.longValue());
    }
    return Instant.EPOCH;
  }
}
