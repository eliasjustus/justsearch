/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * The explicit trust → isolation mapping (tempdoc 560 §5; P2). Isolation is <em>proportional to
 * trust</em>: the host's own code runs in-process; a trusted contribution runs behind the cheapest
 * real boundary (a child process); an untrusted contribution requires a sandbox on top. A required
 * isolation that the runtime cannot yet provide means the contribution is <b>denied</b> — isolation
 * is never silently downgraded to fit (the inverse of "trust at consumption").
 *
 * <p>V1 reality: IN_PROCESS and OUT_OF_PROCESS exist; the SANDBOXED runtime (SES compartment /
 * resolver attenuation, §4.4) is not built, so an {@code UNTRUSTED_PLUGIN} is denied until it is —
 * the same posture the operation executor already takes ({@code UNTRUSTED_PLUGIN} dispatch throws).
 */
public final class IsolationPolicy {
  private IsolationPolicy() {}

  /** The isolation a contribution of the given trust tier MUST run under. */
  public static IsolationLevel requiredFor(TrustTier tier) {
    return switch (tier) {
      case CORE -> IsolationLevel.IN_PROCESS;
      case TRUSTED_PLUGIN -> IsolationLevel.OUT_OF_PROCESS;
      case UNTRUSTED_PLUGIN -> IsolationLevel.SANDBOXED;
    };
  }

  /** Whether the runtime can currently provide a given isolation level (V1: no SANDBOXED yet). */
  public static boolean isAvailable(IsolationLevel level) {
    return switch (level) {
      case IN_PROCESS, OUT_OF_PROCESS -> true;
      case SANDBOXED, DENIED -> false;
    };
  }

  /**
   * The effective isolation for a trust tier: its required level if available, else {@link
   * IsolationLevel#DENIED}. A {@code DENIED} result means the contribution must be refused.
   */
  public static IsolationLevel effectiveFor(TrustTier tier) {
    IsolationLevel required = requiredFor(tier);
    return isAvailable(required) ? required : IsolationLevel.DENIED;
  }

  /** True when a contribution of this trust tier can run under an available isolation. */
  public static boolean isAdmissible(TrustTier tier) {
    return effectiveFor(tier) != IsolationLevel.DENIED;
  }
}
