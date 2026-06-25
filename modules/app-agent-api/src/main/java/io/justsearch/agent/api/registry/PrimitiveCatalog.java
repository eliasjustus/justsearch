/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generic parent for the four primitive catalogs (Operation, Resource, Prompt,
 * DiagnosticChannel).
 *
 * <p>Per slice 481 §7 step 1 + the deferral note in {@link DiagnosticChannelCatalog}:
 * "the shared substrate extraction (a generic {@code PrimitiveCatalog<T>}) is deferred
 * to phase 2 alongside RegistryController parameterization." This interface ships that
 * extraction.
 *
 * <p>The four primitive catalogs share identical interface shape (verified by Pass 8
 * §D.1 audit against the four source files: same {@code namespace()} + {@code definitions()}
 * + {@code findById(specificRef)} + {@code of(...)} factory). This interface formalizes
 * the shape; primitive-specific helpers (e.g., {@link OperationCatalog#toWireName} for
 * LLM tool naming) remain on the specialized sub-interfaces.
 *
 * <p>Type parameters:
 *
 * <ul>
 *   <li>{@code T} — the entry type, bounded by {@link RegistryEntry}.
 *   <li>{@code R} — the corresponding {@link RegistryRef} type that points at {@code T}.
 * </ul>
 *
 * <p>The type parameters express the substrate's existing relationship: each primitive
 * has a typed Ref pointing at it (per slice 447 §X Phase 1's {@code RegistryRef<T>}
 * sealed parent). The catalog interface binds the two together.
 */
public interface PrimitiveCatalog<T extends RegistryEntry, R extends RegistryRef<T>> {

  /** Stable namespace prefix for entries in this catalog (e.g., {@code "core"}). */
  String namespace();

  /** Entries in this catalog. Order matters — emitters preserve list order. */
  List<T> definitions();

  /** Resolve an entry by id within this catalog. */
  default Optional<T> findById(R id) {
    Objects.requireNonNull(id, "id");
    return definitions().stream().filter(e -> idOf(e).equals(id)).findFirst();
  }

  /**
   * Extract the id from an entry. Defaults to {@link RegistryEntry#id()} narrowed via
   * an unchecked cast — the {@code R extends RegistryRef<T>} bound + each primitive
   * record's covariant {@code id()} return type guarantee soundness at runtime, but
   * the cast is required because {@link RegistryEntry#id()} returns the parent
   * {@code RegistryRef<?>}.
   */
  @SuppressWarnings("unchecked")
  default R idOf(T entry) {
    return (R) entry.id();
  }

  /**
   * Alias registry for resolving renamed/aliased IDs. Defaults to empty (no aliases).
   * Override in implementations that receive an injected registry.
   */
  default AliasRegistry aliasRegistry() {
    return AliasRegistry.empty();
  }

  /**
   * Approximate matcher for fuzzy resolution. Defaults to noop (no suggestions).
   * Override in implementations that receive an injected matcher.
   */
  default CatalogMatcher matcher() {
    return CatalogMatcher.noop();
  }

  /**
   * Resolve an ID against this catalog with alias lookup and approximate matching
   * (tempdoc 499 §4.2).
   *
   * <p>Resolution pipeline: (1) exact match, (2) alias redirect, (3) approximate match.
   */
  default ResolutionResult<T> resolve(R ref) {
    Objects.requireNonNull(ref, "ref");

    // 1. Exact match
    Optional<T> exact = findById(ref);
    if (exact.isPresent()) {
      return new ResolutionResult.Resolved<>(exact.get());
    }

    String rawId = ref.value();

    // 2. Alias lookup
    Optional<AliasRegistry.AliasEntry> alias = aliasRegistry().lookup(rawId);
    if (alias.isPresent()) {
      String canonicalId = alias.get().canonicalId();
      Optional<T> aliasTarget = definitions().stream()
          .filter(e -> idOf(e).value().equals(canonicalId))
          .findFirst();
      if (aliasTarget.isPresent()) {
        return new ResolutionResult.Redirected<>(
            aliasTarget.get(), rawId, alias.get().reason());
      }
    }

    // 3. Approximate matching
    List<ResolutionResult.Suggestion<T>> alts = matcher().findAlternatives(
        rawId, definitions(), e -> idOf(e).value(), 3);
    ResolutionResult.FailureMode mode = alts.isEmpty()
        ? ResolutionResult.FailureMode.UNKNOWN
        : ResolutionResult.FailureMode.TYPO;
    return new ResolutionResult.Unresolved<>(rawId,
        new ResolutionResult.UnresolvedDiagnosis(mode,
            "No entry '" + rawId + "' in " + namespace() + " catalog"),
        alts);
  }
}
