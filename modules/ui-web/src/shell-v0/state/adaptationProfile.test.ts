// @vitest-environment happy-dom

/**
 * 569 §19 Seam 4 — the one adaptation/accessibility authority: persist per-profile + project to global
 * DOM state, omitted axes untouched.
 *
 * Tempdoc 855 §17 R1 — the contrast axis is retired here (the canonical store is the backend
 * `UISettings.highContrast`, written by `themeState.applyAppearance`). The contrast assertions this
 * file used to make are PORTED, not deleted: the "projects contrast" ones become the migration
 * contract below, and the "does not fight the legacy appearance contrast" one becomes the stronger
 * claim that this module no longer touches the class at all.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  applyAdaptationProfile,
  getAdaptationProfile,
  migrateLegacyContrastPreference,
} from './adaptationProfile.js';
import { __resetUserStateForTest, getDocument, mutateDocument } from './UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
  document.documentElement.className = '';
});

describe('applyAdaptationProfile', () => {
  it('persists the axes per-profile and projects motion to a global class', () => {
    applyAdaptationProfile({ density: 'compact', motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({ density: 'compact', motion: 'reduced' });
    expect(getDocument().userConfig.density).toBe('compact'); // density threads via userConfig
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(true);
  });

  it('merges partial updates (omitted axes untouched) and toggles back off', () => {
    applyAdaptationProfile({ density: 'compact' });
    applyAdaptationProfile({ motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({ density: 'compact', motion: 'reduced' });
    applyAdaptationProfile({ motion: 'full' });
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(false);
    expect(getAdaptationProfile().density).toBe('compact'); // untouched
  });

  it('never writes the high-contrast class (that authority is applyAppearance alone)', () => {
    document.documentElement.classList.add('high-contrast'); // the appearance writer set it
    applyAdaptationProfile({ density: 'spacious', motion: 'reduced' });
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });
});

/** Seed the retired FE-local axis the way a pre-855 build persisted it. */
function seedLegacyContrast(value: 'normal' | 'high'): void {
  mutateDocument((doc) => ({
    ...doc,
    userConfig: {
      ...doc.userConfig,
      accessibilityProfile: { ...doc.userConfig.accessibilityProfile, contrast: value },
    },
  }));
}

const legacyContrast = (): 'normal' | 'high' | undefined =>
  getDocument().userConfig.accessibilityProfile?.contrast;

/** A fetch double: GET returns `canonical`, POST records the body and succeeds (unless `postOk`). */
function fetchDouble(canonical: boolean, postOk = true) {
  const posts: unknown[] = [];
  const impl = vi.fn((_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      posts.push(JSON.parse(String(init.body)));
      return Promise.resolve({ ok: postOk, json: () => Promise.resolve({}) } as Response);
    }
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ ui: { highContrast: canonical } }),
    } as Response);
  });
  return { impl: impl as unknown as typeof fetch, posts, calls: impl };
}

describe('855 §17 R1 — one-time contrast migration', () => {
  it('a disagreeing legacy value wins once: written through, projected, then cleared', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(false);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toEqual([{ ui: { highContrast: true } }]);
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    expect(legacyContrast()).toBeUndefined();
  });

  it('migrates a legacy OFF over a canonical ON just as faithfully', async () => {
    seedLegacyContrast('normal');
    document.documentElement.classList.add('high-contrast'); // restoreAppearanceOnBoot applied canonical
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toEqual([{ ui: { highContrast: false } }]);
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
    expect(legacyContrast()).toBeUndefined();
  });

  it('is idempotent — a second boot makes no request at all', async () => {
    seedLegacyContrast('high');
    const first = fetchDouble(false);
    await migrateLegacyContrastPreference(first.impl);
    const second = fetchDouble(false);
    await migrateLegacyContrastPreference(second.impl);
    expect(second.calls).not.toHaveBeenCalled();
    expect(second.posts).toEqual([]);
  });

  it('agreeing values write nothing (and still retire the axis)', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toEqual([]);
    expect(legacyContrast()).toBeUndefined();
  });

  it('an unset legacy axis is a no-op with no network call', async () => {
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.calls).not.toHaveBeenCalled();
    expect(legacyContrast()).toBeUndefined();
  });

  it('keeps the legacy value when the write-through fails, so nothing is silently lost', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(false, /* postOk */ false);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toEqual([{ ui: { highContrast: true } }]);
    expect(legacyContrast()).toBe('high'); // retried on the next boot
  });

  it('keeps the legacy value when the settings read fails (never guesses the canonical value)', async () => {
    seedLegacyContrast('high');
    const impl = vi.fn(() => Promise.reject(new Error('offline'))) as unknown as typeof fetch;
    await migrateLegacyContrastPreference(impl);
    expect(legacyContrast()).toBe('high');
  });
});
