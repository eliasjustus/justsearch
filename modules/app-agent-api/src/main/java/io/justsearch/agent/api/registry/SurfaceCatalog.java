/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Module-owned catalog of {@link Surface} manifest entries.
 *
 * <p>Per slice 449 §4: Surface is a Manifest (the second tier alongside
 * Plugin), not a primitive. SurfaceCatalog is parallel-but-distinct from
 * the per-primitive catalogs ({@link OperationCatalog} / {@link ResourceCatalog}
 * / {@code DiagnosticChannelCatalog} / {@link PromptCatalog}).
 *
 * <p>The catalog is consumed by the FE chrome's per-zone dispatchers
 * (ActivityRail, Stage, HUD, etc.) — each filters by {@code placement} +
 * {@code audience ∈ visibleAudienceSet} and mounts the resolved
 * {@link Surface#mountTag}. V1 ships only one {@code Stage} dispatcher
 * upgrade (Library calibration per §B phase 6 Option α); the broader
 * chrome refactor lands post-calibration.
 */
public interface SurfaceCatalog extends DeclarationCatalog<Surface, SurfaceRef> {

  String namespace();

  List<Surface> definitions();

  default Optional<Surface> findById(SurfaceRef id) {
    Objects.requireNonNull(id, "id");
    return definitions().stream().filter(s -> s.id().equals(id)).findFirst();
  }

  default AliasRegistry aliasRegistry() {
    return AliasRegistry.empty();
  }

  default CatalogMatcher matcher() {
    return CatalogMatcher.noop();
  }

  /**
   * Resolve a surface ID with alias lookup and approximate matching
   * (tempdoc 499 §4.2). Parallel to {@link PrimitiveCatalog#resolve}.
   */
  default ResolutionResult<Surface> resolve(SurfaceRef ref) {
    Objects.requireNonNull(ref, "ref");

    Optional<Surface> exact = findById(ref);
    if (exact.isPresent()) {
      return new ResolutionResult.Resolved<>(exact.get());
    }

    String rawId = ref.value();

    Optional<AliasRegistry.AliasEntry> alias = aliasRegistry().lookup(rawId);
    if (alias.isPresent()) {
      String canonicalId = alias.get().canonicalId();
      Optional<Surface> aliasTarget = definitions().stream()
          .filter(s -> s.id().value().equals(canonicalId))
          .findFirst();
      if (aliasTarget.isPresent()) {
        return new ResolutionResult.Redirected<>(
            aliasTarget.get(), rawId, alias.get().reason());
      }
    }

    List<ResolutionResult.Suggestion<Surface>> alts = matcher().findAlternatives(
        rawId, definitions(), s -> s.id().value(), 3);
    ResolutionResult.FailureMode mode = alts.isEmpty()
        ? ResolutionResult.FailureMode.UNKNOWN
        : ResolutionResult.FailureMode.TYPO;
    return new ResolutionResult.Unresolved<>(rawId,
        new ResolutionResult.UnresolvedDiagnosis(mode,
            "No entry '" + rawId + "' in " + namespace() + " catalog"),
        alts);
  }

  static SurfaceCatalog of(String namespace, List<Surface> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Surface> defs = List.copyOf(definitions);
    return new SurfaceCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Surface> definitions() {
        return defs;
      }
    };
  }
}
