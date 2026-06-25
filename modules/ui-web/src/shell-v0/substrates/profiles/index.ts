// SPDX-License-Identifier: Apache-2.0
/**
 * Workspace Profiles substrate — Tempdoc 543 §13.2.3 + §13.6.
 *
 * A Workspace Profile is the §13.2.3 design's "set of manifests +
 * Scope snapshot" data shape. Activation:
 *   1. apply the Profile's `enabledManifestIds` set — manifests
 *      outside this set with `profileBinding === 'profile-scoped'`
 *      are uninstalled; profile-scoped manifests in the set are
 *      installed via their registered factory (if not already).
 *   2. `restoreScope()` applies the persisted Scope snapshot.
 *
 * Profile inheritance: a Profile may have `inheritsFrom: string`
 * pointing to a parent Profile id. Activation walks the parent chain
 * accumulating manifest set + Scope snapshot, then layers the child's
 * overrides on top. Inheritance is set arithmetic (union of manifest
 * sets, Scope merge child-wins) — dissolves the VS Code flat-vs-
 * overlay debate.
 *
 * Persistence: profiles persist to localStorage. The active profile
 * id persists too — boot restores both via
 * `restoreProfilesFromStorage()`.
 *
 * KCS bridge per §19: future `useWorkspaceProfile()` capability
 * module.
 */

import type { ScopeSnapshot } from '../scope/index.js';
import { serializeScope, restoreScope } from '../scope/index.js';
// §25.α3 — defensive memo invalidation on profile activation. restoreScope
// triggers shellContextState which already invalidates the EvaluationContext
// memo via its subscriber, but a direct call here guarantees correctness
// even if the subscriber wiring is ever broken or reorganized.
import { bumpScopeVersion } from '../evaluationContext/index.js';
import {
  getProfileScopedManifestFactory,
  __resetProfileScopedManifestFactoriesForTest,
} from '../manifestFactoryRegistry.js';
import { safeLocalStorage } from '../../primitives/storage.js';
import { notifyAll } from '../../primitives/notify.js';
import {
  listInstalledManifests,
  installContributionManifest,
  uninstallContributionManifest,
  getInstalledManifest,
  type ContributionManifest,
} from '../manifest/index.js';

/**
 * A Workspace Profile. `enabledManifestIds` may include manifest ids
 * that aren't currently installed; activation re-installs them via
 * a registered factory (see `registerProfileScopedManifestFactory`).
 * `inheritsFrom` walks a parent chain at activation time.
 */
export interface WorkspaceProfile {
  readonly id: string;
  readonly label: string;
  readonly inheritsFrom?: string;
  /** Manifest contributor-ids enabled in this profile. */
  readonly enabledManifestIds: readonly string[];
  /** Persistent Scope subset (audience, corpus, model, ...). */
  readonly scope: ScopeSnapshot;
  /** When this profile was created (ISO-8601). */
  readonly createdAt: string;
  /** Free-form description shown in UX. */
  readonly description?: string;
}

const PROFILES_KEY = 'justsearch.workspace-profiles.v1';
const ACTIVE_PROFILE_KEY = 'justsearch.active-profile.v1';

const _profiles = new Map<string, WorkspaceProfile>();
const _listeners = new Set<() => void>();
let _activeProfileId: string | null = null;
let _restored = false;

// ============================================================
// Persistence
// ============================================================

// §25.α4 — shared primitive (was duplicated in effects/index.ts).
function writeProfiles(): void {
  const storage = safeLocalStorage();
  if (!storage) return;
  try {
    storage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        version: 1,
        profiles: Array.from(_profiles.values()),
      }),
    );
    if (_activeProfileId !== null) {
      storage.setItem(ACTIVE_PROFILE_KEY, _activeProfileId);
    } else {
      storage.removeItem(ACTIVE_PROFILE_KEY);
    }
  } catch {
    /* swallow */
  }
}

