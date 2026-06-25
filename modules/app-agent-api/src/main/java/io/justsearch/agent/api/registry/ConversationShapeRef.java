/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link ConversationShape} manifest entry.
 *
 * <p>Per tempdoc 491 §5.3: {@code ConversationShape} is a Manifest tier (third Manifest tier
 * alongside Plugin and Surface) — it composes the substrate's behavioral SPIs (defined in
 * {@code io.justsearch.agent.api.conversation}) into a runnable LLM-output flow. It is not a
 * fifth registry primitive; the primitive count stays at four (Operation / Resource / Prompt /
 * DiagnosticChannel). Per the established precedent for {@link SurfaceRef}, this Ref type IS
 * in the {@link RegistryRef} sealed permits so cross-reference shape factoring works (a
 * {@code RegistryRef<ConversationShape>} reads unambiguously as "reference to a
 * ConversationShape").
 *
 * <p>The Ref lives in this package (and not in {@code conversation}) because Java sealed
 * interfaces require permitted subtypes to be in the same module — and since this module has
 * no {@code module-info.java}, the constraint tightens to "same package." This mirrors the
 * placement of {@link SurfaceRef} alongside {@link Surface}.
 *
 * <p>Format mirrors {@link OperationRef} / {@link SurfaceRef}:
 * {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}. Examples: {@code core.agent-run},
 * {@code core.summarize}, {@code core.rag-ask}, {@code core.navigate-chat},
 * {@code vendor.acme.custom-flow}.
 */
public record ConversationShapeRef(@JsonValue String value)
    implements RegistryRef<ConversationShape> {

  @JsonCreator
  public ConversationShapeRef {
    value = NamespacedId.validate(value, "ConversationShapeRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
