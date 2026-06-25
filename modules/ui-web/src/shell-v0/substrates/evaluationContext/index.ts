// SPDX-License-Identifier: Apache-2.0
/**
 * EvaluationContext — Tempdoc 543 §13.2.1 kernel primitive + substrate.
 *
 * The primitive: every WhenExpression evaluation runs against a
 * *layered* projection, not against the ambient ShellContext alone.
 *
 *   EvaluationContext := Scope                  (ambient — 543 §3.B)
 *                      ∪ TargetFacts(addressable)    (per-row facts)
 *                      ∪ EnvironmentSignals          (transient)
 *
 * TargetFacts are produced by per-AddressableKind projector functions.
 * Per 507 §2.2 FDM boundary: projectors live in feature modules; the
 * kernel owns the registry, not the implementations.
 *
 * KCS bridge per §19: under future three-layer KCS this becomes
 * `useEvaluationContext({addressable, scope?})` + projector-registry
 * capability modules.
 *
 * Per §13.2.1 confidence-pass: the WhenExpression grammar is flat-key
 * only. Projectors return underscore-flattened keys (e.g.,
 * `selectedItem_kind`, `result_mimeType`) — NOT dotted paths.
 * Predicates written like `selectedItem_kind == "search-result"`
 * compile cleanly against the existing parser.
 *
 * Per §13.2.1 perf finding: at plugin-ecosystem scale, projection
 * runs at 500+ evals/sec peak. This module memoizes per
 * `(scopeVersion, addressable.kind, addressable.id)` cache key.
 * `bumpScopeVersion()` invalidates on ShellContext change.
 */

import { getShellContext } from '../../state/shellContextState.js';
import type {
  Addressable,
  AddressableKind,
} from '../addressable.js';

// ============================================================
// Projector registry
// ============================================================

/**
 * A projector projects an Addressable's payload into flat-key facts
 * the WhenExpression evaluator can read. Conventional key naming:
 *
 *   `<prefix>_<field>` — e.g., `selectedItem_kind`,
 *   `result_mimeType`, `citation_confidence`.
 *
 * The prefix avoids collision with Scope's flat keys (`audience`,
 * `selectionKind`, etc.) — pick a prefix that makes sense for the
 * AddressableKind.
 *
 * Projectors must be PURE (no I/O, no observable side effects). The
 * composer memoizes their results across many evaluations within a
 * scope-version.
 */
export type Projector = (addressable: Addressable) => Readonly<
  Record<string, unknown>
>;

const _projectors = new Map<NonNullable<AddressableKind>, Projector>();
const _projectorListeners = new Set<() => void>();

function notifyProjectorChange(): void {
  for (const l of _projectorListeners) {
    try {
      l();
    } catch {
      /* swallow */
    }
  }
}

/**
 * Register a projector for an AddressableKind. Re-registering for the
 * same kind replaces the prior projector (idempotent under HMR).
 *
 * Per layer placement: callers are feature modules (or plugins for
 * their own kinds). The kernel does not own the projector functions.
 */
export function registerProjector(
  kind: NonNullable<AddressableKind>,
  project: Projector,
): void {
  _projectors.set(kind, project);
  notifyProjectorChange();
  // Bump the scope version so any stale projection cache invalidates.
  bumpScopeVersion();
}

/** Remove a projector by kind. */
export function unregisterProjector(
  kind: NonNullable<AddressableKind>,
): boolean {
  const removed = _projectors.delete(kind);
  if (removed) {
    notifyProjectorChange();
    bumpScopeVersion();
  }
  return removed;
}

/** Look up a projector by kind. */
export function getProjector(
  kind: NonNullable<AddressableKind>,
): Projector | undefined {
  return _projectors.get(kind);
}

/** All registered kinds, sorted. */
export function listProjectors(): readonly NonNullable<AddressableKind>[] {
  return Array.from(_projectors.keys()).sort();
}

