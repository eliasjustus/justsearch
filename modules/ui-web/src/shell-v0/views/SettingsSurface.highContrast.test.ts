// @vitest-environment happy-dom

/**
 * Tempdoc 855 §15.4/§17 R1 — the end-to-end High-contrast path. NOTHING covered this before: the two
 * competing HC set-sites both shipped with zero e2e coverage, which is why a two-authority defect
 * survived in a shipped surface. This pins the surviving one end to end.
 *
 * The chain under test: the Accessibility switch → `SettingsSurface.patch()` → the APPEARANCE_FLOW
 * statechart's `HC_ON`/`HC_OFF` edge → its two effects → (a) `set-appearance`, which reaches
 * `themeState.applyAppearance` and writes the `high-contrast` root class, and (b) `save-settings`,
 * the narrow `{ui:{highContrast}}` POST to `/api/settings/v2`.
 *
 * HONEST SEAM: `jf-set-appearance` is a global chrome listener (`Shell.ts` — `setAppearanceListener`),
 * and mounting the whole chrome around a settings unit test is not feasible; the test binds the same
 * two-line adapter onto the REAL `applyAppearance` authority. So the assertion covers everything from
 * the click to the class EXCEPT Shell's own event registration, which `Shell` owns.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { applyAppearance, __resetThemeStateForTest } from '../state/themeState.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { SETTINGS_INTERFACE_BODY } from '../themes/builtinPresentations.js';
import { restoreActivePresentationOnBoot } from '../state/presentationState.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';

interface PostedCall {
  readonly path: string;
  readonly body: unknown;
}

let posted: PostedCall[] = [];
let appearanceListener: ((e: Event) => void) | null = null;

/** Stand-in for `Shell.setAppearanceListener` (Shell.ts) — the same adapter onto the same authority. */
function bindAppearanceListener(): void {
  appearanceListener = (e: Event) => {
    const d = (e as CustomEvent<{ theme?: string; highContrast?: boolean }>).detail ?? {};
    void applyAppearance({
      ...(d.theme !== undefined ? { theme: d.theme } : {}),
      ...(d.highContrast !== undefined ? { highContrast: d.highContrast } : {}),
    });
  };
  document.addEventListener('jf-set-appearance', appearanceListener);
}

async function mountSettings(): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement & {
    updateComplete: Promise<unknown>;
  };
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: (path: string, init?: { method?: string; body?: string | object }) => {
        if (init?.method === 'POST') {
          posted.push({ path, body: JSON.parse(String(init.body)) });
          return Promise.resolve(new Response('{}', { status: 200 }));
        }
        return Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 }));
      },
    },
  });
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

