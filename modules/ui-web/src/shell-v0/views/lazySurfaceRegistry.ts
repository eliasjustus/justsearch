// SPDX-License-Identifier: Apache-2.0
/**
 * Lazy loaders for navigable route surfaces.
 *
 * Each of these surface modules has exactly ONE module-scope side effect —
 * `customElements.define('jf-<x>-surface', …)` — and was previously pulled into
 * the eager app entry chunk by a side-effect `import './views/<X>Surface.js'`
 * in `index.ts`. They are only ever *mounted* when the user navigates to them
 * (`chrome/Shell.ts` `renderOneSurface()`), so importing them eagerly is pure
 * cold-start cost: it inflates `app_main` (the ui-bundle byte budget) for code
 * the first paint never touches.
 *
 * Deferring them to a dynamic `import()` keyed by mount tag moves each surface
 * into its own async chunk, loaded on first navigation. A navigated view
 * legitimately shows a brief loading state, so there is no UX regression.
 *
 * NOT lazy: the always-present chrome (`UnifiedChatView` — the default landing view via its
 * retrieve tier, so first paint never flashes a loader — plus `Shell`) and shared components,
 * imported eagerly via `chrome/Shell.ts` → `index.ts`. (Search Thread S5b — the standalone
 * `jf-search-surface` that used to hold this "kept eager in index.ts" exception is retired.)
 */

/** mount tag → dynamic import of the module that defines that custom element.
 *  The explicit `<string, () => Promise<unknown>>` type arg is load-bearing:
 *  without it, Map infers the value type from the first entry and the other
 *  (differently-typed) import() loaders fail to unify. */
const LOADERS: ReadonlyMap<string, () => Promise<unknown>> = new Map<
  string,
  () => Promise<unknown>
>([
  ['jf-library-surface', () => import('./LibrarySurface.js')],
  ['jf-help-surface', () => import('./HelpSurface.js')],
  ['jf-presentation-gallery-surface', () => import('./PresentationGallerySurface.js')],
  ['jf-presentation-editor-surface', () => import('./PresentationEditorSurface.js')],
  ['jf-brain-surface', () => import('./BrainSurface.js')],
  // Tempdoc 565 §15: WorkflowSurface retired — a workflow run is now a MODE of the one window,
  // not a standalone route surface (the eager import + this lazy loader both removed).
  ['jf-settings-surface', () => import('./SettingsSurface.js')],
  // Tempdoc 629 (remaining-work) — the unified Security & Privacy surface (encryption control +
  // at-rest status), moved out of Settings.
  ['jf-security-surface', () => import('./SecuritySurface.js')],
  ['jf-browse-surface', () => import('./BrowseSurface.js')],
  ['jf-health-surface', () => import('./HealthSurface.js')],
  ['jf-log-surface', () => import('./LogSurface.js')],
  ['jf-activity-surface', () => import('./ActivitySurface.js')],
  ['jf-memory-surface', () => import('./MemorySurface.js')],
  // Tempdoc 575 §17 Face B (the System Self-View / "Now") was lazy-mounted here as a standalone surface;
  // 578 Workstream A retired the surface and folded it into Health, which imports SystemSelfView.js
  // directly — so the lazy row is gone (no catalog entry resolves to jf-system-self-view anymore).
  // Tempdoc 571 §11 / 578 — the System hub host (presents Health · Logs · Activity as tabs).
  ['jf-system-surface', () => import('./SystemSurface.js')],
  // Tempdoc 576 §15 / 530 Layer 3-4 — the governance dashboard (read-only kernel projection). DEEPLINK
  // dev/operator tool, off-rail; lazy-loaded on first navigation.
  ['jf-governance-view', () => import('./GovernanceView.js')],
  // Tempdoc 583 §D.3b — the API explorer (read-only route-manifest projection). DEEPLINK dev tool,
  // off-rail; lazy-loaded on first navigation (sibling of the governance dashboard).
  ['jf-api-explorer-view', () => import('./ApiExplorerView.js')],
  // Tempdoc 818 slice 1 — the from-scratch Search v2 window skeleton. DEVELOPER/DEEPLINK, no rail
  // entry (578: one task, one window); reachable only at #justsearch://surface/core.search-v2-surface
  // while the comparison against UnifiedChatView runs (818 §5 sunset criterion).
  ['jf-search-v2', () => import('./search-v2/SearchV2View.js')],
  // Tempdoc 822 slice 1 — the Search v3 window, rebuilt on the T3 Code donor system.
  // DEVELOPER/DEEPLINK, no rail entry; reachable only at
  // #justsearch://surface/core.search-v3-surface while the donor-copy pipeline is calibrated.
  ['jf-sv3-window', () => import('./search-v3/SearchV3View.js')],
]);

/** In-flight (and resolved) loads, deduped by tag. Cleared on failure so a
 *  later navigation can retry (e.g. a transient chunk-fetch error). */
const inFlight = new Map<string, Promise<unknown>>();

/** Whether `tag` is a lazily-loaded route surface (vs an eager / unknown tag). */
export function isLazySurface(tag: string): boolean {
  return LOADERS.has(tag);
}

/**
 * Idempotently import the module that defines `tag`'s custom element. Resolves
 * once the module has been evaluated (i.e. `customElements.define` has run).
 * A no-op (resolved promise) for non-lazy tags. Safe to call on every render.
 */
export function ensureSurfaceLoaded(tag: string): Promise<unknown> {
  const existing = inFlight.get(tag);
  if (existing) return existing;
  const loader = LOADERS.get(tag);
  if (!loader) return Promise.resolve();
  const p = loader().catch((err) => {
    inFlight.delete(tag); // allow a retry on the next navigation
    throw err;
  });
  inFlight.set(tag, p);
  return p;
}

/**
 * Test hook (tempdoc 586 follow-up): await every in-flight lazy-surface import. A test that renders
 * the shell triggers {@link ensureSurfaceLoaded} as a fire-and-forget side effect (the rail does not
 * block render on the import); under full-suite parallel load that dynamic `import()` can resolve
 * AFTER the test environment is torn down, which vitest 4 surfaces as a fatal `EnvironmentTeardownError`.
 * Awaiting this in an `afterEach` drains the imports before teardown so the leak cannot escape the test.
 */
export function __flushInFlightSurfaces(): Promise<unknown> {
  return Promise.allSettled([...inFlight.values()]);
}
