// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 468 — V1.5 alpha plugin trust verification (signature-presence stub).
 *
 * Per `slices/470-v1-5-user-ui-authorship-substrate.md` §10 Q5
 * (ratified 2026-05-07):
 *   - V1.5 alpha = signature-presence stub. Any plugin that declares
 *     a `signature` field is accepted; the value is not verified.
 *     A loader-side warning UX flags the unverified signature.
 *   - V1.5.1 = full Sigstore chain verification. Per 470 §B.B.5,
 *     the verification path is recommended to live BACKEND-SIDE
 *     (Java Sigstore client) rather than browser-side, with the
 *     verified result flowing through the existing capability
 *     handshake. V1.5.1 design: the FE loader sends the plugin
 *     bundle hash + signature to a `/api/plugins/verify` endpoint;
 *     the Java worker runs the chain validation; the FE acts on
 *     the boolean response.
 *
 * V1.5 alpha treats the signature as opaque: if present, the plugin
 * is `signed` (no chain check); if absent, the plugin is `local-dev`.
 * This is a deliberate weak guarantee — the loader DOES NOT block
 * unsigned plugins in V1.5 alpha. It exposes the trust state via
 * {@link verifyPluginTrust} so the host UI can decide what to do
 * (display warning chip, require user confirm, etc.).
 */

import type { PluginManifest } from './plugin-types.js';

/**
 * V1.5 trust verdict after running {@link verifyPluginTrust}.
 *  - `signed`: plugin manifest declares a non-empty `signature`.
 *    V1.5 alpha doesn't check the value; V1.5.1 will run a Sigstore
 *    chain check and downgrade to `unverified` on chain failure.
 *  - `unsigned`: plugin manifest has no `signature` field. Treated
 *    as `local-dev` by the host's tier-mapping logic.
 *  - `unverified`: V1.5.1 reserved value for "signature present but
 *    chain verification failed." NOT EMITTED IN V1.5 ALPHA.
 */
export type PluginTrustVerdict = 'signed' | 'unsigned' | 'unverified';

/**
 * Manifest extension declaring a Sigstore-style signature.
 * V1.5 alpha shape: signature is an opaque string; V1.5.1 will
 * formalize the bundle shape (artifact hash + transparency-log
 * entry + certificate chain).
 */
export interface PluginManifestWithSignature extends PluginManifest {
  /**
   * Sigstore signature (Cosign bundle JSON or equivalent).
   * V1.5 alpha: opaque string; presence-only check.
   * V1.5.1: full Sigstore bundle shape with chain verification
   * delegated to the backend Sigstore Java client.
   */
  signature?: string;
}

/**
 * Verify a plugin's trust state. V1.5 alpha = presence-only.
 *
 * @param manifest the plugin manifest (with optional `signature`).
 * @returns trust verdict per {@link PluginTrustVerdict}.
 */
export function verifyPluginTrust(
  manifest: PluginManifest,
): PluginTrustVerdict {
  const sig = (manifest as PluginManifestWithSignature).signature;
  if (typeof sig === 'string' && sig.length > 0) {
    return 'signed';
  }
  return 'unsigned';
}

/**
 * Map a trust verdict to the implied {@link PluginTrustTier} per
 * 470 §10 Q4 (Decision D-3 ratified 2026-05-07: 3-tier enum +
 * orthogonal provenance):
 *   - `signed` → TRUSTED_PLUGIN (host-endorsed; provenance =
 *     'signed-vendor')
 *   - `unsigned` → UNTRUSTED_PLUGIN (third-party; provenance =
 *     'local-dev' or 'user-installed' depending on origin)
 *   - `unverified` → UNTRUSTED_PLUGIN (V1.5.1 only; signature
 *     present but chain check failed)
 *
 * V1.5 alpha-specific note: this is the LOADER's perspective.
 * Compiled-in plugins (TRUSTED_PLUGIN at registration time per
 * `PluginRegistry.ts:227`) bypass this mapping — they're trusted
 * by virtue of being part of the bundled host code.
 */
export function trustVerdictToTier(
  verdict: PluginTrustVerdict,
): 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN' {
  return verdict === 'signed' ? 'TRUSTED_PLUGIN' : 'UNTRUSTED_PLUGIN';
}
