/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed privacy axis on a {@link Resource}.
 *
 * <p>Slice 445 substrate-extension. Lifts the convention currently encoded by
 * ADR-0028 + the {@code LibraryResolveHashOnlyCallerPin} ArchUnit test into a
 * typed declaration on the Resource record. The validator can enforce that
 * {@link PathPolicy#HASHED_REQUIRES_RESOLVER} entries supply a {@link #resolver}
 * Operation; FE consumers branch on {@link #pathPolicy} to know whether to
 * render path strings directly or to resolve through a separate request.
 *
 * <p>Existing Resources with no path-typed fields use {@link #noPaths()};
 * Resources that need path-resolution declare the resolver explicitly.
 *
 * <p>{@link #loopbackOnly} captures the existing CLAUDE.md hard invariant
 * ("Local API binds to 127.0.0.1 only") at the per-Resource level. It is
 * informational today (the local API server already enforces loopback);
 * declaring it on the Resource lets future deployment topologies (multi-host)
 * mark which Resources require loopback-confined wire. For the slice-445
 * landing it defaults to {@code true}.
 *
 * <p>Stability: stable (API contract); new {@link PathPolicy} values follow
 * shape-governance.
 */
public record Privacy(
    PathPolicy pathPolicy,
    boolean loopbackOnly,
    Optional<OperationRef> resolver)
    implements PreciseWire {

  public Privacy {
    Objects.requireNonNull(pathPolicy, "pathPolicy");
    Objects.requireNonNull(resolver, "resolver");
    if (pathPolicy == PathPolicy.HASHED_REQUIRES_RESOLVER && resolver.isEmpty()) {
      throw new IllegalArgumentException(
          "PathPolicy.HASHED_REQUIRES_RESOLVER requires a resolver Operation id");
    }
  }

  /**
   * Sane default for Resources whose schema declares no path-typed fields.
   * Equivalent to {@code new Privacy(PathPolicy.NO_PATHS, true, Optional.empty())}.
   */
  public static Privacy noPaths() {
    return new Privacy(PathPolicy.NO_PATHS, true, Optional.empty());
  }

  /**
   * Convenience for Resources that carry path hashes. Caller supplies the
   * resolver Operation id (typically {@code core.resolve-path-hash}).
   */
  public static Privacy hashedWithResolver(OperationRef resolver) {
    return new Privacy(PathPolicy.HASHED_REQUIRES_RESOLVER, true, Optional.of(resolver));
  }
}
