// @vitest-environment happy-dom
/**
 * SettingsWindow tests — tempdoc 855 Phase 0.
 *
 * Covers the three properties the MODAL navigation contract depends on:
 *   - `open` is the single source of truth driving the native <dialog>;
 *   - the close routine flips `open` immediately (the UI never waits on a navigation round-trip)
 *     and emits `settings-window-close` — and makes NO history call of its own (855 §11.1 D4: only
 *     the Shell knows whether opening pushed an entry, so only the Shell may unwind one);
 *   - the settings surface mounts from the CATALOG (not a hard-coded tag) and stays connected
 *     while the window is closed (§11.2 — member intents must always reach a live subscriber).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsWindow.js';
import type { SettingsWindow } from './SettingsWindow.js';
import { __seedForTest, __resetForTest } from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';
import '../views/SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';

const SETTINGS_ID = 'core.settings-surface';
const SETTINGS_TAG = 'jf-test-settings-surface';

class TestSettingsElement extends HTMLElement {
  constructor() {
    super();
    this.textContent = 'SETTINGS-MOUNTED';
  }
}

function settingsSurface(): Surface {
  return {
    id: SETTINGS_ID,
    presentation: {
      labelKey: 'registry-surface.settings-surface.label',
      descriptionKey: 'registry-surface.settings-surface.description',
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    placement: 'MODAL',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: SETTINGS_TAG,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };
}

function catalogOf(...entries: Surface[]): SurfaceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries,
  };
}

/** Listen for the window's one close signal (855 §11.1 D4). */
function spyOnClose(el: SettingsWindow): ReturnType<typeof vi.fn> {
  const spy = vi.fn();
  el.addEventListener('settings-window-close', spy);
  return spy;
}

