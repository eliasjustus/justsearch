// SPDX-License-Identifier: Apache-2.0
/**
 * pinnedSearchState — projection over UserStateDocument's
 * `pinnedSearches` slice.
 *
 * Slice 486 G36 (consumer-feature §21 / §22 Tier A): user-authored
 * pinned search queries. Mirrors `userConfigState.ts` line-for-
 * line as the canonical projection pattern over UserStateDocument.
 *
 * API:
 *   - getPinnedSearches() — snapshot
 *   - subscribePinnedSearches(listener) — projection subscription
 *   - pinSearch(query) — de-dupes by trimmed query; returns pin
 *   - unpinSearch(id) — removes by id; returns true if removed
 *   - isPinned(query) — true if a pin exists with this trimmed query
 *
 * Persistence: writes through to localStorage via
 * UserStateDocument's `mutateDocument`. Listener errors are
 * swallowed (matching the existing posture in userConfigState).
 *
 * V1 design choices (per slice 486 G36 plan):
 *   - de-dupe by `query.trim()`; existing pin returned on duplicate
 *   - no capacity limit
 *   - no user-customizable label (label = query string)
 *   - no reordering API; ordering is creation-order (newest-last
 *     by `pinnedAt`)
 */
import {
  getDocument,
  subscribeProjection,
  mutateDocument,
  type SearchPin,
  type SearchPinRun,
  type SearchFilterSpec,
} from './UserStateDocument.js';

export type {
  SearchPin,
  SearchPinRun,
  SearchFilterSpec,
} from './UserStateDocument.js';

/**
 * Slice 486 G36-widening (run-history) — ring-buffer cap for per-pin runs. Older
 * entries shift out FIFO once `recordRun` would exceed this
 * count. Five is enough for at-a-glance "is this query still
 * productive" without bloating localStorage.
 */
export const MAX_RUNS_PER_PIN = 5;

/**
 * Slice 486 G36-widening (run-history) — minimum gap between recorded runs for the
 * same pin. Re-firing the same query within this window (e.g.,
 * the user clears + retypes; surface re-mounts) is treated as
 * the same run and skipped. Coarse heuristic; deliberately
 * sloppy.
 */
export const MIN_RUN_GAP_MS = 3000;

type Listener = (pins: readonly SearchPin[]) => void;

/** Snapshot of the current pinnedSearches list (ordered creation-order). */
export function getPinnedSearches(): readonly SearchPin[] {
  return getDocument().pinnedSearches;
}

/**
 * Subscribe to pinnedSearches changes. Listener fires once with
 * the current value on subscribe, then on every mutation.
 * Listener errors are swallowed (same posture as userConfigState).
 */
export function subscribePinnedSearches(listener: Listener): () => void {
  return subscribeProjection((doc) => doc.pinnedSearches, listener);
}

/**
 * Returns true if a pin already exists for the given query
 * (compared by `query.trim()`). Used by SearchSurface to
 * conditionally render the pin button.
 */
export function isPinned(query: string): boolean {
  const trimmed = query.trim();
  if (trimmed.length === 0) return false;
  return getDocument().pinnedSearches.some(
    (p) => p.query.trim() === trimmed,
  );
}

/**
 * Pin a query. De-dupes by `query.trim()`: pinning the same
 * trimmed query twice yields a single entry (the original pin
 * is preserved). Empty / whitespace-only queries are rejected
 * (returns null without mutating).
 *
 * Returns the resulting pin (existing or newly-created) so callers
 * can use the id for follow-up actions (e.g., immediate unpin).
 */
