// SPDX-License-Identifier: Apache-2.0
/**
 * themeState — projection over UserStateDocument.
 *
 * V1.5 alpha shipped this as an ad-hoc per-domain store with its
 * own localStorage key (`justsearch.activeTheme`). 478 §4.B
 * refactor: themeState is now a PROJECTION over the consolidated
 * UserStateDocument. The public API is preserved unchanged so
 * existing consumers (Shell.ts, Settings UI, main.jsx boot)
 * don't need any code changes.
 *
 * Persistence consolidation:
 *   - V1.5 alpha: localStorage['justsearch.activeTheme']
 *   - V1.5.1: localStorage['justsearch.userState.v1'] under the
 *     `activeThemeId` slice. UserStateDocument migrates the legacy
 *     key on first boot.
 *
 * Slice 474 alpha — V1.5 Theme Manifest tier (palette themes only):
 *  - Palette themes via CSS file injection. User selects a theme
 *    by id; the loader fetches `/themes/<id>.css` (or accepts an
 *    inline CSS string for tests / Tauri-fs-API-loaded user
 *    themes), injects it into `<head>` as `<style id="jf-active-theme">`,
 *    and broadcasts the active theme id via the userConfig channel.
 *
 * Slice 477 H2.2 — themes inject under `@layer user-theme` so they
 * compose correctly with `tokens.css`'s `@layer core-theme`. The
 * `applyTheme` injection wraps automatically.
 */

import {
  getDocument,
  subscribeProjection,
  mutateDocument,
} from './UserStateDocument.js';
import {
  fetchAndCompileTokenTree,
  compileTokenTreeToCss,
  type DesignTokenTree,
} from '../themes/designTokenTree.js';
import { deriveRoleForegrounds } from '../themes/roleForegrounds.js';
import {
  getThemeCatalogEntry,
  setCustomThemeEntries,
  type ThemeCatalogEntry,
} from '../themes/themesCatalog.js';
import { setUiMode } from './uiModeState.js';

const ACTIVE_THEME_STYLE_ID = 'jf-active-theme';

type Listener = (themeId: string | null) => void;

/**
 * Subscribe to active-theme changes. Listener fires once with the
 * current active theme id on subscribe (null if no theme is
 * applied), then on every change. Returns an unsubscribe function.
 */
export function subscribeActiveTheme(listener: Listener): () => void {
  return subscribeProjection((doc) => doc.activeThemeId, listener);
}

/** Get the currently active theme id, or null if no theme applied. */
export function getActiveThemeId(): string | null {
  return getDocument().activeThemeId;
}

/**
 * Tempdoc 567 — mirror the persisted custom themes (`UserStateDocument.customThemes`)
 * into the theme catalog's custom layer, so user-saved themes appear in the picker
 * and resolve by id (their `DesignTokenTree` is compiled on apply). Call at boot
 * (before the persisted-theme restore) and after every saveTheme/deleteTheme write.
 */
export function syncCustomThemesToCatalog(): void {
  const custom = getDocument().customThemes ?? [];
  setCustomThemeEntries(
    custom.map(
      (tree: DesignTokenTree): ThemeCatalogEntry => ({
        id: tree.id,
        displayName: tree.displayName,
        description: tree.description ?? 'Custom theme',
        tokens: tree,
      }),
    ),
  );
}

/**
 * Slice 471 V1.5.1 polish — restore the active theme on app boot
 * by re-fetching its CSS and re-injecting. Call from `main.jsx`
 * after the rest of the chrome boots. If the persisted active theme
 * is null, this is a no-op. Errors during fetch (e.g., theme file
 * deleted) are swallowed; the persisted id is cleared so we don't
 * keep retrying every boot, and the chrome falls back to default
 * tokens.
 */
export async function restoreActiveThemeIfPersisted(
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  const id = getActiveThemeId();
  if (!id) return;
  try {
    // C1: route the palette restore through the one appearance writer.
    await applyAppearance({ paletteId: id }, fetchImpl);
  } catch {
    // Theme file may have been removed; clear the persisted id
    // so we don't keep retrying every boot.
    mutateDocument((doc) => ({ ...doc, activeThemeId: null }));
  }
}

/**
 * Apply a theme by injecting its CSS into the document head.
 *
 * Slice 477 H2.2 — the injected CSS is automatically wrapped in
 * `@layer user-theme { ... }`. `tokens.css` wraps its `:root`/
 * `[data-theme=...]`/density blocks in `@layer core-theme`, with
 * declared order `core-theme, user-theme, user-override`. So
 * user-theme rules win over core-theme rules at any specificity.
 * `.high-contrast` accessibility overrides remain UNLAYERED on
 * purpose — unlayered rules beat all explicit layers per the CSS
 * Cascade spec, so a user-theme can never disable accessibility.
 *
 * @param themeId stable id for the theme (e.g., `core.solarized`).
 *   Used as the userConfig.activeThemeId value and as the listener
 *   payload. Pass null + empty cssText to clear the active theme.
 * @param cssText the theme CSS source. The caller is responsible
 *   for fetching it from `/themes/<id>.css` (production) or a
 *   user-supplied path (Tauri fs API). Theme authors write plain
 *   CSS (`:root { --token: ... }`); the host applies the layer
 *   wrapper at injection time.
 */
