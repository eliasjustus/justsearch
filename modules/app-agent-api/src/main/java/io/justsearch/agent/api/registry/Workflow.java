/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * A <b>Workflow</b> — the LANGUAGE_MEDIATED Manifest tier of the unified extension substrate
 * (tempdoc 560 §4.4 / tempdoc 534). Like {@link Plugin} and {@link Surface}, it reuses the shared
 * cross-cutting axes (id/Presentation/Provenance/Audience/{@code consumers}) and adds a per-kind
 * payload: an ordered list of {@link WorkflowNode}s. Each node delegates to an existing substrate
 * (LLM/tool/gate) — the proof that the substrates <em>compose</em> rather than fragment: a workflow
 * introduces no new mechanism, it only sequences the ones that already exist.
 */
public record Workflow(
    WorkflowRef id,
    Presentation presentation,
    Provenance provenance,
    Audience audience,
    List<WorkflowNode> nodes,
    List<ConsumerHook> consumers) implements ConsumerDeclaring {

  public Workflow {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    provenance = provenance == null ? Provenance.core("1.0") : provenance;
    audience = audience == null ? Audience.USER : audience;
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    consumers = consumers == null ? List.of() : List.copyOf(consumers);
    // §5 keystone — NonEmpty<ConsumerHook> unrepresentable (rung 2): Workflow is inline-only, so a
    // zero-consumer Workflow cannot be constructed (tempdoc 560 §5).
    if (consumers.isEmpty()) {
      throw new IllegalArgumentException(
          "Workflow "
              + id.value()
              + " must declare >=1 consumer — a zero-consumer declaration is unrepresentable"
              + " (tempdoc 560 §5 NonEmpty<ConsumerHook> keystone).");
    }
  }
}
