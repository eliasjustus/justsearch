// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 450 §1.4 — Locale-aware relative-time helper for Lit + React contexts.
 *
 * Buckets:
 *   <60s        "just now"
 *   <60min      "Nm ago"
 *   <24h        "Nh ago"
 *   <7d         "Nd ago"
 *   >=7d        ISO date (YYYY-MM-DD)
 *
 * Empty / invalid input → empty string (caller-controlled fallback).
 *
 * Implementation notes: uses `Intl.RelativeTimeFormat` for the buckets <7d
 * to honor browser locale; the >=7d branch falls back to ISO date for
 * stability (relative time becomes meaningless past a week — users want
 * the absolute date for old timestamps).
 */

const SECOND_MS = 1000;
const MINUTE_MS = 60 * SECOND_MS;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;
const WEEK_MS = 7 * DAY_MS;

export function formatRelativeIso(iso: string, now: Date = new Date()): string {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const deltaMs = now.getTime() - t;
  // Future timestamps render as "just now" rather than negative — typical
  // clock skew is small enough that "in 3s" reads as more confusing than
  // "just now" for a "last indexed" display.
  if (deltaMs < SECOND_MS * 30) return 'just now';
  if (deltaMs < HOUR_MS) {
    const minutes = Math.floor(deltaMs / MINUTE_MS);
    return formatUnit(-minutes, 'minute');
  }
  if (deltaMs < DAY_MS) {
    const hours = Math.floor(deltaMs / HOUR_MS);
    return formatUnit(-hours, 'hour');
  }
  if (deltaMs < WEEK_MS) {
    const days = Math.floor(deltaMs / DAY_MS);
    return formatUnit(-days, 'day');
  }
  // >=7d: ISO date.
  return new Date(t).toISOString().slice(0, 10);
}

function formatUnit(n: number, unit: Intl.RelativeTimeFormatUnit): string {
  if (typeof Intl?.RelativeTimeFormat === 'function') {
    try {
      return new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' }).format(n, unit);
    } catch {
      // Fall through to manual format on Intl edge cases.
    }
  }
  // Manual fallback: e.g., "5 minutes ago".
  const abs = Math.abs(n);
  return `${abs} ${unit}${abs === 1 ? '' : 's'} ${n < 0 ? 'ago' : 'from now'}`;
}
