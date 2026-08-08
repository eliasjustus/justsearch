/**
 * The rail's time buckets (tempdoc 818 slice 3) — a PURE function of (rows, now).
 *
 * The whole point of injecting `now` is that the boundaries become testable, so this file tests the
 * boundaries: the first and last instant of each calendar day around `now`, not a comfortable
 * mid-afternoon sample from every bucket.
 */
import { describe, it, expect } from 'vitest';
import { bucketOf, bucketSessions, messageCountLabel } from './sessionBuckets.js';

/** A fixed reference instant: 2026-08-08, mid-morning LOCAL time (the buckets are local-calendar). */
const NOW = new Date(2026, 7, 8, 10, 30, 0, 0).getTime();

/** Local midnight of the day `offset` days before NOW's day. */
function dayStart(offset: number): number {
  return new Date(2026, 7, 8 - offset, 0, 0, 0, 0).getTime();
}
/** The last representable instant of that same local day. */
function dayEnd(offset: number): number {
  return new Date(2026, 7, 8 - offset, 23, 59, 59, 999).getTime();
}

function row(id: string, lastActiveAt: number, messageCount = 1) {
  return { id, label: id, lastActiveAt, messageCount };
}

describe('818 sessionBuckets — the boundaries', () => {
  it('puts both ends of today’s calendar day in Today', () => {
    expect(bucketOf(dayStart(0), NOW)).toBe('today');
    expect(bucketOf(NOW, NOW)).toBe('today');
    expect(bucketOf(dayEnd(0), NOW)).toBe('today');
  });

  it('crosses into Yesterday at midnight, not at 24 elapsed hours', () => {
    // One millisecond earlier than today's midnight is the previous calendar day…
    expect(bucketOf(dayStart(0) - 1, NOW)).toBe('yesterday');
    // …and 23:59 last night is "yesterday" even though it was 40 minutes ago in wall-clock terms.
    expect(bucketOf(dayEnd(1), NOW)).toBe('yesterday');
    expect(bucketOf(dayStart(1), NOW)).toBe('yesterday');
  });

  it('runs This week from two days back to six, then flips to Older on the seventh', () => {
    expect(bucketOf(dayEnd(2), NOW)).toBe('this-week');
    expect(bucketOf(dayStart(6), NOW)).toBe('this-week');
    expect(bucketOf(dayEnd(6), NOW)).toBe('this-week');
    // The seventh calendar day back is the first one that is no longer "this week".
    expect(bucketOf(dayEnd(7), NOW)).toBe('older');
    expect(bucketOf(dayStart(400), NOW)).toBe('older');
  });

  it('reads a future timestamp as Today rather than as a fifth, unnameable bucket', () => {
    expect(bucketOf(NOW + 86_400_000, NOW)).toBe('today');
  });

  it('reads no clock of its own: the same rows bucket differently under a different now', () => {
    const rows = [row('a', dayStart(0))];
    expect(bucketSessions(rows, NOW)[0]?.id).toBe('today');
    // Same row, a week later — the function's answer moved because `now` moved, not because time did.
    expect(bucketSessions(rows, NOW + 7 * 86_400_000)[0]?.id).toBe('older');
  });
});

describe('818 sessionBuckets — the grouping', () => {
  it('emits buckets newest-first, keeps input order inside a bucket, and omits empty ones', () => {
    const rows = [
      row('older-1', dayStart(30)),
      row('today-1', NOW),
      row('week-1', dayStart(3)),
      row('today-2', dayStart(0)),
    ];
    const buckets = bucketSessions(rows, NOW);

    expect(buckets.map((b) => b.id)).toEqual(['today', 'this-week', 'older']);
    expect(buckets.map((b) => b.label)).toEqual(['Today', 'This week', 'Older']);
    // Yesterday has no rows, so it is not a heading standing for nothing.
    expect(buckets.some((b) => b.id === 'yesterday')).toBe(false);
    expect(buckets[0]?.rows.map((r) => r.id)).toEqual(['today-1', 'today-2']);
  });

  it('groups nothing into nothing', () => {
    expect(bucketSessions([], NOW)).toEqual([]);
  });

  it('L6 — the row meta derives from the count it describes, singular included', () => {
    expect(messageCountLabel(1)).toBe('1 message');
    expect(messageCountLabel(0)).toBe('0 messages');
    expect(messageCountLabel(12)).toBe('12 messages');
  });
});
