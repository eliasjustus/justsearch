// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup Track A — viewerAudienceState projection tests.
 *
 * happy-dom is required so `localStorage` exists; UserStateDocument's
 * `mutateDocument` path persists to localStorage, and the Track CC
 * persistence-round-trip case below depends on that.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  getViewerAudience,
  setViewerAudience,
  subscribeViewerAudience,
} from './viewerAudienceState';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
} from './UserStateDocument';

describe('viewerAudienceState', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });
  afterEach(() => {
    __resetUserStateForTest();
  });

  it('defaults to USER when no value is set', () => {
    expect(getViewerAudience()).toBe('USER');
  });

  it('persists the chosen audience', () => {
    setViewerAudience('OPERATOR');
    expect(getViewerAudience()).toBe('OPERATOR');
    setViewerAudience('DEVELOPER');
    expect(getViewerAudience()).toBe('DEVELOPER');
  });

  it('persists across simulated page reload (Track CC)', () => {
    // Set a non-default value; UserStateDocument writes to localStorage
    // synchronously via mutateDocument.
    setViewerAudience('OPERATOR');
    expect(getViewerAudience()).toBe('OPERATOR');
    // Simulate page reload: drop in-memory state but keep localStorage
    // contents. Next getViewerAudience() must re-parse from disk.
    __resetInMemoryStateForTest();
    expect(getViewerAudience()).toBe('OPERATOR');
  });

  it('fires subscribers on change', () => {
    const seen: string[] = [];
    const unsub = subscribeViewerAudience((a) => seen.push(a));
    // Initial fire on subscribe.
    expect(seen).toEqual(['USER']);
    setViewerAudience('OPERATOR');
    expect(seen).toEqual(['USER', 'OPERATOR']);
    setViewerAudience('OPERATOR'); // no-op
    expect(seen).toEqual(['USER', 'OPERATOR']);
    setViewerAudience('DEVELOPER');
    expect(seen).toEqual(['USER', 'OPERATOR', 'DEVELOPER']);
    unsub();
    setViewerAudience('USER');
    expect(seen).toEqual(['USER', 'OPERATOR', 'DEVELOPER']);
  });
});
