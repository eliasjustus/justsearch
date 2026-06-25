// @vitest-environment happy-dom
//
// Tempdoc 548 §4.4a (S1) — per-version persistence SNAPSHOT tests.
//
// The migration ladder is path-dependent: a current build must still deserialize
// documents written in OLD on-disk versions. These tests pin a FULL literal
// on-disk body per persisted version — every optional slice populated — and assert
// the loaded view preserves every field (and round-trips). A future migration edit
// that drops a slice from real old on-disk data fails here. This is the per-version
// closure the design (§4.4) names for the F9/F11/F14/F18 persistence-loss class —
// the right shape for a path-dependent ladder (vs. projecting the schema from the
// in-memory signal graph, which the confidence pass refuted).

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  getDocument,
  getProfile,
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  __DOCUMENT_STORAGE_KEY,
  __DOCUMENT_STORAGE_KEY_V1,
  DEFAULT_PROFILE_ID,
} from './UserStateDocument.js';

function loadFrom(key: string, body: unknown) {
  localStorage.setItem(key, JSON.stringify(body));
  __resetInMemoryStateForTest(); // drop in-memory; next read re-parses localStorage
  return getDocument();
}

describe('UserStateDocument — per-version persistence snapshots (S1, §4.4a)', () => {
  beforeEach(() => __resetUserStateForTest());
  afterEach(() => __resetUserStateForTest());

  // A FULL v1-consolidated (legacy) on-disk body: every optional slice populated.
  const V1_FULL = {
    version: 1,
    userConfig: { version: 1 },
    activeThemeId: 'core.dark',
    pinnedSearches: [{ id: 'p1', query: 'kubernetes', pinnedAt: 111, runs: [] }],
    keybindingOverrides: [{ key: 'ctrl+k', commandId: 'shell.palette', source: 'user' }],
    savedViews: [
      { id: 'sv1', label: 'My View', url: 'justsearch://search?q=x', surfaceId: 'core.search', savedAt: 222 },
    ],
    viewerAudience: 'OPERATOR',
    acknowledgedAdvisories: ['adv-1'],
    pluginSettings: { acme: { configured: true } },
    recentCommandIds: ['shell.palette', 'shell.go-to-search'],
  };

  it('v1-legacy on-disk body migrates to v2 with EVERY slice preserved', () => {
    const doc = loadFrom(__DOCUMENT_STORAGE_KEY_V1, V1_FULL);
    expect(doc.version).toBe(2);
    expect(doc.activeProfileId).toBe(DEFAULT_PROFILE_ID);
    // Per-profile slices lifted into the default profile + flattened on the view.
    expect(doc.activeThemeId).toBe('core.dark');
    expect(doc.pinnedSearches.map((p) => p.query)).toEqual(['kubernetes']);
    expect(doc.keybindingOverrides?.map((k) => k.commandId)).toEqual(['shell.palette']);
    expect(doc.savedViews?.map((v) => v.url)).toEqual(['justsearch://search?q=x']);
    expect(doc.viewerAudience).toBe('OPERATOR');
    // Cross-profile slices preserved at the top level.
    expect(doc.acknowledgedAdvisories).toEqual(['adv-1']);
    expect(doc.pluginSettings).toEqual({ acme: { configured: true } });
    expect(doc.recentCommandIds).toEqual(['shell.palette', 'shell.go-to-search']);
    // The lifted profile itself carries the per-profile slices.
    const prof = getProfile(DEFAULT_PROFILE_ID);
    expect(prof?.activeThemeId).toBe('core.dark');
    expect(prof?.savedViews?.map((v) => v.url)).toEqual(['justsearch://search?q=x']);
  });

  it('v1-legacy migration round-trips: the persisted v2 re-reads with everything intact', () => {
    loadFrom(__DOCUMENT_STORAGE_KEY_V1, V1_FULL); // migration persists a v2 body
    __resetInMemoryStateForTest();
    const doc = getDocument(); // re-read the persisted v2
    expect(doc.version).toBe(2);
    expect(doc.activeThemeId).toBe('core.dark');
    expect(doc.savedViews?.map((v) => v.url)).toEqual(['justsearch://search?q=x']);
    expect(doc.viewerAudience).toBe('OPERATOR');
    expect(doc.acknowledgedAdvisories).toEqual(['adv-1']);
    expect(doc.recentCommandIds).toEqual(['shell.palette', 'shell.go-to-search']);
  });

  // A FULL v2 on-disk body: two profiles + every cross-profile slice.
  const V2_FULL = {
    version: 2,
    activeProfileId: 'default',
    profiles: {
      default: {
        id: 'default', label: 'Default',
        userConfig: { version: 1 }, activeThemeId: 'core.light',
        pinnedSearches: [{ id: 'pd', query: 'alpha', pinnedAt: 1, runs: [] }],
        keybindingOverrides: [{ key: 'ctrl+p', commandId: 'shell.palette', source: 'user' }],
        savedViews: [{ id: 'svd', label: 'D', url: 'justsearch://search?q=a', surfaceId: 'core.search', savedAt: 1 }],
        viewerAudience: 'USER',
      },
      ops: {
        id: 'ops', label: 'Ops',
        userConfig: { version: 1 }, activeThemeId: 'core.dark',
        pinnedSearches: [], viewerAudience: 'OPERATOR',
      },
    },
    acknowledgedAdvisories: ['x'],
    pluginSettings: { acme: { k: 1 } },
    recentCommandIds: ['c1'],
    walkthroughState: { welcome: { activeStepIndex: 2, completedStepIds: ['s1'], dismissed: false } },
  };

  it('v2 on-disk body loads with all profiles + cross-profile slices preserved', () => {
    const doc = loadFrom(__DOCUMENT_STORAGE_KEY, V2_FULL);
    expect(doc.version).toBe(2);
    expect(Object.keys(doc.profiles).sort()).toEqual(['default', 'ops']);
    expect(doc.activeThemeId).toBe('core.light'); // active profile = default
    expect(getProfile('ops')?.viewerAudience).toBe('OPERATOR');
    expect(getProfile('ops')?.activeThemeId).toBe('core.dark');
    expect(doc.acknowledgedAdvisories).toEqual(['x']);
    expect(doc.pluginSettings).toEqual({ acme: { k: 1 } });
    expect(doc.recentCommandIds).toEqual(['c1']);
    expect(doc.walkthroughState?.welcome?.activeStepIndex).toBe(2);
  });
});
