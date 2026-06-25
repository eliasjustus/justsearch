// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — Aggregate-in-context registry.
 *
 * Single registry keyed by `(WireAggregateKind, SurfaceContextKind)`.
 * Each (kind, context) cell can hold multiple entries; dispatch
 * returns the highest-ranked. Plugin contributions register with
 * elevated rank to override core strategies (capability gating is
 * applied at install time by `PluginRegistry`, not here).
 *
 * The shape mirrors the existing `resourceRegistry.ts` (slice
 * 3a.1.4 §B.6) — flat list with linear dispatch, plugin-friendly
 * register/unregister, idempotent re-registration via rank.
 *
 * The strategy signature closes over the typed aggregate + context.
 * Returning `null | typeof nothing` means "the canonical strategy
 * decided to render nothing here" (e.g., audience gate failed).
 */

import type { TemplateResult, nothing as litNothing } from 'lit';
import type {
  AggregateOf,
  WireAggregateKind,
} from './aggregateKinds.js';
import type {
  SurfaceContextKind,
  SurfaceContextOf,
} from './surfaceContextKinds.js';
import type { Provenance } from '../../api/types/registry.js';

/**
 * The render type a canonical strategy may return. `null` /
 * `typeof litNothing` are both acceptable for "render nothing"
 * (matches Lit's templating conventions).
 */
export type StrategyResult = TemplateResult | typeof litNothing | null;

/**
 * Canonical strategy signature. Closes over both the typed
 * aggregate and the context's prop shape. The host parameter is
 * passed through for strategies that need to dispatch additional
 * registry lookups (e.g., resolving operation IDs to operations).
 */
export type AggregateStrategy<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
> = (
  aggregate: AggregateOf<K>,
  ctx: SurfaceContextOf<C>,
  host: StrategyHost,
) => StrategyResult;

/**
 * Minimal host surface passed to strategies. Today only the API
 * base is needed; future cells (e.g., operation invocation buttons
 * that fetch fresh status before render) can grow this.
 */
export interface StrategyHost {
  readonly apiBase: string;
}

interface Entry<K extends WireAggregateKind, C extends SurfaceContextKind> {
  readonly aggregate: K;
  readonly context: C;
  readonly rank: number;
  readonly strategy: AggregateStrategy<K, C>;
  readonly source: 'core' | { plugin: string };
  /**
   * 548 §4.3 — uniform Provenance carried for attribution. `source` remains the
   * dedup/removal identity (511-followup-D `sameSlot`); provenance is additive
   * (optional so core registrations without a plugin provenance are unaffected).
   */
  readonly provenance?: Provenance;
}

// Stored as Entry<any, any> internally because the registry holds
// heterogeneous (K, C) pairs. Dispatch narrows back to the requested
// pair via the typed dispatch function below.
const _entries: Array<Entry<WireAggregateKind, SurfaceContextKind>> = [];

/**
 * Tempdoc 511-followup-D-patches — slot identity for dedup.
 *
 * Two entries occupy the same slot when their `(aggregate, context,
 * rank, source)` agree. Re-registering a slot replaces the prior
 * entry (idempotent under Vite HMR re-runs of bootstrap.ts;
 * `_entries` is module-state and survives HMR).
 *
 * `source` is a discriminated union — string `'core'` or
 * `{ plugin: string }` — so equality is checked structurally.
 */
function sameSlot(
  a: Entry<WireAggregateKind, SurfaceContextKind>,
  b: Entry<WireAggregateKind, SurfaceContextKind>,
): boolean {
  if (a.aggregate !== b.aggregate) return false;
  if (a.context !== b.context) return false;
  if (a.rank !== b.rank) return false;
  if (typeof a.source === 'string' || typeof b.source === 'string') {
    return a.source === b.source;
  }
  return a.source.plugin === b.source.plugin;
}