export function pinSearch(
  query: string,
  filterSpec?: SearchFilterSpec,
): SearchPin | null {
  const trimmed = query.trim();
  if (trimmed.length === 0) return null;
  // Slice 486 G36-widening (filter-snapshot) — only attach filterSpec if it has at least
  // one bound. An empty object is treated as "no filter".
  const normalized = normalizeFilterSpec(filterSpec);
  let result: SearchPin | null = null;
  mutateDocument((doc) => {
    const existing = doc.pinnedSearches.find(
      (p) => p.query.trim() === trimmed,
    );
    if (existing) {
      result = existing;
      return doc; // No mutation; same reference signals no-op.
    }
    const pin: SearchPin = {
      id: makePinId(),
      query,
      pinnedAt: Date.now(),
      runs: [],
      ...(normalized !== undefined ? { filterSpec: normalized } : {}),
    };
    result = pin;
    return {
      ...doc,
      pinnedSearches: [...doc.pinnedSearches, pin],
    };
  });
  return result;
}

/**
 * Slice 486 G36-widening (filter-snapshot) — return a normalized snapshot of the filter
 * spec, dropping non-finite bounds and returning undefined when
 * no usable bound remains. Keeps both `pinSearch` and chip-
 * restore consistent.
 */
function normalizeFilterSpec(
  spec: SearchFilterSpec | undefined,
): SearchFilterSpec | undefined {
  if (spec === undefined) return undefined;
  const fromOk =
    typeof spec.modifiedFromMs === 'number' &&
    Number.isFinite(spec.modifiedFromMs);
  const toOk =
    typeof spec.modifiedToMs === 'number' &&
    Number.isFinite(spec.modifiedToMs);
  if (!fromOk && !toOk) return undefined;
  const out: { modifiedFromMs?: number; modifiedToMs?: number } = {};
  if (fromOk) out.modifiedFromMs = spec.modifiedFromMs;
  if (toOk) out.modifiedToMs = spec.modifiedToMs;
  return out;
}

/**
 * Remove a pin by id. Returns true if a pin was removed,
 * false if no pin existed with that id (no-op).
 */
export function unpinSearch(id: string): boolean {
  let removed = false;
  mutateDocument((doc) => {
    const filtered = doc.pinnedSearches.filter((p) => {
      if (p.id === id) {
        removed = true;
        return false;
      }
      return true;
    });
    if (!removed) return doc;
    return { ...doc, pinnedSearches: filtered };
  });
  return removed;
}

/**
 * Slice 486 G36-widening (run-history) — record a successful run for any pin matching
 * `query` (compared by `query.trim()` like `pinSearch`). No-op
 * when:
 *   - the trimmed query is empty,
 *   - no pin matches (the query isn't pinned),
 *   - the most-recent run for that pin is younger than
 *     `MIN_RUN_GAP_MS` (de-dupe rapid re-fires).
 *
 * On record, the new run is appended; if the resulting list
 * exceeds `MAX_RUNS_PER_PIN`, the oldest entries are dropped
 * (FIFO).
 */
export function recordRun(query: string, totalHits: number): void {
  const trimmed = query.trim();
  if (trimmed.length === 0) return;
  const now = Date.now();
  mutateDocument((doc) => {
    let mutated = false;
    const next = doc.pinnedSearches.map((p) => {
      if (p.query.trim() !== trimmed) return p;
      const last =
        p.runs.length > 0 ? (p.runs[p.runs.length - 1] ?? null) : null;
      if (last !== null && now - last.ranAt < MIN_RUN_GAP_MS) {
        return p;
      }
      mutated = true;
      const appended: SearchPinRun = { ranAt: now, totalHits };
      const combined = [...p.runs, appended];
      const trimmedRuns =
        combined.length > MAX_RUNS_PER_PIN
          ? combined.slice(combined.length - MAX_RUNS_PER_PIN)
          : combined;
      return { ...p, runs: trimmedRuns };
    });
    if (!mutated) return doc;
    return { ...doc, pinnedSearches: next };
  });
}

/**
 * Generate a stable pin id. Prefers crypto.randomUUID when
 * available; falls back to a timestamp+random hybrid for older
 * environments. The id is opaque to callers.
 */
function makePinId(): string {
  // crypto.randomUUID is available in modern browsers + Node 19+.
  // happy-dom shims it in tests.
  const c = (globalThis as { crypto?: { randomUUID?: () => string } })
    .crypto;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  return `pin-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 10)}`;
}
