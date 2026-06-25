/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import java.util.List;

/**
 * The {@link ConversationShape} manifest entry for the workflow runner (tempdoc 560 Phase 2) —
 * an {@link ExecutionMode#SHAPE_DRIVEN} × {@link IterationMode#WITHIN_TURN_ITERATION} ×
 * {@link PersistenceMode#EPHEMERAL} cell whose body is {@link WorkflowShapeRunner}.
 *
 * <p>A workflow run sequences existing substrates (LLM / tool / gate) — it composes, it does not
 * fork — so the shape declares no per-SPI ids; the runner drives each node by delegating to the
 * engine (LlmStep), the {@code GatedOperationExecutor} (ToolStep), and the consent gate (GateStep).
 * Stable id: {@code core.workflow-run}.
 */
public final class WorkflowRunShape {

  /** Stable {@link ConversationShapeRef} for the workflow shape. */
  public static final ConversationShapeRef ID = new ConversationShapeRef("core.workflow-run");

  /** I18n key for the shape's display label (FE-resolved). */
  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.workflow-run.label");

  /** I18n key for the shape's display description. */
  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.workflow-run.description");

  /**
   * SSE event vocabulary the workflow shape emits. Reuses the agent loop's tool-call event names
   * ({@code tool_call_pending} / {@code tool_call_approved} / {@code tool_call_rejected} /
   * {@code tool_exec_started} / {@code tool_exec_completed} / {@code chunk} / {@code done} /
   * {@code error}) so the FE can reuse the agent surface's tool-call card + authorization flow,
   * and adds workflow-structure events ({@code workflow_started} / {@code node_started} /
   * {@code node_completed}) so the view can render per-node progress.
   */
  private static final List<String> EVENT_SCHEMA =
      List.of(
          "session_started",
          "workflow_started",
          "node_started",
          "chunk",
          "tool_call_pending",
          "tool_call_approved",
          "tool_call_rejected",
          "tool_exec_started",
          "tool_exec_completed",
          "node_completed",
          "done",
          "error");

  private WorkflowRunShape() {}

  /** Build the {@link ConversationShape} manifest entry. */
  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        Presentation.of(LABEL_KEY, DESCRIPTION_KEY),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SHAPE_DRIVEN,
        IterationMode.WITHIN_TURN_ITERATION,
        PersistenceMode.EPHEMERAL,
        List.of(),
        List.of(),
        List.of(),
        null,
        // Tempdoc 564 Phase 0: ConversationShape.eventSchema is typed (List<EventDescriptor>);
        // this shape declares names only (typed payload fields are filled when/if needed).
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
