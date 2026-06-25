/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link Workflow} catalog entry (tempdoc 560 §4.4 /
 * tempdoc 534). Format mirrors the other refs: {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 *
 * <p>{@code Workflow} is the LANGUAGE_MEDIATED Manifest tier — a composed declaration whose nodes
 * each delegate to an existing substrate primitive; {@code WorkflowRef} is therefore a
 * {@link RegistryRef} parallel to {@link SurfaceRef} / {@link PluginRef} (Manifest refs), not a
 * sealed {@link RegistryEntry} primitive.
 */
public record WorkflowRef(@JsonValue String value) implements RegistryRef<Workflow> {

  @JsonCreator
  public WorkflowRef {
    value = NamespacedId.validate(value, "WorkflowRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
