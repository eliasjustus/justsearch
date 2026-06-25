/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Identity signal for a contributor's Provenance — Tempdoc 543 §22.7 (the §13.2.3.1
 * multi-axis Provenance extension, wire-sourced).
 *
 * <p>Carries the backend's identity-verification verdict for a plugin contribution. Today
 * (V1.5.1) the FE stamps {@code identity.verified = true} for every TRUSTED_PLUGIN at
 * install time — a placeholder that anticipates this record. Once the V1.5.2 signing chain
 * lands, the backend will populate {@code verified} from real Sigstore validation and the
 * signature field will carry the verification artifact; the FE will receive both via the
 * wire instead of stamping locally.
 *
 * <p>Until V1.5.2 ships, this field is optional on Provenance and the FE retains its
 * placeholder stamping for back-compat with pre-V1.5.2 plugin installs.
 *
 * @param verified true iff the backend's identity validation accepted the plugin (signing
 *     chain verified, OR placeholder true for compiled-in CORE / TRUSTED_PLUGIN per V1.5.1)
 * @param signature optional verification artifact (Sigstore signature, public key id, etc.)
 *     — present when V1.5.2 signing chain stamps it; null otherwise
 */
public record PluginIdentity(boolean verified, @Nullable String signature) implements PreciseWire {

  /** Convenience constructor for the no-signature case. */
  public PluginIdentity(boolean verified) {
    this(verified, null);
  }

  /** Canonical "verified-by-construction" identity for CORE / compiled-in contributions. */
  public static PluginIdentity verifiedCore() {
    return new PluginIdentity(true, null);
  }
}
