// SPDX-License-Identifier: Apache-2.0
/**
 * searchFiltersState — ephemeral, session-only filter state for
 * the search surface.
 *
 * Slice 486 G36-widening (filter-snapshot): live filter inputs (date range on `modified_at`)
 * that the user toggles on the search surface. Deliberately
 * NOT persisted to localStorage — opening the surface fresh
 * starts with no filter applied. Saved searches (pinned chips)
 * capture a *snapshot* of the filter at pin time into
 * `SearchPin.filterSpec` and restore it on click.
 *
 * V1 shape (per slice 486 §G34):
 *   - Only `modifiedAt: { fromMs?, toMs? }`.
 *   - Both bounds optional. Empty (or undefined) = no filter
 *     sent on the request.
 *
 * Pub-sub posture mirrors searchState: listeners fire on every
 * mutation that changes the active filter; no-op writes (set
 * the same value) do not fire.
 */

// Re-export the canonical persistence shape so callers can
// import the type from this module without a deeper dependency.
export type { SearchFilterSpec } from './UserStateDocument.js';
import type { SearchFilterSpec } from './UserStateDocument.js';

const EMPTY: SearchFilterSpec = Object.freeze({});

let current: SearchFilterSpec = EMPTY;
const listeners = new Set<(s: SearchFilterSpec) => void>();

/** Snapshot of the active filter spec. */
export function getFilters(): SearchFilterSpec {
  return current;
}

/**
 * Subscribe to filter changes. Listener fires once with the
 * current value on subscribe, then on every mutating call.
 * Returns an unsubscribe handle.
 */
export function subscribeFilters(
  listener: (s: SearchFilterSpec) => void,
): () => void {
  listeners.add(listener);
  try {
    listener(current);
  } catch {
    // Match userConfigState posture: swallow listener errors.
  }
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Replace the active filter range. Pass `undefined` for either
 * bound to omit it. Passing both `undefined` clears the filter.
 *
 * No-op when the new spec is value-equal to the current one
 * (saves listeners from spurious fires).
 */
export function setFilterRange(
  fromMs: number | undefined,
  toMs: number | undefined,
): void {
  const next: SearchFilterSpec = (() => {
    const out: { modifiedFromMs?: number; modifiedToMs?: number } = {};
    if (typeof fromMs === 'number' && Number.isFinite(fromMs)) {
      out.modifiedFromMs = fromMs;
    }
    if (typeof toMs === 'number' && Number.isFinite(toMs)) {
      out.modifiedToMs = toMs;
    }
    return out;
  })();
  if (
    next.modifiedFromMs === current.modifiedFromMs &&
    next.modifiedToMs === current.modifiedToMs
  ) {
    return; // no-op
  }
  current = next;
  for (const l of listeners) {
    try {
      l(current);
    } catch {
      // ignore — same posture as userConfigState
    }
  }
}

/**
 * Tempdoc 508-followup §β4 — clear the active filter as part of a
 * profile switch invalidation. Equivalent to `setFilterRange(undefined,
 * undefined)` but named for the call site (so the boot-time wiring in
 * `main.jsx` reads intent-fully).
 */
export function clearFilters(): void {
  setFilterRange(undefined, undefined);
}

/**
 * Returns true when the spec has at least one bound set.
 * Callers (searchState's request builder, SearchSurface chip
 * subscript) use this to decide whether to attach the filter.
 */
export function hasActiveFilter(spec: SearchFilterSpec): boolean {
  return (
    typeof spec.modifiedFromMs === 'number' ||
    typeof spec.modifiedToMs === 'number'
  );
}

// ----- Tempdoc 577 Goal 1 Phase 6 (Move E) — keyword facet selections -----
//
// The clickable half of dual filtering: per-field value selections projected
// from the response's emitted facet counts. Session-ephemeral like the date
// range (not persisted, not yet pinned — pin-snapshot integration is a named
// follow-up). The selections feed buildSearchIntent, which maps them onto the
// wire's existing keyword filters (fileKind / mimeBase / language / metaAuthor).

let facetSelections: Record<string, string[]> = {};
const facetListeners = new Set<(s: Record<string, string[]>) => void>();

/** Snapshot of the active facet selections (field → selected values). */
export function getFacetSelections(): Record<string, string[]> {
  return facetSelections;
}

export function subscribeFacetSelections(
  listener: (s: Record<string, string[]>) => void,
): () => void {
  facetListeners.add(listener);
  try {
    listener(facetSelections);
  } catch {
    // Match the date-filter posture: swallow listener errors.
  }
  return () => {
    facetListeners.delete(listener);
  };
}

/** Toggle one facet value on/off; empty fields are dropped from the map. */
export function toggleFacetValue(field: string, value: string): void {
  const cur = facetSelections[field] ?? [];
  const next = cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value];
  const out: Record<string, string[]> = { ...facetSelections };
  if (next.length === 0) delete out[field];
  else out[field] = next;
  facetSelections = out;
  for (const l of facetListeners) {
    try {
      l(facetSelections);
    } catch {
      // ignore
    }
  }
}

export function clearFacetSelections(): void {
  if (Object.keys(facetSelections).length === 0) return;
  facetSelections = {};
  for (const l of facetListeners) {
    try {
      l(facetSelections);
    } catch {
      // ignore
    }
  }
}

export function hasFacetSelections(): boolean {
  return Object.keys(facetSelections).length > 0;
}

/**
 * Test-only: reset the module-level state. Mirrors
 * `__resetUserStateForTest`. Not exported via barrel files.
 */
export function __resetSearchFiltersForTest(): void {
  current = EMPTY;
  listeners.clear();
  facetSelections = {};
  facetListeners.clear();
}

// ----- Slice 489 §5 — URL substrate adapter -----
//
// Exposes searchFiltersState as a URL-addressable store under the abstract
// storeId "search.filters". The router's URLProjector subscribes for
// state→URL writes; the NavigationHandler (slice 492) calls
// restoreFromSnapshot when realizing a Navigation Intent.

export interface SearchFiltersSnapshot {
  modifiedFromMs?: string | string[];
  modifiedToMs?: string | string[];
}

export function serializeSearchFilters(): SearchFiltersSnapshot {
  const out: SearchFiltersSnapshot = {};
  if (typeof current.modifiedFromMs === 'number') {
    out.modifiedFromMs = String(current.modifiedFromMs);
  }
  if (typeof current.modifiedToMs === 'number') {
    out.modifiedToMs = String(current.modifiedToMs);
  }
  return out;
}

export function restoreSearchFilters(snapshot: SearchFiltersSnapshot): void {
  const fromRaw = Array.isArray(snapshot.modifiedFromMs)
    ? snapshot.modifiedFromMs[0]
    : snapshot.modifiedFromMs;
  const toRaw = Array.isArray(snapshot.modifiedToMs)
    ? snapshot.modifiedToMs[0]
    : snapshot.modifiedToMs;
  const from =
    typeof fromRaw === 'string' && fromRaw.length > 0 ? Number(fromRaw) : undefined;
  const to =
    typeof toRaw === 'string' && toRaw.length > 0 ? Number(toRaw) : undefined;
  setFilterRange(
    typeof from === 'number' && Number.isFinite(from) ? from : undefined,
    typeof to === 'number' && Number.isFinite(to) ? to : undefined,
  );
}
