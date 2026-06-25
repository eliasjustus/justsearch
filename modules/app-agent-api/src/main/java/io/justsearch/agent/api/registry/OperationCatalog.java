/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Module-owned catalog of Operation entries.
 *
 * <p>Per tempdoc 429 §A.1 + §B.A: standalone interface, not a {@code NamedCatalog<T>}
 * subtype. The earlier generalization plan was rejected as cosmetic — Operation and
 * Metric catalogs have disjoint consumer sets, so a shared parent interface enables
 * no shared behavior.
 *
 * <p>Per slice 481 §7 step 1 (2026-05-08): now extends {@link PrimitiveCatalog}, which
 * formalizes the shape shared with Resource / Prompt / DiagnosticChannel catalogs. The
 * earlier 429 §A.1 reasoning rejected a {@code NamedCatalog<T>} that would have spanned
 * Operation + Metric (different concerns); {@link PrimitiveCatalog} spans only the four
 * registry primitives (same concern: backend-declared capability projection). The wire-
 * name helpers below are Operation-specific (LLM tool naming per OpenAI function-name
 * regex) and remain on this interface, not on the parent.
 *
 * <p>Per §C.A: the substrate types live in {@code app-agent-api} (build-graph forced —
 * placing them in {@code app-services} would create an {@code app-agent → app-services}
 * cycle).
 */
public interface OperationCatalog extends PrimitiveCatalog<Operation, OperationRef> {

  /** Stable namespace prefix for entries in this catalog (e.g., {@code "core"}, {@code "vendor.acme"}). */
  @Override
  String namespace();

  /** Entries in this catalog. Order matters — emitters preserve list order for stable LLM tool ordering. */
  @Override
  List<Operation> definitions();

  /** Convenience wrapper around {@link PrimitiveCatalog#findById}. */
  default Optional<Operation> findByIdValue(String idValue) {
    Objects.requireNonNull(idValue, "idValue");
    return definitions().stream()
        .filter(op -> op.id().value().equals(idValue))
        .findFirst();
  }

  /**
   * Project an {@link OperationRef} to its wire-protocol form. Per tempdoc 429 §F.21 C1:
   * the OperationRef is the single identity; the LLM-facing form is a deterministic
   * transliteration that replaces {@code .} and {@code -} with {@code _} so the result
   * matches OpenAI's function-name regex {@code ^[a-zA-Z0-9_-]+$}. Examples:
   *
   * <ul>
   *   <li>{@code core.search-index} → {@code core_search_index}
   *   <li>{@code core.bulk-reindex} → {@code core_bulk_reindex}
   *   <li>{@code vendor.acme.export} → {@code vendor_acme_export}
   * </ul>
   *
   * <p>The forward projection is total + deterministic; the reverse mapping is by
   * iterating the catalog and comparing transliterated forms (see {@link #findByWireName}).
   */
  static String toWireName(OperationRef id) {
    Objects.requireNonNull(id, "id");
    return id.value().replace('.', '_').replace('-', '_');
  }

  /**
   * Resolve by wire-protocol name. The substrate's identity is {@link OperationRef};
   * the wire form is computed via {@link #toWireName(OperationRef)}. The reverse mapping
   * iterates the catalog and matches transliterated forms — unambiguous in practice
   * because {@link OperationRef}'s pattern restricts possible collisions to the rare
   * case of two ids sharing a transliteration. Falls back to {@link #findByIdValue}
   * so handoff tools and non-tool operations not yet using namespaced ids still
   * resolve.
   */
  default Optional<Operation> findByWireName(String wireName) {
    Objects.requireNonNull(wireName, "wireName");
    for (Operation op : definitions()) {
      if (toWireName(op.id()).equals(wireName)) {
        return Optional.of(op);
      }
    }
    return findByIdValue(wireName);
  }

  /**
   * Resolve a wire-name against this catalog using the full resolution pipeline
   * (exact → fuzzy). Returns a typed {@link ResolutionResult} that a
   * {@link ResolutionRecoveryPolicy} can consume (tempdoc 499 §3.5).
   */
  default ResolutionResult<Operation> resolveByWireName(String wireName) {
    Objects.requireNonNull(wireName, "wireName");
    Optional<Operation> exact = findByWireName(wireName);
    if (exact.isPresent()) {
      return new ResolutionResult.Resolved<>(exact.get());
    }
    List<ResolutionResult.Suggestion<Operation>> alts = matcher().findAlternatives(
        wireName, definitions(), o -> toWireName(o.id()), 3);
    ResolutionResult.FailureMode mode = alts.isEmpty()
        ? ResolutionResult.FailureMode.UNKNOWN
        : ResolutionResult.FailureMode.TYPO;
    return new ResolutionResult.Unresolved<>(wireName,
        new ResolutionResult.UnresolvedDiagnosis(mode, "Unknown tool: " + wireName),
        alts);
  }

  /** Returns a definitions-only catalog wrapper. Useful for tests and synthetic catalogs. */
  static OperationCatalog of(String namespace, List<Operation> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Operation> defs = List.copyOf(definitions);
    return new OperationCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Operation> definitions() {
        return defs;
      }
    };
  }
}