/** Register a canonical strategy. Returns an unregister function. */
export function registerAggregateStrategy<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(entry: Entry<K, C>): () => void {
  // The `unknown` indirection is required because TypeScript's
  // variance check is bivariant on the strategy function but rejects
  // direct K/C narrowing in a strict context. The cast is safe — the
  // registry only ever reads entries via `dispatchAggregateStrategy`
  // which re-narrows on the way out.
  const stored = entry as unknown as Entry<
    WireAggregateKind,
    SurfaceContextKind
  >;
  // Tempdoc 511-followup-D-patches: replace, don't append. Re-running
  // bootstrap.ts under HMR would otherwise accumulate duplicates in
  // `_entries` (module-state survives module-instance replacement).
  // The dispatch semantics ("highest rank, ties → last-registered
  // wins") are unchanged: a plugin override at a different rank or
  // different `source` still occupies its own slot.
  const existingIdx = _entries.findIndex((e) => sameSlot(e, stored));
  if (existingIdx >= 0) _entries.splice(existingIdx, 1);
  _entries.push(stored);
  return () => {
    const i = _entries.indexOf(stored);
    if (i >= 0) _entries.splice(i, 1);
  };
}

/**
 * Resolve the active strategy for the given `(aggregate, context)`
 * cell. Returns the highest-ranked entry; ties broken in
 * registration order (later wins, so the most recent registration —
 * typically the plugin override — wins at equal rank).
 */
export function dispatchAggregateStrategy<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(aggregate: K, context: C): AggregateStrategy<K, C> | null {
  let best: Entry<WireAggregateKind, SurfaceContextKind> | null = null;
  for (const entry of _entries) {
    if (entry.aggregate !== aggregate) continue;
    if (entry.context !== context) continue;
    if (best === null || entry.rank >= best.rank) {
      best = entry;
    }
  }
  if (!best) return null;
  return best.strategy as AggregateStrategy<K, C>;
}

/**
 * Render an aggregate in a context. Wrapper that handles missing
 * strategy + null returns. Aggregate components (e.g.,
 * `<jf-operation>`) use this rather than calling dispatch + strategy
 * separately.
 */
export function renderAggregate<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(
  aggregate: K,
  context: C,
  data: AggregateOf<K>,
  ctx: SurfaceContextOf<C>,
  host: StrategyHost,
): StrategyResult {
  const strategy = dispatchAggregateStrategy(aggregate, context);
  if (!strategy) return null;
  return strategy(data, ctx, host);
}

/**
 * Diagnostic snapshot. Useful for tests that assert "the canonical
 * core strategy for (Operation, button) is registered" and for the
 * retroactive Pass-8 sweep that walks the registry to find missing
 * cells.
 */
export function getRegisteredCells(): ReadonlyArray<{
  aggregate: WireAggregateKind;
  context: SurfaceContextKind;
  rank: number;
  source: 'core' | { plugin: string };
}> {
  return _entries.map((e) => ({
    aggregate: e.aggregate,
    context: e.context,
    rank: e.rank,
    source: e.source,
  }));
}

/**
 * Test-only reset of the entries store. Production code never calls
 * this.
 *
 * Tempdoc 543 §20.7 C2: back-compat preservation. Pre-Slice 6 this
 * cleared only `_entries` (Slice 6's __contextPolicies didn't exist).
 * The Slice 6 follow-up briefly clobbered `_contextPolicies` here as
 * well, silently changing the contract for existing callers. The
 * reverted-to-back-compat behavior: this clears ONLY `_entries`.
 * Tests that need to also reset dispatch-policy state call
 * `__resetAggregateSubstrateForTest` (below).
 */
export function __clearAggregateRegistry(): void {
  _entries.length = 0;
}

/**
 * Tempdoc 543 §20.7 C2 — full substrate reset for tests that need
 * both `_entries` AND `_contextPolicies` cleared. Use this in test
 * setups that exercise Slice 6's Multi-Provider Dispatch policies;
 * existing tests that only cared about entries continue calling
 * `__clearAggregateRegistry` and see unchanged behavior.
 */
export function __resetAggregateSubstrateForTest(): void {
  _entries.length = 0;
  _contextPolicies.clear();
}

// ============================================================
// Tempdoc 543 §13.3.2 — Multi-Provider Dispatch
// ============================================================

