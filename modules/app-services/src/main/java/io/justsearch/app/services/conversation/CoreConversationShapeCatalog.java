/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.app.services.conversation.shapes.BatchSummarizeShape;
import io.justsearch.app.services.conversation.shapes.HierarchicalSummarizeShape;
import io.justsearch.app.services.conversation.shapes.NavigateChatShape;
import io.justsearch.app.services.conversation.shapes.RAGAskShape;
import io.justsearch.app.services.conversation.shapes.ExtractShape;
import io.justsearch.app.services.conversation.shapes.FreeChatShape;
import io.justsearch.app.services.conversation.shapes.SummarizeShape;
import java.util.List;
import java.util.Set;

/**
 * The CORE-tier {@link ConversationShapeCatalog} — known shapes contributed by JustSearch
 * itself (as opposed to plugin-contributed shapes per §10 Q4 plugin contribution path).
 *
 * <p>Per tempdoc 491 §9 Phase B: shipped one shape — {@link AgentRunShape} (the agent loop's
 * encapsulation). Phase C1 adds {@link SummarizeShape}; subsequent phases add
 * {@code BatchSummarizeShape}, {@code HierarchicalSummaryShape}, {@code RAGAskShape}, and
 * Phase D adds {@code NavigateChatShape} (URL emission, slice 487).
 */
public final class CoreConversationShapeCatalog {

  /** Stable namespace prefix matching the rest of the CORE catalogs. */
  public static final String NAMESPACE = "core";

  /**
   * Tempdoc 561 surface tier — the user-facing direct-LLM interaction shapes (the "modes" of the
   * one interaction window). This is the single authority the {@code interaction-surface} discipline
   * gate projects: at most ONE visible (USER-audience, RAIL/STAGE) surface may consume any of these
   * shapes, and that surface is the one interaction window ({@code core.unified-chat-surface}) — its
   * FE view ({@code jf-unified-chat-view}) presets the affordance per shape. DEEPLINK surfaces route
   * into it; they are exempt from the cardinality bound. The FE mirror is
   * {@code coreInteractionShapes.ts}; the gate enforces the two stay in sync (no second authority).
   */
  public static final Set<String> CORE_USER_INTERACTION_SHAPES =
      Set.of(
          "core.rag-ask",
          "core.free-chat",
          "core.extract",
          "core.agent-run",
          // Tempdoc 565 §15.C — the workflow run is a mode of the one window; its bespoke surface retired.
          "core.workflow-run");

  private CoreConversationShapeCatalog() {}

  /** Build the CORE shape catalog. */
  public static ConversationShapeCatalog catalog() {
    List<ConversationShape> shapes =
        List.of(
            AgentRunShape.definition(),
            SummarizeShape.definition(),
            BatchSummarizeShape.definition(),
            HierarchicalSummarizeShape.definition(),
            RAGAskShape.definition(),
            NavigateChatShape.definition(),
            FreeChatShape.definition(),
            ExtractShape.definition(),
            // Tempdoc 560 Phase 2 — the workflow runner shape (executable Workflow type).
            WorkflowRunShape.definition());
    return ConversationShapeCatalog.of(NAMESPACE, shapes);
  }
}
