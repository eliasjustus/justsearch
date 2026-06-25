/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.services.conversation.AgUiEventTranslator;
import io.justsearch.app.services.conversation.AgentRunShape;
import io.justsearch.app.services.conversation.ConversationEngine;
import java.util.Set;
import io.justsearch.telemetry.Telemetry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST controller for the agent capability's LIVE-RUN + per-run-control surface: starting/attaching/
 * resuming an agent run, the unified approval/gate dispatch, and the per-run control inputs
 * (autonomy, budget, context, steering, cancel).
 *
 * <p>Tempdoc 585 §B.5 (Hybrid C structural remedy): this class was the repo's #3 class-size offender
 * (1131 LOC). Three cohesive concerns were lifted out to halt the accretion trajectory before it
 * became a second LocalApiServer:
 * <ul>
 *   <li>the {@code AgentEvent → SSE-wire} vocabulary + the hub-observer eviction seam →
 *       {@link AgentSseWriter} (the keystone — the biggest mass and an accretor in its own right);
 *   <li>the 8 pure-read session/history handlers → {@link AgentSessionController} (narrowed to the
 *       {@code AgentRunQueries} read interface tempdoc 584 segregated);
 *   <li>the tool inventory + FE virtual-operations sidecar → {@link AgentToolsController}.
 * </ul>
 * What remains here is the run/control core: it needs the full loop+control {@link AgentService} and
 * the {@link AgentSseWriter}. All endpoint contracts (paths, request/response shapes, SSE framing,
 * audience/authorization) are preserved exactly.
 *
 * <p>Tempdoc 374 alpha.25 U14-B + slice 1.1.a merge integration: the agent service is
 * resolved live via a {@link Supplier} so the controller reflects late-bound activation
 * (e.g., the "Reload Brain" path) instead of capturing a stale unavailable instance at
 * construction time.
 *
 * <p>Tempdoc 491 Phase B (2026-05-12): {@code POST /api/chat/agent} routes through the
 * {@link ConversationEngine} via the {@code AgentRunShape}. The agent loop's body
 * ({@code AgentLoopService}) is encapsulated unchanged behind a {@code ToolIteratingShapeRunner};
 * the SSE wire vocabulary is preserved exactly.
 */
