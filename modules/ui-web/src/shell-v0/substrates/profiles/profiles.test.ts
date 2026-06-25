// @vitest-environment happy-dom

/**
 * Workspace Profiles substrate unit tests — Tempdoc 543 §13.2.3 + §13.6.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  saveProfile,
  deleteProfile,
  duplicateProfile,
  getProfile,
  listProfiles,
  getActiveProfileId,
  subscribeProfiles,
  activateProfile,
  resolveEffectiveProfile,
  createProfileFromCurrent,
  registerProfileScopedManifestFactory,
  __resetProfilesForTest,
  restoreProfilesFromStorage,
  type WorkspaceProfile,
} from './index.js';
import { __resetManifestRegistryForTest, installContributionManifest, listInstalledManifests } from '../manifest/index.js';
import { __resetActionsForTest } from '../actions/index.js';
import { __resetShellContextForTest, updateShellContext, getShellContext } from '../../state/shellContextState.js';
import { makeCoreProvenance } from '../../primitives/provenance.js';

beforeEach(async () => {
  await __resetProfilesForTest();
  await __resetManifestRegistryForTest();
  __resetActionsForTest();
  __resetShellContextForTest();
});

const STUB_PROFILE = (
  overrides: Partial<WorkspaceProfile> = {},
): WorkspaceProfile => ({
  id: 'p1',
  label: 'Profile 1',
  enabledManifestIds: [],
  scope: {},
  createdAt: '2026-01-01T00:00:00Z',
  ...overrides,
});

describe('Profile registry (§13.6)', () => {
  it('save/get/delete round-trip', () => {
    saveProfile(STUB_PROFILE());
    expect(getProfile('p1')?.label).toBe('Profile 1');
    expect(deleteProfile('p1')).toBe(true);
    expect(getProfile('p1')).toBeUndefined();
  });

  it('listProfiles sorted by createdAt asc', () => {
    saveProfile(STUB_PROFILE({ id: 'b', createdAt: '2026-02-01T00:00:00Z' }));
    saveProfile(STUB_PROFILE({ id: 'a', createdAt: '2026-01-01T00:00:00Z' }));
    expect(listProfiles().map((p) => p.id)).toEqual(['a', 'b']);
  });

  it('subscribeProfiles fires on save + delete', () => {
    const listener = vi.fn();
    subscribeProfiles(listener);
    saveProfile(STUB_PROFILE());
    deleteProfile('p1');
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('duplicateProfile copies under new id; updates createdAt', () => {
    saveProfile(STUB_PROFILE());
    const copy = duplicateProfile('p1', 'p2', 'Profile 2');
    expect(copy?.id).toBe('p2');
    expect(copy?.label).toBe('Profile 2');
    expect(copy?.createdAt).not.toBe('2026-01-01T00:00:00Z');
  });
});

describe('Activation (§13.6)', () => {
  it('restoreScope is applied to live ShellContext', async () => {
    saveProfile(STUB_PROFILE({ scope: { audience: 'DEVELOPER' } }));
    await activateProfile('p1');
    expect(getShellContext().audience).toBe('DEVELOPER');
    expect(getActiveProfileId()).toBe('p1');
  });

  it('uninstalls profile-scoped manifests not in target set', async () => {
    await installContributionManifest({
      id: 'scoped.a',
      version: '1',
      provenance: makeCoreProvenance(),
      profileBinding: 'profile-scoped',
      contributes: {},
    });
    saveProfile(STUB_PROFILE({ enabledManifestIds: [] }));
    await activateProfile('p1');
    expect(
      listInstalledManifests().find((m) => m.id === 'scoped.a'),
    ).toBeUndefined();
  });

  it('keeps global manifests across activation', async () => {
    await installContributionManifest({
      id: 'global.a',
      version: '1',
      provenance: makeCoreProvenance(),
      profileBinding: 'global',
      contributes: {},
    });
    saveProfile(STUB_PROFILE({ enabledManifestIds: [] }));
    await activateProfile('p1');
    expect(
      listInstalledManifests().find((m) => m.id === 'global.a'),
    ).toBeDefined();
  });

  it('re-installs target manifests via registered factory', async () => {
    const factory = vi.fn(() => ({
      id: 'factory.a',
      version: '1',
      provenance: makeCoreProvenance(),
      profileBinding: 'profile-scoped' as const,
      contributes: {},
    }));
    registerProfileScopedManifestFactory('factory.a', factory);
    saveProfile(STUB_PROFILE({ enabledManifestIds: ['factory.a'] }));
    await activateProfile('p1');
    expect(factory).toHaveBeenCalledTimes(1);
    expect(
      listInstalledManifests().find((m) => m.id === 'factory.a'),
    ).toBeDefined();
  });
});

describe('Inheritance (§13.6)', () => {
  it('child profile inherits parent enabledManifestIds (union)', () => {
    saveProfile(STUB_PROFILE({ id: 'parent', enabledManifestIds: ['a'] }));
    saveProfile(
      STUB_PROFILE({
        id: 'child',
        inheritsFrom: 'parent',
        enabledManifestIds: ['b'],
      }),
    );
    const eff = resolveEffectiveProfile('child');
    expect([...(eff?.enabledManifestIds ?? [])].sort()).toEqual(['a', 'b']);
  });

  it('child scope wins on key conflict (Scope merge)', () => {
    saveProfile(
      STUB_PROFILE({
        id: 'parent',
        scope: { audience: 'USER', activeCorpusId: 'corpus-parent' },
      }),
    );
    saveProfile(
      STUB_PROFILE({
        id: 'child',
        inheritsFrom: 'parent',
        scope: { audience: 'DEVELOPER' },
      }),
    );
    const eff = resolveEffectiveProfile('child');
    expect(eff?.scope.audience).toBe('DEVELOPER'); // child wins
    expect(eff?.scope.activeCorpusId).toBe('corpus-parent'); // parent preserved
  });

  it('cycle in inheritance → resolveEffectiveProfile returns null', () => {
    saveProfile(STUB_PROFILE({ id: 'a', inheritsFrom: 'b' }));
    saveProfile(STUB_PROFILE({ id: 'b', inheritsFrom: 'a' }));
    expect(resolveEffectiveProfile('a')).toBeNull();
  });

  it('missing parent → null', () => {
    saveProfile(STUB_PROFILE({ id: 'orphan', inheritsFrom: 'missing' }));
    expect(resolveEffectiveProfile('orphan')).toBeNull();
  });
});

describe('createProfileFromCurrent + persistence', () => {
  it('snapshots current Scope into the new profile', () => {
    updateShellContext({ audience: 'OPERATOR' });
    const p = createProfileFromCurrent('snap', 'Snapshot');
    expect(p.scope.audience).toBe('OPERATOR');
  });

  it('round-trips through localStorage', () => {
    saveProfile(STUB_PROFILE());
    // Reset only in-memory by re-importing module state via reset
    // helper that *clears* storage too — so manually pre-save raw,
    // wipe in-memory, then restore.
    const raw = localStorage.getItem(
      'justsearch.workspace-profiles.v1',
    );
    expect(raw).toBeTruthy();
    // Wipe in-memory (NOT clearing storage by hand):
    _resetInMemoryOnly();
    expect(listProfiles()).toHaveLength(0);
    restoreProfilesFromStorage();
    expect(listProfiles()).toHaveLength(1);
  });
});

// Helper: reset in-memory only (saves+restores storage payload).
function _resetInMemoryOnly(): void {
  const profilesRaw = localStorage.getItem(
    'justsearch.workspace-profiles.v1',
  );
  const activeRaw = localStorage.getItem('justsearch.active-profile.v1');
  // Use the test reset (clears in-memory + storage), then re-seed
  // storage so restoreProfilesFromStorage finds it.
  void __resetProfilesForTest();
  if (profilesRaw)
    localStorage.setItem(
      'justsearch.workspace-profiles.v1',
      profilesRaw,
    );
  if (activeRaw)
    localStorage.setItem('justsearch.active-profile.v1', activeRaw);
}
