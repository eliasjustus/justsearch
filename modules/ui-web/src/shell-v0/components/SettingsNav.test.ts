// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * `<jf-settings-nav>` — tempdoc 855 §9.5: the register-driven vertical grouped nav.
 *
 * Covers the projection contract (nav renders exactly what the register declares), the accordion
 * (only the active category shows sub-anchors), the danger-group isolation, and the two events the
 * host (`SettingsSurface`) wires (`category-select` / `anchor-jump`).
 */
import { describe, it, expect, afterEach, beforeEach } from 'vitest';
import './SettingsNav.js';
import type { SettingsGroup } from '../views/settingsRegister.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';

const FIXTURE_REGISTER: readonly SettingsGroup[] = [
  {
    id: 'general',
    labelKey: 'settings.group.general',
    categories: [
      {
        id: 'appearance',
        kind: 'native',
        labelKey: 'settings.category.appearance',
        sections: [
          { key: 'interface', labelKey: 'settings.section.interface' },
          { key: 'theme', labelKey: 'settings.section.theme' },
        ],
      },
      { id: 'core.presentation-gallery-surface', kind: 'member', memberSurfaceId: 'core.presentation-gallery-surface' },
    ],
  },
  {
    id: 'danger-group',
    labelKey: 'settings.group.data',
    danger: true,
    categories: [
      {
        id: 'data',
        kind: 'native',
        labelKey: 'settings.category.data',
        sections: [{ key: 'data', labelKey: 'settings.section.data' }],
      },
    ],
  },
];

async function mountNav(activeCategory: string): Promise<HTMLElement & { register: unknown; activeAnchor: string | null }> {
  const el = document.createElement('jf-settings-nav') as HTMLElement & {
    register: unknown;
    activeCategory: string;
    activeAnchor: string | null;
    updateComplete: Promise<unknown>;
  };
  el.register = FIXTURE_REGISTER;
  el.activeCategory = activeCategory;
  el.activeAnchor = null;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-settings-nav', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest({
      'settings.group.general': 'General',
      'settings.group.data': 'Data',
      'settings.category.appearance': 'Appearance',
      'settings.category.data': 'Data',
      'settings.section.interface': 'Interface',
      'settings.section.theme': 'Theme',
      'settings.section.data': 'Data',
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
  });

  it('renders one category row per declared category, in register order', async () => {
    const el = await mountNav('appearance');
    const rows = Array.from(
      el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button.category-row'),
    );
    expect(rows.map((r) => r.dataset.settingsCategory)).toEqual([
      'appearance',
      'core.presentation-gallery-surface',
      'data',
    ]);
  });

  it('accordion: only the ACTIVE category shows its sub-anchors', async () => {
    const el = await mountNav('appearance');
    const anchors = Array.from(el.shadowRoot!.querySelectorAll('button.anchor-row')).map(
      (b) => b.textContent?.trim(),
    );
    expect(anchors).toEqual(['Interface', 'Theme']);
  });

  it('switching the active category moves the accordion', async () => {
    const el = await mountNav('data');
    const anchors = Array.from(el.shadowRoot!.querySelectorAll('button.anchor-row')).map(
      (b) => b.textContent?.trim(),
    );
    expect(anchors).toEqual(['Data']);
  });

  it('a member category (no declared sections) shows no sub-anchors even when active', async () => {
    const el = await mountNav('core.presentation-gallery-surface');
    expect(el.shadowRoot!.querySelectorAll('button.anchor-row').length).toBe(0);
  });

  it('clicking a category row dispatches category-select with its id', async () => {
    const el = await mountNav('appearance');
    let detail: { id: string } | null = null;
    el.addEventListener('category-select', (e) => {
      detail = (e as CustomEvent<{ id: string }>).detail;
    });
    const dataRow = el.shadowRoot!.querySelector<HTMLButtonElement>(
      'button[data-settings-category="data"]',
    )!;
    dataRow.click();
    expect(detail).toEqual({ id: 'data' });
  });

  it('clicking a sub-anchor dispatches anchor-jump with its key', async () => {
    const el = await mountNav('appearance');
    let detail: { key: string } | null = null;
    el.addEventListener('anchor-jump', (e) => {
      detail = (e as CustomEvent<{ key: string }>).detail;
    });
    const themeAnchor = el.shadowRoot!.querySelector<HTMLButtonElement>(
      'button[data-settings-anchor="theme"]',
    )!;
    themeAnchor.click();
    expect(detail).toEqual({ key: 'theme' });
  });

  it('the danger group isolates its category row with the danger class', async () => {
    const el = await mountNav('appearance');
    const dataRow = el.shadowRoot!.querySelector('button[data-settings-category="data"]')!;
    expect(dataRow.classList.contains('danger')).toBe(true);
    const appearanceRow = el.shadowRoot!.querySelector(
      'button[data-settings-category="appearance"]',
    )!;
    expect(appearanceRow.classList.contains('danger')).toBe(false);
  });

  it('aria-current marks the active category row', async () => {
    const el = await mountNav('data');
    const dataRow = el.shadowRoot!.querySelector('button[data-settings-category="data"]')!;
    const appearanceRow = el.shadowRoot!.querySelector(
      'button[data-settings-category="appearance"]',
    )!;
    expect(dataRow.getAttribute('aria-current')).toBe('true');
    expect(appearanceRow.hasAttribute('aria-current')).toBe(false);
  });
});
