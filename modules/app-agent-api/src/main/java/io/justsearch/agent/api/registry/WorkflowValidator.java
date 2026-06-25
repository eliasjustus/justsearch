/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enforces tempdoc 534's load-bearing invariant — <b>every workflow node delegates to an existing
 * substrate</b> — by checking each node's reference resolves against the live catalogs. The sealed
 * {@link WorkflowNode} type already makes a non-delegating node unrepresentable; this validator is
 * the runtime consumer that additionally rejects a node whose ref points at a substrate that does
 * not exist (a dangling delegation). It is the {@link Workflow} type's first consumer.
 *
 * <p>Decoupled by design: the caller supplies the sets of known ids (from whatever
 * {@code ConversationShapeCatalog} / {@code OperationCatalog} are live), so the validator depends on
 * no specific catalog wiring.
 */
public final class WorkflowValidator {
  private WorkflowValidator() {}

  /** Returns one message per node whose delegation target does not resolve (empty ⇒ valid). */
  public static List<String> validate(
      Workflow workflow, Set<String> knownShapeIds, Set<String> knownOperationIds) {
    List<String> errors = new ArrayList<>();
    for (WorkflowNode node : workflow.nodes()) {
      switch (node) {
        case WorkflowNode.LlmStep step -> {
          if (!knownShapeIds.contains(step.shape().value())) {
            errors.add(
                node.nodeId() + ": LlmStep delegates to unknown conversation shape " + step.shape().value());
          }
        }
        case WorkflowNode.ToolStep step -> {
          if (!knownOperationIds.contains(step.operation().value())) {
            errors.add(
                node.nodeId() + ": ToolStep delegates to unknown operation " + step.operation().value());
          }
        }
        case WorkflowNode.GateStep ignored -> {
          // A confirm strategy is an intrinsic substrate (the trust gate) — always a valid delegation.
        }
      }
    }
    return errors;
  }

  public static boolean isValid(Workflow workflow, Set<String> knownShapeIds, Set<String> knownOperationIds) {
    return validate(workflow, knownShapeIds, knownOperationIds).isEmpty();
  }
}