/** Restore profiles + active-profile id from localStorage. Idempotent. */
export function restoreProfilesFromStorage(): void {
  if (_restored) return;
  _restored = true;
  const storage = safeLocalStorage();
  if (!storage) return;
  try {
    const raw = storage.getItem(PROFILES_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as {
        version: number;
        profiles: WorkspaceProfile[];
      };
      if (parsed?.version === 1 && Array.isArray(parsed.profiles)) {
        for (const p of parsed.profiles) {
          if (typeof p.id === 'string') _profiles.set(p.id, p);
        }
      }
    }
    const activeRaw = storage.getItem(ACTIVE_PROFILE_KEY);
    if (typeof activeRaw === 'string' && activeRaw.length > 0) {
      _activeProfileId = activeRaw;
    }
  } catch {
    /* swallow corrupt storage */
  }
}

// ============================================================
// Profile registry
// ============================================================

function notify(): void {
  notifyAll(_listeners);
}

/** Create or replace a Workspace Profile. */
export function saveProfile(profile: WorkspaceProfile): void {
  _profiles.set(profile.id, profile);
  writeProfiles();
  notify();
}

/** Remove a profile. If it's active, active becomes null. */
export function deleteProfile(id: string): boolean {
  const had = _profiles.delete(id);
  if (had && _activeProfileId === id) {
    _activeProfileId = null;
  }
  if (had) {
    writeProfiles();
    notify();
  }
  return had;
}

/** Snapshot current Scope + profile-scoped manifests into a new Profile. */
export function createProfileFromCurrent(
  id: string,
  label: string,
  options?: { description?: string; inheritsFrom?: string },
): WorkspaceProfile {
  const installed = listInstalledManifests();
  const enabledManifestIds = installed
    .filter((m) => (m.profileBinding ?? 'global') === 'profile-scoped')
    .map((m) => m.id);
  const profile: WorkspaceProfile = {
    id,
    label,
    enabledManifestIds,
    scope: serializeScope(),
    createdAt: new Date().toISOString(),
    ...(options?.description !== undefined
      ? { description: options.description }
      : {}),
    ...(options?.inheritsFrom !== undefined
      ? { inheritsFrom: options.inheritsFrom }
      : {}),
  };
  saveProfile(profile);
  return profile;
}

/** Duplicate a profile under a new id + label. */
export function duplicateProfile(
  sourceId: string,
  newId: string,
  newLabel: string,
): WorkspaceProfile | null {
  const src = _profiles.get(sourceId);
  if (!src) return null;
  const copy: WorkspaceProfile = {
    ...src,
    id: newId,
    label: newLabel,
    createdAt: new Date().toISOString(),
  };
  saveProfile(copy);
  return copy;
}

/** Get a profile by id. */
export function getProfile(id: string): WorkspaceProfile | undefined {
  return _profiles.get(id);
}

/** List all profiles sorted by createdAt asc. */
export function listProfiles(): readonly WorkspaceProfile[] {
  return Array.from(_profiles.values()).sort((a, b) =>
    a.createdAt.localeCompare(b.createdAt),
  );
}

/** Current active profile id (or null). */
export function getActiveProfileId(): string | null {
  return _activeProfileId;
}

