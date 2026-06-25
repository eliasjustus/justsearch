// SPDX-License-Identifier: Apache-2.0
/**
 * Resource-view renderer registry — slice 3a.1.4 §B.6.
 *
 * Parallel to the JSON Forms renderer registry (`./registry.ts` from
 * slice 3a.0). The JSON Forms registry dispatches by JSON schema shape;
 * this registry dispatches by `(Category, hint?, density?)` — the typed
 * Resource Category axis introduced in slice 444a, plus the optional
 * render-hint axis on `MetricRef` (slice 3a.1.4 §B.4).
 *
 * Default registrations cover the four Categories that have a shipping
 * renderer at slice 3a.1.4 close: STATE → `<jf-status-card>`,
 * EVENT_STREAM → `<jf-status-card>` (placeholder until
 * `<jf-event-stream-list>` ships in Phase 7), TABULAR → `<jf-table>`,
 * TIMESERIES → `<jf-timeseries-sparkline>` (Phase 4). HISTORY has
 * no default renderer (slice 444c will ship one); dispatch returns
 * `null` and `isCategorySupported(category)` reports `false` for it,
 * so consumers can distinguish "Category not yet implemented" from
 * "wrong hint." (Slice 448 phase 6 retired LOG_TAIL — operator-trace
 * surfaces use the sibling DiagnosticChannel primitive instead.)
 *
 * Plugin extension: plugins register additional entries via
 * `registerResourceRenderer(entry)` during their `register(hostApi)`
 * hook (slice 3a.1.6 PluginRegistry V1.1). The `unregister` hook
 * calls the unsubscribe function returned by `register*Renderer`.
 *
 * The registry is a flat list (no per-Category buckets); dispatch
 * filters by Category first, then ranks. This keeps the data model
 * simple at the cost of an O(N) filter — N is small (single-digit
 * default + plugin contributions), so the cost is negligible.
 */

import type { DensityVariant } from './userConfig.js';

/**
 * Information-shape axis on a `Resource`. Mirrors the Java `Category`
 * enum (`modules/app-agent-api/.../registry/Category.java`). Hand-
 * written here as a string-union; slice 3a.1.8 Phase 4 will supersede
 * with contract-driven types. The wire-types.ts generated file does
 * not currently emit `Category` because the `Resource` registry record
 * is not in `WireTypesTsGenerationTest.WIRE_TYPES`.
 */
export type Category =
  | 'STATE'
  | 'EVENT_STREAM'
  | 'HISTORY'
  | 'TABULAR'
  | 'TIMESERIES';

/**
 * Closed list of Categories. Useful for exhaustive switches and
 * test fixtures. Slice 448 phase 6 retired LOG_TAIL.
 */
export const CATEGORIES: readonly Category[] = [
  'STATE',
  'EVENT_STREAM',
  'HISTORY',
  'TABULAR',
  'TIMESERIES',
] as const;

/**
 * Registered Resource-view renderer entry.
 *
 *  - `category`: the typed Resource Category this entry handles.
 *  - `hint`: optional render-hint refinement (e.g., `'SPARK'`,
 *    `'GAUGE'`, `'HISTOGRAM'`). Matched literally against the
 *    `MetricRef.hint` value or any FE-supplied hint string.
 *    Absence in the entry means "matches any hint" (default behavior).
 *  - `density`: optional density refinement (per
 *    `userConfig.DensityVariant`). Same semantics as hint.
 *  - `rank`: higher wins on ties. Defaults at rank 0; specialized
 *    plugin contributions can rank above defaults to override.
 *  - `tag`: the custom-element tag name dispatch returns. The
 *    consumer instantiates and configures the element (matches the
 *    JSON Forms registry's tag-based pattern; the consumer knows
 *    which props to set on the element type).
 */
export interface ResourceRendererEntry {
  category: Category;
  hint?: string;
  density?: DensityVariant;
  rank: number;
  tag: string;
}

/** Internal registry storage. Cleared in tests via `clearResourceRendererRegistry()`. */
const _entries: ResourceRendererEntry[] = [];

/**
 * Register a renderer. Returns an unsubscribe function that removes
 * the entry from the registry. Mirrors slice 3a.1.6's
 * register-and-return-unsubscribe pattern.
 *
 * The same `(category, hint, density, tag)` tuple may be registered
 * multiple times; ranks decide which wins. Plugins should pick a rank
 * appropriate to their override intent.
 */
export function registerResourceRenderer(
  entry: ResourceRendererEntry,
): () => void {
  _entries.push(entry);
  return () => {
    const i = _entries.indexOf(entry);
    if (i >= 0) {
      _entries.splice(i, 1);
    }
  };
}

/**
 * Dispatch query: find the highest-ranked tag whose entry matches the
 * given Category + (optional) hint + (optional) density.
 *
 * Match rules:
 *  - Entry's `category` MUST equal the query's `category`.
 *  - If entry's `hint` is set, it MUST equal the query's `hint`.
 *    If entry's `hint` is unset, the entry matches regardless of the
 *    query's hint.
 *  - Same rule for `density`.
 *  - Entries with more specific matches (hint set, density set) outrank
 *    entries with less specific matches at the same numeric rank;
 *    specificity is broken by entry's hint+density count.
 *
 * Returns the winning entry's `tag`, or `null` when no entry matches.
 * Use `isCategorySupported(category)` to distinguish "no shipping
 * renderer for this Category" from "wrong hint."
 */
export function dispatchResourceRenderer(query: {
  category: Category;
  hint?: string;
  density?: DensityVariant;
}): string | null {
  let best: { entry: ResourceRendererEntry; specificity: number } | null = null;
  for (const entry of _entries) {
    if (entry.category !== query.category) continue;
    if (entry.hint !== undefined && entry.hint !== query.hint) continue;
    if (entry.density !== undefined && entry.density !== query.density) continue;
    const specificity =
      (entry.hint !== undefined ? 1 : 0) + (entry.density !== undefined ? 1 : 0);
    if (
      best === null ||
      entry.rank > best.entry.rank ||
      (entry.rank === best.entry.rank && specificity > best.specificity)
    ) {
      best = { entry, specificity };
    }
  }
  return best?.entry.tag ?? null;
}

/**
 * Whether the Category has at least one registered renderer (any rank,
 * any hint, any density). Consumers use this to render an "unsupported"
 * placeholder distinct from "no match for this hint" when a Category's
 * producing slice (e.g., 444c HISTORY) hasn't shipped.
 */
export function isCategorySupported(category: Category): boolean {
  return _entries.some((e) => e.category === category);
}

/**
 * Read-only snapshot of registered entries. Useful for diagnostics +
 * tests that need to assert default registrations present.
 */
export function getResourceRendererRegistry(): readonly ResourceRendererEntry[] {
  return _entries.slice();
}

/**
 * Clear the registry. Tests-only. Production code should never call
 * this; the registry is process-singleton and registrations land at
 * import time.
 */
export function clearResourceRendererRegistry(): void {
  _entries.length = 0;
}
