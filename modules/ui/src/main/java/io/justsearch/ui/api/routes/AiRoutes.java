/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.app.services.conversation.shapes.BatchSummarizeShape;
import io.justsearch.app.services.conversation.shapes.HierarchicalSummarizeShape;
import io.justsearch.app.services.conversation.shapes.NavigateChatShape;
import io.justsearch.app.services.conversation.shapes.RAGAskShape;
import io.justsearch.app.services.conversation.shapes.SummarizeShape;
import io.justsearch.ui.api.AiInstallController;
import io.justsearch.ui.api.AiModelsController;
import io.justsearch.ui.api.AiPackController;
import io.justsearch.ui.api.AiRuntimeController;
import io.justsearch.ui.api.ChatController;
import io.justsearch.ui.api.PreviewController;

public final class AiRoutes {
  private AiRoutes() {}

  public static void register(
      Javalin app,
      PreviewController previewController,
      AiInstallController aiInstallController,
      AiPackController aiPackController,
      AiRuntimeController aiRuntimeController,
      AiModelsController aiModelsController,
      ChatController chatController) {
    // Tempdoc 491 Phase C1: /api/chat/summarize routes through ConversationEngine via the
    // SummarizeShape. The legacy /api/summarize + /api/summarize/stream endpoints are
    // removed (no wrapper intermediate per §9 Phase C — clients migrate atomically).
    app.post(
        "/api/chat/summarize",
        chatController.handler(SummarizeShape.ID, "/api/chat/summarize"));
    // Tempdoc 491 Phase C2.2: /api/chat/batch-summarize routes through the
    // BatchSummarizeShape; legacy /api/summarize/batch/stream is removed in the same commit.
    app.post(
        "/api/chat/batch-summarize",
        chatController.handler(BatchSummarizeShape.ID, "/api/chat/batch-summarize"));
    // Tempdoc 491 Phase C2.3: /api/chat/hierarchical-summarize routes through the
    // SHAPE_DRIVEN HierarchicalSummarizeShape (HierarchicalShapeRunner); legacy
    // /api/summarize/hierarchical/stream is removed in the same commit.
    app.post(
        "/api/chat/hierarchical-summarize",
        chatController.handler(
            HierarchicalSummarizeShape.ID, "/api/chat/hierarchical-summarize"));
    // Tempdoc 491 Phase C3: /api/chat/ask routes through the substrate-driven RAGAskShape;
    // legacy /api/ask/stream is removed in the same commit.
    app.post("/api/chat/ask", chatController.handler(RAGAskShape.ID, "/api/chat/ask"));
    // Slice 487 Phase 2.4: /api/chat/url-emit hosts the URL-emission consumer
    // (NavigateChatShape). The LLM emits Markdown URLs in its response;
    // URLExtractor parses them on onDone and dispatches each via BackendIntentRouter,
    // emitting navigate.url_extracted / navigate.url_dispatched / navigate.url_rejected
    // SSE events. The endpoint name uses "url-emit" rather than "navigate" because
    // the endpoint dispatches both Navigation and Invocation envelopes (the URL
    // grammar supports both shapes); "navigate" would describe only half the surface.
    app.post(
        "/api/chat/url-emit",
        chatController.handler(NavigateChatShape.ID, "/api/chat/url-emit"));
    // Slice 496 §3.B: FreeChat — plain persistent conversation.
    app.post(
        "/api/chat/free",
        chatController.handler(
            io.justsearch.app.services.conversation.shapes.FreeChatShape.ID,
            "/api/chat/free"));
    // Slice 496 §3.C: Extract — structured output with JSON schema constraint.
    app.post(
        "/api/chat/extract",
        chatController.handler(
            io.justsearch.app.services.conversation.shapes.ExtractShape.ID,
            "/api/chat/extract"));
    // Slice 497: Dynamic dispatch — the unified chat surface reads shapeId from the body
    // so the FE picks the ConversationShape per-message via affordance state.
    app.post("/api/chat/dispatch", chatController.dynamicHandler("/api/chat/dispatch"));
    app.get("/api/chat/conversations", chatController::handleListSessions);
    app.get("/api/chat/conversations/{sessionId}/history", chatController::handleLoadHistory);
    app.delete("/api/chat/conversations/{sessionId}", chatController::handleDeleteConversation);
    // Slice 513 — conversation branching.
    app.post("/api/chat/conversations/{sessionId}/branch", chatController::handleBranchConversation);
    // Tempdoc 610 Phase C — effective-context floor (rewind): set / clear.
    app.post(
        "/api/chat/conversations/{sessionId}/context-floor",
        chatController::handleSetContextFloor);
    app.delete(
        "/api/chat/conversations/{sessionId}/context-floor",
        chatController::handleClearContextFloor);
    // Tempdoc 610 Phase D — compaction (summarize-then-floor).
    app.post("/api/chat/conversations/{sessionId}/compact", chatController::handleCompact);
    // Tempdoc 610 §E.2 — edit the compaction summary in place (no re-summarize).
    app.post(
        "/api/chat/conversations/{sessionId}/context-floor/summary",
        chatController::handleEditContextFloorSummary);
    // Tempdoc 610 §E.3 — per-message exclude from the effective context (toggle).
    app.post(
        "/api/chat/conversations/{sessionId}/messages/{messageId}/exclude",
        chatController::handleToggleMessageExcluded);
    // Tempdoc 610 §J.3 — per-source exclude from retrieval (toggle); sourceId rides in the body.
    app.post(
        "/api/chat/conversations/{sessionId}/sources/exclude",
        chatController::handleToggleSourceExcluded);
    app.get("/api/preview", previewController::handlePreview);

    app.get("/api/ai/install/manifest", aiInstallController::handleGetManifest);
    app.get("/api/ai/install/status", aiInstallController::handleGetStatus);
    app.post("/api/ai/install/start", aiInstallController::handleStart);
    app.post("/api/ai/install/cancel", aiInstallController::handleCancel);
    app.post("/api/ai/install/repair", aiInstallController::handleRepair);

    app.get("/api/ai/packs/status", aiPackController::handleGetStatus);
    app.get("/api/ai/packs/installed", aiPackController::handleGetInstalled);
    app.post("/api/ai/packs/preflight", aiPackController::handlePreflight);
    app.post("/api/ai/packs/import", aiPackController::handleImport);

    app.get("/api/ai/runtime/status", aiRuntimeController::handleGetStatus);
    app.post("/api/ai/runtime/activate", aiRuntimeController::handleActivate);
    app.post("/api/ai/runtime/deactivate", aiRuntimeController::handleDeactivate);

    // Tempdoc 656 Task 4: registry-vs-disk reconciliation, read-only.
    app.get("/api/ai/models/status", aiModelsController::handleGetStatus);

    // Tempdoc 491 §9.B: /api/summarize/batch/stream removed in C2.2;
    // /api/summarize/hierarchical/stream removed in C2.3; /api/ask/stream removed in C3.
  }
}