/** Subscribe to profile-registry or active-profile changes. */
export function subscribeProfiles(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

// ============================================================
// Inheritance resolution
// ============================================================

/**
 * Resolve the effective profile (parent chain walked, child wins on
 * key collisions). Returns null if the profile or any ancestor is
 * missing, or if the chain has a cycle.
 */
export function resolveEffectiveProfile(
  id: string,
): WorkspaceProfile | null {
  const visited = new Set<string>();
  const chain: WorkspaceProfile[] = [];
  let cursor: string | undefined = id;
  while (cursor !== undefined) {
    if (visited.has(cursor)) return null; // cycle
    visited.add(cursor);
    const p = _profiles.get(cursor);
    if (!p) return null;
    chain.unshift(p); // parent-first
    cursor = p.inheritsFrom;
  }
  if (chain.length === 0) return null;
  const effectiveIds = new Set<string>();
  let mergedScope: ScopeSnapshot = {};
  for (const p of chain) {
    for (const mid of p.enabledManifestIds) effectiveIds.add(mid);
    mergedScope = { ...mergedScope, ...p.scope };
  }
  const tip = chain[chain.length - 1]!;
  return {
    id: tip.id,
    label: tip.label,
    enabledManifestIds: Array.from(effectiveIds),
    scope: mergedScope,
    createdAt: tip.createdAt,
    ...(tip.inheritsFrom !== undefined
      ? { inheritsFrom: tip.inheritsFrom }
      : {}),
    ...(tip.description !== undefined
      ? { description: tip.description }
      : {}),
  };
}

// ============================================================
// Activation
// ============================================================

/**
 * Activate a Workspace Profile. Resolves inheritance, then:
 *   1. Computes set-difference between installed profile-scoped
 *      manifests and the target set.
 *   2. Uninstalls profile-scoped manifests not in the target.
 *   3. Installs target manifests via registered factory (if a
 *      factory is registered AND the manifest isn't already
 *      installed).
 *   4. Calls `restoreScope(profile.scope)`.
 *   5. Sets the active profile id + persists.
 *   6. Notifies subscribers.
 *
 * NOTE: profile-scoped manifests must register a factory via
 * `registerProfileScopedManifestFactory` to be re-installable across
 * profile switches; manifest runtime code (handlers, lifecycle
 * hooks) can't be persisted to localStorage.
 */
export async function activateProfile(id: string): Promise<void> {
  const eff = resolveEffectiveProfile(id);
  if (!eff) throw new Error(`Profile not found or cycle: ${id}`);
  const targetSet = new Set(eff.enabledManifestIds);
  const installed = listInstalledManifests();
  for (const m of installed) {
    if ((m.profileBinding ?? 'global') !== 'profile-scoped') continue;
    if (!targetSet.has(m.id)) {
      await uninstallContributionManifest(m.id);
    }
  }
  for (const mid of targetSet) {
    if (!getInstalledManifest(mid)) {
      const factory = getProfileScopedManifestFactory(mid);
      if (factory) {
        await installContributionManifest(factory() as ContributionManifest);
      }
    }
  }
  restoreScope(eff.scope);
  // §25.α3 — defensive bump: guarantees EvaluationContext memo invalidation
  // even if shellContextState's subscriber wiring is reorganized later.
  bumpScopeVersion();
  _activeProfileId = id;
  writeProfiles();
  notify();
}

// ============================================================
// Manifest factory registry
// ============================================================

// The profile-scoped manifest factory store moved to the leaf
// `manifestFactoryRegistry.ts` (cycle break, tempdoc 530 UI-cycle gate): the
// manifest substrate registers factories there synchronously at install time
// (previously a deferred dynamic import('../profiles') — the manifest→profiles
// cycle edge), and `activateProfile` (above) reads them via
// `getProfileScopedManifestFactory`. Re-exported here so existing importers
// (tests, manifest uninstall) keep their import path.
export {
  registerProfileScopedManifestFactory,
  unregisterProfileScopedManifestFactory,
} from '../manifestFactoryRegistry.js';

// ============================================================
// Test helpers
// ============================================================

export async function __resetProfilesForTest(): Promise<void> {
  const installed = listInstalledManifests();
  for (const m of installed) {
    if ((m.profileBinding ?? 'global') === 'profile-scoped') {
      await uninstallContributionManifest(m.id);
    }
  }
  _profiles.clear();
  _listeners.clear();
  __resetProfileScopedManifestFactoriesForTest();
  _activeProfileId = null;
  _restored = false;
  const storage = safeLocalStorage();
  storage?.removeItem(PROFILES_KEY);
  storage?.removeItem(ACTIVE_PROFILE_KEY);
}
