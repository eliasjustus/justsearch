// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 486 G36-widening (run-history) — coarse "Xm ago" / "Xh ago" / "Xd ago" formatter.
 *
 * Used by SearchSurface to label the most-recent run on each
 * pinned search. Granularity is deliberately rough: at-a-glance
 * recency, not precise timestamps.
 *
 * Buckets:
 *   - < 60s         → "just now"
 *   - < 60m         → "Nm ago"
 *   - < 24h         → "Nh ago"
 *   - otherwise     → "Nd ago"
 *
 * Future timestamps (now < ms) are rendered as "just now"
 * defensively — prevents nonsense like "in 17m" if the user's
 * clock jumps backwards between recording and rendering.
 */

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function formatRelative(ms: number, now: number = Date.now()): string {
  const delta = now - ms;
  if (delta < MINUTE) return 'just now';
  if (delta < HOUR) return `${Math.floor(delta / MINUTE)}m ago`;
  if (delta < DAY) return `${Math.floor(delta / HOUR)}h ago`;
  return `${Math.floor(delta / DAY)}d ago`;
}
