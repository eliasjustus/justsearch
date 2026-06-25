// SPDX-License-Identifier: Apache-2.0
/**
 * Provenance — uniform attribution model for contribution registries.
 *
 * Tempdoc 543 §3.A — every contribution to a contribution registry
 * carries a typed Provenance value (tier, contributorId, version).
 * This module re-exports the existing wire-typed `Provenance` from
 * `api/types/registry.ts` (which mirrors `Provenance.java`) and adds
 * helpers for the most common first-party-registration case.
 *
 * Why a re-export rather than a new type: 543 §12.2 confirmed
 * `Provenance` is a wire-shape mirroring backend Java. Adopting it
 * uniformly across contribution interfaces is a TS-side refactor;
 * the multi-axis extension fields (`identity` / `review` /
 * `capability` / `installedAt`) are TS-only optional fields populated
 * locally by the manifest installer + plugin trust handshake.
 *
 * Companion: per §19 KCS-bridge, this module's exports map to a
 * future `useProvenance(contributorId)` capability module under the
 * three-layer KCS direction. For now: module-level exports under
 * shell-v0/primitives/.
 */

export type { Provenance, ProvenanceTier } from '../../api/types/registry.js';

import type { Provenance } from '../../api/types/registry.js';

/**
 * Canonical provenance for first-party (core) registrations.
 * `version: '0'` is a sentinel meaning "compiled-in; no published
 * version applies." First-party callers use this; plugin
 * registrations construct their own Provenance with the plugin's
 * id + version at install time.
 *
 * Per §13.2.3.1: CORE is verified-by-construction (it's in-source);
 * review is implicit at every commit (no separate review pass);
 * capability is unrestricted (the kernel itself).
 */
export const CORE_PROVENANCE: Provenance = Object.freeze({
  tier: 'CORE',
  contributorId: 'core',
  version: '0',
  identity: Object.freeze({ verified: true }),
});

/**
 * Returns a fresh `CORE` Provenance object. Equivalent to
 * `CORE_PROVENANCE` for first-party-registration call sites; exists
 * so future per-feature versioning (e.g., a Settings feature
 * reporting its own version) can replace the sentinel without
 * affecting call sites.
 */
export function makeCoreProvenance(): Provenance {
  return CORE_PROVENANCE;
}

/**
 * Returns a Provenance for a plugin contribution.
 *
 * `tier` defaults to `'TRUSTED_PLUGIN'`; pass `'UNTRUSTED_PLUGIN'`
 * for plugins that haven't passed the trust handshake.
 *
 * Per §13.2.3.1 the multi-axis extension fields default to:
 *   - identity: unset (plugin trust handshake populates when run)
 *   - review: unset (marketplace populates after PR review)
 *   - capability: unset (manifest's `capabilities` array populates)
 *   - installedAt: §25.α7 — stamped at install site
 *     (`installContributionManifest` / `PluginRegistry.install`),
 *     NOT at this helper. The helper-side stamp was misplaced — a
 *     manifest manufactured in one place and installed in another
 *     produced a misleading "installedAt" reflecting the helper
 *     invocation, not the actual install. Callers that want the
 *     timestamp must pass it via `extras` or rely on the install
 *     site to stamp it.
 *
 * Callers can pass an `extras` object to override defaults — plugin
 * install paths populate the extras from the manifest + trust
 * handshake.
 */
export function makePluginProvenance(
  pluginId: string,
  version: string,
  tier: 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN' = 'TRUSTED_PLUGIN',
  extras?: Partial<Omit<Provenance, 'tier' | 'contributorId' | 'version'>>,
): Provenance {
  return Object.freeze({
    tier,
    contributorId: pluginId,
    version,
    ...(extras ?? {}),
  });
}

/**
 * §25.α7 — stamp `installedAt` on an existing Provenance at the moment
 * a manifest is installed. Returns a new frozen Provenance carrying the
 * input identity / review / capability / version unchanged plus a fresh
 * ISO timestamp. The Provenance is otherwise opaque to the substrate
 * so we don't lose information across the stamping.
 */
export function stampInstalledAt(p: Provenance): Provenance {
  return Object.freeze({
    ...p,
    installedAt: new Date().toISOString(),
  });
}

/**
 * Tempdoc 543 §13.2.3.1 — derived display tier. Collapses
 * `identity` / `review` / `capability` signals into a richer chip
 * value than the raw `tier`. Chrome code branches on this derived
 * value so future multi-signal rendering passes don't require
 * touching call sites.
 */
export function displayTier(
  p: Provenance,
): 'CORE' | 'VERIFIED' | 'TRUSTED' | 'UNTRUSTED' {
  if (p.tier === 'CORE') return 'CORE';
  if (p.tier === 'UNTRUSTED_PLUGIN') return 'UNTRUSTED';
  if (p.identity?.verified) return 'VERIFIED';
  return 'TRUSTED';
}

/**
 * Type guard — is the provenance non-CORE (i.e., contributed by
 * something other than first-party code)? Consumers use this to
 * decide whether to render an attribution chip in chrome.
 */
export function isNonCore(provenance: Provenance): boolean {
  return provenance.tier !== 'CORE';
}

// §21.A2 (D5) — `resolveProvenance` retired. With `provenance` now required
// on every contribution registry interface, callers read `.provenance`
// directly. The legacy `source` field is retained as a historical render
// tag (chrome may branch on it for non-attribution purposes) but no longer
// drives Provenance derivation.
