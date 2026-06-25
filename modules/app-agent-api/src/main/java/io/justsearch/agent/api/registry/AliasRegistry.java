/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cross-catalog redirect table for renamed or aliased registry IDs (tempdoc 499 §4.4).
 *
 * <p>Aliases are separate from fuzzy matching: an alias is a known, precise redirect
 * (e.g., {@code core.library → core.library-surface} after a rename). The alias lookup
 * runs before approximate matching in the resolution pipeline so that known redirects
 * produce {@link ResolutionResult.Redirected} (silent success) rather than
 * {@link ResolutionResult.Unresolved} with a "did you mean?" suggestion.
 *
 * <p>Aliases are cross-catalog by nature — a removed operation might redirect to a surface.
 */
public interface AliasRegistry {

  Optional<AliasEntry> lookup(String aliasId);

  record AliasEntry(String canonicalId, ResolutionResult.RedirectReason reason) {
    public AliasEntry {
      Objects.requireNonNull(canonicalId, "canonicalId");
      Objects.requireNonNull(reason, "reason");
    }
  }

  static AliasRegistry empty() {
    return aliasId -> Optional.empty();
  }

  static AliasRegistry fromMap(Map<String, AliasEntry> aliases) {
    Objects.requireNonNull(aliases, "aliases");
    var copy = Map.copyOf(aliases);
    return aliasId -> Optional.ofNullable(copy.get(aliasId));
  }
}
