/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Generic substrate-driven shape handler.
 *
 * <p>Per tempdoc 491 §9 Phase C: each new typed endpoint (e.g., {@code /api/chat/summarize},
 * {@code /api/chat/ask}, {@code /api/chat/summarize/batch}, {@code /api/chat/summarize/hierarchical})
 * binds to a fixed {@link ConversationShapeRef} and delegates to this controller. The
 * controller parses the body as a Map, reads the {@code X-JustSearch-Audience} header
 * (default {@link Audience#USER}), initializes SSE response headers, and invokes
 * {@link ConversationEngine#run} with a sink that writes each emitted
 * {@link io.justsearch.agent.api.conversation.SseEvent} to the wire.
 *
 * <p>The agent shape's controller ({@code AgentController}) handles the agent-specific
 * 503 short-circuit (when {@code AgentService.isAvailable()} is false) before delegating
 * to the engine. Other shapes — like summarize / RAG ask — route through this generic
 * controller; their LLM-unavailable case surfaces as an SSE {@code error} event from
 * the engine's substrate-driven path.
 */
public final class ChatController {

  private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUDIENCE_HEADER = "X-JustSearch-Audience";

  private final ConversationEngine engine;
  private final SseWriter sseWriter;
  private final Telemetry telemetry;
  private final ConversationStore conversationStore;
  // Tempdoc 610 Phase D — the one-shot summarizer for compaction (reuses the
  // existing OnlineAiService.summarize). Supplied (not held) so the controller
  // tolerates the runtime being unavailable / hot-swapped, mirroring the
  // assembly's onlineAiSupplier.
  private final Supplier<OnlineAiService> onlineAi;

  public ChatController(
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore,
      Supplier<OnlineAiService> onlineAi) {
    this.engine = engine;
    this.sseWriter = sseWriter;
    this.telemetry = telemetry;
    this.conversationStore = conversationStore;
    this.onlineAi = onlineAi;
  }

  public ChatController(
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore) {
    this(engine, sseWriter, telemetry, conversationStore, OnlineAiService::unavailable);
  }

  public ChatController(ConversationEngine engine, SseWriter sseWriter, Telemetry telemetry) {
    this(engine, sseWriter, telemetry, ConversationStore.noop());
  }

  /** Returns a handler that runs the supplied shape via the engine. */
  public io.javalin.http.Handler handler(ConversationShapeRef shapeId, String route) {
    return ctx -> dispatch(ctx, shapeId, route);
  }

  /**
   * Returns a handler that reads {@code shapeId} from the JSON body and dispatches dynamically.
   * Used by the unified chat surface ({@code POST /api/chat/dispatch}) so the FE picks the
   * shape per-message via affordance state.
   */
  public io.javalin.http.Handler dynamicHandler(String route) {
    return ctx -> {
      @SuppressWarnings("unchecked")
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : MAPPER.readValue(ctx.body(), Map.class);
      Object rawShapeId = body.get("shapeId");
      if (rawShapeId == null || rawShapeId.toString().isBlank()) {
        sseWriter.initSseHeaders(ctx, route);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Missing required field: shapeId");
        err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
        err.put("errorClass", ApiErrorCode.INVALID_REQUEST.errorClass().name());
        err.put("retryable", false);
        sseWriter.writeEvent(ctx, "error", err);
        return;
      }
      dispatch(ctx, new ConversationShapeRef(rawShapeId.toString().trim()), route);
    };
  }

  private void dispatch(Context ctx, ConversationShapeRef shapeId, String route) {
    sseWriter.initSseHeaders(ctx, route);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : MAPPER.readValue(ctx.body(), Map.class);
      Audience audience = readAudience(ctx);
      engine.run(
          shapeId,
          body,
          audience,
          sseEvent -> sseWriter.writeEvent(ctx, sseEvent.name(), sseEvent.payload()));
    } catch (ConversationEngine.AudienceDeniedException denied) {
      LOG.info("Audience denied for shape {}: {}", shapeId.value(), denied.getMessage());
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", denied.getMessage());
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      err.put("errorClass", ApiErrorCode.INVALID_REQUEST.errorClass().name());
      err.put("retryable", ApiErrorCode.INVALID_REQUEST.isRetryable());
      sseWriter.writeEvent(ctx, "error", err);
    } catch (ConversationEngine.ShapeNotFoundException notFound) {
      LOG.error("Shape not registered: {}", shapeId.value());
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", notFound.getMessage());
      err.put("errorCode", ApiErrorCode.NOT_FOUND.name());
      err.put("errorClass", ApiErrorCode.NOT_FOUND.errorClass().name());
      err.put("retryable", ApiErrorCode.NOT_FOUND.isRetryable());
      sseWriter.writeEvent(ctx, "error", err);
    } catch (Exception e) {
      LOG.error("Chat dispatch failed for shape {}", shapeId.value(), e);
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
      err.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      err.put("errorClass", ApiErrorCode.BAD_REQUEST.errorClass().name());
      err.put("retryable", ApiErrorCode.BAD_REQUEST.isRetryable());
      sseWriter.writeEvent(ctx, "error", err);
    }
    // Suppress unused-field warning until telemetry is wired into per-shape spans (Phase D).
    if (telemetry == null) {
      LOG.trace("telemetry sink not configured");
    }
  }

  public void handleListSessions(Context ctx) {
    String shapeId = ctx.queryParam("shapeId");
    String limitParam = ctx.queryParam("limit");
    int limit = Math.min(limitParam != null ? Integer.parseInt(limitParam) : 20, 100);
    List<ConversationStore.SessionSummary> sessions =
        conversationStore.listSessions(shapeId, limit);
    List<Map<String, Object>> result = sessions.stream().map(s -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sessionId", s.sessionId());
      m.put("shapeId", s.shapeId());
      m.put("createdAtMs", s.createdAtMs());
      m.put("lastActiveAtMs", s.lastActiveAtMs());
      m.put("messageCount", s.messageCount());
      m.put("firstUserMessage", s.firstUserMessage());
      // Slice 513 — branching: expose parent pointers when present.
      if (s.parentSessionId() != null) m.put("parentSessionId", s.parentSessionId());
      if (s.branchPointMessageId() != null) {
        m.put("branchPointMessageId", s.branchPointMessageId());
      }
      return m;
    }).toList();
    ctx.json(Map.of("sessions", result));
  }

  public void handleLoadHistory(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    List<Map<String, Object>> messages = conversationStore.loadHistory(sessionId);
    // Slice 513 + 515 FIX-7 — include parent pointers in the response so the
    // FE can mark inherited messages with a branch indicator. Direct
    // getSessionMeta lookup replaces the O(N) listSessions+filter scan.
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("messages", messages);
    Optional<ConversationStore.SessionSummary> meta =
        conversationStore.getSessionMeta(sessionId);
    meta.ifPresent(s -> {
      if (s.parentSessionId() != null) {
        response.put("parentSessionId", s.parentSessionId());
        // Slice 515 FIX-8 — surface the parent's first-message preview so the
        // FE branch indicator can name the parent without a second roundtrip.
        conversationStore.getSessionMeta(s.parentSessionId()).ifPresent(parent -> {
          if (parent.firstUserMessage() != null && !parent.firstUserMessage().isEmpty()) {
            response.put("parentFirstUserMessage", parent.firstUserMessage());
          }
        });
      }
      if (s.branchPointMessageId() != null) {
        response.put("branchPointMessageId", s.branchPointMessageId());
      }
      // Tempdoc 610 Phase C — surface the effective-context floor so the FE can
      // render the floor divider + out-of-context band on reload. Additive,
      // mirrors the parent-pointer fields above.
      if (s.contextFloor() != null) {
        response.put("contextFloor", s.contextFloor());
      }
      // Tempdoc 610 Phase D — surface the compaction summary so the divider can
      // offer "Show summary" (the trust requirement: the user sees what the
      // assistant now sees).
      if (s.contextFloorSummary() != null) {
        response.put("contextFloorSummary", s.contextFloorSummary());
      }
    });
    // Tempdoc 610 §E.3 — surface the per-message excluded set so the FE renders the toggle state +
    // out-of-context treatment on reload (additive, like the floor fields above).
    List<String> excluded = conversationStore.excludedMessageIds(sessionId);
    if (!excluded.isEmpty()) {
      response.put("excludedMessageIds", excluded);
    }
    // Tempdoc 610 §J.3 — surface the per-source excluded set so the FE renders the hidden-source
    // toggle state + dim treatment on reload (additive, like the per-message set above).
    List<String> excludedSources = conversationStore.excludedSourceIds(sessionId);
    if (!excludedSources.isEmpty()) {
      response.put("excludedSourceIds", excludedSources);
    }
    ctx.json(response);
  }

  /**
   * Tempdoc 610 Phase C — POST /api/chat/conversations/{sessionId}/context-floor
   * with body {@code {"floorMessageId": "..."}}. Sets the session's
   * effective-context floor (the next turn's prompt starts here; the transcript
   * still displays everything above it).
   */
  public void handleSetContextFloor(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String floorMessageId = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object f = body == null ? null : body.get("floorMessageId");
      if (f instanceof String s) floorMessageId = s;
    } catch (RuntimeException ignored) {
      // empty/invalid body → treated as a clear (floorMessageId stays null).
    }
    if (floorMessageId == null || floorMessageId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: floorMessageId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    conversationStore.setContextFloor(sessionId, floorMessageId);
    ctx.json(Map.of("ok", true, "contextFloor", floorMessageId));
  }

  /**
   * Tempdoc 610 Phase C — DELETE /api/chat/conversations/{sessionId}/context-floor.
   * Clears the floor (restores full context).
   */
  public void handleClearContextFloor(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    conversationStore.setContextFloor(sessionId, null);
    ctx.json(Map.of("ok", true));
  }

  /**
   * Tempdoc 610 Phase D — POST /api/chat/conversations/{sessionId}/compact with
   * body {@code {"floorMessageId": "..."}}. Summarizes the messages ABOVE the
   * floor (one-shot via OnlineAiService.summarize) and attaches the summary to
   * the floor, so the next turn sees "[summary] + recent turns" while the
   * transcript still displays everything. Returns the generated summary.
   */
  public void handleCompact(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String floorMessageId = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object f = body == null ? null : body.get("floorMessageId");
      if (f instanceof String s) floorMessageId = s;
    } catch (RuntimeException ignored) {
      // fall through to the 400 below
    }
    if (floorMessageId == null || floorMessageId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: floorMessageId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    // The prefix to summarize = the resolved history up to (not including) the
    // floor. If the floor is the first message there is nothing to compact.
    List<Map<String, Object>> full = conversationStore.loadHistory(sessionId);
    List<Map<String, Object>> prefix = new ArrayList<>();
    for (Map<String, Object> m : full) {
      if (floorMessageId.equals(m.get("id"))) break;
      prefix.add(m);
    }
    if (prefix.isEmpty()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Nothing to compact before the chosen message");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    StringBuilder transcript = new StringBuilder();
    for (Map<String, Object> m : prefix) {
      transcript
          .append(String.valueOf(m.getOrDefault("role", "user")))
          .append(": ")
          .append(String.valueOf(m.getOrDefault("content", "")))
          .append("\n");
    }
    String summary;
    try {
      summary = onlineAi.get().summarize(transcript.toString()).get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.warn("Compaction summarize failed for {}", sessionId, e);
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Summarization unavailable");
      err.put("errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name());
      ctx.status(503).json(err);
      return;
    }
    if (summary == null || summary.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Summarization produced no output");
      err.put("errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name());
      ctx.status(503).json(err);
      return;
    }
    conversationStore.compactContext(sessionId, floorMessageId, summary);
    ctx.json(Map.of("ok", true, "contextFloor", floorMessageId, "contextFloorSummary", summary));
  }

  /**
   * Tempdoc 610 §E.2 — POST /api/chat/conversations/{sessionId}/context-floor/summary with body
   * {@code {"summaryText": "..."}}. Replaces the stored compaction summary IN PLACE so the user can
   * correct what the summarizer got wrong (the §E.1 "no write barriers" answer). The floor is
   * unchanged and there is NO re-summarization — it reuses {@code compactContext} with the session's
   * current floor. 400 if the conversation has no compaction summary to edit.
   */
  public void handleEditContextFloorSummary(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String summaryText = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object s = body == null ? null : body.get("summaryText");
      if (s instanceof String str) summaryText = str;
    } catch (RuntimeException ignored) {
      // fall through to the 400 below
    }
    if (summaryText == null || summaryText.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: summaryText");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    Optional<ConversationStore.SessionSummary> meta = conversationStore.getSessionMeta(sessionId);
    String floorId = meta.map(ConversationStore.SessionSummary::contextFloor).orElse(null);
    String existing = meta.map(ConversationStore.SessionSummary::contextFloorSummary).orElse(null);
    if (floorId == null || existing == null) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "No compaction summary to edit for this conversation");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    conversationStore.compactContext(sessionId, floorId, summaryText);
    ctx.json(Map.of("ok", true, "contextFloor", floorId, "contextFloorSummary", summaryText));
  }

  /**
   * Tempdoc 610 §E.3 — POST /api/chat/conversations/{sessionId}/messages/{messageId}/exclude with
   * body {@code {"excluded": true|false}}. Toggles whether a single message is dropped from the
   * effective context. The transcript still displays it (loadHistory is unaffected); only
   * loadEffectiveContext filters it.
   */
  public void handleToggleMessageExcluded(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String messageId = ctx.pathParam("messageId");
    boolean excluded = true;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object e = body == null ? null : body.get("excluded");
      if (e instanceof Boolean b) excluded = b;
    } catch (RuntimeException ignored) {
      // default to excluded=true
    }
    conversationStore.excludeMessage(sessionId, messageId, excluded);
    ctx.json(Map.of("ok", true, "messageId", messageId, "excluded", excluded));
  }

  /**
   * Tempdoc 610 §J.3 — POST /api/chat/conversations/{sessionId}/sources/exclude with body
   * {@code {"sourceId": "...", "excluded": true|false}}. The sourceId (a unit-separator-joined
   * parentDocId+chunkIndex id with slashes/colons) rides in the body, not the path. Toggles whether
   * a retrieved source is hidden from this conversation's retrieval — the Worker drops the matching
   * chunk pre-search on subsequent turns; past transcript turns are unaffected.
   */
  public void handleToggleSourceExcluded(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String sourceId = "";
    boolean excluded = true;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      if (body != null) {
        Object s = body.get("sourceId");
        if (s instanceof String str) sourceId = str;
        Object e = body.get("excluded");
        if (e instanceof Boolean b) excluded = b;
      }
    } catch (RuntimeException ignored) {
      // default to excluded=true / empty sourceId (no-op below)
    }
    if (sourceId.isBlank()) {
      ctx.status(400).json(Map.of("ok", false, "error", "sourceId required"));
      return;
    }
    conversationStore.excludeSource(sessionId, sourceId, excluded);
    ctx.json(Map.of("ok", true, "sourceId", sourceId, "excluded", excluded));
  }

  public void handleDeleteConversation(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    try {
      conversationStore.deleteSession(sessionId);
      ctx.json(Map.of("ok", true));
    } catch (BranchesPreventDeletionException blocked) {
      // Slice 515 FIX-3 — 409 Conflict with the child session ids so the
      // FE can offer a cascade-delete UX (out of scope here).
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", blocked.getMessage());
      err.put("errorCode", "BRANCHES_PREVENT_DELETION");
      err.put("childSessionIds", blocked.childSessionIds());
      ctx.status(409).json(err);
    }
  }

  /**
   * Slice 513 — POST /api/chat/conversations/{sessionId}/branch?fromMsgId=...
   *
   * <p>Creates a new session that branches from {@code sessionId} at
   * {@code fromMsgId}. The new session carries no messages of its own; the
   * store's loadHistory resolves the parent prefix on each call. Returns the
   * new sessionId for the FE to navigate into.
   */
  public void handleBranchConversation(Context ctx) {
    String parentSessionId = ctx.pathParam("sessionId");
    String fromMsgId = ctx.queryParam("fromMsgId");
    if (fromMsgId == null || fromMsgId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required query parameter: fromMsgId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    String newSessionId = "uc-" + UUID.randomUUID();
    try {
      conversationStore.branchFrom(parentSessionId, fromMsgId, newSessionId);
    } catch (IllegalArgumentException invalid) {
      // Slice 515 FIX-2 — non-existent parent or branch-point id → 400 with
      // structured error. The store no longer silently falls back to a
      // full-parent prefix walk.
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", invalid.getMessage());
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", newSessionId);
    result.put("parentSessionId", parentSessionId);
    result.put("branchPointMessageId", fromMsgId);
    ctx.json(result);
  }

  private static Audience readAudience(Context ctx) {
    String raw = ctx.header(AUDIENCE_HEADER);
    if (raw == null || raw.isBlank()) {
      return Audience.USER;
    }
    try {
      return Audience.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      LOG.debug("Unrecognized {} header value {}; defaulting to USER", AUDIENCE_HEADER, raw);
      return Audience.USER;
    }
  }
}
