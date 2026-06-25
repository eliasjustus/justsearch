// SPDX-License-Identifier: Apache-2.0
/**
 * §4.1 altitude collapse — project a FE {@link PluginManifest} onto the unified
 * declaration model ({@link PluginDeclaration}), the FE side of "the PluginManifest
 * is projected onto the backend Plugin, one declaration model spanning
 * FE+Head+Worker+Brain" (tempdoc 560 §4.1).
 *
 * The backend {@code Plugin} (a {@code ConsumerDeclaring} over the shared
 * {@code Declaration} axes in {@code io.justsearch.agent.api.registry}) is the
 * authority for the shape; this is the FE manifest viewed AS that shape. The
 * projection is faithful — every axis comes from real data, none is invented:
 *
 *  - {@code id} / {@code presentation.label} / {@code provenance.version} come
 *    straight from the manifest;
 *  - {@code provenance.trustTier} is the FE's real trust signal — the explicit
 *    registration tier when the host knows it (compiled-in plugins register as
 *    {@code TRUSTED_PLUGIN}), otherwise the signature-presence verdict via
 *    {@link verifyPluginTrust} — the SAME three-value lattice the backend uses,
 *    one lattice across both processes (§4.3);
 *  - {@code audience} = {@code USER} with a {@code shell-host} consumer: a
 *    plugin's contributions are surfaces the shell renders into the human UI, so
 *    the live consumer is the shell host serving the {@code USER}.
 *
 * The FE runtime payload (the executable {@code register}/{@code activate} hooks,
 * {@code contractVersion}, {@code tagNamespace}) is per-kind and stays on the
 * manifest — it is NOT lifted into the shared declaration (per the AHA test §4.1
 * invokes).
 */

import { verifyPluginTrust, trustVerdictToTier } from './PluginTrust.js';
import type {
  PluginDeclaration,
  PluginManifest,
  SubstrateTrustTier,
} from './plugin-types.js';

/**
 * Project a plugin manifest onto the one declaration model.
 *
 * @param manifest the FE runtime manifest.
 * @param registrationTier the tier the host assigned at registration, when known
 *   (compiled-in core/first-party plugins). When omitted, the tier is derived
 *   from the manifest's signature-presence trust verdict.
 */
export function pluginDeclaration(
  manifest: PluginManifest,
  registrationTier?: SubstrateTrustTier,
): PluginDeclaration {
  const trustTier: SubstrateTrustTier =
    registrationTier ?? trustVerdictToTier(verifyPluginTrust(manifest));
  const dot = manifest.id.indexOf('.');
  const vendor = dot > 0 ? manifest.id.slice(0, dot) : manifest.id;
  return {
    id: manifest.id,
    presentation: { label: manifest.displayName },
    provenance: { trustTier, vendor, version: manifest.version },
    audience: 'USER',
    consumers: [{ consumerId: 'shell-host', audience: 'USER' }],
  };
}
