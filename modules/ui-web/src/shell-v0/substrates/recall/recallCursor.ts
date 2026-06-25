// SPDX-License-Identifier: Apache-2.0
/**
 * recallCursor — Tempdoc 577 §2.14 Root I (#20): the ONE "seen" authority.
 *
 * Before this, the "What the AI did" digest owned a PRIVATE last-seen cursor (a journal-entry-id
 * in `justsearch.ai-digest.last-seen.v1`) while History / Timeline / Inbox projected the one 550
 * federated ledger with no notion of "seen" at all — two authorities for recall (the 553 drift
 * class: the digest could say "3 new" off a cursor nothing else respected). This store is the
 * single owner of "have I seen this yet", keyed over the unified log's chronological axis
 * (an ISO-8601 `occurredAt` timestamp), so EVERY recall surface — the catch-up digest, and any
 * "new since you looked" marker on History/Inbox — reads ONE cursor over ONE log.
 *
 * The unit is an ISO-8601 UTC timestamp string: lexical comparison IS chronological order for the
 * `Z`-suffixed timestamps the ledger emits, so "newer than seen" is a string compare (no Date
 * parse, no drift). The empty string is "never looked" (everything is new).
 */
import { safeLocalStorage } from '../../primitives/storage.js';

const SEEN_KEY = 'justsearch.recall.seen-cursor.v1';

let cursor = readCursor();
const subscribers = new Set<() => void>();

function readCursor(): string {
  const raw = safeLocalStorage()?.getItem(SEEN_KEY);
  // An ISO-8601 string or '' (never looked). Defensive: reject anything that isn't a plausible ISO.
  return typeof raw === 'string' && raw.length > 0 ? raw : '';
}

function writeCursor(iso: string): void {
  safeLocalStorage()?.setItem(SEEN_KEY, iso);
}

function notify(): void {
  for (const fn of subscribers) fn();
}

/** The current seen-cursor (an ISO-8601 timestamp, or '' for "never looked"). */
export function getSeenCursor(): string {
  return cursor;
}

/**
 * Advance the cursor to `iso` (monotonic — a stale/older mark is ignored, so an out-of-order
 * refresh can never rewind "seen"). Notifies subscribers only on a real advance.
 */
export function markSeen(iso: string): void {
  if (typeof iso !== 'string' || iso.length === 0) return;
  if (iso > cursor) {
    cursor = iso;
    writeCursor(iso);
    notify();
  }
}

/** True iff `occurredAt` is newer than the seen-cursor (the "new since you looked" predicate). */
export function isNewSinceSeen(occurredAt: string): boolean {
  return typeof occurredAt === 'string' && occurredAt > cursor;
}

/** Subscribe to cursor advances. Returns an unsubscribe fn. */
export function subscribeSeenCursor(fn: () => void): () => void {
  subscribers.add(fn);
  return () => {
    subscribers.delete(fn);
  };
}

/** Test-only: reset the in-memory + persisted cursor. */
export function __resetRecallCursor(): void {
  cursor = '';
  safeLocalStorage()?.removeItem(SEEN_KEY);
  notify();
}
