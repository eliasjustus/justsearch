/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 565 §26.C — projects a {@link WorkflowCatalog} into the lean wire shape the workflow PICKER
 * consumes (served at {@code /api/registry/workflows}). The picker is a light consumer — it needs each
 * workflow's id, its i18n label/description, its audience, and its node list (the &gt;1-node count is
 * what makes the §26.A/§26.B run-structure render meaningful) — NOT the full node payload (prompts /
 * operation refs / gate strategies), which stays a backend execution detail.
 *
 * <p>This is the ONE authority for the workflow-picker wire: the hardcoded {@code WORKFLOW_ID} const it
 * replaces (§25.2) was a fork of 561's mode-catalog authority; the launcher now PROJECTS this catalog.
 * Deliberately lighter than {@code UIOperationView} (no PreciseWire / generated-Zod contract) — a picker
 * does not need the fail-closed parse boundary {@code /api/status} does (AHA: unify what shares a reason
 * to change). The per-entry shape is pinned by {@code UIWorkflowEmitterTest}.
 */
public final class UIWorkflowEmitter {

  private UIWorkflowEmitter() {}

  /** Project every workflow in the catalog to its picker-wire entry (declaration order preserved). */
  public static List<Map<String, Object>> project(WorkflowCatalog catalog) {
    return catalog.definitions().stream().map(UIWorkflowEmitter::toEntry).toList();
  }

  private static Map<String, Object> toEntry(Workflow wf) {
    Map<String, Object> presentation = new LinkedHashMap<>();
    presentation.put("labelKey", wf.presentation().labelKey().value());
    presentation.put("descriptionKey", wf.presentation().descriptionKey().value());

    List<Map<String, Object>> nodes =
        wf.nodes().stream()
            .map(
                n -> {
                  Map<String, Object> node = new LinkedHashMap<>();
                  node.put("nodeId", n.nodeId());
                  node.put("kind", kindOf(n));
                  return node;
                })
            .toList();

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", wf.id().value());
    entry.put("type", "workflow");
    entry.put("presentation", presentation);
    entry.put("audience", wf.audience().name());
    entry.put("nodes", nodes);
    return entry;
  }

  /** The node's substrate kind — the same {@code llm|tool|gate} vocabulary the runner emits on its events. */
  private static String kindOf(WorkflowNode node) {
    return switch (node) {
      case WorkflowNode.LlmStep ignored -> "llm";
      case WorkflowNode.ToolStep ignored -> "tool";
      case WorkflowNode.GateStep ignored -> "gate";
    };
  }
}
