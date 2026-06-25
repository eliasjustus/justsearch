// @vitest-environment happy-dom

/**
 * Slice 486 G36 — pinnedSearchState tests.
 *
 * Mirrors UserStateDocument.test.ts patterns:
 *   - default empty
 *   - pin / unpin lifecycle
 *   - de-dupe by trimmed query
 *   - subscribe fires on mutation, not on no-op
 *   - listener errors swallowed
 *   - persistence round-trip via UserStateDocument
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  getPinnedSearches,
  subscribePinnedSearches,
  pinSearch,
  unpinSearch,
  isPinned,
  recordRun,
  MAX_RUNS_PER_PIN,
  MIN_RUN_GAP_MS,
} from './pinnedSearchState.js';
import {
  __resetUserStateForTest,
  getDocument,
} from './UserStateDocument.js';

describe('pinnedSearchState — defaults + lifecycle', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('returns empty list on first read', () => {
    expect(getPinnedSearches()).toEqual([]);
  });

  it('pinSearch creates a new entry', () => {
    const pin = pinSearch('configuration');
    expect(pin).not.toBeNull();
    expect(pin!.query).toBe('configuration');
    expect(pin!.id).toMatch(/.+/);
    expect(typeof pin!.pinnedAt).toBe('number');
    expect(getPinnedSearches()).toHaveLength(1);
    expect(getPinnedSearches()[0]).toEqual(pin);
  });

  it('pinSearch de-dupes by trimmed query', () => {
    const first = pinSearch('hello');
    const second = pinSearch('hello'); // exact same
    const third = pinSearch('  hello  '); // padded — same trimmed
    expect(first!.id).toBe(second!.id);
    expect(first!.id).toBe(third!.id);
    expect(getPinnedSearches()).toHaveLength(1);
  });

  it('pinSearch preserves the un-trimmed query string verbatim', () => {
    const pin = pinSearch('  hello world  ');
    expect(pin!.query).toBe('  hello world  ');
  });

  it('pinSearch rejects empty / whitespace-only queries', () => {
    expect(pinSearch('')).toBeNull();
    expect(pinSearch('   ')).toBeNull();
    expect(pinSearch('\t\n')).toBeNull();
    expect(getPinnedSearches()).toEqual([]);
  });

  it('unpinSearch removes by id and returns true', () => {
    const pin = pinSearch('foo')!;
    pinSearch('bar');
    expect(getPinnedSearches()).toHaveLength(2);
    expect(unpinSearch(pin.id)).toBe(true);
    expect(getPinnedSearches()).toHaveLength(1);
    expect(getPinnedSearches()[0]!.query).toBe('bar');
  });

  it('unpinSearch returns false for unknown id', () => {
    pinSearch('foo');
    expect(unpinSearch('does-not-exist')).toBe(false);
    expect(getPinnedSearches()).toHaveLength(1);
  });

  it('isPinned returns truth correctly', () => {
    expect(isPinned('hello')).toBe(false);
    pinSearch('hello');
    expect(isPinned('hello')).toBe(true);
    expect(isPinned('  hello  ')).toBe(true); // trimmed match
    expect(isPinned('hello world')).toBe(false);
  });

  it('isPinned rejects empty / whitespace-only queries', () => {
    pinSearch('foo');
    expect(isPinned('')).toBe(false);
    expect(isPinned('   ')).toBe(false);
  });
});

describe('pinnedSearchState — subscription', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('subscribePinnedSearches fires once with current value on subscribe', () => {
    const listener = vi.fn();
    subscribePinnedSearches(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith([]);
  });

  it('listener fires on pin mutation', () => {
    const listener = vi.fn();
    subscribePinnedSearches(listener);
    listener.mockClear();
    pinSearch('hello');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0]![0]).toHaveLength(1);
  });

  it('listener does NOT fire on de-dup (no-op pin)', () => {
    pinSearch('hello');
    const listener = vi.fn();
    subscribePinnedSearches(listener);
    listener.mockClear();
    pinSearch('hello'); // no-op — already pinned
    expect(listener).not.toHaveBeenCalled();
  });

  it('listener does NOT fire on unknown unpin (no-op)', () => {
    pinSearch('hello');
    const listener = vi.fn();
    subscribePinnedSearches(listener);
    listener.mockClear();
    unpinSearch('does-not-exist');
    expect(listener).not.toHaveBeenCalled();
  });

  it('unsubscribe stops further notifications', () => {
    const listener = vi.fn();
    const off = subscribePinnedSearches(listener);
    listener.mockClear();
    off();
    pinSearch('after-unsub');
    expect(listener).not.toHaveBeenCalled();
  });

  it('one listener throwing does not stop others', () => {
    const bad = vi.fn(() => {
      throw new Error('synthetic');
    });
    const good = vi.fn();
    subscribePinnedSearches(bad);
    subscribePinnedSearches(good);
    bad.mockClear();
    good.mockClear();
    pinSearch('test');
    expect(bad).toHaveBeenCalled();
    expect(good).toHaveBeenCalled();
  });
});

describe('pinnedSearchState — persistence + ordering', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('pin order is creation-order (newest-last)', () => {
    pinSearch('first');
    pinSearch('second');
    pinSearch('third');
    const pins = getPinnedSearches();
    expect(pins.map((p) => p.query)).toEqual(['first', 'second', 'third']);
  });

  it('pinnedAt timestamps reflect creation order', () => {
    const a = pinSearch('a')!;
    const b = pinSearch('b')!;
    expect(a.pinnedAt).toBeLessThanOrEqual(b.pinnedAt);
  });

  it('pinned state persists through UserStateDocument', () => {
    pinSearch('persist-me');
    // Read the underlying document to confirm the slice landed.
    expect(getDocument().pinnedSearches).toHaveLength(1);
    expect(getDocument().pinnedSearches[0]!.query).toBe('persist-me');
  });
});

// Slice 486 G36-widening (run-history) — recordRun behavior.
describe('pinnedSearchState — recordRun', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-05-08T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
    __resetUserStateForTest();
  });

  it('appends a run for a pinned query', () => {
    pinSearch('configuration');
    recordRun('configuration', 17);
    const pins = getPinnedSearches();
    expect(pins[0]!.runs).toHaveLength(1);
    expect(pins[0]!.runs[0]).toEqual({ ranAt: Date.now(), totalHits: 17 });
  });

  it('is a no-op when the query is not pinned', () => {
    pinSearch('configuration');
    recordRun('something-else', 5);
    expect(getPinnedSearches()[0]!.runs).toEqual([]);
  });

  it('is a no-op for empty / whitespace-only queries', () => {
    pinSearch('configuration');
    recordRun('', 5);
    recordRun('   ', 5);
    expect(getPinnedSearches()[0]!.runs).toEqual([]);
  });

  it('matches by trimmed query (matches pin de-dupe semantics)', () => {
    pinSearch('configuration');
    recordRun('  configuration  ', 17);
    expect(getPinnedSearches()[0]!.runs).toHaveLength(1);
  });

  it('skips re-records inside MIN_RUN_GAP_MS', () => {
    pinSearch('configuration');
    recordRun('configuration', 17);
    vi.advanceTimersByTime(MIN_RUN_GAP_MS - 100);
    recordRun('configuration', 99);
    expect(getPinnedSearches()[0]!.runs).toHaveLength(1);
    expect(getPinnedSearches()[0]!.runs[0]!.totalHits).toBe(17);
  });

  it('records a second run after MIN_RUN_GAP_MS elapses', () => {
    pinSearch('configuration');
    recordRun('configuration', 17);
    vi.advanceTimersByTime(MIN_RUN_GAP_MS + 1);
    recordRun('configuration', 99);
    expect(getPinnedSearches()[0]!.runs).toHaveLength(2);
    expect(getPinnedSearches()[0]!.runs[1]!.totalHits).toBe(99);
  });

  it('caps runs at MAX_RUNS_PER_PIN with FIFO drop', () => {
    pinSearch('configuration');
    for (let i = 0; i < MAX_RUNS_PER_PIN + 3; i++) {
      recordRun('configuration', i);
      vi.advanceTimersByTime(MIN_RUN_GAP_MS + 1);
    }
    const runs = getPinnedSearches()[0]!.runs;
    expect(runs).toHaveLength(MAX_RUNS_PER_PIN);
    // FIFO: oldest entries dropped, so totalHits values are
    // the LAST MAX_RUNS_PER_PIN integers we recorded.
    expect(runs[0]!.totalHits).toBe(3);
    expect(runs[runs.length - 1]!.totalHits).toBe(MAX_RUNS_PER_PIN + 2);
  });

  it('subscribePinnedSearches fires on recordRun mutation', () => {
    pinSearch('configuration');
    const listener = vi.fn();
    const unsub = subscribePinnedSearches(listener);
    listener.mockClear(); // ignore the on-subscribe replay
    recordRun('configuration', 17);
    expect(listener).toHaveBeenCalled();
    unsub();
  });

  it('does not fire subscribers when recordRun is a no-op', () => {
    pinSearch('configuration');
    const listener = vi.fn();
    const unsub = subscribePinnedSearches(listener);
    listener.mockClear();
    recordRun('not-pinned', 5);
    expect(listener).not.toHaveBeenCalled();
    unsub();
  });
});

// Slice 486 G36-widening (filter-snapshot) — pinSearch with filterSpec snapshot.
describe('pinnedSearchState — filterSpec on pinSearch', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('captures filterSpec when both bounds are set', () => {
    const pin = pinSearch('configuration', {
      modifiedFromMs: 1700000000000,
      modifiedToMs: 1800000000000,
    })!;
    expect(pin.filterSpec).toEqual({
      modifiedFromMs: 1700000000000,
      modifiedToMs: 1800000000000,
    });
  });

  it('captures filterSpec with only one bound', () => {
    const pin = pinSearch('configuration', { modifiedFromMs: 1700000000000 })!;
    expect(pin.filterSpec).toEqual({ modifiedFromMs: 1700000000000 });
    expect(pin.filterSpec!.modifiedToMs).toBeUndefined();
  });

  it('omits filterSpec when called without one', () => {
    const pin = pinSearch('configuration')!;
    expect(pin.filterSpec).toBeUndefined();
  });

  it('omits filterSpec when caller passes empty / nonsense object', () => {
    const pin = pinSearch('configuration', {
      modifiedFromMs: Number.NaN,
      modifiedToMs: undefined,
    })!;
    expect(pin.filterSpec).toBeUndefined();
  });

  it('persists filterSpec through UserStateDocument', () => {
    pinSearch('configuration', { modifiedFromMs: 1700000000000 });
    const persisted = getDocument().pinnedSearches[0]!;
    expect(persisted.filterSpec).toEqual({ modifiedFromMs: 1700000000000 });
  });
});
