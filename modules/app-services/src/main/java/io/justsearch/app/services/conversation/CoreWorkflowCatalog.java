/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import io.justsearch.agent.api.registry.WorkflowRef;
import java.util.List;

/**
 * The CORE-tier {@link WorkflowCatalog} (tempdoc 560 Phase 2) — workflows JustSearch itself ships.
 *
 * <p>Holds one demonstration workflow, {@code core.demo-compose}, that exercises all three node
 * kinds in the canonical <b>think → confirm → act</b> shape, proving the substrates compose:
 *
 * <ol>
 *   <li><b>LlmStep</b> → the {@code core.free-chat} conversation shape (the ConversationEngine),
 *   <li><b>GateStep</b> → an inline consent gate (the trust gate),
 *   <li><b>ToolStep</b> → the MCP-host {@code add} tool (an Operation routed through the intent
 *       layer) — the same tool the Phase 1 MCP work surfaced, now composed by a workflow.
 * </ol>
 */
public final class CoreWorkflowCatalog {

  /** Stable namespace prefix matching the rest of the CORE catalogs. */
  public static final String NAMESPACE = "core";

  /** The demonstration workflow's stable id. */
  public static final WorkflowRef DEMO_ID = new WorkflowRef("core.demo-compose");

  /**
   * Tempdoc 565 §26.B/§26.C — a model-only, two-LlmStep workflow ({@code think → draft}). Every node
   * delegates to {@code core.free-chat} (the chat model), so — unlike {@code demo-compose}, which
   * needs the {@code vendor.mcphost.*} pack to validate — it runs in any stack with the chat model
   * loaded. It gives the §26.C picker a second real entry AND a runnable target whose &gt;1 nodes prove
   * the §26.A/§26.B run-structure render (labelled node segments + spine boundaries).
   */
  public static final WorkflowRef RESEARCH_BRIEF_ID = new WorkflowRef("core.research-brief");

  /** The MCP-host reference server's {@code add} tool, projected as an Operation (Phase 1). */
  private static final OperationRef MCP_ADD = new OperationRef("vendor.mcphost.reference-add");

  /**
   * The MCP-host reference server's {@code get-image} tool — returns a non-text (image) content
   * block (Phase 1). Composing it here proves a workflow surfaces rich MCP content, not just text.
   */
  private static final OperationRef MCP_GET_IMAGE =
      new OperationRef("vendor.mcphost.reference-get-image");

  /** The conversation shape the demo's LlmStep delegates to. */
  private static final ConversationShapeRef FREE_CHAT = new ConversationShapeRef("core.free-chat");

  private CoreWorkflowCatalog() {}

  /** Build the CORE workflow catalog. */
  public static WorkflowCatalog catalog() {
    return WorkflowCatalog.of(NAMESPACE, List.of(demoCompose(), researchBrief()));
  }

  /**
   * Tempdoc 565 §26 — the {@code think → draft} model-only workflow: two LlmSteps over the chat model,
   * no tool/gate, so it validates + runs anywhere the chat shape is loaded. Its two nodes render as two
   * labelled run segments (§26.B), the visible proof of the run-structure authority.
   */
  public static Workflow researchBrief() {
    return new Workflow(
        RESEARCH_BRIEF_ID,
        Presentation.of(
            new I18nKey("registry-workflow.research-brief.label"),
            new I18nKey("registry-workflow.research-brief.description")),
        Provenance.core("1.0"),
        Audience.USER,
        List.of(
            new WorkflowNode.LlmStep(
                "think",
                FREE_CHAT,
                "In one short sentence, name a single topic worth a brief research note."),
            new WorkflowNode.LlmStep(
                "draft",
                FREE_CHAT,
                "Write a two-sentence research brief on that topic.")),
        List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
  }

  /** The {@code think → confirm → act} demonstration workflow. */
  public static Workflow demoCompose() {
    return new Workflow(
        DEMO_ID,
        Presentation.of(
            new I18nKey("registry-workflow.demo-compose.label"),
            new I18nKey("registry-workflow.demo-compose.description")),
        Provenance.core("1.0"),
        Audience.USER,
        List.of(
            new WorkflowNode.LlmStep(
                "think",
                FREE_CHAT,
                "In one short sentence, say that you will now add 2 and 40."),
            new WorkflowNode.GateStep("confirm", ConfirmStrategy.Inline.INSTANCE),
            new WorkflowNode.ToolStep("act", MCP_ADD, "{\"a\":2,\"b\":40}"),
            // Phase 1 composition: an MCP tool returning a non-text (image) content block, so the
            // workflow surface renders rich content end-to-end (think → confirm → act → show).
            new WorkflowNode.ToolStep("show-image", MCP_GET_IMAGE, "{}")),
        List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
  }
}