export function applyTheme(themeId: string, cssText: string): void {
  if (typeof document === 'undefined') return;
  let styleEl = document.getElementById(
    ACTIVE_THEME_STYLE_ID,
  ) as HTMLStyleElement | null;
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = ACTIVE_THEME_STYLE_ID;
    document.head.appendChild(styleEl);
  }
  styleEl.textContent = `@layer user-theme {\n${cssText}\n}`;
  styleEl.dataset.themeId = themeId;
  // Persist via the consolidated document; subscribers fire after
  // persistence.
  mutateDocument((doc) =>
    doc.activeThemeId === themeId
      ? doc
      : { ...doc, activeThemeId: themeId },
  );
}

/**
 * §2.C / C1 — THE single appearance writer (the appearance authority).
 *
 * Owns ALL theme-affecting DOM state in ONE function: the `data-theme`
 * light/dark attribute, the `high-contrast` class, AND the palette
 * `@layer user-theme` <style> (via its internal {@link applyTheme} /
 * {@link clearActiveTheme} / {@link loadAndApplyTheme} steps). Every change-path
 * (settings light/dark + high-contrast; the palette pick via the host API) and
 * the boot replay route through this one function, so the three layers cannot
 * drift between load and change.
 *
 * Each field is independent: an omitted field is left untouched, so a pure
 * light/dark change does not re-fetch the palette and vice-versa.
 */
export interface Appearance {
  readonly theme?: 'light' | 'dark' | 'system' | string;
  readonly highContrast?: boolean;
  /**
   * Tempdoc 567 §9.4 — the glass/solid surface mode. `solid` makes surfaces opaque and zeroes every
   * blur (one inherited `--glass-blur-scale`); `glass` (default) is the translucent look. A global
   * rendering preference that composes with any theme (the sibling of `highContrast`), not a per-theme
   * colour choice. `undefined` = leave unchanged.
   */
  readonly surfaceMode?: 'glass' | 'solid';
  /** `undefined` = leave palette; `null`/`''` = clear; string = load + apply. */
  readonly paletteId?: string | null;
}

export async function applyAppearance(
  appearance: Appearance,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  // Attribute/class layer (synchronous — applies even if the caller doesn't await).
  if (typeof document !== 'undefined') {
    const root = document.documentElement;
    if (appearance.theme !== undefined) {
      if (appearance.theme === 'light') root.setAttribute('data-theme', 'light');
      else if (appearance.theme === 'dark') root.setAttribute('data-theme', 'dark');
      else root.removeAttribute('data-theme'); // 'system' / unknown → OS default
    }
    if (appearance.highContrast !== undefined) {
      root.classList.toggle('high-contrast', appearance.highContrast === true);
    }
    // Tempdoc 567 §9.4 — glass/solid surface mode. `solid` sets the attribute the unlayered
    // `[data-surface-mode="solid"]` block keys off (opaque surfaces + `--glass-blur-scale:0`); `glass`
    // (the default) removes it. One writer, mirroring the high-contrast class toggle above.
    if (appearance.surfaceMode !== undefined) {
      if (appearance.surfaceMode === 'solid') root.setAttribute('data-surface-mode', 'solid');
      else root.removeAttribute('data-surface-mode');
    }
  }
  // Palette layer (async — fetches the theme CSS / DesignTokenTree).
  if (appearance.paletteId !== undefined) {
    if (appearance.paletteId === null || appearance.paletteId === '') {
      clearActiveTheme();
    } else {
      await loadAndApplyTheme(appearance.paletteId, fetchImpl);
    }
  }
  // 569 Move 3 / 558 — co-project each colour role's foreground from its resolved
  // background to a contrast floor (the on-colour follows the fill; white-on-bright
  // is never the rendered pair). Runs after theme + palette are applied so the probe
  // resolves the active accent.
  if (typeof document !== 'undefined') {
    deriveRoleForegrounds();
  }
}

/**
 * §2.C / C1 — the ONE boot entry: replay BOTH the persisted light/dark +
 * high-contrast (from settings) AND the persisted palette, through the single
 * appearance writer. Without this, light/dark + high-contrast only applied once
 * the Settings surface mounted — the app booted default-dark even when the user
 * had chosen light. Best-effort: an unreachable settings fetch leaves the
 * default appearance; the palette restore is independent.
 */
