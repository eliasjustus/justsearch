/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link Prompt} entry.
 *
 * <p>Format mirrors {@link OperationRef}: {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}.
 * Examples: {@code core.summarize-document}, {@code vendor.acme.report-template}.
 *
 * <p>Per slice 447-impl-A: parallels {@link OperationRef}, {@link DiagnosticChannelRef},
 * {@link ResourceRef}, and {@link SurfaceRef} as a value-typed identifier. Distinct from
 * {@link OperationRef} so cross-primitive references stay unambiguous.
 *
 * <p>Per slice 447-followup/2.1: implements {@link NamespacedId} sealed interface for
 * shape factoring — namespace regex + validation logic single-sourced.
 */
public record PromptRef(@JsonValue String value) implements RegistryRef<Prompt> {

  @JsonCreator
  public PromptRef {
    value = NamespacedId.validate(value, "PromptRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