/** Activate the switch the way a user does — the inner `role="switch"` element, one shadow root in. */
async function toggleHighContrast(el: HTMLElement): Promise<void> {
  const row = el.shadowRoot!.querySelector('[data-testid="settings-high-contrast"]');
  expect(row, 'the Accessibility section must render the High contrast row').toBeTruthy();
  const sw = row!.querySelector('jf-switch') as HTMLElement & { updateComplete: Promise<unknown> };
  expect(sw, 'the control is the shared jf-switch atom').toBeTruthy();
  await sw.updateComplete;
  (sw.shadowRoot!.querySelector('[role="switch"]') as HTMLElement).click();
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

beforeEach(() => {
  posted = [];
  document.documentElement.className = '';
  __resetUserConfigForTest();
  __resetThemeStateForTest();
  __resetSessionRegistryForTest();
  __resetPresentationForTest();
  __resetForTest();
  __seedForTest({
    'settings.related.label': 'Related settings',
    'settings.section.accessibility': 'Accessibility',
  });
  bindAppearanceListener();
  // Tempdoc 855 fix round 2 — apply the REAL default presentation (production boot's
  // `restoreActivePresentationOnBoot()` applies `CORE_DECLARED` when nothing is persisted, and
  // `__resetUserConfigForTest` above clears any persisted `activePresentationId`). Without this,
  // `activeBodyFor(SETTINGS_INTERFACE_REGION)` resolves undefined and every test below silently
  // exercises the FALLBACK render path instead of the one production actually ships.
  restoreActivePresentationOnBoot();
});

afterEach(() => {
  if (appearanceListener) document.removeEventListener('jf-set-appearance', appearanceListener);
  appearanceListener = null;
  document.body.innerHTML = '';
  document.documentElement.className = '';
  __resetPresentationForTest();
  __resetForTest();
  vi.restoreAllMocks();
});

describe('SettingsSurface — High contrast, end to end', () => {
  it('activating the switch applies the root class AND persists the narrow POST', async () => {
    const el = await mountSettings();
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);

    await toggleHighContrast(el);

    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    expect(posted).toEqual([{ path: '/api/settings/v2', body: { ui: { highContrast: true } } }]);
  });

  it('toggling off reverses both the class and the persisted value', async () => {
    const el = await mountSettings();
    await toggleHighContrast(el);
    await toggleHighContrast(el);

    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
    expect(posted).toEqual([
      { path: '/api/settings/v2', body: { ui: { highContrast: true } } },
      { path: '/api/settings/v2', body: { ui: { highContrast: false } } },
    ]);
  });

  it('renders exactly ONE high-contrast control in the whole surface (§17 R1)', async () => {
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const switches = Array.from(root.querySelectorAll('jf-switch')).filter(
      (s) => (s as HTMLElement & { label?: string }).label === 'High contrast',
    );
    expect(switches.length).toBe(1);
  });

  it('the declared Interface region no longer declares a second high-contrast control', () => {
    // The other half of "exactly one": the surface test above already runs the DECLARED path
    // (`beforeEach` now applies `CORE_DECLARED`, the same default production boot applies), so its
    // `jf-switch` count already covers this region's rendered output. This test pins the SOURCE —
    // the region's schema is what the projection engine renders when a presentation body is applied —
    // so a future body cannot reintroduce the second toggle even before it is ever mounted.
    const properties = (
      SETTINGS_INTERFACE_BODY.schema as unknown as { properties: Record<string, unknown> }
    ).properties;
    expect(Object.keys(properties)).not.toContain('highContrast');
  });

  it('renders the declared Interface region (default path), not the built-in fallback', async () => {
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const declaredSurface = root.querySelector('jf-declared-surface');
    expect(
      declaredSurface,
      'production boot applies CORE_DECLARED, so the default path is the declared region',
    ).toBeTruthy();
  });
});

describe('SettingsSurface — the Appearance → Accessibility cross-link (§17 R1)', () => {
  it('replaces the pruned toggle with a keyboard-operable link to the target section', async () => {
    const el = await mountSettings();
    const link = el.shadowRoot!.querySelector('.link-row') as HTMLButtonElement | null;
    expect(link, 'Appearance must point at where the control now lives').toBeTruthy();
    expect(link!.tagName).toBe('BUTTON'); // native button = focus + Enter/Space for free
    expect(link!.textContent?.trim()).toContain('Accessibility'); // the target's own catalog label
  });

  it('renders exactly once, as a sibling of the declared surface — not projected inside it (F1)', async () => {
    // Fix-round F1 — `renderInterfaceRegion()` renders EITHER `<jf-declared-surface>` OR the
    // built-in fallback, never both, so the row has to sit at the branch join to survive on the
    // default declared path. Before the fix it lived inside `renderAppearance()`, which the
    // declared branch never calls — this is the assertion that would have failed against that
    // placement (the declared path renders CORE_DECLARED by default via `beforeEach` above).
    const el = await mountSettings();
    const root = el.shadowRoot!;
    const declaredSurface = root.querySelector('jf-declared-surface');
    expect(declaredSurface, 'the declared region must render on the default path').toBeTruthy();
    // jf-declared-surface renders through its OWN shadow root, so a light-DOM query against the
    // host element can only see slotted/projected children — there are none here, which is the
    // point: the row is a document sibling, not something projected into the declared surface.
    expect(declaredSurface!.querySelector('.link-row')).toBeNull();
    const linkRows = root.querySelectorAll('.link-row');
    expect(linkRows.length).toBe(1);
  });

  it('activating it scrolls the Accessibility sub-anchor into view', async () => {
    const el = await mountSettings();
    const target = el.shadowRoot!.querySelector('[data-settings-anchor="accessibility"]');
    expect(target, 'the anchor the link addresses must exist in the register-driven render').toBeTruthy();
    // The observable outcome is the scroll, not `activeAnchor`: the scroll-spy re-derives that from
    // measured rects, and every rect is zero-height under happy-dom, so it settles back to null here.
    const scrolled = vi.fn();
    (target as unknown as { scrollIntoView: () => void }).scrollIntoView = scrolled;
    (el.shadowRoot!.querySelector('.link-row') as HTMLButtonElement).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(scrolled).toHaveBeenCalledTimes(1);
  });
});
