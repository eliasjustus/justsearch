/**
 * Resource-view renderer registry tests — slice 3a.1.4 Phase 3.
 *
 * Covers:
 *  - Category-only dispatch (no hint, no density).
 *  - Hint-narrowed dispatch.
 *  - Density-narrowed dispatch.
 *  - Rank tie-breaking (higher rank wins).
 *  - Specificity tie-breaking (more-specific entry wins at same rank).
 *  - Plugin extension via registerResourceRenderer + unsubscribe.
 *  - `isCategorySupported` distinguishes "no shipping renderer" from
 *    "wrong hint."
 *  - Default registrations (loaded via the public barrel) cover the
 *    four shipping Categories.
 *
 * Each test resets the registry to a known state via
 * `clearResourceRendererRegistry()` so test ordering is irrelevant.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CATEGORIES,
  type Category,
  clearResourceRendererRegistry,
  dispatchResourceRenderer,
  getResourceRendererRegistry,
  isCategorySupported,
  registerResourceRenderer,
  type ResourceRendererEntry,
} from './resourceRegistry.js';

describe('Resource-view renderer registry', () => {
  beforeEach(() => {
    clearResourceRendererRegistry();
  });

  afterEach(() => {
    clearResourceRendererRegistry();
  });

  it('returns null when no entry matches a Category', () => {
    expect(dispatchResourceRenderer({ category: 'STATE' })).toBeNull();
    expect(isCategorySupported('STATE')).toBe(false);
  });

  it('Category-only dispatch returns the registered tag', () => {
    registerResourceRenderer({ category: 'STATE', rank: 0, tag: 'jf-status-card' });
    expect(dispatchResourceRenderer({ category: 'STATE' })).toBe('jf-status-card');
    expect(isCategorySupported('STATE')).toBe(true);
  });

  it('rejects entries from a different Category', () => {
    registerResourceRenderer({ category: 'STATE', rank: 0, tag: 'jf-status-card' });
    expect(dispatchResourceRenderer({ category: 'TABULAR' })).toBeNull();
    expect(isCategorySupported('TABULAR')).toBe(false);
  });

  it('hint-narrowed entry matches only its own hint', () => {
    registerResourceRenderer({
      category: 'TIMESERIES',
      hint: 'GAUGE',
      rank: 0,
      tag: 'jf-timeseries-gauge',
    });
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'GAUGE' }),
    ).toBe('jf-timeseries-gauge');
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'SPARK' }),
    ).toBeNull();
  });

  it('hint-unset entry matches any hint (default behavior)', () => {
    registerResourceRenderer({
      category: 'TIMESERIES',
      rank: 0,
      tag: 'jf-timeseries-sparkline',
    });
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'SPARK' }),
    ).toBe('jf-timeseries-sparkline');
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'GAUGE' }),
    ).toBe('jf-timeseries-sparkline');
    expect(dispatchResourceRenderer({ category: 'TIMESERIES' })).toBe(
      'jf-timeseries-sparkline',
    );
  });

  it('density-narrowed entry matches only its own density', () => {
    registerResourceRenderer({
      category: 'STATE',
      density: 'compact',
      rank: 0,
      tag: 'jf-status-card-compact',
    });
    expect(
      dispatchResourceRenderer({ category: 'STATE', density: 'compact' }),
    ).toBe('jf-status-card-compact');
    expect(
      dispatchResourceRenderer({ category: 'STATE', density: 'comfortable' }),
    ).toBeNull();
  });

  it('higher rank wins on ties', () => {
    registerResourceRenderer({
      category: 'TIMESERIES',
      rank: 0,
      tag: 'jf-default',
    });
    registerResourceRenderer({
      category: 'TIMESERIES',
      rank: 10,
      tag: 'jf-override',
    });
    expect(dispatchResourceRenderer({ category: 'TIMESERIES' })).toBe(
      'jf-override',
    );
  });

  it('more-specific entry (hint set) outranks broader entry at same rank', () => {
    registerResourceRenderer({
      category: 'TIMESERIES',
      rank: 0,
      tag: 'jf-default',
    });
    registerResourceRenderer({
      category: 'TIMESERIES',
      hint: 'GAUGE',
      rank: 0,
      tag: 'jf-gauge',
    });
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'GAUGE' }),
    ).toBe('jf-gauge');
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'SPARK' }),
    ).toBe('jf-default');
  });

  it('density+hint specificity outranks hint-only at same rank', () => {
    registerResourceRenderer({
      category: 'STATE',
      hint: 'badge',
      rank: 0,
      tag: 'jf-badge',
    });
    registerResourceRenderer({
      category: 'STATE',
      hint: 'badge',
      density: 'compact',
      rank: 0,
      tag: 'jf-badge-compact',
    });
    expect(
      dispatchResourceRenderer({
        category: 'STATE',
        hint: 'badge',
        density: 'compact',
      }),
    ).toBe('jf-badge-compact');
    expect(
      dispatchResourceRenderer({
        category: 'STATE',
        hint: 'badge',
        density: 'comfortable',
      }),
    ).toBe('jf-badge');
  });

  it('plugin can register and later unregister via returned unsubscribe', () => {
    const unsubscribe = registerResourceRenderer({
      category: 'HISTORY',
      rank: 100,
      tag: 'plugin-history-renderer',
    });
    expect(dispatchResourceRenderer({ category: 'HISTORY' })).toBe(
      'plugin-history-renderer',
    );
    expect(isCategorySupported('HISTORY')).toBe(true);
    unsubscribe();
    expect(dispatchResourceRenderer({ category: 'HISTORY' })).toBeNull();
    expect(isCategorySupported('HISTORY')).toBe(false);
  });

  it('isCategorySupported distinguishes wrong-hint from no-renderer', () => {
    registerResourceRenderer({
      category: 'TIMESERIES',
      hint: 'GAUGE',
      rank: 0,
      tag: 'jf-gauge',
    });
    // Wrong hint: registered Category but no matching entry
    expect(
      dispatchResourceRenderer({ category: 'TIMESERIES', hint: 'SPARK' }),
    ).toBeNull();
    expect(isCategorySupported('TIMESERIES')).toBe(true);
    // Unsupported Category: no entry at all
    expect(dispatchResourceRenderer({ category: 'HISTORY' })).toBeNull();
    expect(isCategorySupported('HISTORY')).toBe(false);
  });

  it('CATEGORIES enumerates the five closed values (LOG_TAIL retired in slice 448 phase 6)', () => {
    expect(CATEGORIES).toEqual([
      'STATE',
      'EVENT_STREAM',
      'HISTORY',
      'TABULAR',
      'TIMESERIES',
    ]);
    // Compile-time exhaustiveness check via a helper — adding a Category
    // value to the union without updating CATEGORIES would break this.
    const seen: Record<Category, boolean> = {
      STATE: false,
      EVENT_STREAM: false,
      HISTORY: false,
      TABULAR: false,
      TIMESERIES: false,
    };
    for (const c of CATEGORIES) seen[c] = true;
    expect(Object.values(seen).every((v) => v)).toBe(true);
  });

  it('getResourceRendererRegistry returns a snapshot, not a live view', () => {
    registerResourceRenderer({ category: 'STATE', rank: 0, tag: 'a' });
    const snap1 = getResourceRendererRegistry();
    expect(snap1).toHaveLength(1);
    registerResourceRenderer({ category: 'STATE', rank: 0, tag: 'b' });
    expect(snap1).toHaveLength(1); // snapshot is unaffected
    const snap2 = getResourceRendererRegistry();
    expect(snap2).toHaveLength(2);
  });
});

describe('Resource-view renderer registry — default registrations', () => {
  // The defaults file is an ESM side-effect module evaluated once per
  // bundle. The clear-and-reimport-per-test pattern doesn't work because
  // the dynamic import returns the cached module (no re-evaluation).
  // Instead: clear once, dynamically import the defaults file once (which
  // re-fires side effects on the in-memory registry instance), then run
  // all assertions inline. This is structurally honest about what the
  // defaults file does at bundle-import time.
  it('produces the four expected registrations and leaves HISTORY unsupported', async () => {
    clearResourceRendererRegistry();
    vi.resetModules();
    // Re-import the registry so the defaults module's `registerResourceRenderer`
    // call lands in the same module instance our re-imports will read.
    const registry = await import('./resourceRegistry.js');
    await import('./resourceRegistryDefaults.js');

    expect(registry.isCategorySupported('STATE')).toBe(true);
    expect(registry.dispatchResourceRenderer({ category: 'STATE' })).toBe(
      'jf-status-card',
    );

    expect(registry.isCategorySupported('EVENT_STREAM')).toBe(true);
    expect(
      registry.dispatchResourceRenderer({ category: 'EVENT_STREAM' }),
    ).toBe('jf-status-card');

    expect(registry.isCategorySupported('TABULAR')).toBe(true);
    expect(registry.dispatchResourceRenderer({ category: 'TABULAR' })).toBe(
      'jf-table',
    );

    expect(registry.isCategorySupported('TIMESERIES')).toBe(true);
    expect(
      registry.dispatchResourceRenderer({ category: 'TIMESERIES' }),
    ).toBe('jf-timeseries-sparkline');

    // HISTORY ships with slice 444c. (Slice 448 phase 6 retired LOG_TAIL —
    // operator-trace surfaces use the sibling DiagnosticChannel primitive.)
    expect(registry.isCategorySupported('HISTORY')).toBe(false);
    expect(registry.dispatchResourceRenderer({ category: 'HISTORY' })).toBeNull();

    // Exactly four of the five Categories supported by defaults.
    const supportedCount = registry.CATEGORIES.filter(
      registry.isCategorySupported,
    ).length;
    expect(supportedCount).toBe(4);
  });
});

// Type-level smoke: ensure ResourceRendererEntry is structurally usable
// from external code. Adding a Category value would break this if the
// type definition went stale.
const _typeSmoke: ResourceRendererEntry = {
  category: 'TIMESERIES',
  hint: 'SPARK',
  density: 'compact',
  rank: 0,
  tag: 'jf-timeseries-sparkline',
};
void _typeSmoke;
