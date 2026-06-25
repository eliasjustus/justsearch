// @vitest-environment happy-dom

/**
 * Tests for the slice 471 userConfig runtime singleton.
 *
 * Covers:
 *  - get/subscribe initial state
 *  - setSurfaceOverride round-trip + notify
 *  - clearSurfaceOverride no-op when missing + clear when present
 *  - clearAllSurfaceOverrides
 *  - subscribe receives initial value, unsubscribe stops notifications
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  __resetUserConfigForTest,
  clearAllLayoutOverrides,
  clearAllSurfaceOverrides,
  clearSurfaceOverride,
  clearSurfaceVisibility,
  getUserConfig,
  setSurfaceOrder,
  setSurfaceOverride,
  setSurfaceVisibility,
  subscribeUserConfig,
} from './userConfigState.js';

describe('userConfigState (slice 471)', () => {
  afterEach(() => {
    __resetUserConfigForTest();
  });

  it('initial state is V1 empty userConfig', () => {
    const cfg = getUserConfig();
    expect(cfg.version).toBe(1);
    expect(cfg.surfaceOverride).toBeUndefined();
  });

  it('setSurfaceOverride writes the override entry', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    const cfg = getUserConfig();
    expect(cfg.surfaceOverride).toEqual({
      'core.library-surface': 'acme.alt-library-surface',
    });
  });

  it('setSurfaceOverride preserves prior overrides', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceOverride('core.brain-surface', 'acme.alt-brain-surface');
    const cfg = getUserConfig();
    expect(cfg.surfaceOverride).toEqual({
      'core.library-surface': 'acme.alt-library-surface',
      'core.brain-surface': 'acme.alt-brain-surface',
    });
  });

  it('clearSurfaceOverride removes the entry', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceOverride('core.brain-surface', 'acme.alt-brain-surface');
    clearSurfaceOverride('core.library-surface');
    const cfg = getUserConfig();
    expect(cfg.surfaceOverride).toEqual({
      'core.brain-surface': 'acme.alt-brain-surface',
    });
  });

  it('clearSurfaceOverride collapses surfaceOverride to undefined when last entry removed', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    clearSurfaceOverride('core.library-surface');
    expect(getUserConfig().surfaceOverride).toBeUndefined();
  });

  it('clearSurfaceOverride is a no-op when no matching entry', () => {
    const before = getUserConfig();
    clearSurfaceOverride('core.unknown-surface');
    expect(getUserConfig()).toBe(before);
  });

  it('clearAllSurfaceOverrides removes all entries', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceOverride('core.brain-surface', 'acme.alt-brain-surface');
    clearAllSurfaceOverrides();
    expect(getUserConfig().surfaceOverride).toBeUndefined();
  });

  it('subscribe receives the initial value', () => {
    const listener = vi.fn();
    const unsub = subscribeUserConfig(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0]).toEqual({ version: 1 });
    unsub();
  });

  it('subscribe receives subsequent mutations', () => {
    const listener = vi.fn();
    const unsub = subscribeUserConfig(listener);
    listener.mockClear();
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0].surfaceOverride).toEqual({
      'core.library-surface': 'acme.alt-library-surface',
    });
    unsub();
  });

  it('unsubscribe stops further notifications', () => {
    const listener = vi.fn();
    const unsub = subscribeUserConfig(listener);
    unsub();
    listener.mockClear();
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    expect(listener).not.toHaveBeenCalled();
  });

  it('listener errors do not break subsequent listeners', () => {
    const failing = vi.fn().mockImplementation(() => {
      throw new Error('boom');
    });
    const ok = vi.fn();
    subscribeUserConfig(failing);
    subscribeUserConfig(ok);
    failing.mockClear();
    ok.mockClear();
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    expect(failing).toHaveBeenCalledTimes(1);
    expect(ok).toHaveBeenCalledTimes(1);
  });
});

describe('userConfigState — slice 472 layout authorship', () => {
  afterEach(() => {
    __resetUserConfigForTest();
  });

  it('setSurfaceVisibility writes the visibility entry', () => {
    setSurfaceVisibility('core.help-surface', false);
    expect(getUserConfig().surfaceVisibility).toEqual({
      'core.help-surface': false,
    });
  });

  it('setSurfaceVisibility(true) is recorded explicitly (distinguishable from absence)', () => {
    setSurfaceVisibility('core.help-surface', false);
    setSurfaceVisibility('core.help-surface', true);
    expect(getUserConfig().surfaceVisibility).toEqual({
      'core.help-surface': true,
    });
  });

  it('clearSurfaceVisibility removes the entry', () => {
    setSurfaceVisibility('core.help-surface', false);
    setSurfaceVisibility('core.settings-surface', false);
    clearSurfaceVisibility('core.help-surface');
    expect(getUserConfig().surfaceVisibility).toEqual({
      'core.settings-surface': false,
    });
  });

  it('clearSurfaceVisibility collapses to undefined when last entry removed', () => {
    setSurfaceVisibility('core.help-surface', false);
    clearSurfaceVisibility('core.help-surface');
    expect(getUserConfig().surfaceVisibility).toBeUndefined();
  });

  it('setSurfaceOrder writes the order array', () => {
    setSurfaceOrder([
      'core.search-surface',
      'core.library-surface',
      'core.brain-surface',
    ]);
    expect(getUserConfig().surfaceOrder).toEqual([
      'core.search-surface',
      'core.library-surface',
      'core.brain-surface',
    ]);
  });

  it('setSurfaceOrder([]) clears the order override', () => {
    setSurfaceOrder(['core.search-surface', 'core.library-surface']);
    setSurfaceOrder([]);
    expect(getUserConfig().surfaceOrder).toBeUndefined();
  });

  it('clearAllLayoutOverrides removes visibility + order + activeLayoutId', () => {
    setSurfaceVisibility('core.help-surface', false);
    setSurfaceOrder(['core.search-surface', 'core.library-surface']);
    clearAllLayoutOverrides();
    const cfg = getUserConfig();
    expect(cfg.surfaceVisibility).toBeUndefined();
    expect(cfg.surfaceOrder).toBeUndefined();
    expect(cfg.activeLayoutId).toBeUndefined();
  });

  it('clearAllLayoutOverrides preserves surfaceOverride (different concern)', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceVisibility('core.help-surface', false);
    clearAllLayoutOverrides();
    const cfg = getUserConfig();
    expect(cfg.surfaceVisibility).toBeUndefined();
    expect(cfg.surfaceOverride).toEqual({
      'core.library-surface': 'acme.alt-library-surface',
    });
  });

  it('layout mutators notify subscribers', () => {
    const listener = vi.fn();
    subscribeUserConfig(listener);
    listener.mockClear();
    setSurfaceVisibility('core.help-surface', false);
    expect(listener).toHaveBeenCalledTimes(1);
    setSurfaceOrder(['core.search-surface', 'core.library-surface']);
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('userConfigState — V1.5.1 persistence', () => {
  afterEach(() => {
    __resetUserConfigForTest();
  });

  // 478 §4.B + 508 §11.3 V2: persistence consolidated under
  // 'justsearch.userState.v2'; userConfig lives inside the active
  // profile (profiles[activeProfileId].userConfig).
  const DOC_KEY = 'justsearch.userState.v2';
  const readUserConfig = (doc: { profiles: Record<string, { userConfig: unknown }>; activeProfileId: string }): { [k: string]: unknown } => {
    return doc.profiles[doc.activeProfileId]!.userConfig as { [k: string]: unknown };
  };

  it('mutations persist to localStorage (consolidated document)', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceVisibility('core.help-surface', false);
    const stored = localStorage.getItem(DOC_KEY);
    expect(stored).not.toBeNull();
    const doc = JSON.parse(stored!);
    expect(doc.version).toBe(2);
    const uc = readUserConfig(doc);
    expect(uc.version).toBe(1);
    expect(uc.surfaceOverride).toEqual({
      'core.library-surface': 'acme.alt-library-surface',
    });
    expect(uc.surfaceVisibility).toEqual({
      'core.help-surface': false,
    });
  });

  it('clear-all mutators persist the cleared state', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    setSurfaceVisibility('core.help-surface', false);
    clearAllSurfaceOverrides();
    clearAllLayoutOverrides();
    const stored = localStorage.getItem(DOC_KEY);
    expect(stored).not.toBeNull();
    const doc = JSON.parse(stored!);
    const uc = readUserConfig(doc);
    expect(uc.surfaceOverride).toBeUndefined();
    expect(uc.surfaceVisibility).toBeUndefined();
  });

  it('__resetUserConfigForTest clears localStorage', () => {
    setSurfaceOverride('core.library-surface', 'acme.alt-library-surface');
    expect(localStorage.getItem(DOC_KEY)).not.toBeNull();
    __resetUserConfigForTest();
    expect(localStorage.getItem(DOC_KEY)).toBeNull();
  });

  // Note: testing the boot-time `loadFromStorage` path requires
  // re-importing the module, which Vitest doesn't easily support
  // mid-test. The behavior is covered by the equivalent live-smoke
  // (apply Focus Mode → reload page → customizations persist).
});
