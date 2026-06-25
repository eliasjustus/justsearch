// @vitest-environment happy-dom

/**
 * Tempdoc 560 §26 — the bundled token-editor plugin manifest. Shape regression guard: the boot
 * install in main.jsx + the registry's customElements/surface-contribution wiring depend on this exact
 * shape (id === tagNamespace, a navigable vendor.* USER/RAIL surface, a declared custom element,
 * translations, and an unregister cleanup hook).
 */
import { describe, expect, it } from 'vitest';
import {
  createTokenEditorPluginManifest,
  TOKEN_EDITOR_PLUGIN_ID,
} from './TokenEditorPlugin.js';
import type { PluginHostApi } from '../../plugin-api/plugin-types.js';

describe('createTokenEditorPluginManifest (560 §26)', () => {
  it('is the token-editor plugin with tagNamespace === id and contract 1.1', () => {
    const m = createTokenEditorPluginManifest();
    expect(m.id).toBe('token-editor');
    expect(TOKEN_EDITOR_PLUGIN_ID).toBe('token-editor');
    expect(m.tagNamespace).toBe(m.id);
    expect(m.contractVersion).toBe('1.1');
  });

  it('declares one navigable USER/RAIL vendor.* surface mounting token-editor-panel', () => {
    const s = createTokenEditorPluginManifest().capabilities?.surfaces?.[0];
    expect(s?.id).toBe('vendor.token-editor.editor-surface');
    expect(s?.mountTag).toBe('token-editor-panel');
    expect(s?.audience).toBe('USER');
    expect(s?.placement).toBe('RAIL');
    // vendor.* prefix is required for the surface to be router-navigable (parser.ts SurfaceRef regex)
    expect(s?.id).toMatch(/^vendor\.[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$/);
  });

  it('register() contributes the custom element, the surface, and en translations', () => {
    const contribution = createTokenEditorPluginManifest().register(
      {} as unknown as PluginHostApi,
    );
    expect(contribution?.customElements?.[0]?.tagSuffix).toBe('panel');
    expect(typeof contribution?.customElements?.[0]?.klass).toBe('function');
    expect(contribution?.surfaceContributions?.[0]?.contribution?.id).toBe(
      'vendor.token-editor.editor-surface',
    );
    expect(contribution?.translations?.en?.['surface.token-editor.label']).toBe('Theme Editor');
  });

  it('has an unregister cleanup hook (preview revert on uninstall)', () => {
    expect(typeof createTokenEditorPluginManifest().unregister).toBe('function');
  });
});
