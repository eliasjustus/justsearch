// @vitest-environment happy-dom

/**
 * Regression test for F11 (static-analysis finding, 2026-05-25).
 *
 * CLAIM UNDER TEST: `setActiveProfileId` must rebind ALL per-profile
 * slices from the target profile, so switching to profile B yields
 * B's stored state — never the previously-active profile's.
 *
 * DEFECT: `setActiveProfileId` (UserStateDocument.ts) rebinds only
 * `userConfig`, `activeThemeId`, `pinnedSearches`, and
 * `keybindingOverrides` from the target profile. It does NOT rebind
 * `savedViews` or `viewerAudience` — those carry over from the
 * previously-active profile via the `...doc` spread. Because
 * `mutateDocument` then re-keys the active profile from the flat
 * fields (`storageFromView`, flat-fields-win), the stale values are
 * persisted ONTO the target profile, durably corrupting it.
 *
 * Effect: switching A -> B overwrites B's saved-view bookmarks and
 * view-audience preference with A's, on disk.
 *
 * Note on severity: `viewerAudience` is a VIEW PREFERENCE, not an
 * access-control boundary (see viewerAudienceState.ts header) — so
 * this is data-correctness / UX corruption (lost bookmarks, surprise
 * audience tier), not a privilege issue.
 *
 * EXPECTED STATUS: the first two tests FAIL against current code —
 * that failure IS the proof of the bug. They go green when
 * `setActiveProfileId` is fixed to rebind `savedViews` +
 * `viewerAudience` from the target profile. The third test (a
 * positive control on `pinnedSearches`, which IS rebound) passes
 * today and exists to prove the harness is sound and the defect is
 * specific to the two un-rebound fields.
 *
 * Isolation: the leak lives entirely in `setActiveProfileId`, so we
 * seed two fully-formed profiles directly into localStorage and
 * perform exactly ONE switch. Each assertion then passes or fails
 * for a single, unambiguous reason.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  __DOCUMENT_STORAGE_KEY,
  getActiveProfileId,
  setActiveProfileId,
  getProfile,
} from './UserStateDocument.js';
import { getSavedViews } from './savedViewState.js';
import { getViewerAudience } from './viewerAudienceState.js';

/**
 * Seed a v2 document with two profiles ('default' active, plus 'b'),
 * each carrying DISTINCT savedViews + viewerAudience + pinnedSearches,
 * then drop in-memory state so the next read re-parses the seed.
 */
function seedTwoProfilesAndReload(): void {
  const seed = {
    version: 2,
    activeProfileId: 'default',
    profiles: {
      default: {
        id: 'default',
        label: 'Default',
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [
          { id: 'pin-a', query: 'alpha', pinnedAt: 1, runs: [] },
        ],
        savedViews: [
          {
            id: 'sv-a',
            label: 'A bookmark',
            url: 'justsearch://search?q=alpha',
            surfaceId: 'core.search',
            savedAt: 1,
          },
        ],
        viewerAudience: 'DEVELOPER',
      },
      b: {
        id: 'b',
        label: 'B',
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [
          { id: 'pin-b', query: 'bravo', pinnedAt: 2, runs: [] },
        ],
        savedViews: [
          {
            id: 'sv-b',
            label: 'B bookmark',
            url: 'justsearch://search?q=bravo',
            surfaceId: 'core.search',
            savedAt: 2,
          },
        ],
        viewerAudience: 'OPERATOR',
      },
    },
  };
  localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(seed));
  // Force the next read to re-parse the seeded localStorage body
  // (clears the in-memory DEFAULT_DOCUMENT + initialized flag).
  __resetInMemoryStateForTest();
}

describe('UserStateDocument — setActiveProfileId per-profile rebind (F11)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    seedTwoProfilesAndReload();
    // Sanity: the seed loaded and 'default' is active with its values.
    expect(getActiveProfileId()).toBe('default');
    expect(getSavedViews().map((v) => v.url)).toEqual([
      'justsearch://search?q=alpha',
    ]);
    expect(getViewerAudience()).toBe('DEVELOPER');
  });

  it('switching default -> b rebinds viewerAudience from the target profile', () => {
    setActiveProfileId('b');
    // Active view must reflect B's stored audience, not default's.
    expect(getViewerAudience()).toBe('OPERATOR');
    // B's persisted profile must not have been clobbered with A's value.
    expect(getProfile('b')?.viewerAudience).toBe('OPERATOR');
  });

  it('switching default -> b rebinds savedViews from the target profile', () => {
    setActiveProfileId('b');
    // Active view must reflect B's stored bookmarks, not default's.
    expect(getSavedViews().map((v) => v.url)).toEqual([
      'justsearch://search?q=bravo',
    ]);
    // B's persisted profile must not have been clobbered with A's bookmarks.
    expect(getProfile('b')?.savedViews?.map((v) => v.url)).toEqual([
      'justsearch://search?q=bravo',
    ]);
  });

  it('the corruption is durable: B\'s values survive a reload after the switch', () => {
    setActiveProfileId('b');
    // Simulate a page reload: drop in-memory state, re-read what the
    // switch persisted to localStorage.
    __resetInMemoryStateForTest();

    expect(getActiveProfileId()).toBe('b');
    expect(getViewerAudience()).toBe('OPERATOR');
    expect(getSavedViews().map((v) => v.url)).toEqual([
      'justsearch://search?q=bravo',
    ]);
  });

  it('positive control: pinnedSearches (which IS rebound) switches correctly', () => {
    // This passes today. It proves the harness + seed are sound, so a
    // failure of the two tests above is the savedViews/viewerAudience
    // omission specifically — not a broken setup.
    setActiveProfileId('b');
    expect(getProfile('b')?.pinnedSearches.map((p) => p.query)).toEqual([
      'bravo',
    ]);
  });
});

describe('UserStateDocument — profile switch is a non-destructive pointer op (548 §4.4)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    seedTwoProfilesAndReload();
  });

  it('a switch CYCLE default -> b -> default preserves BOTH profiles\' own slices', () => {
    // 548 §4.4: the switch is a pure pointer change on the storage authority;
    // the flat view is a `computed` projection. There is no flat-view spread on
    // the switch path, so the outgoing profile's slices cannot bleed into the
    // incoming one in EITHER direction. Under the old `flatSlicesFromProfile`
    // spread + `storageFromView` re-key, the return leg (b -> default) would
    // have re-keyed 'default' from b's flat view — the F11 mechanism. This
    // test fails the moment that mechanism returns.
    setActiveProfileId('b');
    expect(getViewerAudience()).toBe('OPERATOR');
    expect(getSavedViews().map((v) => v.url)).toEqual(['justsearch://search?q=bravo']);

    setActiveProfileId('default');
    // The return leg must show default's OWN slices, untouched by b.
    expect(getViewerAudience()).toBe('DEVELOPER');
    expect(getSavedViews().map((v) => v.url)).toEqual(['justsearch://search?q=alpha']);

    // Both stored profiles remain intact — neither was re-keyed from the
    // other's flat view (rebind-drift is now structurally unrepresentable).
    expect(getProfile('default')?.viewerAudience).toBe('DEVELOPER');
    expect(getProfile('b')?.viewerAudience).toBe('OPERATOR');
    expect(getProfile('default')?.savedViews?.map((v) => v.url)).toEqual([
      'justsearch://search?q=alpha',
    ]);
    expect(getProfile('b')?.savedViews?.map((v) => v.url)).toEqual([
      'justsearch://search?q=bravo',
    ]);
  });
});
