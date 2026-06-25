/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.agent.AgentBatchSummary;
import io.justsearch.app.api.agent.AgentHistoryResponse;
import io.justsearch.app.api.agent.AgentSessionSummary;
import io.justsearch.app.api.agent.AgentSessionsResponse;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 585 §B.5 (Hybrid C, the read-axis cut): the agent capability's READ-TIME session/history
 * endpoints, lifted out of {@link AgentController}. These 8 handlers (session snapshots/lists,
 * persisted events, the transcript bundle, operation history/detail, undo) are **pure reads** — each
 * calls exactly one method of the narrow {@link AgentRunQueries} surface that tempdoc 584 segregated
 * out of {@code AgentService}.
 *
 * <p>This controller depends on {@code Supplier<AgentRunQueries>} — **not** the full loop+control
 * {@code AgentService} — realizing on the controller side the consumer-narrowing 584 set up on the
 * service side (and could not apply to {@code InteractionThreadController}, §584 B.3). A new
 * read-projection endpoint attaches here, on the read interface, never on the orchestrator-coupled
 * core. The streaming resume endpoints and {@code cancelSession} are deliberately NOT here — they
 * need {@code isAvailable()} / the control surface / the SSE writer, so they stay on the run/control
 * core (§B.3 boundary wrinkle).
 *
 * <p>Behaviour-preserving: the handler bodies moved verbatim (the late-bound supplier resolves the
 * live agent the same way the parent did).
 */
final class AgentSessionController {
  private static final Logger LOG = LoggerFactory.getLogger(AgentSessionController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Supplier<AgentRunQueries> queriesSupplier;
  private final Telemetry telemetry;

  AgentSessionController(Supplier<AgentRunQueries> queriesSupplier, Telemetry telemetry) {
    this.queriesSupplier = queriesSupplier;
    this.telemetry = telemetry;
  }

  /** Resolves the live agent read surface. Always re-fetches so late-bound updates surface. */
  private AgentRunQueries queries() {
    return queriesSupplier.get();
  }

  /** GET /api/chat/sessions/last - Return last persisted agent session snapshot. */
  void handleSessionLast(Context ctx) {
    var snapshot = queries().lastSessionSnapshot();
    if (snapshot == null || snapshot.isEmpty()) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "No persisted agent session found", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(snapshot);
  }

  /** GET /api/chat/sessions/{id}/events - Replay persisted session events for debug. */
  void handleSessionEvents(Context ctx) {
    String sessionId = ctx.pathParam("id");
    List<Map<String, Object>> events = queries().sessionEvents(sessionId);
    if (events == null || events.isEmpty()) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "No events found for session " + sessionId, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(Map.of("sessionId", sessionId, "events", events));
  }

  /**
   * GET /api/chat/sessions - List recent persisted agent sessions (newest first).
   * Tempdoc 415 follow-up (C20).
   */
  void handleListSessions(Context ctx) {
    String limitParam = ctx.queryParam("limit");
    int limit = 20;
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    limit = Math.max(1, Math.min(limit, 100));
    // Tempdoc 564 Phase 3: project the agent layer's untyped Maps to the typed wire record at the
    // controller boundary (app-api can't depend back on app-agent-api). Jackson converts each Map to
    // the record by component name — identical JSON, now schema-generated + FE-validated.
    List<AgentSessionSummary> sessions =
        queries().listSessions(limit).stream()
            .map(m -> MAPPER.convertValue(m, AgentSessionSummary.class))
            .toList();
    ctx.json(new AgentSessionsResponse(sessions));
  }

  /**
   * GET /api/chat/sessions/{id} - Full persisted snapshot for a specific session.
   * Tempdoc 415 follow-up (C20).
   */
  void handleSessionDetail(Context ctx) {
    String sessionId = ctx.pathParam("id");
    var snapshot = queries().sessionSnapshot(sessionId);
    if (snapshot == null || snapshot.isEmpty()) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Session not found: " + sessionId,
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(snapshot);
  }

  /**
   * GET /api/chat/sessions/{id}/transcript - Bundled meta + events for download.
   * Tempdoc 415 follow-up (C33). {@code Content-Disposition: attachment} triggers a browser
   * download instead of inline render.
   */
  void handleSessionTranscript(Context ctx) {
    String sessionId = ctx.pathParam("id");
    var meta = queries().sessionSnapshot(sessionId);
    if (meta == null || meta.isEmpty()) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Session not found: " + sessionId,
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    var events = queries().sessionEvents(sessionId);
    ctx.header(
        "Content-Disposition", "attachment; filename=\"agent-session-" + sessionId + ".json\"");
    ctx.json(Map.of("meta", meta, "events", events != null ? events : List.of()));
  }

  /** POST /api/chat/agent/undo — Undo a previous tool execution. */
  void handleUndo(Context ctx) {
    try {
      var body = MAPPER.readTree(ctx.body());
      String toolName = body.path("toolName").asText();
      String executionId = body.path("executionId").asText();
      if (toolName.isEmpty() || executionId.isEmpty()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "toolName and executionId are required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      var result = queries().undoOperation(toolName, executionId);
      ctx.json(Map.of(
          "success", result.success(),
          "output", result.message(),
          "executionId", result.executionId().orElse("")));
    } catch (Exception e) {
      LOG.error("Undo failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.INTERNAL_ERROR, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** GET /api/chat/agent/history — List recent operation batches. */
  void handleHistory(Context ctx) {
    String limitParam = ctx.queryParam("limit");
    int limit = 20;
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    limit = Math.max(1, Math.min(limit, 100));
    // Tempdoc 564 Phase 3: project the untyped batch Maps to the typed wire record (see handleListSessions).
    List<AgentBatchSummary> batches =
        queries().operationHistory(limit).stream()
            .map(m -> MAPPER.convertValue(m, AgentBatchSummary.class))
            .toList();
    ctx.json(new AgentHistoryResponse(batches));
  }

  /** GET /api/chat/agent/history/{batchId} — Get full batch detail. */
  void handleHistoryDetail(Context ctx) {
    String batchId = ctx.pathParam("batchId");
    var detail = queries().operationDetail(batchId);
    if (detail == null) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "Batch not found: " + batchId, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(detail);
  }
}
