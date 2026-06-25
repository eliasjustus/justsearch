/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Module-owned catalog of {@link ConversationShape} manifest entries.
 *
 * <p>Per tempdoc 491 §5.3: parallel-but-distinct from the per-primitive catalogs
 * ({@link OperationCatalog} / {@link ResourceCatalog} / {@link PromptCatalog} /
 * {@link DiagnosticChannelCatalog}). {@link ConversationShape} is a Manifest (composes
 * substrate SPIs into a runnable LLM-output flow), not a fifth primitive — mirroring the
 * precedent set by {@link SurfaceCatalog} for {@link Surface} manifests (slice 449).
 *
 * <p>The catalog is consumed by the {@code ConversationEngine}: the engine resolves a
 * shape by id, validates the request audience against the shape's declared audience, then
 * dispatches to either the substrate-driven or shape-driven execution path based on the
 * shape's {@link io.justsearch.agent.api.conversation.ExecutionMode}.
 *
 * <p><strong>Phase B status</strong>: the catalog interface is defined and registered with
 * one entry — {@code AgentRunShape} (encapsulating the existing agent loop). Phase C adds
 * fresh shapes (RAG ask, summarize variants); Phase D adds URL emission and the plugin
 * contribution path.
 */
public interface ConversationShapeCatalog
    extends DeclarationCatalog<ConversationShape, ConversationShapeRef> {

  /** Stable namespace prefix for entries in this catalog (e.g., {@code "core"}). */
  String namespace();

  /** Entries in this catalog. Order matters — emitters preserve list order. */
  List<ConversationShape> definitions();

  /** Resolve a shape by id within this catalog. */
  default Optional<ConversationShape> findById(ConversationShapeRef id) {
    Objects.requireNonNull(id, "id");
    return definitions().stream().filter(s -> s.id().equals(id)).findFirst();
  }

  /** Returns a definitions-only catalog wrapper. Useful for tests and synthetic catalogs. */
  static ConversationShapeCatalog of(String namespace, List<ConversationShape> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<ConversationShape> defs = List.copyOf(definitions);
    return new ConversationShapeCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<ConversationShape> definitions() {
        return defs;
      }
    };
  }
}