export async function restoreAppearanceOnBoot(
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  // (1) light/dark + high-contrast from persisted settings.
  try {
    const res = await fetchImpl('/api/settings/v2');
    if (res.ok) {
      const data = (await res.json()) as { ui?: Appearance & { mode?: string } };
      if (data.ui) {
        await applyAppearance(
          { theme: data.ui.theme, highContrast: data.ui.highContrast === true },
          fetchImpl,
        );
        // Q8: seed the app-wide UI-mode authority from the same settings read.
        setUiMode(data.ui.mode);
      }
    }
  } catch {
    // Settings endpoint not ready / unreachable at boot — leave default.
  }
  // (2) Tempdoc 567 — register persisted custom themes into the catalog BEFORE
  // restoring the active palette, so a persisted custom-theme id resolves.
  syncCustomThemesToCatalog();
  // (2b) Tempdoc 567 §9.4 — replay the persisted glass/solid surface mode (FE-only, in the
  // user-state document alongside the palette), so a solid-mode choice survives reload.
  const persistedSurfaceMode = getDocument().surfaceMode;
  if (persistedSurfaceMode !== undefined) {
    await applyAppearance({ surfaceMode: persistedSurfaceMode }, fetchImpl);
  }
  // (3) the persisted palette (separately persisted in the user-state document).
  await restoreActiveThemeIfPersisted(fetchImpl);
}

/**
 * Tempdoc 567 §9.4 — set the glass/solid surface mode: apply it live through the one appearance
 * writer AND persist it (FE-only, in the user-state document, alongside the palette). Apply + persist
 * are bundled here so a caller (the Settings toggle) cannot drift them.
 */
export function setSurfaceMode(mode: 'glass' | 'solid'): void {
  void applyAppearance({ surfaceMode: mode });
  mutateDocument((doc) => ({ ...doc, surfaceMode: mode }));
}

/** Tempdoc 567 §9.4 — the persisted surface mode (`glass` default if unset). */
export function getSurfaceMode(): 'glass' | 'solid' {
  return getDocument().surfaceMode ?? 'glass';
}

/**
 * Clear the active theme — removes the injected `<style>` and
 * notifies subscribers with null.
 */
export function clearActiveTheme(): void {
  if (typeof document !== 'undefined') {
    const styleEl = document.getElementById(ACTIVE_THEME_STYLE_ID);
    if (styleEl) {
      styleEl.remove();
    }
  }
  mutateDocument((doc) =>
    doc.activeThemeId === null ? doc : { ...doc, activeThemeId: null },
  );
}

/**
 * Fetch a theme by id and apply it.
 *
 * 478 §4.E: tries the `.json` (DesignTokenTree) format first; if
 * the file exists + validates, the host compiles it to CSS and
 * applies. Otherwise falls back to the `.css` format (legacy V1.5
 * alpha path; full CSS freedom). The format detection is by
 * file-existence — JSON-only themes don't ship a `.css` file;
 * CSS-only themes don't ship a `.json` file.
 *
 * Marketplace V1.6+ themes are expected to use the JSON format
 * (constrained safety). Host-shipped themes (core.nord etc.)
 * remain CSS for full author flexibility.
 *
 * @param themeId stable id.
 * @param fetchImpl injection point for tests; defaults to global fetch.
 * @returns resolves once the theme is applied.
 * @throws if both .json and .css fetches fail.
 */
export async function loadAndApplyTheme(
  themeId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  // Tempdoc 567 — a CUSTOM theme in the catalog carries its DesignTokenTree
  // directly; compile + apply it through the one writer, no file fetch.
  const entry = getThemeCatalogEntry(themeId);
  if (entry?.tokens) {
    applyTheme(themeId, compileTokenTreeToCss(entry.tokens));
    return;
  }
  // Try JSON token-tree format first (478 §4.E preferred path).
  const jsonResult = await fetchAndCompileTokenTree(
    `/themes/${encodeURIComponent(themeId)}.json`,
    fetchImpl,
  );
  if (jsonResult !== null) {
    applyTheme(themeId, jsonResult.css);
    return;
  }

  // Fallback to CSS format (V1.5 alpha legacy).
  const response = await fetchImpl(`/themes/${encodeURIComponent(themeId)}.css`);
  if (!response.ok) {
    throw new Error(
      `Theme '${themeId}': neither /themes/${themeId}.json nor /themes/${themeId}.css ` +
        `loaded successfully (last attempt: status ${response.status})`,
    );
  }
  const cssText = await response.text();
  applyTheme(themeId, cssText);
}

/** Test-only: reset module state. */
export function __resetThemeStateForTest(): void {
  if (typeof document !== 'undefined') {
    const styleEl = document.getElementById(ACTIVE_THEME_STYLE_ID);
    if (styleEl) styleEl.remove();
  }
  // The consolidated reset clears both projections; tests for
  // themeState in isolation should call this AND/OR
  // __resetUserStateForTest depending on intent.
  // Reset via mutator preserving the V2 profile collection — the
  // built-in default profile is what we restore.
  mutateDocument((doc) => ({
    ...doc,
    activeThemeId: null,
  }));
}
