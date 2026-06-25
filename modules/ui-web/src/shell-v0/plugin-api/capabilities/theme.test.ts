// @vitest-environment happy-dom

/**
 * Tempdoc 567 §8 — host.theme capability (createThemeApi) producer-side guarantees.
 *
 * Covers the behaviour added in §8:
 *  - saveTheme rejects a known-but-DERIVED token — "seeds only" is structural at the capability
 *    boundary, not just the editor's display filter (#4);
 *  - saveTheme accepts seed tokens and persists them, retrievable via exportTheme as JSON (#2);
 *  - exportTheme returns null for an unknown / non-custom (built-in) id (#2);
 *  - UNTRUSTED tier structurally omits the write methods (§4.4 / §24 tier attenuation).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createThemeApi } from './theme.js';
import { __resetUserStateForTest } from '../../state/UserStateDocument.js';
import { __resetCatalogForTest } from '../../themes/themesCatalog.js';

beforeEach(() => {
  __resetUserStateForTest();
  __resetCatalogForTest();
});
afterEach(() => {
  __resetUserStateForTest();
  __resetCatalogForTest();
});

describe('createThemeApi saveTheme — seeds-only structural guard (§8 #4)', () => {
  it('rejects a known-but-derived token (neither seed nor role)', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    expect(() =>
      api.saveTheme?.({
        id: 'custom.bad',
        displayName: 'Bad',
        tokens: { 'surface-1': '#ffffff' },
      }),
    ).toThrow(/not authorable/);
  });

  it('accepts a role foreground token (accent-on-command) — the "seeds + roles" surface', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    expect(() =>
      api.saveTheme?.({
        id: 'custom.role',
        displayName: 'Role',
        tokens: { 'h-teal': '120', 'accent-on-command': '#000000' },
      }),
    ).not.toThrow();
    expect(api.exportTheme('custom.role')).not.toBeNull();
  });

  it('accepts seed tokens and persists them (retrievable via exportTheme)', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({
      id: 'custom.good',
      displayName: 'Good',
      tokens: { 'h-teal': '200', 'p-glass': '10, 20, 30' },
    });
    const json = api.exportTheme('custom.good');
    expect(json).not.toBeNull();
    // exportTheme serializes the full DesignTokenTree (a complete, re-importable theme).
    const parsed = JSON.parse(json as string) as {
      id: string;
      tokens: Record<string, string>;
    };
    expect(parsed.id).toBe('custom.good');
    expect(parsed.tokens['h-teal']).toBe('200');
    expect(parsed.tokens['p-glass']).toBe('10, 20, 30');
  });
});

describe('createThemeApi exportTheme (§8 #2)', () => {
  it('returns null for an unknown id', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    expect(api.exportTheme('nope')).toBeNull();
  });

  it('returns null for a built-in (non-custom) theme', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    // core.nord ships as a cssPath built-in (no token tree) → not exportable as a tree.
    expect(api.exportTheme('core.nord')).toBeNull();
  });
});

describe('createThemeApi deleteTheme (§8 #3 / 567)', () => {
  it('removes the theme and reverts the active theme to default when it was applied', async () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({ id: 'custom.del', displayName: 'Del', tokens: { 'h-teal': '120' } });
    await api.selectTheme?.('custom.del');
    expect(api.getActiveThemeId()).toBe('custom.del');
    api.deleteTheme?.('custom.del');
    expect(api.getActiveThemeId()).toBeNull();
    expect(api.exportTheme('custom.del')).toBeNull();
  });

  it('removing a non-active custom theme leaves the active theme intact', async () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({ id: 'custom.a', displayName: 'A', tokens: { 'h-teal': '120' } });
    api.saveTheme?.({ id: 'custom.b', displayName: 'B', tokens: { 'h-teal': '200' } });
    await api.selectTheme?.('custom.a');
    api.deleteTheme?.('custom.b');
    expect(api.getActiveThemeId()).toBe('custom.a');
    expect(api.exportTheme('custom.b')).toBeNull();
    expect(api.exportTheme('custom.a')).not.toBeNull();
  });
});

describe('createThemeApi importTheme (§8 deferred → built)', () => {
  it('round-trips a saved theme: export → import re-persists an identical tree', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({
      id: 'custom.rt',
      displayName: 'Round Trip',
      tokens: { 'h-teal': '200', 'p-glass': '10, 20, 30' },
    });
    const json = api.exportTheme('custom.rt') as string;
    api.deleteTheme?.('custom.rt');
    expect(api.exportTheme('custom.rt')).toBeNull();
    const { id, displayName } = api.importTheme!(json);
    expect(id).toBe('custom.rt');
    expect(displayName).toBe('Round Trip');
    expect(api.exportTheme('custom.rt')).not.toBeNull();
  });

  it('rejects an imported theme carrying a derived (non-authorable) token — import is not a backdoor', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    const evil = JSON.stringify({
      schemaVersion: 1,
      id: 'custom.evil',
      displayName: 'Evil',
      tokens: { 'surface-1': '#ffffff' },
    });
    expect(() => api.importTheme!(evil)).toThrow(/not authorable/);
    expect(api.exportTheme('custom.evil')).toBeNull();
  });

  it('rejects invalid JSON and an invalid tree', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    expect(() => api.importTheme!('{not json')).toThrow(/not valid JSON/);
    expect(() => api.importTheme!(JSON.stringify({ schemaVersion: 1 }))).toThrow(/invalid theme/);
  });

  it('refuses to shadow a built-in theme id', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    const shadow = JSON.stringify({
      schemaVersion: 1,
      id: 'core.nord',
      displayName: 'Fake Nord',
      tokens: { 'h-teal': '120' },
    });
    expect(() => api.importTheme!(shadow)).toThrow(/built-in theme id/);
  });
});

describe('createThemeApi renameTheme (§8 deferred → built)', () => {
  it('changes displayName, keeps the id stable, and preserves an active selection', async () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({ id: 'custom.rn', displayName: 'Old Name', tokens: { 'h-teal': '120' } });
    await api.selectTheme?.('custom.rn');
    api.renameTheme!('custom.rn', 'New Name');
    // Active selection survives — rename never touches the id.
    expect(api.getActiveThemeId()).toBe('custom.rn');
    const renamed = api.listThemes().find((t) => t.id === 'custom.rn');
    expect(renamed?.displayName).toBe('New Name');
    // The persisted tree carries the new label.
    const parsed = JSON.parse(api.exportTheme('custom.rn') as string) as { displayName: string };
    expect(parsed.displayName).toBe('New Name');
  });

  it('rejects a blank name and an unknown id', () => {
    const api = createThemeApi('TRUSTED_PLUGIN', 'test');
    api.saveTheme?.({ id: 'custom.rn2', displayName: 'Name', tokens: { 'h-teal': '120' } });
    expect(() => api.renameTheme!('custom.rn2', '   ')).toThrow(/non-empty/);
    expect(() => api.renameTheme!('custom.nope', 'X')).toThrow(/not a custom theme/);
  });
});

describe('createThemeApi tier attenuation', () => {
  it('UNTRUSTED structurally omits the write methods but keeps reads', () => {
    const api = createThemeApi('UNTRUSTED_PLUGIN', 'test');
    expect(api.saveTheme).toBeUndefined();
    expect(api.deleteTheme).toBeUndefined();
    expect(api.importTheme).toBeUndefined();
    expect(api.renameTheme).toBeUndefined();
    expect(api.previewTokens).toBeUndefined();
    expect(api.selectTheme).toBeUndefined();
    expect(typeof api.exportTheme).toBe('function');
    expect(typeof api.listThemes).toBe('function');
  });
});