final class AgentController {
  private static final Logger LOG = LoggerFactory.getLogger(AgentController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUDIENCE_HEADER = "X-JustSearch-Audience";

  /**
   * Tempdoc 508-followup §β1 — whitelist of shape ids dispatchable through {@code
   * /api/chat/agent}. The endpoint historically ran exactly the agent shape and ignored
   * {@code body.shapeId}; honoring the field would silently broaden what callers can invoke.
   * The whitelist is a forward-compat seam — a new shape that wants to share this endpoint
   * (vs. its own controller) registers here. The engine's catalog also validates, but the
   * controller gate produces a clearer 400 with the offending value.
   */
  private static final Set<String> ALLOWED_SHAPE_IDS =
      Set.of(
          AgentRunShape.ID.value(),
          // Tempdoc 560 Phase 2 — the workflow shape shares this endpoint (engine.run dispatches
          // by shapeId); its tool-call/approval wire vocabulary matches the agent shape's.
          io.justsearch.app.services.conversation.WorkflowRunShape.ID.value());

  private final Supplier<AgentService> agentServiceSupplier;
  private final ConversationEngine engine;
  private final AgentSseWriter sseWriter;
  private final Telemetry telemetry;

  /**
   * Tempdoc 604 — the agent SSE streams (live run / attach / resume / fork) are event-only and emit
   * NOTHING while a run is parked at an approval gate, so the FE cannot tell a parked-alive run from
   * a transport-hung one. This scheduler beats an out-of-band {@code heartbeat} frame on every open
   * agent stream at the generated cadence, the positive-liveness signal the FE watchdog resets on.
   */
  private final ScheduledExecutorService heartbeatScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "agent-stream-heartbeat");
            t.setDaemon(true);
            return t;
          });
  // Tempdoc 560 Phase 2: pending-gate registry for workflow runs (nullable; set post-construction
  // once the WorkflowShapeRunner is wired). The workflow approve/reject endpoints complete its
  // futures out-of-band, unblocking the runner thread.
  private io.justsearch.app.services.conversation.WorkflowGateRegistry workflowGateRegistry;

  AgentController(
      Supplier<AgentService> agentServiceSupplier,
      ConversationEngine engine,
      AgentSseWriter sseWriter,
      Telemetry telemetry) {
    this.agentServiceSupplier = agentServiceSupplier;
    this.engine = engine;
    this.sseWriter = sseWriter;
    this.telemetry = telemetry;
  }

  /** Resolves the live agent service. Always re-fetches so late-bound updates surface. */
  private AgentService agentService() {
    return agentServiceSupplier.get();
  }

  /** A streaming body that blocks the handler thread until the run terminates; may throw. */
  @FunctionalInterface
  private interface StreamBody {
    void run() throws Exception;
  }

  /**
   * Tempdoc 604 — run a blocking agent stream with an out-of-band liveness heartbeat. Schedules a
   * {@code heartbeat} frame every {@link StreamLivenessWindows#STREAM_HEARTBEAT_INTERVAL_SECONDS} for
   * the life of the stream, then cancels it when the body returns/throws. The heartbeat is written
   * via {@link AgentSseWriter#writeEvent} (per-context synchronized, so it interleaves safely with
   * the hub-observer event writes), carries no trace span (so it never enters the replay buffer nor
   * advances {@code Last-Event-ID}), and is not an {@code AgentEvent} (so it does not touch the closed
   * event-vocabulary contract). The FE ignores it as an unmapped event and resets its liveness
   * watchdog on it.
   */
  private void withHeartbeat(Context ctx, StreamBody body) throws Exception {
    ScheduledFuture<?> heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            () -> {
              Map<String, Object> beat = new LinkedHashMap<>();
              beat.put("ts", System.currentTimeMillis());
              sseWriter.writeEvent(ctx, "heartbeat", beat);
            },
            StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS,
            StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
    try {
      body.run();
    } finally {
      heartbeat.cancel(false);
    }
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  void shutdown() {
    heartbeatScheduler.shutdownNow();
  }

  /** Test-only (tempdoc 638 PE): whether {@link #shutdown()} has stopped the heartbeat scheduler. */
  boolean isHeartbeatSchedulerShutdown() {
    return heartbeatScheduler.isShutdown();
  }

  /**
   * POST /api/chat/agent — Start an agent session and stream events.
   *
   * <p>Tempdoc 491 Phase B: this handler is now a thin shape-binding around {@link
   * ConversationEngine#run}. It performs the same pre-flight availability check (HTTP 503
   * with structured JSON error preserved for FE compatibility), parses the body as an
   * opaque Map, reads the audience header, and delegates to the engine via the
   * {@code AgentRunShape}. The agent-loop's existing event vocabulary is preserved exactly
   * by {@code ToolIteratingShapeRunner.translate}.
   */
  void handleRunStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Agent capability is not available", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    sseWriter.initSseHeaders(ctx, "/api/chat/agent");

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
      Audience audience = readAudience(ctx);
      ConversationShapeRef shapeRef = resolveShapeRef(body);
      withHeartbeat(
          ctx,
          () ->
              engine.run(
                  shapeRef,
                  body,
                  audience,
                  // Tempdoc 577 §2.14 Root I (#13) — the initiating run's hub observer EVICTS on
                  // disconnect (throws so RunEventHub.deliver drops it), the precondition the
                  // zero-observer park needs.
                  sseEvent -> sseWriter.writeOrEvict(ctx, sseEvent.name(), sseEvent.payload())));
    } catch (UnknownShapeException unknown) {
      LOG.warn("Rejecting agent run for unknown shapeId: {}", unknown.getMessage());
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", unknown.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("errorClass", ApiErrorCode.BAD_REQUEST.errorClass().name());
      errPayload.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", errPayload);
    } catch (Exception e) {
      LOG.error("Failed to start agent session", e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("errorClass", ApiErrorCode.BAD_REQUEST.errorClass().name());
      errPayload.put("retryable", ApiErrorCode.BAD_REQUEST.isRetryable());
      sseWriter.writeEvent(ctx, "error", errPayload);
    }
  }

  /**
   * Tempdoc 508-followup §β1 — resolve the shape ref from {@code body.shapeId}. Missing /
   * blank / non-string values default to {@link AgentRunShape#ID} for back-compat with
   * pre-§β1 clients (and with the historical hardcoded behavior). Any other value must
   * appear in {@link #ALLOWED_SHAPE_IDS} or this throws {@link UnknownShapeException},
   * which the caller translates into a structured 400 error SSE event.
   *
   * <p>The plan flagged this fix as "host.ai shape-dispatch honesty": prior to this change
   * the controller silently ignored {@code body.shapeId} and always ran {@code AgentRunShape},
   * so any plugin passing a different id was lied to about which shape ran.
   */
  static ConversationShapeRef resolveShapeRef(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("shapeId");
    if (raw == null) {
      return AgentRunShape.ID;
    }
    String value = raw.toString().trim();
    if (value.isEmpty()) {
      return AgentRunShape.ID;
    }
    if (!ALLOWED_SHAPE_IDS.contains(value)) {
      throw new UnknownShapeException(
          "unknown shapeId: '" + value + "'. Allowed: " + ALLOWED_SHAPE_IDS);
    }
    return new ConversationShapeRef(value);
  }

  /** Internal signal: client requested a shapeId outside the whitelist. */
  static final class UnknownShapeException extends RuntimeException {
    UnknownShapeException(String message) {
      super(message);
    }
  }

  /**
   * Reads the {@code X-JustSearch-Audience} request header per tempdoc 491 §5.4. Default
   * {@link Audience#USER} when missing or unparseable. The header value is uppercased before
   * matching the enum; unknown values fall back to USER (forward-compat — a future audience
   * value should not break old clients).
   */
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

  /**
   * POST /api/chat/approve — Tempdoc 565 §15.C enforcement: the ONE approval endpoint. "A run is a
   * run" all the way down — the shared {@code <jf-authorization-host>} ceremony routes here for BOTH
   * an agent tool-call gate and a workflow GateStep/ToolStep gate. The dispatch tries the agent gate
   * (keyed by {@code sessionId}+{@code callId}) first, then the session-agnostic
   * {@link io.justsearch.app.services.conversation.WorkflowGateRegistry} (keyed by {@code callId}),
   * else 404 — so the FE no longer branches the URL by run shape.
   */
  void handleApprove(Context ctx) {
    dispatchGate(ctx, true);
  }

  /** POST /api/chat/reject — reject sibling of {@link #handleApprove} (same unified dispatch). */
  void handleReject(Context ctx) {
    dispatchGate(ctx, false);
  }

  /**
   * The unified gate-dispatch DECISION (Context-free, unit-testable): try the agent gate
   * ({@code sessionId}+{@code callId}) first, then the session-agnostic workflow gate registry
   * ({@code callId}). Returns whether SOME gate with that callId was found and completed — the HTTP
   * mapping (200 vs 404) lives in {@link #dispatchGate}. This is the single backend authority the two
   * forked endpoints collapsed into (565 §15.C).
   */
  boolean resolveApprovalGate(String sessionId, String callId, boolean approved, String reason) {
    // 1) Agent gate (within the session). Returns whether a gate with that callId actually existed.
    boolean handled =
        approved
            ? agentService().tryApproveToolCall(sessionId, callId)
            : agentService().tryRejectToolCall(sessionId, callId, reason);
    // 2) Else the session-agnostic workflow gate registry (keyed by callId).
    if (!handled && workflowGateRegistry != null) {
      handled = workflowGateRegistry.complete(callId, approved);
    }
    return handled;
  }

  private void dispatchGate(Context ctx, boolean approved) {
    try {
      var body = MAPPER.readTree(ctx.body());
      String callId = body.path("callId").asText();
      String sessionId = body.path("sessionId").asText(null);
      String reason = body.path("reason").asText("User rejected");
      boolean handled = resolveApprovalGate(sessionId, callId, approved, reason);
      if (!handled) {
        ctx.status(404)
            .json(
                ApiErrorHandler.toResponse(
                    ApiErrorCode.NOT_FOUND,
                    "No pending approval gate for callId " + callId,
                    telemetry,
                    ApiErrorHandler.routeOf(ctx)));
        return;
      }
      ctx.json(Map.of("status", approved ? "approved" : "rejected"));
    } catch (Exception e) {
      LOG.error("Failed to resolve approval gate", e);
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 560 Phase 2 — late-bind the workflow pending-gate registry (nullable). Consumed by the
   * unified {@link #dispatchGate} as the second gate authority (after the agent gate).
   */
  void setWorkflowGateRegistry(
      io.justsearch.app.services.conversation.WorkflowGateRegistry registry) {
    this.workflowGateRegistry = registry;
  }

  /**
   * POST /api/chat/agent/autonomy — Tempdoc 561 P-D: update the live autonomy dial for a running
   * session. Body: {@code {"sessionId": "...", "level": "watch|assist|auto"}}. The backend issuance
   * policy reads the session's dial on the next gated tool call; the FE no longer re-derives.
   */
  void handleAutonomy(Context ctx) {
    handleControl(ctx, "Failed to set session autonomy", body -> {
      agentService().setSessionAutonomy(body.path("sessionId").asText(), body.path("level").asText());
      ctx.json(Map.of("status", "ok"));
    });
  }

  /**
   * Tempdoc 585 §D Phase 2 (A2) — the shared per-run control envelope: parse the JSON body, run the
   * endpoint's {@code action}, and map a thrown exception to the structured 400 every control handler
   * used to repeat. The action writes its own success / pre-validation-400 / 404 response (the part
   * that genuinely differs per endpoint). DRYs handleAutonomy/RaiseBudget/Steering/Budget-/Context-
   * Decision on the now-small post-585 controller.
   */
  private void handleControl(Context ctx, String failureLog, ThrowingConsumer<JsonNode> action) {
    try {
      action.accept(MAPPER.readTree(ctx.body()));
    } catch (Exception e) {
      LOG.error(failureLog, e);
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 585 §D Phase 2 (A2) — the shared gate→response map the 4 boolean-returning control
   * handlers repeat: HTTP 200 {@code {status: okStatus}} when the delegate accepted, else 404
   * {@code {error: notFound}} (the run is not live / not parked at the gate).
   */
  private static void okOr404(Context ctx, boolean ok, String okStatus, String notFound) {
    if (ok) {
      ctx.json(Map.of("status", okStatus));
    } else {
      ctx.status(404).json(Map.of("error", notFound));
    }
  }

  /** A body-consuming control action that may throw (mapped to a 400 by {@link #handleControl}). */
  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T t) throws Exception;
  }

  /**
   * POST /api/chat/agent/budget — Tempdoc 577 Ext III (the over-budget remedy): grant a running
   * session additional tokens. Body: {@code {"sessionId": "...", "addTokens": N}}. The loop's
   * between-step budget check and the next {@code budget_update} reflect the raise. Mirrors
   * {@link #handleAutonomy}/{@link #handleSteeringDirective} (per-run control inputs). 404 if the
   * session is unknown/finished.
   */
  void handleRaiseBudget(Context ctx) {
    handleControl(ctx, "Failed to raise session budget", body -> {
      String sessionId = body.path("sessionId").asText();
      int addTokens = body.path("addTokens").asInt(0);
      if (sessionId.isBlank() || addTokens <= 0) {
        ctx.status(400).json(Map.of("error", "sessionId and a positive addTokens are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().raiseSessionBudget(sessionId, addTokens),
          "ok",
          "Session not found or not running: " + sessionId);
    });
  }

  /**
   * POST /api/chat/agent/budget-decision — Tempdoc 577 §2.12 Move 2: resolve a HELD budget gate.
   * Body: {@code {"sessionId": "...", "decision": "finalize"|"stop"}}. CONTINUE arrives via the
   * raise endpoint (raising the budget IS the continue decision). Mirrors approve/reject: 404 when
   * the run is not parked at a gate.
   */
  void handleBudgetDecision(Context ctx) {
    handleControl(ctx, "Failed to resolve budget gate", body -> {
      String sessionId = body.path("sessionId").asText();
      String decision = body.path("decision").asText();
      if (sessionId.isBlank() || decision.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and decision are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().resolveBudgetGate(sessionId, decision),
          "ok",
          "No held budget gate for session: " + sessionId);
    });
  }

  /**
   * POST /api/chat/agent/context-decision — Tempdoc 577 §2.14 Root II (#14): resolve a HELD
   * context-pressure gate. Body: {@code {"sessionId": "...", "decision": "continue"|"summarize"|"stop"}}.
   * Mirrors the budget-decision endpoint: 404 when the run is not parked at a context gate.
   */
  void handleContextDecision(Context ctx) {
    handleControl(ctx, "Failed to resolve context gate", body -> {
      String sessionId = body.path("sessionId").asText();
      String decision = body.path("decision").asText();
      if (sessionId.isBlank() || decision.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and decision are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().resolveContextGate(sessionId, decision),
          "ok",
          "No held context gate for session: " + sessionId);
    });
  }

  /**
   * GET /api/chat/agent/{sessionId}/attach — Tempdoc 577 §2.14 Root I (#13): ATTACH a new SSE
   * observer to a LIVE run. The run is a backend-owned entity (its loop survives the initiating
   * socket closing), so a reattaching tab — or a second observer — subscribes to the run's event
   * hub: it replays the run's buffered history, then streams ongoing events in the SAME wire
   * vocabulary the initiating run stream uses (via {@link AgentSseWriter#writeAgentEvent}). Blocks
   * until the run terminates. When the session is not live (terminal/evicted), emits a
   * {@code not_live} signal so the FE falls back to the persisted thread record instead.
   */
  void handleAttachStream(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    sseWriter.initSseHeaders(ctx, "/api/chat/agent/attach");
    try {
      // Tempdoc 585 §D Phase 2 (B1) — precise reconnect: a reattaching client echoes the WHATWG
      // Last-Event-ID it last saw (the monotonic trace span). The hub then replays only newer
      // buffered events instead of the whole window — no duplicate replay. Absent/unparseable ⇒
      // Long.MIN_VALUE ⇒ replay the full window (a fresh attach, the prior behaviour).
      long fromSeq = parseLastEventId(ctx.header("Last-Event-ID"));
      withHeartbeat(
          ctx,
          () -> {
            boolean attached =
                agentService()
                    .attachToRun(sessionId, fromSeq, event -> sseWriter.writeAgentEvent(ctx, event));
            if (!attached) {
              // Not an in-flight run — signal the FE to read the persisted record
              // (events.ndjson / thread).
              Map<String, Object> notLive = new LinkedHashMap<>();
              notLive.put("sessionId", sessionId);
              notLive.put("reason", "not-live");
              sseWriter.writeEvent(ctx, "attach_not_live", notLive);
            }
          });
    } catch (Exception e) {
      LOG.error("Attach stream failed for session {}", sessionId, e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
      errPayload.put("errorCode", ApiErrorCode.BAD_REQUEST.name());
      errPayload.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", errPayload);
    }
  }

  /**
   * POST /api/chat/agent/{sessionId}/ag-ui — Tempdoc 585 §D Phase 3 (C3): attach an AG-UI-protocol
   * observer to a LIVE run. Identical attach mechanics to {@link #handleAttachStream} (the run's hub,
   * the Last-Event-ID cursor, the disconnect-eviction), but each event is projected through the
   * sibling {@link AgUiEventTranslator} instead of the native one — so the SAME run streams as AG-UI
   * events to an AG-UI client (CopilotKit, etc.). Stays under {@code /api/chat/*}: the removed
   * {@code /api/agent/*} namespace is NOT resurrected (Hard Invariant #3).
   */
  void handleAgUiAttachStream(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    sseWriter.initSseHeaders(ctx, "/api/chat/agent/ag-ui");
    try {
      long fromSeq = parseLastEventId(ctx.header("Last-Event-ID"));
      boolean attached =
          agentService()
              .attachToRun(
                  sessionId,
                  fromSeq,
                  event -> {
                    var agui = AgUiEventTranslator.translate(event);
                    sseWriter.writeOrEvict(ctx, agui.name(), agui.payload());
                  });
      if (!attached) {
        // AG-UI signals a non-live run with a RUN_ERROR (its terminal-error event).
        Map<String, Object> notLive = new LinkedHashMap<>();
        notLive.put("type", "RUN_ERROR");
        notLive.put("message", "not a live run");
        sseWriter.writeEvent(ctx, "RUN_ERROR", notLive);
      }
    } catch (Exception e) {
      LOG.error("AG-UI attach stream failed for session {}", sessionId, e);
      Map<String, Object> errPayload = new LinkedHashMap<>();
      errPayload.put("type", "RUN_ERROR");
      errPayload.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
      sseWriter.writeEvent(ctx, "RUN_ERROR", errPayload);
    }
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — parse a {@code Last-Event-ID} header into a replay cursor. Accepts
   * the bare numeric id we emit ({@code "42"}) and tolerates a {@code "span-000042"} form; anything
   * absent or unparseable yields {@link Long#MIN_VALUE} (replay the whole buffered window).
   */
  private static long parseLastEventId(String header) {
    if (header == null || header.isBlank()) {
      return Long.MIN_VALUE;
    }
    String token = header.trim();
    int dash = token.lastIndexOf('-');
    if (dash >= 0 && dash + 1 < token.length()) {
      token = token.substring(dash + 1);
    }
    try {
      return Long.parseLong(token);
    } catch (NumberFormatException notNumeric) {
      return Long.MIN_VALUE;
    }
  }

  /**
   * POST /api/chat/agent/steer — Tempdoc 565 §30 (the DIRECTION authority's {@code interject}):
   * queue a free-form human steering directive for a running session. Body:
   * {@code {"sessionId": "...", "text": "..."}}. The loop drains it at the next step boundary and
   * folds it into the next LLM call; a {@code directive_acknowledged} SSE event echoes back. Mirrors
   * {@link #handleAutonomy} (the {@code set-posture} sibling). 404 if the session is unknown/finished.
   */
  void handleSteeringDirective(Context ctx) {
    handleControl(ctx, "Failed to inject steering directive", body -> {
      String sessionId = body.path("sessionId").asText();
      String text = body.path("text").asText();
      if (sessionId.isBlank() || text.isBlank()) {
        ctx.status(400).json(Map.of("error", "sessionId and text are required"));
        return;
      }
      okOr404(
          ctx,
          agentService().injectSteeringDirective(sessionId, text),
          "injected",
          "session not found or finished");
    });
  }

  /**
   * POST /api/chat/sessions/resume-last - Resume last persisted agent session. The
   * sibling read-only session/history endpoints live in {@link AgentSessionController} (tempdoc
   * 585 §B.5); the resume streams stay here because they call {@code isAvailable()} and stream
   * agent events through the {@link AgentSseWriter}.
   */
  void handleResumeLastStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Agent capability is not available", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/resume-last");
    try {
      withHeartbeat(
          ctx, () -> agentService().resumeLastSession(event -> sseWriter.writeAgentEvent(ctx, event)));
    } catch (Exception e) {
      LOG.error("Failed to resume last agent session", e);
      Map<String, Object> resumeErr = new LinkedHashMap<>();
      resumeErr.put("error", e.getMessage());
      resumeErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      resumeErr.put("errorClass", ApiErrorCode.RESUME_FAILED.errorClass().name());
      resumeErr.put("retryable", ApiErrorCode.RESUME_FAILED.isRetryable());
      sseWriter.writeEvent(ctx, "error", resumeErr);
    }
  }

  /**
   * POST /api/chat/sessions/{id}/resume - Resume a specific persisted session.
   * Tempdoc 415 follow-up (C20). Mirrors {@link #handleResumeLastStream(Context)} but
   * addressed by id.
   */
  void handleResumeSessionStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.SERVICE_UNAVAILABLE,
                  "Agent capability is not available",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String sessionId = ctx.pathParam("id");
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/{id}/resume");
    try {
      withHeartbeat(
          ctx,
          () -> agentService().resumeSession(sessionId, event -> sseWriter.writeAgentEvent(ctx, event)));
    } catch (Exception e) {
      LOG.error("Failed to resume agent session {}", sessionId, e);
      Map<String, Object> resumeErr = new LinkedHashMap<>();
      resumeErr.put("error", e.getMessage());
      resumeErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      resumeErr.put("errorClass", ApiErrorCode.RESUME_FAILED.errorClass().name());
      resumeErr.put("retryable", ApiErrorCode.RESUME_FAILED.isRetryable());
      sseWriter.writeEvent(ctx, "error", resumeErr);
    }
  }

  /**
   * POST /api/chat/sessions/{id}/fork — Tempdoc 585 §D Phase 3 (C2): time-travel fork. Branch a NEW
   * run from a finished one, rewinding to its last user turn and optionally editing that question
   * (body {@code {"editedMessage": "..."}}; blank/absent re-runs the original). Mirrors resume's
   * streaming shape but has no resume-state gate.
   */
  void handleForkSessionStream(Context ctx) {
    if (!agentService().isAvailable()) {
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.SERVICE_UNAVAILABLE,
                  "Agent capability is not available",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String sessionId = ctx.pathParam("id");
    sseWriter.initSseHeaders(ctx, "/api/chat/sessions/{id}/fork");
    String editedMessage = "";
    try {
      if (ctx.body() != null && !ctx.body().isBlank()) {
        editedMessage = MAPPER.readTree(ctx.body()).path("editedMessage").asText("");
      }
    } catch (Exception parseIgnored) {
      // Malformed/empty body ⇒ a plain re-roll of the original last question.
    }
    try {
      String forkMessage = editedMessage;
      withHeartbeat(
          ctx,
          () ->
              agentService()
                  .forkSession(sessionId, forkMessage, event -> sseWriter.writeAgentEvent(ctx, event)));
    } catch (Exception e) {
      LOG.error("Failed to fork agent session {}", sessionId, e);
      Map<String, Object> forkErr = new LinkedHashMap<>();
      forkErr.put("error", e.getMessage());
      forkErr.put("errorCode", ApiErrorCode.RESUME_FAILED.name());
      forkErr.put("retryable", false);
      sseWriter.writeEvent(ctx, "error", forkErr);
    }
  }

  /** DELETE /api/chat/sessions/{id} - Cancel an agent session. */
  void handleCancelSession(Context ctx) {
    String sessionId = ctx.pathParam("id");
    agentService().cancelSession(sessionId);
    ctx.json(Map.of("status", "cancelled"));
  }
}
