// SPDX-License-Identifier: Apache-2.0
/**
 * xUiRendererRegistry — the leaf hint→tag registry for the `x-ui-renderer` dispatcher (543 §13.3.1).
 *
 * 569 Phase 0 extracted this from `XUiRendererControl` into a dependency LEAF so the dispatcher, the
 * renderers (which self-register), and the lazy loaders can all depend on it without forming an
 * import cycle (the lazy loaders dynamically import the renderers, which register here — if they
 * registered against the control, that closed a control→loaders→renderer→control cycle the UI-cycle
 * gate forbids). The registry maps `hintName → custom-element-tag`; first-party + plugin renderers
 * register via {@link registerXUiRenderer}. Lookups are case-sensitive on the hint name.
 */

const _hintRegistry = new Map<string, string>();
const _listeners = new Set<() => void>();

function notify(): void {
  for (const l of _listeners) {
    try {
      l();
    } catch {
      /* swallow */
    }
  }
}

/**
 * Register a renderer for an `x-ui-renderer` hint value. `mountTag` must be a custom-element tag
 * already defined elsewhere (the registry owns hint→tag routing, not element registration).
 * Re-registering the same hint replaces the prior entry (idempotent under Vite HMR).
 */
export function registerXUiRenderer(hint: string, mountTag: string): void {
  _hintRegistry.set(hint, mountTag);
  notify();
}

/** Remove a registered renderer for a hint. */
export function unregisterXUiRenderer(hint: string): boolean {
  const removed = _hintRegistry.delete(hint);
  if (removed) notify();
  return removed;
}

/** Look up the mount tag for an x-ui-renderer hint, or undefined. */
export function getXUiRendererTag(hint: string): string | undefined {
  return _hintRegistry.get(hint);
}

/** All registered hint names (sorted) — for diagnostics + tests. */
export function listXUiRenderers(): readonly string[] {
  return Array.from(_hintRegistry.keys()).sort();
}

/** Subscribe to registry-change notifications. */
export function subscribeXUiRenderers(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetXUiRendererRegistryForTest(): void {
  _hintRegistry.clear();
  _listeners.clear();
}
