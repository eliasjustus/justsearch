/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Derives a {@link Surface}'s {@link Altitude} from the authority it consumes (tempdoc 571 §4c) — the
 * single move that makes altitude a <em>projection</em> rather than a hand-set declaration. Shared by
 * {@code RegistryController} (which stamps the derived altitude onto the {@code /api/registry/surfaces}
 * wire) and {@code SurfaceAreaValidator} (which fails the build on a derivation conflict), so the wire
 * value and the validated value cannot drift. The {@code surface-altitude} discipline gate is the
 * independent third witness — it reimplements this same derivation over the parsed catalogs.
 *
 * <p>The <strong>signal set</strong> a surface raises:
 *
 * <ul>
 *   <li>consumes ≥1 {@code DiagnosticChannel} ⟹ {@link Altitude#DIAGNOSTIC} (a channel-consuming
 *       surface cannot hide as PRODUCT);
 *   <li>consumes a {@link Role#DIAGNOSTIC}-role Resource ⟹ {@link Altitude#DIAGNOSTIC};
 *   <li>consumes a {@link Role#TRUST}-role Resource ⟹ {@link Altitude#TRUST};
 *   <li>{@link Placement#HEADLESS_AGENT_TOOL} ⟹ {@link Altitude#TOOL}.
 * </ul>
 *
 * <p>Resolution: an empty signal set ⟹ {@link Altitude#PRODUCT} (the benign default, incl.
 * empty-consumes surfaces — 571 §8 R3); exactly one distinct signal ⟹ that altitude; two or more
 * distinct signals ⟹ a <strong>conflict</strong> — the merge-foreclosure (571 §4c): one surface cannot
 * carry two authorities. Consumed Operations are affordances, never a signal (the primary-authority
 * rule, 571 §8 R2). PRODUCT-role Resources raise no signal (so a surface may consume product data
 * alongside its one diagnostic / trust authority without conflict).
 */
public final class SurfaceAltitude {

  private SurfaceAltitude() {}

  /**
   * The outcome of deriving a surface's altitude: the resolved {@link #altitude}, the raw
   * {@link #signals} that produced it, and whether they {@link #conflict}. On conflict the resolved
   * altitude is the benign {@link Altitude#PRODUCT} (so the wire stays safe) and {@code conflict} is
   * {@code true} (so the validator / gate fail the build).
   */
  public record Derivation(Altitude altitude, Set<Altitude> signals, boolean conflict) {}

  /**
   * Derive the altitude of {@code surface} given a role index over every registered Resource.
   *
   * @param surface the surface whose altitude to derive (its {@code consumes} + {@code placement}).
   * @param resourceRoles id → {@link Role} for every registered Resource; a consumed Resource absent
   *     from the map (or mapped to {@link Role#PRODUCT}) raises no signal.
   */
  public static Derivation derive(Surface surface, Map<ResourceRef, Role> resourceRoles) {
    Set<Altitude> signals = new LinkedHashSet<>();
    SurfaceConsumes consumes = surface.consumes();
    if (!consumes.diagnosticChannels().isEmpty()) {
      signals.add(Altitude.DIAGNOSTIC);
    }
    for (ResourceRef ref : consumes.resources()) {
      Role role = resourceRoles.getOrDefault(ref, Role.PRODUCT);
      switch (role) {
        case DIAGNOSTIC -> signals.add(Altitude.DIAGNOSTIC);
        case TRUST -> signals.add(Altitude.TRUST);
        case PRODUCT -> {
          // product data — no altitude signal.
        }
      }
    }
    if (surface.placement() == Placement.HEADLESS_AGENT_TOOL) {
      signals.add(Altitude.TOOL);
    }
    if (signals.isEmpty()) {
      return new Derivation(Altitude.PRODUCT, signals, false);
    }
    if (signals.size() == 1) {
      return new Derivation(signals.iterator().next(), signals, false);
    }
    return new Derivation(Altitude.PRODUCT, signals, true);
  }
}
