/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable, namespaced identifier for a {@link Plugin} catalog entry.
 *
 * <p>Format mirrors {@link OperationRef} / {@link SurfaceRef}:
 * {@code ^(core|vendor\.\w+)\.[a-z][a-z0-9-]*$}. Examples: {@code vendor.mcphost.reference},
 * {@code vendor.acme.dashboard-pack}.
 *
 * <p>Tempdoc 560 §4.1: a {@code Plugin} is a <b>Manifest tier</b> — a composed declaration that
 * <em>bundles</em> primitive contributions (Operations/Resources/Prompts/Surfaces), not a fifth
 * sealed {@link RegistryEntry} primitive. {@code PluginRef} is therefore a {@link RegistryRef}
 * parallel to {@link SurfaceRef} / {@link ConversationShapeRef} (Manifest refs), typed distinctly so
 * cross-tier references stay unambiguous. "First-party and third-party differ only by authority +
 * trust, never by mechanism" (§2): a {@code Plugin} is one {@code COMPOSITION} declaration whose
 * provenance carries its trust tier.
 */
public record PluginRef(@JsonValue String value) implements RegistryRef<Plugin> {

  @JsonCreator
  public PluginRef {
    value = NamespacedId.validate(value, "PluginRef");
  }

  @Override
  public String toString() {
    return value;
  }
}
