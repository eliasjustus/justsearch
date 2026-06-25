// SPDX-License-Identifier: Apache-2.0
/**
 * Profile activation diff — Tempdoc 543 §14.3 δ4 / §25.δ4.
 *
 * "What would happen if I activated this profile?" — without actually
 * activating. Returns a typed diff:
 *   - manifestsToInstall: manifests that would install (currently not
 *     installed, are profile-scoped, are in the target profile's set)
 *   - manifestsToUninstall: manifests that would uninstall (currently
 *     installed, are profile-scoped, not in the target profile's set)
 *   - manifestsUnchanged: profile-scoped manifests in both sets
 *   - scopeDelta: keys whose value differs between the active scope
 *     and the target profile's scope
 *
 * Pure: no side effects. Read-only against the live profile registry +
 * installed manifests + scope state. UI consumers (a confirmation
 * dialog) render this before activation; the activation itself remains
 * the existing activateProfile() call.
 */

import {
  listInstalledManifests,
  type ContributionManifest,
} from '../manifest/index.js';
import {
  getProfile,
  resolveEffectiveProfile,
} from './index.js';
import { getShellContext } from '../../state/shellContextState.js';

export interface ProfileActivationDiff {
  readonly targetProfileId: string;
  readonly targetProfileExists: boolean;
  readonly manifestsToInstall: ReadonlyArray<string>;
  readonly manifestsToUninstall: ReadonlyArray<string>;
  readonly manifestsUnchanged: ReadonlyArray<string>;
  readonly scopeDelta: ReadonlyArray<{
    readonly key: string;
    readonly before: unknown;
    readonly after: unknown;
  }>;
}

/**
 * Compute the diff WITHOUT activating the profile. Safe to call
 * repeatedly; no mutations.
 */
export function diffProfileActivation(
  targetProfileId: string,
): ProfileActivationDiff {
  const profile = getProfile(targetProfileId);
  if (!profile) {
    return {
      targetProfileId,
      targetProfileExists: false,
      manifestsToInstall: [],
      manifestsToUninstall: [],
      manifestsUnchanged: [],
      scopeDelta: [],
    };
  }
  const eff = resolveEffectiveProfile(targetProfileId);
  const targetSet = new Set(eff?.enabledManifestIds ?? []);
  const installed = listInstalledManifests();
  const installedScoped: ContributionManifest[] = installed.filter(
    (m) => (m.profileBinding ?? 'global') === 'profile-scoped',
  );
  const installedIds = new Set(installedScoped.map((m) => m.id));

  const toInstall: string[] = [];
  for (const id of targetSet) {
    if (!installedIds.has(id)) toInstall.push(id);
  }
  const toUninstall: string[] = [];
  for (const m of installedScoped) {
    if (!targetSet.has(m.id)) toUninstall.push(m.id);
  }
  const unchanged: string[] = [];
  for (const m of installedScoped) {
    if (targetSet.has(m.id)) unchanged.push(m.id);
  }

  // Scope delta: enumerate keys in the target profile's scope and
  // compare to the live ShellContext.
  const scopeDelta: Array<{
    readonly key: string;
    readonly before: unknown;
    readonly after: unknown;
  }> = [];
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  const targetScope = (eff?.scope ?? {}) as unknown as Record<string, unknown>;
  for (const key of Object.keys(targetScope)) {
    if (targetScope[key] !== ctx[key]) {
      scopeDelta.push({
        key,
        before: ctx[key],
        after: targetScope[key],
      });
    }
  }

  return {
    targetProfileId,
    targetProfileExists: true,
    manifestsToInstall: toInstall,
    manifestsToUninstall: toUninstall,
    manifestsUnchanged: unchanged,
    scopeDelta,
  };
}
