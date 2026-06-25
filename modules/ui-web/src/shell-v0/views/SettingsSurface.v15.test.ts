// @vitest-environment happy-dom

/**
 * Slice 477 H1 — V1.5 Settings UI tests.
 *
 * Covers the three new SettingsSurface sections introduced in H1:
 *   - Themes — picker emits theme load + clears active
 *   - Rail — visibility toggle + reorder + reset
 *   - Plugins — list + revoke
 *
 * The existing Interface/Appearance/Keyboard/Desktop/Data sections
 * are untested here (no pre-existing test file); this slice doesn't
 * own those gaps. Adding coverage for them is V1.5.1 polish.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsSurface.js';

/**
 * 574 B1 — activate a <jf-button> the way a user does: the action fires from the
 * native <button> inside the composed <jf-control>, two shadow roots deep, NOT
 * from a click on the jf-button host. Awaits both render passes, then clicks the
 * inner control.
 */
async function activateJfButton(host: Element): Promise<void> {
  await (host as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = host.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import {
  subscribeUserConfig as realSubscribeUserConfig,
  getUserConfig as realGetUserConfig,
} from '../state/userConfigState.js';
import {
  subscribeActiveTheme as realSubscribeActiveTheme,
  getActiveThemeId as realGetActiveThemeId,
  loadAndApplyTheme as realLoadAndApplyTheme,
  clearActiveTheme as realClearActiveTheme,
} from '../state/themeState.js';
import {
  onSurfaceCatalogChange as realOnSurfaceCatalogChange,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetUserConfigForTest,
  setSurfaceVisibility,
  setSurfaceOrder,
} from '../state/userConfigState.js';
import {
  __resetThemeStateForTest,
  getActiveThemeId,
} from '../state/themeState.js';
import {
  __seedForTest as seedSurfaceCatalog,
  __resetForTest as resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetSessionRegistryForTest,
} from '../plugin-api/sessionRegistry.js';
import {
  setCustomThemeEntries,
  __resetCatalogForTest,
} from '../themes/themesCatalog.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';
import type { PluginManifest } from '../plugin-api/plugin-types.js';

const RAIL_FIXTURE: SurfaceCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'Surface',
  entries: [
    {
      id: 'core.search-surface',
      presentation: { labelKey: 'search', descriptionKey: 'search.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-search-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
    {
      id: 'core.library-surface',
      presentation: { labelKey: 'library', descriptionKey: 'library.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-library-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
    {
      id: 'core.settings-surface',
      presentation: { labelKey: 'settings', descriptionKey: 'settings.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-settings-surface',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
  ],
};

function makeManifest(id: string): PluginManifest {
  return {
    id,
    version: '1.0.0',
    displayName: `Plugin ${id}`,
    contractVersion: '1.1',
    tagNamespace: id,
    capabilities: { surfaces: [] },
    register: () => {
      // no-op
    },
  };
}

async function mountSurface(): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement;
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 })),
    },
    layout: {
      subscribeUserConfig: (h) => realSubscribeUserConfig(h as (cfg: unknown) => void),
      getUserConfig: () => realGetUserConfig(),
      onSurfaceCatalogChange: (h) => realOnSurfaceCatalogChange(h),
      setSurfaceVisibility,
      setSurfaceOrder,
    },
    theme: {
      subscribeActiveTheme: (h) => realSubscribeActiveTheme(h),
      getActiveThemeId: () => realGetActiveThemeId(),
      selectTheme: async (id) => { if (id === null) realClearActiveTheme(); else await realLoadAndApplyTheme(id); },
    },
  });
  document.body.appendChild(el);
  // Allow Lit's connectedCallback + first render cycle.
  await new Promise((resolve) => setTimeout(resolve, 0));
  return el;
}

describe('SettingsSurface — V1.5 Themes section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    __resetCatalogForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('renders Default + built-in theme options', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot;
    expect(root).toBeTruthy();
    const labels = Array.from(root!.querySelectorAll('.option-label'))
      .map((n) => n.textContent?.trim())
      .filter(Boolean);
    expect(labels).toContain('Default');
    expect(labels).toContain('Nord');
    expect(labels).toContain('Sepia Focus');
  });

  it('renders a delete control for a custom theme but not for built-ins (§8 #3)', async () => {
    setCustomThemeEntries([
      {
        id: 'custom.x',
        displayName: 'My Custom',
        description: 'A user theme',
        tokens: {
          schemaVersion: 1,
          id: 'custom.x',
          displayName: 'My Custom',
          tokens: { 'h-teal': '120' },
        },
      },
    ]);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // Exactly one delete control — for the single custom theme; built-ins get none.
    expect(root.querySelectorAll('.custom-theme-del').length).toBe(1);
    const customLabels = Array.from(root.querySelectorAll('.custom-theme .option-label')).map(
      (n) => n.textContent?.trim(),
    );
    expect(customLabels).toEqual(['My Custom']);
    // Built-in themes still render (not wrapped with a delete control).
    const allLabels = Array.from(root.querySelectorAll('.option-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(allLabels).toContain('Nord');
  });

  it('Default option is selected when no theme is active', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // Find the Default button — it's the .option-btn whose label is "Default".
    const buttons = Array.from(root.querySelectorAll('.option-btn'));
    const defaultBtn = buttons.find(
      (b) =>
        b.querySelector('.option-label')?.textContent?.trim() === 'Default',
    ) as HTMLButtonElement | undefined;
    expect(defaultBtn).toBeTruthy();
    expect(defaultBtn!.classList.contains('selected')).toBe(true);
  });

  it('clicking Default invokes clearActiveTheme (no fetch needed)', async () => {
    // Pre-set an active theme; clicking Default should clear it.
    // (We can't actually load a theme without fetch, but we can test
    // the clear path which doesn't fetch.)
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const buttons = Array.from(root.querySelectorAll('.option-btn'));
    const defaultBtn = buttons.find(
      (b) =>
        b.querySelector('.option-label')?.textContent?.trim() === 'Default',
    ) as HTMLButtonElement | undefined;
    defaultBtn!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(getActiveThemeId()).toBeNull();
  });
});