async function mountWindow(): Promise<SettingsWindow> {
  const el = document.createElement('jf-settings-window') as SettingsWindow;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/**
 * 855 §11.1 P1 — the settings element's first mount is deferred to idle (`setTimeout` fallback in
 * this happy-dom env, which has no `requestIdleCallback`). Tests that assert on the mounted element
 * without opening the window first must flush that tick.
 */
async function flushIdleMount(el: SettingsWindow): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

describe('jf-settings-window', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    if (!customElements.get(SETTINGS_TAG)) {
      customElements.define(SETTINGS_TAG, TestSettingsElement);
    }
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  it('keeps the dialog closed until `open` is set', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const dlg = el.shadowRoot?.querySelector('dialog');
    expect(dlg).not.toBeNull();
    expect(dlg?.open).toBe(false);
  });

  it('the `open` property drives the native dialog both ways', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    el.open = true;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('dialog')?.open).toBe(true);
    el.open = false;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('dialog')?.open).toBe(false);
  });

  it('mounts the catalog-declared settings surface', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    await flushIdleMount(el);
    const mounted = el.shadowRoot?.querySelector(`.body ${SETTINGS_TAG}`);
    expect(mounted).not.toBeNull();
    expect(mounted?.textContent).toBe('SETTINGS-MOUNTED');
  });

  it('mounts the surface while CLOSED and keeps the SAME element across open/close (§11.2)', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    await flushIdleMount(el);
    const first = el.shadowRoot?.querySelector(SETTINGS_TAG);
    expect(first).not.toBeNull();
    expect(el.open).toBe(false);

    el.open = true;
    await el.updateComplete;
    el.open = false;
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector(SETTINGS_TAG)).toBe(first);
    expect(first?.isConnected).toBe(true);
  });

  it('the close button closes immediately and emits settings-window-close', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const el = await mountWindow();
    const closes = spyOnClose(el);
    el.open = true;
    await el.updateComplete;

    el.shadowRoot?.querySelector<HTMLButtonElement>('button.close')?.click();
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(el.shadowRoot?.querySelector('dialog')?.open).toBe(false);
    expect(closes).toHaveBeenCalledTimes(1);
    // 855 §11.1 D4 — the window itself never touches history; the Shell decides how to unwind.
    expect(backSpy).not.toHaveBeenCalled();
    backSpy.mockRestore();
  });

  it('the close event bubbles OUT of the shadow root (composed) so the Shell can hear it', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const heard = vi.fn();
    document.addEventListener('settings-window-close', heard);
    el.open = true;
    await el.updateComplete;

    el.shadowRoot?.querySelector<HTMLButtonElement>('button.close')?.click();
    await el.updateComplete;

    expect(heard).toHaveBeenCalledTimes(1);
    document.removeEventListener('settings-window-close', heard);
  });

  it('Escape (the dialog `cancel` event) runs the same close routine', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const el = await mountWindow();
    const closes = spyOnClose(el);
    el.open = true;
    await el.updateComplete;

    el.shadowRoot
      ?.querySelector('dialog')
      ?.dispatchEvent(new Event('cancel', { cancelable: true }));
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(closes).toHaveBeenCalledTimes(1);
    expect(backSpy).not.toHaveBeenCalled();
    backSpy.mockRestore();
  });

  it('a click on the dialog itself (the backdrop) closes the window', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const closes = spyOnClose(el);
    el.open = true;
    await el.updateComplete;

    el.shadowRoot?.querySelector('dialog')?.click();
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(closes).toHaveBeenCalledTimes(1);
  });

  it('closing is a no-op when the window is already closed (no stray close event)', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const closes = spyOnClose(el);

    el.shadowRoot?.querySelector<HTMLButtonElement>('button.close')?.click();
    await el.updateComplete;

    expect(closes).not.toHaveBeenCalled();
  });

  it('dismiss() closes the window WITHOUT emitting (the address already moved)', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const closes = spyOnClose(el);
    el.open = true;
    await el.updateComplete;

    el.dismiss();
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(el.shadowRoot?.querySelector('dialog')?.open).toBe(false);
    expect(closes).not.toHaveBeenCalled();
  });

  it('Escape AFTER a dismiss does not emit a second close (the double-unwind defect)', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    const closes = spyOnClose(el);
    el.open = true;
    await el.updateComplete;

    el.dismiss();
    await el.updateComplete;
    el.shadowRoot
      ?.querySelector('dialog')
      ?.dispatchEvent(new Event('cancel', { cancelable: true }));
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(closes).not.toHaveBeenCalled();
  });

  it('requestClose() (Escape/X/backdrop) restores focus to the pre-open invoker', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const trigger = document.createElement('button');
    trigger.textContent = 'open settings';
    document.body.appendChild(trigger);
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    const el = await mountWindow();
    el.open = true;
    await el.updateComplete;
    // happy-dom's showModal() does not itself move focus, so simulate what a real modal does:
    // focus lands on content inside the dialog (here, its own close button) while it is open.
    // `document.activeElement` retargets across the shadow boundary to the host element, so check
    // focus via `shadowRoot.activeElement` instead.
    const closeBtn = el.shadowRoot?.querySelector<HTMLButtonElement>('button.close');
    closeBtn?.focus();
    expect(el.shadowRoot?.activeElement).toBe(closeBtn);

    closeBtn?.click();
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('dismiss() (navigation-initiated close) suppresses ModalityController\'s own restore-to-invoker (855 §11.2 merge-blocker)', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const trigger = document.createElement('button');
    trigger.textContent = 'open settings';
    document.body.appendChild(trigger);
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    const el = await mountWindow();
    el.open = true;
    await el.updateComplete;
    // Simulate the dialog holding focus while open (same setup as the requestClose() test above;
    // `shadowRoot.activeElement` is the shadow-boundary-safe way to check it — see that test).
    const closeBtn = el.shadowRoot?.querySelector<HTMLButtonElement>('button.close');
    closeBtn?.focus();
    expect(el.shadowRoot?.activeElement).toBe(closeBtn);

    el.dismiss();
    await el.updateComplete;

    expect(el.open).toBe(false);
    // This only verifies the CONTROLLER-level suppression: with `skipFocusRestore` set,
    // `ModalityController.exit()` makes no `.focus()` call of its own, so happy-dom (which does not
    // implement the native `<dialog>` auto-restore-focus-on-close behaviour) observes focus staying
    // off `trigger`. It does NOT verify browser behavior end to end — a real browser's native
    // `<dialog>.close()` (called just before this, inside `ModalController.close`) may still return
    // focus to the invoker on its own regardless of this flag (audit-measured on the Back-dismissal
    // path); that is judged benign for the dismissal cases this flag covers, not something this
    // jsdom-based test can observe either way.
    expect(document.activeElement).not.toBe(trigger);
  });

  it('renders an empty state (and does not throw) when the catalog has no settings surface', async () => {
    __seedForTest(catalogOf());
    const el = await mountWindow();
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain('not available');
  });

  // S4 — the window chrome carries no hand-stamped product name: the accessible name is the
  // catalog-declared surface label projected through `present`, not the literal "Settings".
  it('names the dialog + close control from the catalog surface label, not a hard-coded string', async () => {
    __seedForTest(catalogOf(settingsSurface()));
    const el = await mountWindow();
    // No i18n catalog is loaded in this env, so `present` humanizes the id: "Settings".
    expect(el.shadowRoot?.querySelector('dialog')?.getAttribute('aria-label')).toBe('Settings');
    const close = el.shadowRoot?.querySelector('button.close');
    expect(close?.getAttribute('aria-label')).toBe('Close Settings');
    expect(close?.getAttribute('title')).toBe('Close Settings');
  });
});

