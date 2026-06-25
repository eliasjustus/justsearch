/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A tier-agnostic catalog of {@link Workflow} Manifest declarations (tempdoc 560 §4.1 / §534). */
public interface WorkflowCatalog extends DeclarationCatalog<Workflow, WorkflowRef> {
  String namespace();

  List<Workflow> definitions();

  default Optional<Workflow> findById(WorkflowRef id) {
    return definitions().stream().filter(w -> w.id().equals(id)).findFirst();
  }

  static WorkflowCatalog of(String namespace, List<Workflow> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Workflow> defs = List.copyOf(definitions);
    return new WorkflowCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Workflow> definitions() {
        return defs;
      }
    };
  }
}
