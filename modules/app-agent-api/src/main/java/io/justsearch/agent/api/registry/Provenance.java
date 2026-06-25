/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Origin metadata for an Operation/Resource/Prompt entry.
 *
 * <p>Per tempdoc 429 §"Type-system invariants" + §A.5: provenance describes WHO declared
 * the entry; the trust-tier-aware {@link OperationHandler} executor branches on
 * {@link TrustTier} for dispatch (CORE / TRUSTED_PLUGIN equivalent in V1 / UNTRUSTED_PLUGIN
 * throws).
 *
 * <p>{@code contributorId} for CORE entries is always {@code "core"}; for plugins it's
 * the plugin's manifest id.
 */
public record Provenance(
    TrustTier tier, String contributorId, String version, @Nullable PluginIdentity identity)
    implements PreciseWire {

  public Provenance {
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(contributorId, "contributorId");
    Objects.requireNonNull(version, "version");
    if (contributorId.isBlank()) {
      throw new IllegalArgumentException("contributorId must be non-blank");
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("version must be non-blank");
    }
    // `identity` is intentionally nullable: V1.5.1 plugins predate the wire field and
    // arrive with identity == null. The FE's resolveProvenance fallback stamps a
    // placeholder when null is observed.
  }

  /**
   * Backward-compat 3-arg constructor — Tempdoc 543 §22.7 follow-up adds the optional
   * {@code identity} field per the §13.2.3.1 multi-axis Provenance extension. Pre-V1.5.2
   * callers that don't carry identity continue to compile via this delegation.
   */
  public Provenance(TrustTier tier, String contributorId, String version) {
    this(tier, contributorId, version, null);
  }

  /** Standard CORE provenance for entries declared in JustSearch's own code. */
  public static Provenance core(String version) {
    return new Provenance(TrustTier.CORE, "core", version, PluginIdentity.verifiedCore());
  }
}
