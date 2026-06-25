/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import java.time.Instant;
import java.util.Objects;

/**
 * Tempdoc 550 thesis IV — the ONE capability/grant primitive.
 *
 * <p>A {@code Grant} is a caveat-bearing authorization: an identity ({@link #grantId()}), a
 * {@link GrantScope} caveat (what it authorizes), an {@link #expiry()}, and whether it is
 * {@link #singleUse()}. The four authorization mechanisms in the substrate are positions on THIS
 * one model — not four independent systems:
 *
 * <ul>
 *   <li>the <b>consent capsule</b> = a single-use, short-TTL Grant with a {@link BoundAction} scope
 *       (operation + args hash) — the maximally-attenuated grant; the only realized Grant today
 *       (see {@code ConsentCapsuleService}, which mints/verifies via this type);
 *   <li>durable <b>"allow-always" consent</b> = a Grant with a {@link CapabilityFamily} scope,
 *       {@code singleUse=false}, and a long/absent expiry — a wider caveat set. The scope variant
 *       exists here; its issuance + gate consumer is a future slice (not built as a hollow);
 *   <li>the <b>autonomy dial</b> = the ISSUANCE POLICY ({@code IntentGateEvaluator}) that decides
 *       which grants auto-issue per (source tier × risk) — a policy, not a grant;
 *   <li>the <b>Global Hard Stop</b> = a GLOBAL REVOCATION, enforced at the gate: the lattice DENIES
 *       every {@code UNTRUSTED} dispatch when engaged, so no non-user grant is honored — a
 *       revocation policy, not a grant.
 * </ul>
 *
 * <p>Attenuation is <b>narrowing-only</b>: a holder may add caveats (shrink scope, shorten expiry),
 * never widen them — see {@link #attenuate(GrantScope, Instant)}. Delegation via chained MAC is the
 * model's documented extension axis; no delegator consumer exists yet, so it is intentionally not
 * implemented here (an unconsumed crypto surface would be a liability, not an asset).
 */
public record Grant(String grantId, GrantScope scope, Instant expiry, boolean singleUse) {

  public Grant {
    Objects.requireNonNull(grantId, "grantId");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(expiry, "expiry");
  }

  /** True if this grant has expired at {@code now}. */
  public boolean isExpired(Instant now) {
    return now.toEpochMilli() > expiry.toEpochMilli();
  }

  /**
   * Narrow this grant: the result authorizes a subset of what this grant did, never more. The new
   * scope must be {@link GrantScope#narrowerOrEqual(GrantScope) narrower-or-equal} and the new
   * expiry no later than the current one; otherwise an {@link IllegalArgumentException} is thrown
   * (attenuation cannot widen). The grant id is preserved (the same authority, further constrained).
   */
  public Grant attenuate(GrantScope narrowerScope, Instant earlierOrSameExpiry) {
    Objects.requireNonNull(narrowerScope, "narrowerScope");
    Objects.requireNonNull(earlierOrSameExpiry, "earlierOrSameExpiry");
    if (!narrowerScope.narrowerOrEqual(scope)) {
      throw new IllegalArgumentException("attenuation cannot widen scope");
    }
    if (earlierOrSameExpiry.toEpochMilli() > expiry.toEpochMilli()) {
      throw new IllegalArgumentException("attenuation cannot extend expiry");
    }
    return new Grant(grantId, narrowerScope, earlierOrSameExpiry, singleUse);
  }

  /** The caveat describing WHAT a grant authorizes. */
  public sealed interface GrantScope permits BoundAction, CapabilityFamily {

    /**
     * Does this scope authorize an invocation of {@code operationId} whose canonicalized arguments
     * hash to {@code argsHash}?
     */
    boolean authorizes(String operationId, String argsHash);

    /** True if {@code this} authorizes no more than {@code other} (the attenuation partial order). */
    boolean narrowerOrEqual(GrantScope other);
  }

  /** The maximally-attenuated scope: exactly one (operation, arguments) pair. The capsule's scope. */
  public record BoundAction(String operationId, String argsHash) implements GrantScope {

    public BoundAction {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(argsHash, "argsHash");
    }

    @Override
    public boolean authorizes(String op, String hash) {
      return operationId.equals(op) && argsHash.equals(hash);
    }

    @Override
    public boolean narrowerOrEqual(GrantScope other) {
      // A bound action is narrower-or-equal to: itself, and any family that would admit its op
      // (family membership is resolved by the durable-grant consumer; absent that catalog a bound
      // action is narrower-or-equal only to an equal bound action — the safe, conservative answer).
      return this.equals(other);
    }
  }

  /**
   * A wider scope: any invocation of operations in a named capability family — durable
   * "allow-always" consent. Args-independent (it authorizes a family, not an exact payload). The
   * future durable-grant consumer resolves family membership against the operation's declared
   * capability; the bare scope cannot decide that here, so {@link #authorizes} is conservative.
   */
  public record CapabilityFamily(String family) implements GrantScope {

    public CapabilityFamily {
      Objects.requireNonNull(family, "family");
    }

    @Override
    public boolean authorizes(String op, String hash) {
      // Conservative until a durable-grant consumer resolves family membership (op → capability):
      // the bare scope does not self-authorize a bound check. Fail-closed.
      return false;
    }

    @Override
    public boolean narrowerOrEqual(GrantScope other) {
      return this.equals(other);
    }
  }
}
