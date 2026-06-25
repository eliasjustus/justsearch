// SPDX-License-Identifier: Apache-2.0
/**
 * Bounded-projection primitives — tempdoc 550 thesis III(b).
 *
 * "Aggregation is a property of the projection, not the data." A high-cardinality glance/timeline
 * projection (the left-rail task list; the Activity timeline now that indexing terminal outcomes
 * flow into it) must **cap + group + summarize** so a true backlog or a large indexing burst can
 * never render as N individual rows. These are the small, pure, independently-tested primitives the
 * projection layer draws from — extracted (rather than left inline in one component) because two
 * consumers share the reason to change: a glance/timeline view must summarize bursts, not render
 * 1:1. They are deliberately minimal — no contorting one function to fit two layouts (that would be
 * fake-DRY); each consumer composes the primitives it needs.
 *
 * Not consumed by the advisory inbox: that is a filtered, scrollable drawer (already bounded by
 * scroll + the advisory ring + its filter chips) — a different bounding regime. Forcing a hard cap
 * there would be over-unification (the §5 AHA guardrail) and a UX regression.
 */

/** Count items per key. Shared by the rail (status chips) and Activity (which collections burst). */
export function countByKey<T>(
  items: readonly T[],
  keyOf: (item: T) => string,
): Map<string, number> {
  const m = new Map<string, number>();
  for (const it of items) {
    const k = keyOf(it);
    m.set(k, (m.get(k) ?? 0) + 1);
  }
  return m;
}

export interface CapResult<T> {
  /** The kept items (the first `cap`). */
  readonly shown: readonly T[];
  /** How many items were dropped past the cap (drives a "+N more" affordance). */
  readonly overflow: number;
}

/** Cap a list to its first `cap` items, reporting the overflow count. Used by the rail. */
export function capWithOverflow<T>(items: readonly T[], cap: number): CapResult<T> {
  if (cap < 0) {
    throw new Error(`cap must be >= 0, got ${cap}`);
  }
  const shown = items.slice(0, cap);
  return { shown, overflow: items.length - shown.length };
}

/**
 * Collapse runs of adjacent same-key rows into one summary, preserving input order and leaving
 * other rows individual. `keyOf` returns the grouping key, or `null` for a row that must never be
 * grouped (rendered as-is). A run of length `>= minRun` (default 2) of the same non-null key is
 * replaced by a single `summarize(key, items)` value; shorter runs pass through unchanged.
 *
 * Used by the Activity timeline to collapse an indexing burst ("Indexed 1,240 · default") instead
 * of rendering 1,240 individual `kind=index` rows.
 */
export function collapseBursts<T, S>(
  rows: readonly T[],
  opts: {
    keyOf: (row: T) => string | null;
    summarize: (key: string, items: readonly T[]) => S;
    minRun?: number;
  },
): Array<T | S> {
  const minRun = opts.minRun ?? 2;
  const out: Array<T | S> = [];
  let i = 0;
  while (i < rows.length) {
    const key = opts.keyOf(rows[i]!);
    if (key === null) {
      out.push(rows[i]!);
      i += 1;
      continue;
    }
    // Extend the run while the next row shares this non-null key.
    let j = i + 1;
    while (j < rows.length && opts.keyOf(rows[j]!) === key) {
      j += 1;
    }
    const run = rows.slice(i, j);
    if (run.length >= minRun) {
      out.push(opts.summarize(key, run));
    } else {
      for (const r of run) out.push(r);
    }
    i = j;
  }
  return out;
}