/**
 * Per-SurfaceContextKind dispatch policy.
 *
 *   - `'winner'` (default): single highest-ranked strategy renders.
 *     Existing 511 semantics; preserves backward compat.
 *   - `'merge'`: all matching strategies render and their outputs
 *     compose. Caller of `renderAggregateMulti` receives an array
 *     in rank-descending order; the chrome host decides composition
 *     (concat with separator, sectioned popover, etc.).
 *   - `'rank-first-non-empty'`: walk in rank-descending order and
 *     return the first strategy whose output is not `null` /
 *     `nothing`. Useful when high-rank strategies may decline
 *     (audience gate, missing data) and a default lower-rank
 *     strategy should pick up.
 *
 * Unset contexts default to `'winner'`. Slice 8 (HoverPreview) flips
 * `'hover-preview'` to `'merge'` so multiple strategies stack into
 * one popover.
 */
export type DispatchPolicy = 'winner' | 'merge' | 'rank-first-non-empty';

const _contextPolicies = new Map<SurfaceContextKind, DispatchPolicy>();

/** Configure the dispatch policy for a SurfaceContextKind. */
export function setDispatchPolicy(
  context: SurfaceContextKind,
  policy: DispatchPolicy,
): void {
  _contextPolicies.set(context, policy);
}

/** Look up the configured policy for a SurfaceContextKind. */
export function getDispatchPolicy(
  context: SurfaceContextKind,
): DispatchPolicy {
  return _contextPolicies.get(context) ?? 'winner';
}

/** Test-only reset of policy configuration. */
export function __clearDispatchPolicies(): void {
  _contextPolicies.clear();
}

/**
 * Multi-strategy resolver. Returns ALL strategies matching the
 * `(aggregate, context)` cell in rank-descending order. Ties broken
 * by registration order (later wins among equal-rank, matching the
 * single-winner dispatch).
 *
 * Always returns an array (possibly empty); the caller decides
 * composition. Use `renderAggregateMulti` for the common "render
 * each, drop null/nothing" case.
 */
export function dispatchAggregateStrategies<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(aggregate: K, context: C): AggregateStrategy<K, C>[] {
  const matching: Entry<WireAggregateKind, SurfaceContextKind>[] = [];
  for (const entry of _entries) {
    if (entry.aggregate !== aggregate) continue;
    if (entry.context !== context) continue;
    matching.push(entry);
  }
  matching.sort((a, b) => {
    if (b.rank !== a.rank) return b.rank - a.rank;
    return _entries.indexOf(b) - _entries.indexOf(a);
  });
  return matching.map((e) => e.strategy as AggregateStrategy<K, C>);
}

/**
 * Multi-strategy render. Walks `dispatchAggregateStrategies` per the
 * configured policy:
 *
 *   - `'winner'`: returns at most one result (top strategy's output);
 *     empty array if no strategy or strategy returned null/nothing.
 *   - `'merge'`: returns all non-empty results in rank-descending
 *     order. Caller composes them (e.g., stacked popover sections).
 *   - `'rank-first-non-empty'`: returns the first non-empty result
 *     walking in rank order.
 *
 * Consumers can treat empty / single / multiple uniformly; the
 * existing single-winner `renderAggregate` remains available for
 * back-compat call sites.
 */
export function renderAggregateMulti<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(
  aggregate: K,
  context: C,
  data: AggregateOf<K>,
  ctx: SurfaceContextOf<C>,
  host: StrategyHost,
): Array<TemplateResult> {
  const policy = getDispatchPolicy(context);
  const strategies = dispatchAggregateStrategies(aggregate, context);
  if (strategies.length === 0) return [];
  const out: Array<TemplateResult> = [];
  for (const strategy of strategies) {
    const r = strategy(data, ctx, host);
    if (r === null) continue;
    // Lit's `nothing` is a Symbol; check by typeof.
    if (typeof r === 'symbol') continue;
    out.push(r as TemplateResult);
    if (policy === 'winner' || policy === 'rank-first-non-empty') break;
  }
  return out;
}

/**
 * Plugin uninstall hook. Removes all entries contributed by the given
 * plugin id. Mirrors the pattern in `SurfaceCatalogClient` /
 * `ResourceCatalogClient` / `RecoveryOverlayClient`. Idempotent.
 */
export function removePluginAggregateStrategies(pluginId: string): void {
  for (let i = _entries.length - 1; i >= 0; i -= 1) {
    const src = _entries[i]!.source;
    if (typeof src === 'object' && src !== null && src.plugin === pluginId) {
      _entries.splice(i, 1);
    }
  }
}