/** Subscribe to projector-registry changes. */
export function subscribeProjectors(listener: () => void): () => void {
  _projectorListeners.add(listener);
  return () => {
    _projectorListeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetProjectorRegistryForTest(): void {
  _projectors.clear();
  _projectorListeners.clear();
  _scopeVersion = 0;
  _memo.clear();
}

// ============================================================
// Scope version + memoization
// ============================================================

let _scopeVersion = 0;

/**
 * Bump the scope version. Called when ShellContext changes, when a
 * projector registers/unregisters, or when a plugin manifest
 * activates. Invalidates the projection memo.
 *
 * Per §13.2.1: the boot wiring in Shell.ts subscribes to
 * `subscribeShellContext` and calls this on every notify cycle.
 */
export function bumpScopeVersion(): void {
  _scopeVersion += 1;
  _memo.clear();
}

export function getScopeVersion(): number {
  return _scopeVersion;
}

interface MemoEntry {
  readonly version: number;
  readonly facts: Readonly<Record<string, unknown>>;
}

/**
 * Memoization keyed by `(scope-version, addressable.kind,
 * addressable.id)`. Entries from prior scope versions are evicted
 * lazily on lookup (the version check at read time).
 */
const _memo = new Map<string, MemoEntry>();

function memoKey(addressable: Addressable): string {
  return `${addressable.kind ?? 'null'}\x00${addressable.id}`;
}

function getMemoizedFacts(
  addressable: Addressable,
  projector: Projector,
): Readonly<Record<string, unknown>> {
  const key = memoKey(addressable);
  const cached = _memo.get(key);
  if (cached && cached.version === _scopeVersion) {
    return cached.facts;
  }
  const facts = projector(addressable);
  _memo.set(key, { version: _scopeVersion, facts });
  return facts;
}

// ============================================================
// Composer
// ============================================================

export interface BuildEvaluationContextOptions {
  /**
   * The Addressable to project facts for. May be `null` (global
   * evaluation — only Scope ∪ Environment contribute).
   */
  readonly addressable?: Addressable | null;
  /**
   * Optional override for the ambient Scope. Defaults to
   * `getShellContext()`. Test helpers pass a fixture here; production
   * code rarely overrides.
   */
  readonly scope?: Readonly<Record<string, unknown>>;
  /**
   * Optional EnvironmentSignals — transient values that don't belong
   * in Scope (e.g., the current timestamp for a `when` predicate that
   * checks "older than 24h"). Caller-provided; the substrate does
   * not own environment values today.
   */
  readonly environment?: Readonly<Record<string, unknown>>;
}

/**
 * Build the layered EvaluationContext: `Scope ∪ TargetFacts ∪
 * EnvironmentSignals`, in priority order such that TargetFacts and
 * EnvironmentSignals OVERRIDE Scope keys on conflict (a per-row
 * fact about an addressable wins over an ambient-scope value with
 * the same key, which is the intuitive semantic).
 *
 * Per §13.2.1 perf: TargetFacts are memoized per
 * `(scope-version, addressable-id)`. Repeated evaluations of the
 * same predicate against the same addressable hit the memo after
 * the first call within a scope-version.
 */
export function buildEvaluationContext(
  opts: BuildEvaluationContextOptions = {},
): Readonly<Record<string, unknown>> {
  const scope =
    opts.scope ?? (getShellContext() as unknown as Record<string, unknown>);
  const addressable = opts.addressable ?? null;
  const environment = opts.environment ?? {};

  // Fast path: no addressable → no projection cost.
  if (addressable === null || addressable.kind === null) {
    if (Object.keys(environment).length === 0) {
      return scope;
    }
    return { ...scope, ...environment };
  }

  const projector = _projectors.get(addressable.kind);
  if (!projector) {
    // No projector registered for this kind — Scope only.
    return Object.keys(environment).length === 0
      ? scope
      : { ...scope, ...environment };
  }

  const facts = getMemoizedFacts(addressable, projector);
  return { ...scope, ...environment, ...facts };
}

// ============================================================
// §25.β5 — Data-result cache (DataEffect arm consumer)
// ============================================================

/**
 * Per-resultKey latest data return. Populated by applyEffect when a
 * `data-result` Effect dispatches. EvaluationContext predicates +
 * projectors can read the latest value via `getLatestDataResult(key)`
 * so `when` clauses like `latestSearch_resultCount > 0` evaluate
 * against fresh operation returns.
 *
 * Design choice: a flat keyed map (not per-Addressable) because data
 * results are conceptually global state ("the most recent search
 * succeeded"), not per-row. Per-row data goes through Projectors via
 * Addressable payloads, not through this cache.
 */
export interface LatestDataResult {
  readonly operationId: string;
  readonly result: unknown;
  readonly at: number;
}

const _dataResults = new Map<string, LatestDataResult>();

export function setLatestDataResult(
  resultKey: string,
  result: LatestDataResult,
): void {
  _dataResults.set(resultKey, result);
  // Bump scope version so when-clauses that depend on the data
  // result re-evaluate against the fresh value.
  bumpScopeVersion();
}

export function getLatestDataResult(
  resultKey: string,
): LatestDataResult | undefined {
  return _dataResults.get(resultKey);
}

export function listDataResultKeys(): readonly string[] {
  return Array.from(_dataResults.keys()).sort();
}

export function clearLatestDataResult(resultKey: string): boolean {
  const removed = _dataResults.delete(resultKey);
  if (removed) bumpScopeVersion();
  return removed;
}

/** Test-only reset. */
export function __resetDataResultsForTest(): void {
  _dataResults.clear();
}
