/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * One node of a {@link Workflow} (tempdoc 560 §4.4 / tempdoc 534). The load-bearing invariant:
 * <b>every node delegates to an existing substrate</b> — and the sealed type makes any other shape
 * <em>unrepresentable</em>. A node is exactly one of:
 *
 * <ul>
 *   <li>{@link LlmStep} → delegates to a {@link ConversationShape} (the ConversationEngine),
 *   <li>{@link ToolStep} → delegates to an {@link Operation} (the IntentRouter + trust lattice),
 *   <li>{@link GateStep} → delegates to a {@link ConfirmStrategy} (the consent gate, e.g. TYPED_CONFIRM).
 * </ul>
 *
 * Each variant carries a <em>reference</em> to the existing primitive; a workflow can never define a
 * new primitive inline, so it cannot fork the substrate — it only composes it (§534's "every node
 * delegates").
 */
public sealed interface WorkflowNode permits WorkflowNode.LlmStep, WorkflowNode.ToolStep, WorkflowNode.GateStep {
  String nodeId();

  /**
   * Delegates to the ConversationEngine via a declared conversation shape. The optional
   * {@code prompt} seeds the LLM turn; when {@code null} the runner feeds the accumulated
   * workflow context (the previous node's output) as the turn input — the §534 "node
   * output→input" binding.
   */
  record LlmStep(String nodeId, ConversationShapeRef shape, String prompt) implements WorkflowNode {
    public LlmStep {
      requireNode(nodeId);
      Objects.requireNonNull(shape, "shape");
    }

    /** Back-compat: an LlmStep with no seed prompt (runs on the accumulated workflow context). */
    public LlmStep(String nodeId, ConversationShapeRef shape) {
      this(nodeId, shape, null);
    }
  }

  /**
   * Delegates to an operation through the IntentRouter + trust lattice. The {@code argumentsJson}
   * is the operation's existing argument contract (not a new mechanism) — it lets a workflow
   * specify what to invoke the op with; defaults to {@code "{}"} (no arguments).
   */
  record ToolStep(String nodeId, OperationRef operation, String argumentsJson)
      implements WorkflowNode {
    public ToolStep {
      requireNode(nodeId);
      Objects.requireNonNull(operation, "operation");
      argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    /** Back-compat: a ToolStep invoking its operation with empty arguments. */
    public ToolStep(String nodeId, OperationRef operation) {
      this(nodeId, operation, "{}");
    }
  }

  /** Delegates to the consent gate via a confirm strategy (e.g. TYPED_CONFIRM). */
  record GateStep(String nodeId, ConfirmStrategy confirm) implements WorkflowNode {
    public GateStep {
      requireNode(nodeId);
      Objects.requireNonNull(confirm, "confirm");
    }
  }

  private static void requireNode(String nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    if (nodeId.isBlank()) {
      throw new IllegalArgumentException("WorkflowNode nodeId must not be blank");
    }
  }
}
