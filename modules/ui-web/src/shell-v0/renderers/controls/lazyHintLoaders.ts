// SPDX-License-Identifier: Apache-2.0
/**
 * Lazy x-ui-renderer loaders (569 §18.C #2 / Phase 0).
 *
 * The four bespoke surface renderers (folder-card / shortcuts-table / list-items / metric-card) are
 * only needed once the user navigates to the surface that declares them (Library / Help / Health) —
 * not at boot, and not on every surface. So they are NOT eagerly side-effect-imported by
 * `renderers/registry.ts`; instead each is dynamically imported on first dispatch via
 * {@link ensureXUiRenderer}. The import's top-level `registerXUiRenderer(...)` then populates the
 * hint registry and `notify()`s, so the mounting `XUiRendererControl` re-renders and resolves the
 * tag. This moves their bytes out of the eager `app_main` bundle into surface-visit-time chunks.
 *
 * The closed-vocabulary guarantee is unchanged: each hint still maps to exactly one renderer (the
 * `check-declared-surfaces` gate scans the source `registerXUiRenderer` calls, which are untouched);
 * lazy loading only defers WHEN the module loads, never WHAT is registrable.
 *
 * NOTE: this module deliberately imports NOTHING from `XUiRendererControl` (which owns the registry
 * + dispatcher) so the dependency is one-directional (control → loaders), keeping the UI-cycle gate
 * green. Callers already know it is a registry miss before calling `ensureXUiRenderer`.
 */

/** hint → dynamic import of the module whose top-level `registerXUiRenderer(...)` self-registers it. */
const LAZY_HINT_LOADERS: Readonly<Record<string, () => Promise<unknown>>> = {
  'folder-card': () => import('./FolderCardRenderer.js'),
  'shortcuts-table': () => import('./ShortcutsTableRenderer.js'),
  'list-items': () => import('./ListItemsRenderer.js'),
  'metric-card': () => import('./MetricCardRenderer.js'),
};

const _inflight = new Map<string, Promise<void>>();
const _loaded = new Set<string>();

/** True when `hint` has a lazy loader (so a current registry miss is "not loaded yet", not "unknown"). */
export function hasLazyHintLoader(hint: string): boolean {
  return Object.prototype.hasOwnProperty.call(LAZY_HINT_LOADERS, hint);
}

/** The set of hints that have a lazy loader (for the contract test / diagnostics). */
export function listLazyHints(): readonly string[] {
  return Object.keys(LAZY_HINT_LOADERS).sort();
}

/**
 * Ensure the renderer for `hint` is loaded (its module imported, which self-registers it). No-op for
 * a non-lazy hint or one already loaded. Idempotent + de-duped: concurrent calls for the same hint
 * share one in-flight import. Awaiting it guarantees the hint is registered afterwards.
 */
export async function ensureXUiRenderer(hint: string): Promise<void> {
  if (_loaded.has(hint)) return;
  const loader = LAZY_HINT_LOADERS[hint];
  if (loader === undefined) return; // not lazy → caller shows the missing-renderer diagnostic
  let p = _inflight.get(hint);
  if (p === undefined) {
    p = loader().then(() => {
      _loaded.add(hint);
      _inflight.delete(hint);
    });
    _inflight.set(hint, p);
  }
  await p;
}
