/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Module-owned catalog of {@link IntentSource} manifest entries.
 *
 * <p>Per tempdoc 487 §4.1: parallel-but-distinct from the per-primitive
 * catalogs ({@link OperationCatalog} / {@link ResourceCatalog} /
 * {@link PromptCatalog} / {@link DiagnosticChannelCatalog}) and from the
 * other Manifest-tier catalogs ({@link SurfaceCatalog} /
 * {@link ConversationShapeCatalog}). {@code IntentSource} is a Manifest
 * (registers an intent ingress), not a fifth primitive.
 *
 * <p>The catalog is consumed by the per-process {@code IntentRouter}: when
 * an intent arrives carrying a {@link Intent#transport()} tag, the router
 * looks up the source via the catalog to determine {@link SourceTier} for
 * the trust lattice (§4.4). The {@link IntentSource#extractorId()} field
 * names the {@link IntentExtractor} implementation that converts the
 * source's raw payload into {@code Intent} envelopes.
 *
 * <p><strong>Substrate-shape rule (Pass-9 commitment 1, tempdoc 487 §7):</strong>
 * every intent ingress is a named {@code IntentSource} in this catalog. New
 * ingresses register; new {@link IntentExtractor} flavors require Pass-8
 * with proof-by-example.
 */
public interface IntentSourceCatalog extends DeclarationCatalog<IntentSource, IntentSourceRef> {

  /** Stable namespace prefix for entries in this catalog (e.g., {@code "core"}). */
  String namespace();

  /** Entries in this catalog. Order matters — emitters preserve list order. */
  List<IntentSource> definitions();

  /** Resolve a source by id within this catalog. */
  default Optional<IntentSource> findById(IntentSourceRef id) {
    Objects.requireNonNull(id, "id");
    return definitions().stream().filter(s -> s.id().equals(id)).findFirst();
  }

  /**
   * Resolve a source by transport tag. Post-Commit-4: data-driven lookup against
   * each source's {@link IntentSource#transport()} field. Returns the first source
   * whose declared transport matches; if none does, returns empty.
   *
   * <p>The pre-Commit-4 default method used a hard-coded {@code switch} over the
   * {@link TransportTag} enum mapping each value to a CORE-specific source id
   * — wrong for plugin catalogs whose ids don't share the CORE prefix.
   * The current implementation has no CORE-specific knowledge and works
   * uniformly for any catalog (core, plugin, test).
   */
  default Optional<IntentSource> findByTransport(TransportTag transport) {
    Objects.requireNonNull(transport, "transport");
    return definitions().stream()
        .filter(s -> s.transport().filter(t -> t == transport).isPresent())
        .findFirst();
  }

  /** Returns a definitions-only catalog wrapper. Useful for tests and synthetic catalogs. */
  static IntentSourceCatalog of(String namespace, List<IntentSource> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<IntentSource> defs = List.copyOf(definitions);
    return new IntentSourceCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<IntentSource> definitions() {
        return defs;
      }
    };
  }
}
