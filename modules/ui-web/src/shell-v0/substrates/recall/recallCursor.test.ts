// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import {
  getSeenCursor,
  markSeen,
  isNewSinceSeen,
  subscribeSeenCursor,
  __resetRecallCursor,
} from './recallCursor.js';

beforeEach(() => {
  globalThis.localStorage?.clear();
  __resetRecallCursor();
});

describe('recallCursor — the one seen authority', () => {
  it('starts empty ("never looked") so everything is new', () => {
    expect(getSeenCursor()).toBe('');
    expect(isNewSinceSeen('2026-01-01T00:00:00Z')).toBe(true);
  });

  it('markSeen advances the cursor and persists it', () => {
    markSeen('2026-01-01T00:00:05Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
    expect(isNewSinceSeen('2026-01-01T00:00:04Z')).toBe(false);
    expect(isNewSinceSeen('2026-01-01T00:00:06Z')).toBe(true);
    expect(globalThis.localStorage?.getItem('justsearch.recall.seen-cursor.v1')).toBe(
      '2026-01-01T00:00:05Z',
    );
  });

  it('is monotonic — a stale (older) mark never rewinds "seen"', () => {
    markSeen('2026-01-01T00:00:05Z');
    markSeen('2026-01-01T00:00:01Z');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
  });

  it('ignores an empty mark', () => {
    markSeen('2026-01-01T00:00:05Z');
    markSeen('');
    expect(getSeenCursor()).toBe('2026-01-01T00:00:05Z');
  });

  it('notifies subscribers only on a real advance', () => {
    let hits = 0;
    const unsub = subscribeSeenCursor(() => {
      hits += 1;
    });
    markSeen('2026-01-01T00:00:05Z'); // advance → notify
    markSeen('2026-01-01T00:00:02Z'); // stale → no notify
    expect(hits).toBe(1);
    unsub();
    markSeen('2026-01-01T00:00:09Z');
    expect(hits).toBe(1); // unsubscribed
  });
});
