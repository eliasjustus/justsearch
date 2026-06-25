/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * A <b>Plugin</b> — the COMPOSITION Manifest tier of the unified extension substrate (tempdoc 560
 * §4.1). A plugin is <em>not a mechanism</em>: it is a single declaration that bundles
 * contributions which are themselves declarations on the primitive axes ({@link
 * PluginContributions}). First-party and third-party plugins differ only by <b>authority + trust</b>
 * (carried in {@link Provenance}), never by mechanism (§2: "core-as-contribution").
 *
 * <p>Plugin reuses the same <b>shared cross-cutting axes</b> as every other declaration kind — a
 * {@link PluginRef} id, {@link Presentation}, {@link Provenance} (trust + identity), {@link
 * Audience}, and {@code consumers: List<ConsumerHook>} — and adds a per-kind payload ({@link
 * PluginContributions}). It is a Manifest tier (like {@link Surface} / {@link ConversationShape}),
 * deliberately NOT part of the sealed {@link RegistryEntry} primitive hierarchy.
 *
 * <p>Truth boundary (§4.5/§7): a plugin's provenance tier governs what its contributions may do — a
 * {@code TRUSTED_PLUGIN} contributes operations/connectors/views; it never forks the index or an
 * existing core primitive.
 */
public record Plugin(
    PluginRef id,
    Presentation presentation,
    Provenance provenance,
    Audience audience,
    PluginContributions contributions,
    List<ConsumerHook> consumers) implements ConsumerDeclaring {

  public Plugin {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    provenance = provenance == null ? Provenance.core("1.0") : provenance;
    audience = audience == null ? Audience.USER : audience;
    contributions = contributions == null ? PluginContributions.empty() : contributions;
    consumers = consumers == null ? List.of() : List.copyOf(consumers);
    // §5 keystone — NonEmpty<ConsumerHook> as an UNREPRESENTABLE invariant (prevention rung 2), not a
    // gate. Plugin is an inline-only ConsumerDeclaring kind: its consumers are hand-declared, so a
    // zero-consumer Plugin cannot be constructed at all. (Operation/Resource derive consumers from
    // executors/surfaces, so they keep the consumer-presence GATE instead.)
    if (consumers.isEmpty()) {
      throw new IllegalArgumentException(
          "Plugin "
              + id.value()
              + " must declare >=1 consumer — a zero-consumer declaration is unrepresentable"
              + " (tempdoc 560 §5 NonEmpty<ConsumerHook> keystone).");
    }
  }

  /** The trust tier this plugin's contributions inherit (§5 isolation-proportional-to-trust). */
  public TrustTier trustTier() {
    return provenance.tier();
  }
}
