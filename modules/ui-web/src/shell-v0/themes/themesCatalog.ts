// SPDX-License-Identifier: Apache-2.0
/**
 * themesCatalog — V1.5 / 477 H1 — built-in theme catalog.
 *
 * V1.5 alpha lists themes statically. The directory scan + DTCG
 * manifest (477 H3.3 / 474 §B.A Phase 4-5) ships in V1.5.1 polish,
 * at which point this module turns into a thin facade over the
 * scanned catalog. The contract `listAvailableThemes()` stays the
 * same; consumers don't change.
 *
 * Design choice (478 §4.E): themes are the consumer-facing
 * primitive, not the underlying CSS file. `id` is the stable token;
 * `displayName` / `description` localize via the resource catalog
 * once translations land. V1.5 alpha hard-codes English strings.
 *
 * The CSS files at `public/themes/<id>.css` are the V1.5 alpha
 * implementation detail. Once the host-side compilation pass ships
 * (478 §4.E), themes contribute a `DesignTokenTree` and the host
 * compiles the CSS — at that point `cssPath` becomes deprecated
 * and the catalog entries carry the token tree directly.
 */

import type { DesignTokenTree } from './designTokenTree.js';

export interface ThemeCatalogEntry {
  /** Stable id used for `userConfig.activeThemeId`. */
  readonly id: string;
  /** User-facing label in the picker. */
  readonly displayName: string;
  /** One-line description shown under the label. */
  readonly description: string;
  /**
   * V1.5 alpha — the CSS file the loader fetches relative to the web root, e.g.
   * `/themes/nord.css`. Built-in themes carry this; a CUSTOM (user-created) theme
   * omits it and carries {@link tokens} instead (478 §4.E / 567).
   */
  readonly cssPath?: string;
  /**
   * Tempdoc 567 / 478 §4.E — a custom theme carries its `DesignTokenTree`
   * declaration directly; the host compiles it (no file fetch). Mutually
   * exclusive with `cssPath`.
   */
  readonly tokens?: DesignTokenTree;
}

/**
 * Built-in themes shipped with V1.5 alpha. Adding a theme here +
 * the corresponding `public/themes/<id>.css` file is sufficient
 * for the picker to expose it.
 */
export const BUILT_IN_THEMES: readonly ThemeCatalogEntry[] = [
  {
    id: 'core.nord',
    displayName: 'Nord',
    description: 'Cool blues and grays inspired by the Nord palette',
    cssPath: '/themes/core.nord.css',
  },
  {
    id: 'core.sepia-focus',
    displayName: 'Sepia Focus',
    description: 'Warm sepia tones for low-contrast reading',
    cssPath: '/themes/core.sepia-focus.css',
  },
];

/**
 * Slice 477 H3.3 — manifest-driven catalog. Mutated at boot by
 * `loadCatalogFromManifest()`. Falls back to BUILT_IN_THEMES when
 * the manifest is absent / invalid.
 */
// Tempdoc 567 — the catalog is two layers: a `base` (built-ins or the manifest)
// and a `custom` (user-created themes). The merged `active` view is recomputed
// whenever either changes, so a built-in catalog refresh and a custom-theme
// save/delete never clobber each other.
let baseCatalog: readonly ThemeCatalogEntry[] = BUILT_IN_THEMES;
let customCatalog: readonly ThemeCatalogEntry[] = [];
let activeCatalog: readonly ThemeCatalogEntry[] = BUILT_IN_THEMES;

function recompute(): void {
  activeCatalog = customCatalog.length === 0 ? baseCatalog : [...baseCatalog, ...customCatalog];
}

/**
 * Snapshot of available themes (base built-ins/manifest + user custom themes).
 * V1.5.2 with Tauri filesystem watcher mutates the catalog dynamically as
 * user-installed themes appear/disappear.
 */
export function listAvailableThemes(): readonly ThemeCatalogEntry[] {
  return activeCatalog;
}

/** Lookup by id; `undefined` when no theme matches. */
export function getThemeCatalogEntry(
  id: string,
): ThemeCatalogEntry | undefined {
  return activeCatalog.find((t) => t.id === id);
}

/**
 * Slice 477 H3.3 — replace the BASE catalog with manifest-driven entries.
 * Called at boot from main.jsx after fetching `/themes/manifest.json`. If the
 * manifest is invalid or missing, the base stays at BUILT_IN_THEMES. The custom
 * layer (567) is preserved.
 */
export function setActiveCatalog(entries: readonly ThemeCatalogEntry[]): void {
  baseCatalog = entries;
  recompute();
}

/**
 * Tempdoc 567 — set the user CUSTOM theme layer (each entry carries a
 * `DesignTokenTree`). Called at boot (synced from UserStateDocument.customThemes)
 * and after every saveTheme/deleteTheme. Independent of the base layer.
 */
export function setCustomThemeEntries(entries: readonly ThemeCatalogEntry[]): void {
  customCatalog = entries;
  recompute();
}

/** Test-only: revert to the built-in catalog (clears base + custom). */
export function __resetCatalogForTest(): void {
  baseCatalog = BUILT_IN_THEMES;
  customCatalog = [];
  recompute();
}