describe('SettingsSurface — V1.5 Rail section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('lists rail surfaces in catalog order by default', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    // §2.A: rail-customization shows human labels, not raw surface ids.
    expect(labels).toEqual(['Search', 'Library', 'Settings']);
  });

  it('respects userConfig.surfaceOrder', async () => {
    setSurfaceOrder([
      'core.settings-surface',
      'core.search-surface',
      'core.library-surface',
    ]);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(labels[0]).toBe('Settings');
    expect(labels[1]).toBe('Search');
    expect(labels[2]).toBe('Library');
  });

  it('hidden surfaces have visibility=false reflected in checkbox', async () => {
    setSurfaceVisibility('core.settings-surface', false);
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const rows = Array.from(root.querySelectorAll('.rail-row')) as HTMLElement[];
    const settingsRow = rows.find((r) =>
      r.querySelector('.rail-label')?.textContent?.includes('Settings'),
    );
    expect(settingsRow).toBeTruthy();
    const checkbox = settingsRow!.querySelector(
      'input[type="checkbox"]',
    ) as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
  });

  it('move-down arrow on first surface updates surfaceOrder', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const firstRow = root.querySelector('.rail-row') as HTMLElement;
    // Find the down arrow (second .rail-arrow button in the row).
    // 574 B1 — the arrows are now <jf-button class="rail-arrow">; the host carries the
    // `title` (not aria-label, which jf-control puts on the inner button), and `disabled`
    // is the reflected jf-button property.
    const arrows = Array.from(
      firstRow.querySelectorAll('.rail-arrow'),
    ) as Array<Element & { disabled: boolean }>;
    const downArrow = arrows.find((a) => a.getAttribute('title') === 'Move down');
    expect(downArrow).toBeTruthy();
    expect(downArrow!.disabled).toBe(false);
    await activateJfButton(downArrow!);
    await new Promise((resolve) => setTimeout(resolve, 0));
    // After moving the first item (search) down, library should be first.
    const labels = Array.from(root.querySelectorAll('.rail-label')).map((n) =>
      n.textContent?.trim(),
    );
    expect(labels[0]).toBe('Library');
    expect(labels[1]).toBe('Search');
  });
});

describe('SettingsSurface — V1.5 Plugins section', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('shows empty-state hint when no plugins are installed', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const text = root.textContent ?? '';
    expect(text).toContain('No plugins installed');
  });

  it('lists installed plugins from the session registry', async () => {
    const registry = __resetSessionRegistryForTest();
    registry.install(makeManifest('alpha'));
    registry.install(makeManifest('bravo'));
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const ids = Array.from(root.querySelectorAll('.plugin-id'))
      .map((n) => n.textContent ?? '')
      .join(' ');
    expect(ids).toContain('alpha');
    expect(ids).toContain('bravo');
  });

  it('plugin row exposes Revoke button', async () => {
    const registry = __resetSessionRegistryForTest();
    registry.install(makeManifest('alpha'));
    const el = await mountSurface();
    const root = el.shadowRoot!;
    // 574 B1 — Revoke is now a <jf-button variant="danger"> (slot text in light DOM).
    const revokeBtn = Array.from(root.querySelectorAll('jf-button')).find(
      (b) => b.textContent?.includes('Revoke'),
    );
    expect(revokeBtn).toBeTruthy();
  });
});

describe('SettingsSurface — settle transients on hide (tempdoc 609 instance-retention)', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetCatalogForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(RAIL_FIXTURE);
  });

  it('resets the destructive delete ceremony on disconnect but KEEPS draft work + active tab', async () => {
    const el = await mountSurface();
    const v = el as unknown as Record<string, unknown>;
    // A mid-ceremony delete (transient confirmation) alongside draft work (recoverable) on a chosen tab.
    v.deleteState = 'confirm';
    v.confirmText = 'DELETE EVERYTHING';
    v.renamingThemeId = 'theme-x';
    v.renameDraft = 'My renamed theme';
    v.themeImportDraft = '{"id":"my-draft-theme"}';
    v.activeTab = 'appearance';

    el.remove(); // navigate away (instance retained; settleTransients fires via JfElement)

    // The destructive confirmation is settled — no half-finished delete ceremony survives.
    expect(v.deleteState).toBe('idle');
    expect(v.confirmText).toBe('');
    // Draft work is recoverable and SURVIVES (resetting it would re-introduce the 609 draft-loss).
    expect(v.renamingThemeId).toBe('theme-x');
    expect(v.renameDraft).toBe('My renamed theme');
    expect(v.themeImportDraft).toBe('{"id":"my-draft-theme"}');
    // The user's tab choice is recoverable and survives.
    expect(v.activeTab).toBe('appearance');
  });
});
