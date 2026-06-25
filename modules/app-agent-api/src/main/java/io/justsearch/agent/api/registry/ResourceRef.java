/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link Resource} entry.
 *
 * <p>Format mirrors {@link OperationRef}: {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 * Examples: {@code core.health-events}, {@code core.indexing-jobs},
 * {@code vendor.acme.metrics}.
 *
 * <p>Per slice 447-impl-A: parallels {@link OperationRef}, {@link DiagnosticChannelRef},
 * {@link PromptRef}, and {@link SurfaceRef} as a value-typed identifier. Distinct from
 * {@link OperationRef} so cross-primitive references stay unambiguous.
 *
 * <p>Per slice 447-followup/2.1: implements {@link NamespacedId} sealed interface for
 * shape factoring — namespace regex + validation logic single-sourced.
 */
public record ResourceRef(@JsonValue String value) implements RegistryRef<Resource> {

  @JsonCreator
  public ResourceRef {
    value = NamespacedId.validate(value, "ResourceRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
