/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The shared shape of a tier-agnostic catalog of <b>Manifest-tier</b> {@link Declaration}s
 * (tempdoc 560 §4.2 — "one mechanism"). It is the Manifest-tier parallel of {@link PrimitiveCatalog}
 * (which already unifies the sealed-primitive catalogs Operation/Resource/Prompt/DiagnosticChannel):
 * the composed Manifest tiers — {@link Surface} / {@link ConversationShape} / {@link IntentSource} /
 * {@link Plugin} / {@link Workflow} — are now one {@code namespace() + definitions() + findById()}
 * contract rather than five hand-rolled interfaces, so every catalog is the same mechanism over a
 * different declaration kind. Per-kind catalogs add their own resolution helpers (e.g.
 * {@code IntentSourceCatalog.findByTransport}, {@code SurfaceCatalog.resolve}) on top.
 *
 * @param <T> the Manifest declaration kind this catalog holds
 * @param <R> the typed {@link RegistryRef} pointing at {@code T}
 */
public interface DeclarationCatalog<T extends Declaration, R extends RegistryRef<T>> {

  /** Stable namespace prefix for entries in this catalog (e.g., {@code "core"}). */
  String namespace();

  /** Entries in this catalog. Order matters — emitters preserve list order. */
  List<T> definitions();

  /** Resolve an entry by id within this catalog (the shared default; kinds may override). */
  default Optional<T> findById(R id) {
    Objects.requireNonNull(id, "id");
    return definitions().stream().filter(d -> d.id().equals(id)).findFirst();
  }
}
