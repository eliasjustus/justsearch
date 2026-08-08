// SPDX-License-Identifier: Apache-2.0
/**
 * sessionBuckets — the rail mode-A time grouping (tempdoc 818 slice 3).
 *
 * The 818 sidebar copies the thread-sidebar convention, and time buckets are part of that
 * convention (§1.3 "copied convention, five protected divergences"). This module is the whole of
 * the grouping: a pure function of (rows, now) that partitions prior sessions into Today /
 * Yesterday / This week / Older.
 *
 * Two properties this module exists to guarantee:
 *  - **No clock inside.** `now` is a parameter, never `Date.now()` — a bucketing function that reads
 *    the clock cannot be tested at its boundaries, which is exactly where an off-by-one day lives.
 *  - **L6 (derived).** A bucket's membership derives from the timestamps it describes, and empty
 *    buckets are not emitted, so the rail never shows a heading standing for nothing.
 *
 * Day arithmetic is LOCAL-CALENDAR, not elapsed-hours: "yesterday" means the previous calendar day
 * in the reader's own timezone (23:59 last night is yesterday even though it was 40 minutes ago),
 * which is what the heading claims. Rounding the day difference keeps DST transitions — a 23- or
 * 25-hour local day — from shifting a row into the neighbouring bucket.
 */

/** The rows this module groups. Structurally satisfied by the view's own session row. */
export interface BucketableSession {
  readonly id: string;
  readonly label: string;
  /** Epoch millis of the session's last activity — the fact the buckets are cut from. */
  readonly lastActiveAt: number;
  readonly messageCount: number;
}

export type SessionBucketId = 'today' | 'yesterday' | 'this-week' | 'older';

export interface SessionBucket<T extends BucketableSession = BucketableSession> {
  readonly id: SessionBucketId;
  readonly label: string;
  readonly rows: readonly T[];
}

const BUCKET_ORDER: ReadonlyArray<{ id: SessionBucketId; label: string }> = Object.freeze([
  { id: 'today', label: 'Today' },
  { id: 'yesterday', label: 'Yesterday' },
  { id: 'this-week', label: 'This week' },
  { id: 'older', label: 'Older' },
]);

/** Midnight of the local calendar day containing `ms`. */
function startOfLocalDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/**
 * Which bucket a timestamp falls in, relative to `now`. A timestamp in the future (a clock skew, a
 * backend ahead of the browser) reads as today rather than as a fifth, unnameable bucket.
 */
export function bucketOf(lastActiveAt: number, now: number): SessionBucketId {
  const days = Math.round((startOfLocalDay(now) - startOfLocalDay(lastActiveAt)) / 86_400_000);
  if (days <= 0) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 7) return 'this-week';
  return 'older';
}

/**
 * Group sessions into the four time buckets, newest bucket first, preserving each caller's row
 * order within a bucket (the conversation list already arrives newest-first). Buckets with no rows
 * are omitted.
 */
export function bucketSessions<T extends BucketableSession>(
  rows: readonly T[],
  now: number,
): ReadonlyArray<SessionBucket<T>> {
  const byBucket = new Map<SessionBucketId, T[]>();
  for (const row of rows) {
    const id = bucketOf(row.lastActiveAt, now);
    const existing = byBucket.get(id);
    if (existing) existing.push(row);
    else byBucket.set(id, [row]);
  }
  const out: Array<SessionBucket<T>> = [];
  for (const { id, label } of BUCKET_ORDER) {
    const bucketRows = byBucket.get(id);
    if (bucketRows && bucketRows.length > 0) {
      out.push(Object.freeze({ id, label, rows: Object.freeze([...bucketRows]) }));
    }
  }
  return Object.freeze(out);
}

/** L6 — the row's meta line, derived from the count it describes. */
export function messageCountLabel(messageCount: number): string {
  return `${messageCount} ${messageCount === 1 ? 'message' : 'messages'}`;
}
