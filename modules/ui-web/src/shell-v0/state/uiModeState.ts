// SPDX-License-Identifier: Apache-2.0
/**
 * uiModeState — the one app-wide "Simple vs Advanced" UI-mode authority
 * (tempdoc 557 Q8).
 *
 * The mode is a persisted user preference (settings `ui.mode`). It was only
 * read inside SettingsSurface, so other surfaces couldn't gate advanced-only
 * affordances on it. This tiny shared store lets any surface subscribe — e.g.
 * SearchSurface hides the raw retrieval-trace diagnostics outside Advanced mode
 * (Q8). Seeded from `/api/settings/v2` at boot; SettingsSurface republishes on
 * load + change. Defaults to 'simple' (hide advanced affordances until known).
 */
import { createObservableStore } from './createObservableStore.js';

export type UiMode = 'simple' | 'advanced';

// 569 Phase 0 — the shared observable-value primitive (value + listeners + notify + reset).
const store = createObservableStore<UiMode>('simple');

export function getUiMode(): UiMode {
  return store.get();
}

export function isAdvancedMode(): boolean {
  return store.get() === 'advanced';
}

export function setUiMode(next: UiMode | string | undefined): void {
  store.set(next === 'advanced' ? 'advanced' : 'simple');
}

export function subscribeUiMode(listener: (m: UiMode) => void): () => void {
  return store.subscribe(listener, { immediate: true });
}

/** Test-only reset. */
export function __resetUiModeForTest(): void {
  store.reset();
}
