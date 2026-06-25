/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 561 P-A/P-B (correction) — {@code GET /api/thread/{id}}: the unified thread as a READ-TIME
 * projection of the records that already exist, joined by {@code conversationId}. There is NO new
 * store: the answer plane's turns come from {@code ConversationStore} and the action plane's turns
 * come from {@code AgentRunStore} (via {@code AgentService.threadEvents}), so the thread, History, and
 * Timeline all derive from the same agent-run events and cannot structurally disagree (§2.2 total
 * projection). This replaced an earlier fork that wrote a second {@code InteractionLog} store (§11).
 *
 * <p>Wire shape (JSON; FE Zod-validates it):
 *
 * <pre>{@code
 * GET /api/thread/{id} -> {
 *   "conversationId": "...",
 *   "events": [ { "id", "occurredAt", "kind", "originator", "content", "attributes" }, ... ]
 * }
 * }</pre>
 *
 * ordered by the authoritative {@code occurredAt}.
 */
public final class InteractionThreadController {

  private static final Logger log = LoggerFactory.getLogger(InteractionThreadController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private final ConversationStore conversationStore;
  private final AgentService agentService;
  private final io.justsearch.agent.BackgroundRunService backgroundRunService;

  public InteractionThreadController(ConversationStore conversationStore, AgentService agentService) {
    this.conversationStore = Objects.requireNonNull(conversationStore, "conversationStore");
    this.agentService = Objects.requireNonNull(agentService, "agentService");
    // Tempdoc 561 P-D2: the real background producer — fires an agent run detached from any watcher,
    // stamped background (safe-by-default), surfaced by presenceSince on the user's return.
    this.backgroundRunService = new io.justsearch.agent.BackgroundRunService(agentService);
  }

  /** Handles {@code GET /api/thread/{id}}. */
  public void handleGet(Context ctx) {
    String conversationId = ctx.pathParam("id");
    try {
      List<InteractionEvent> events = new ArrayList<>();
      // Answer plane: the chat turns (a projection of the conversation record).
      for (Map<String, Object> msg : conversationStore.loadHistory(conversationId)) {
        InteractionEvent turn = chatTurn(conversationId, msg);
        if (turn != null) {
          events.add(turn);
        }
      }
      // Action plane: every agent run of this conversation, projected read-time from AgentRunStore.
      events.addAll(agentService.threadEvents(conversationId));
      // Interleave by the authoritative timestamp (stable id tiebreak for determinism).
      events.sort(
          Comparator.comparing(InteractionEvent::occurredAt)
              .thenComparing(InteractionEvent::id));

      List<Map<String, Object>> wire = new ArrayList<>(events.size());
      for (InteractionEvent e : events) {
        wire.add(toWireRow(e));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("conversationId", conversationId);
      payload.put("events", wire);
      // Tempdoc 561 P-A / P-A2: the typed loop objects (state + Session>Turn>Iteration + budget) for
      // the conversation's agent runs — a projection of the durable AgentRunStore record.
      List<Map<String, Object>> lifecycles = new ArrayList<>();
      for (io.justsearch.agent.api.lifecycle.AgentLifecycle lc : agentService.lifecycles(conversationId)) {
        lifecycles.add(toLifecycleRow(lc));
      }
      payload.put("lifecycles", lifecycles);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize interaction thread for {}", conversationId, e);
      ctx.status(500).json(Map.of("error", "Failed to read interaction thread"));
    }
  }

  /**
   * Handles {@code GET /api/presence?since=<ISO-8601>} — tempdoc 561 P-D2. The render-on-return inbox
   * source: the typed lifecycles of BACKGROUND agent runs that completed strictly after {@code since}
   * ("what the agent did while you were away"), projected from the durable {@code AgentRunStore}
   * record. {@code since} omitted/blank → all background runs.
   */
  public void handlePresence(Context ctx) {
    Instant since = parseSinceParam(ctx.queryParam("since"));
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (io.justsearch.agent.api.lifecycle.AgentLifecycle lc : agentService.presenceSince(since)) {
        rows.add(toLifecycleRow(lc));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("runs", rows);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize presence inbox", e);
      ctx.status(500).json(Map.of("error", "Failed to read presence"));
    }
  }

  /**
   * Handles {@code POST /api/presence/run} — tempdoc 561 P-D2. The non-interactive trigger: fires an
   * agent run in the BACKGROUND (detached, safe-by-default — write/destructive tools are rejected
   * without a watcher), returning immediately. The completed run surfaces in the render-on-return
   * inbox via {@code GET /api/presence}. Body: {@code {"prompt": "...", "conversationId"?: "..."}} —
   * tempdoc 565 §26.D: when {@code conversationId} is present the run JOINS that conversation's
   * {@code /api/thread} history (rendering as a {@code background} segment); absent ⇒ a stand-alone
   * background run visible only in the cross-conversation inbox.
   */
  @SuppressWarnings("unchecked")
  public void handlePresenceRun(Context ctx) {
    try {
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : MAPPER.readValue(ctx.body(), Map.class);
      Object promptObj = body.get("prompt");
      String prompt = promptObj instanceof String s ? s : "";
      if (prompt.isBlank()) {
        ctx.status(400).json(Map.of("error", "prompt is required"));
        return;
      }
      // Tempdoc 565 §26.D — carry the conversationId so a background run launched from a conversation
      // JOINS that conversation's run history (/api/thread reads it; it renders as a `background`
      // segment, §26.A). Absent ⇒ a stand-alone background run, visible only in the cross-conversation
      // inbox (/api/presence). The 7-arg AgentRequest sets conversationId; the others default it null.
      Object convObj = body.get("conversationId");
      String conversationId = convObj instanceof String c && !c.isBlank() ? c : null;
      io.justsearch.agent.api.AgentRequest request =
          new io.justsearch.agent.api.AgentRequest(
              List.of(Map.of("role", "user", "content", prompt)),
              List.of(),
              5,
              List.of(),
              null,
              null,
              conversationId);
      // Schedule with zero delay so the HTTP thread returns immediately; the run executes detached.
      backgroundRunService.schedule(request, java.time.Duration.ZERO);
      ctx.json(Map.of("ok", true, "scheduled", true));
    } catch (Exception e) {
      log.error("Failed to schedule background run", e);
      ctx.status(500).json(Map.of("error", "Failed to schedule background run"));
    }
  }

  private static Instant parseSinceParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Project one {@code ConversationStore} message into a thread turn (user/assistant only). */
  private static InteractionEvent chatTurn(String conversationId, Map<String, Object> msg) {
    Object role = msg.get("role");
    InteractionEventKind kind;
    String originator;
    if ("user".equals(role)) {
      kind = InteractionEventKind.USER_MESSAGE;
      originator = "user";
    } else if ("assistant".equals(role)) {
      kind = InteractionEventKind.ASSISTANT_MESSAGE;
      originator = "agent";
    } else {
      return null; // system / context messages are not thread turns
    }
    Object id = msg.get("id");
    String eventId =
        id instanceof String s && !s.isBlank() ? s : conversationId + ":chat:" + msg.hashCode();
    Object content = msg.get("content");
    // Tempdoc 561 P-A (evidence on the record): surface the turn's persisted evidence — citations AND
    // the producer-owned calibration — on the thread event so the FE renders grounding + confidence
    // FROM the record, not an FE-side re-match or re-derivation.
    Object citations = msg.get("citations");
    Object calibration = msg.get("calibration");
    Object claimMatches = msg.get("claimMatches");
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (citations instanceof List<?> c && !c.isEmpty()) {
      attributes.put("citations", citations);
    }
    if (calibration instanceof Map<?, ?> cal && !cal.isEmpty()) {
      attributes.put("calibration", calibration);
    }
    // Tempdoc 561 P-A: the per-claim grounding, so the FE renders the inline marks FROM the record.
    if (claimMatches instanceof Map<?, ?> cm && !cm.isEmpty()) {
      attributes.put("claimMatches", claimMatches);
    }
    return new InteractionEvent(
        eventId,
        conversationId,
        parseTs(msg.get("ts")),
        kind,
        originator,
        content instanceof String c ? c : "",
        attributes);
  }

  /** The ONE place an {@link InteractionEvent} becomes a wire row. */
  static Map<String, Object> toWireRow(InteractionEvent e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", e.id());
    m.put("occurredAt", e.occurredAt().toString());
    m.put("kind", e.kind().name());
    m.put("originator", e.originator());
    m.put("content", e.content());
    m.put("attributes", e.attributes());
    return m;
  }

  /** Project a typed {@link io.justsearch.agent.api.lifecycle.AgentLifecycle} into a wire row. */
  static Map<String, Object> toLifecycleRow(io.justsearch.agent.api.lifecycle.AgentLifecycle lc) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sessionId", lc.sessionId());
    m.put("state", lc.state().name());
    m.put("actor", lc.actor());
    m.put("turns", lc.turns().size());
    m.put("iterations", lc.iterationCount());
    m.put("toolCalls", lc.toolCallsExecuted());
    m.put("actors", lc.actors());
    Map<String, Object> budget = new LinkedHashMap<>();
    budget.put("initial", lc.budget().initial());
    budget.put("consumed", lc.budget().consumed());
    budget.put("remaining", lc.budget().remaining());
    budget.put("overBudget", lc.budget().overBudget());
    m.put("budget", budget);
    return m;
  }

  private static Instant parseTs(Object raw) {
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
