// @vitest-environment happy-dom

/**
 * Tests for slice 472's userConfig-driven rail filtering + ordering.
 *
 * The chrome `<jf-shell>` reads `userConfig.surfaceVisibility` +
 * `userConfig.surfaceOrder` and applies them to the rail catalog
 * snapshot in `refreshSurfaces()`. This test exercises that
 * pipeline by seeding the SurfaceCatalog, mutating userConfig, and
 * asserting Shell's `surfaces` array reflects the changes.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetUserConfigForTest,
  setSurfaceOrder,
  setSurfaceVisibility,
} from '../state/userConfigState.js';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';
// Tempdoc 586 follow-up — renderShell() triggers fire-and-forget lazy-surface imports; drain them in
// afterEach so a dynamic import() can't resolve after teardown (vitest-4 EnvironmentTeardownError).
import { __flushInFlightSurfaces } from '../views/lazySurfaceRegistry.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

function makeRailSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `${id}.label`,
      descriptionKey: `${id}.description`,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      operations: [],
      resources: [],
      prompts: [],
      diagnosticChannels: [],
    },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
  };
}

const SEARCH = makeRailSurface('core.search-surface', 'jf-search-surface');
const LIBRARY = makeRailSurface('core.library-surface', 'jf-library-surface');
const BRAIN = makeRailSurface('core.brain-surface', 'jf-brain-surface');
const HELP = makeRailSurface('core.help-surface', 'jf-help-surface');
const SETTINGS = makeRailSurface(
  'core.settings-surface',
  'jf-settings-surface',
);
// Tempdoc 586 F-2 — the two surfaces hidden from the rail in Simple mode.
const SYSTEM = makeRailSurface('core.system-surface', 'jf-system-surface');
const THEME_EDITOR = makeRailSurface(
  'vendor.token-editor.editor-surface',
  'jf-token-editor-surface',
);

function seedSurfacesWithDiagnostics(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [LIBRARY, BRAIN, SEARCH, SETTINGS, SYSTEM, THEME_EDITOR],
  };
  seedSurfaceCatalog(catalog);
}

function seedFiveCoreSurfaces(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [SEARCH, LIBRARY, BRAIN, HELP, SETTINGS],
  };
  seedSurfaceCatalog(catalog);
}

interface ShellElement extends HTMLElement {
  apiBase: string;
  surfaces: Surface[];
  updateComplete: Promise<void>;
}

async function renderShell(): Promise<ShellElement> {
  const shell = document.createElement('jf-shell') as ShellElement;
  shell.apiBase = '';
  document.body.appendChild(shell);
  await shell.updateComplete;
  // Allow the post-connectedCallback userConfig subscription's initial
  // notification + refreshSurfaces() to settle.
  await shell.updateComplete;
  return shell;
}

describe('Shell — userConfig-driven rail (slice 472)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
  });

  afterEach(async () => {
    // Tempdoc 586 follow-up — drain the fire-and-forget lazy-surface imports renderShell() triggered,
    // so a dynamic import() cannot resolve after the happy-dom env is torn down (the deterministic
    // vitest-4 EnvironmentTeardownError that reddened the full-suite exit code while all tests passed).
    await __flushInFlightSurfaces();
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    resetSurfaceCatalog();
    __resetUserConfigForTest();
  });

  it('rail shows all RAIL-placement surfaces when no overrides are set', async () => {
    seedFiveCoreSurfaces();
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.search-surface');
    expect(ids).toContain('core.library-surface');
    expect(ids).toContain('core.brain-surface');
    expect(ids).toContain('core.help-surface');
    expect(ids).toContain('core.settings-surface');
  });

  it('surfaceVisibility=false hides a surface from the rail', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', false);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).not.toContain('core.help-surface');
    // Other surfaces still visible.
    expect(ids).toContain('core.library-surface');
  });

  it('surfaceVisibility=true is the same as absent (default visible)', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', true);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.help-surface');
  });

  it('surfaceOrder reorders the rail; unlisted surfaces follow in catalog order', async () => {
    seedFiveCoreSurfaces();
    setSurfaceOrder(['core.brain-surface', 'core.search-surface']);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
    // Library / Help / Settings follow in catalog order.
    expect(ids.slice(2)).toEqual([
      'core.library-surface',
      'core.help-surface',
      'core.settings-surface',
    ]);
  });

  it('surfaceOrder ids that are not in the catalog are silently skipped', async () => {
    seedFiveCoreSurfaces();
    setSurfaceOrder([
      'core.brain-surface',
      'acme.uninstalled-surface', // not in catalog
      'core.search-surface',
    ]);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
  });

  it('visibility + order compose: hidden surfaces are not in the ordered list', async () => {
    seedFiveCoreSurfaces();
    setSurfaceVisibility('core.help-surface', false);
    setSurfaceOrder([
      'core.help-surface', // hidden — should be skipped
      'core.brain-surface',
      'core.search-surface',
    ]);
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).not.toContain('core.help-surface');
    expect(ids[0]).toBe('core.brain-surface');
    expect(ids[1]).toBe('core.search-surface');
  });
});

describe('Shell — Simple/Advanced rail filter (tempdoc 586 F-2)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  it('Simple mode hides System + Theme Editor from the rail but keeps AI Brain', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('simple');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    // The two advanced/diagnostic surfaces drop off in Simple mode...
    expect(ids).not.toContain('core.system-surface');
    expect(ids).not.toContain('vendor.token-editor.editor-surface');
    // ...while AI Brain stays (the user's explicit choice), as do the consumer surfaces.
    expect(ids).toContain('core.brain-surface');
    expect(ids).toContain('core.library-surface');
    expect(ids).toContain('core.search-surface');
    expect(ids).toContain('core.settings-surface');
  });

  it('Advanced mode restores System + Theme Editor', async () => {
    seedSurfacesWithDiagnostics();
    setUiMode('advanced');
    const shell = await renderShell();
    const ids = shell.surfaces.map((s) => s.id);
    expect(ids).toContain('core.system-surface');
    expect(ids).toContain('vendor.token-editor.editor-surface');
    expect(ids).toContain('core.brain-surface');
  });
});
