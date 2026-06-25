/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * The Head's <b>trust adapter</b> onto the one shared {@code ContributionComposer} (tempdoc 560
 * §4.2/§4.3). The composer (in {@code modules:extension-substrate}) reifies the four substrates —
 * Boundary, Trust, Dispatch, Lifecycle — generically, from two booleans per installation. This class
 * is the projection of the Head's {@link TrustTier} onto those booleans, so the SAME composer enforces
 * the same four substrates for the Head's {@link ContributionRegistry} and for the Worker's content-
 * extractor registry with ZERO per-axis/per-process re-derivation.
 *
 * <ul>
 *   <li><b>Boundary</b> (538/§5; isolation proportional to trust) — {@link #boundaryAdmissible} reads
 *       {@link IsolationPolicy}; an inadmissible tier is refused by the composer, never downgraded.
 *   <li><b>Trust</b> (§4.5; host owns the {@code core.*} namespace) — {@link #isCore} tells the composer
 *       whether the owner may mint {@code core.*} keys. The {@link SourceTier}×{@link RiskTier}→
 *       {@link GateBehavior} lattice half runs per-invocation in the executor/router, not here.
 *   <li><b>Dispatch</b> (487) — {@link OperationDispatcher} / {@code IntentRouter}; the composed view
 *       the consumer routes over.
 *   <li><b>Lifecycle</b> (542) — the composer's atomic install / ownership-keyed revoke.
 * </ul>
 */
public final class ContributionSubstrates {

  private ContributionSubstrates() {}

  /** Trust substrate: only a CORE owner may mint declarations in the reserved {@code core.} namespace. */
  public static boolean isCore(TrustTier tier) {
    return Objects.requireNonNull(tier, "tier") == TrustTier.CORE;
  }

  /**
   * Boundary substrate: can the runtime provide the isolation {@code tier} requires (tempdoc 560 §5;
   * P2)? V1 has no SANDBOXED runtime, so {@link TrustTier#UNTRUSTED_PLUGIN} is inadmissible until one
   * exists — the composer refuses it rather than silently downgrading.
   */
  public static boolean boundaryAdmissible(TrustTier tier) {
    return IsolationPolicy.isAdmissible(Objects.requireNonNull(tier, "tier"));
  }

  /** Human-readable detail for a boundary refusal — the isolation level the tier would require. */
  public static String boundaryDetail(TrustTier tier) {
    return "requires " + IsolationPolicy.requiredFor(Objects.requireNonNull(tier, "tier"));
  }
}