// 855 P4 review should-fix: two-stage ESC seam, with the REAL `jf-settings-surface` (and its
// nested `jf-settings-nav`) mounted, not the `TestSettingsElement` stub the rest of this file uses.
describe('jf-settings-window × the search box\'s two-stage ESC (855 P4 review)', () => {
  const REAL_SETTINGS_SURFACE: Surface = {
    id: SETTINGS_ID,
    presentation: {
      labelKey: 'registry-surface.settings-surface.label',
      descriptionKey: 'registry-surface.settings-surface.description',
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    placement: 'MODAL',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: 'jf-settings-surface',
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };

  beforeEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetUserStateForTest();
    __resetSessionRegistryForTest();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ ui: {} }), { status: 200 }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    vi.restoreAllMocks();
  });

  /**
   * happy-dom's `<dialog>` does not implement the browser's native "Escape while `showModal()` is
   * open fires `cancel`" behavior — `ConfirmDialog.test.ts`'s own precedent simulates that step by
   * dispatching a synthetic `cancel` Event directly on the dialog, not via a keydown. So this test
   * covers exactly what the harness DOES support: (1) our fix's contract — an Escape keydown on an
   * EMPTY search input is NOT intercepted by the nav (no `preventDefault`, propagation not stopped)
   * all the way out to `document`, proving the nav no longer blocks the native mechanism from
   * running; (2) the "no double-close" invariant — the dialog's native cancel (simulated the same
   * way this file's other tests already do) still runs `requestClose()` exactly once, not doubled
   * by anything on the nav's side of the two-stage ESC change.
   */
  it('an Escape on an empty search input is not intercepted by the nav, and the dialog close path fires exactly once', async () => {
    __seedForTest(catalogOf(REAL_SETTINGS_SURFACE));
    const el = document.createElement('jf-settings-window') as SettingsWindow;
    el.host_ = createMockHostApi();
    document.body.appendChild(el);
    await el.updateComplete;
    const closes = spyOnClose(el);

    el.open = true;
    await el.updateComplete;
    // Flush the nested `jf-settings-surface`'s own connectedCallback-triggered init render (and
    // its nested `jf-settings-nav`'s), which lands on a later microtask than THIS element's update.
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;

    const surfaceEl = el.shadowRoot!.querySelector('jf-settings-surface') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    expect(surfaceEl).not.toBeNull();
    await surfaceEl.updateComplete;
    const nav = surfaceEl.shadowRoot!.querySelector('jf-settings-nav') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    expect(nav).not.toBeNull();
    await nav.updateComplete;
    const input = nav.shadowRoot!.querySelector<HTMLInputElement>('input.search-input')!;
    input.focus();
    expect(nav.shadowRoot!.activeElement).toBe(input);

    const outerKeydown = vi.fn();
    document.addEventListener('keydown', outerKeydown);
    const event = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      composed: true,
      cancelable: true,
    });
    input.dispatchEvent(event);
    document.removeEventListener('keydown', outerKeydown);

    expect(event.defaultPrevented).toBe(false);
    expect(outerKeydown).toHaveBeenCalledTimes(1);
    expect(closes).not.toHaveBeenCalled();

    // Simulate the browser's native Escape → dialog `cancel` (the substitution this file's other
    // Escape tests already make) and confirm the close path fires exactly once — not doubled by
    // anything the nav's two-stage ESC change touches.
    el.shadowRoot!.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    await el.updateComplete;

    expect(el.open).toBe(false);
    expect(closes).toHaveBeenCalledTimes(1);
  });
});
