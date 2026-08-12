// @vitest-environment happy-dom

/**
 * The Search v3 shell (tempdoc 822 slice 1) — the window mounts, all five regions are present,
 * the scroll policy holds, and the surface is registered exactly the way a hidden dev route is.
 *
 * No stores are mocked because the slice-1 shell consumes none: it is fixture-first on purpose, so
 * these cases measure geometry and registration and nothing else.
 */
import { describe, it, expect } from 'vitest';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Topbar } from './Sv3Topbar.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { createCorePluginManifest } from '../../plugin-api/CorePlugin.js';
import { isLazySurface } from '../lazySurfaceRegistry.js';
import { COMPONENT_TAGS } from '../../renderers/component-vocabulary.generated.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { MAIN_ROWS, SIDEBAR_GROUPS, SIDEBAR_ROWS } from './fixtures.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/** A nested region renders into its OWN shadow root; settle it before reading. */
async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

/** A component's OWN stylesheet — the last entry in `static styles`, after the shared sheets. */
function own(ctor: { styles?: unknown }): string {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
}

describe('the window mounts with its five regions', () => {
  it('renders sidebar, topbar, main and composer inside the window host', async () => {
    const el = await mount();
    const root = el.shadowRoot;
    expect(root?.querySelector('jf-sv3-sidebar')).toBeTruthy();
    expect(root?.querySelector('.column')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-topbar')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-main')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-composer')).toBeTruthy();
  });

  it('names the window in the topbar and offers two control placeholders', async () => {
    const el = await mount();
    const topbar = await region(el, 'jf-sv3-topbar');
    expect(topbar.shadowRoot?.querySelector('[data-testid="sv3-topbar-title"]')?.textContent).toBe(
      'Search v3',
    );
    expect(
      topbar.shadowRoot?.querySelectorAll('[data-testid="sv3-topbar-control"]'),
    ).toHaveLength(2);
  });

  it('groups the fixture rows under static labels, in fixed order', async () => {
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const labels = [
      ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
    ].map((n) => n.textContent);
    expect(labels).toEqual(SIDEBAR_GROUPS.map((g) => g.label));
    const rows = sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-row"]') ?? [];
    expect(rows).toHaveLength(SIDEBAR_ROWS.length);
    expect(rows).toHaveLength(10);
    // Rendered order is the fixture order — activity never reorders the list.
    expect([...rows].map((r) => (r as Sv3SessionRow).label)).toEqual(
      SIDEBAR_ROWS.map((r) => r.label),
    );
  });

  it('fills the content surface with enough rows to exercise the scroller', async () => {
    const el = await mount();
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(
      MAIN_ROWS.length,
    );
    expect(MAIN_ROWS.length).toBeGreaterThanOrEqual(12);
    expect(main.shadowRoot?.querySelector('h2')?.textContent).toBe('Results');
  });

  it('gives the composer a field and a send control, and nothing else yet', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-input"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-send"]')).toBeTruthy();
  });
});

/**
 * The rendered half of donor §6.1/§6.2 (the CSS half is pinned in sv3-tokens.test.ts): what the
 * fixture set actually puts on screen, and what it deliberately does not.
 */
describe('the session rows render the donor anatomy', () => {
  const rowsOf = async (el: Mounted): Promise<Sv3SessionRow[]> => {
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const rows = [
      ...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? []),
    ] as Sv3SessionRow[];
    await Promise.all(rows.map((r) => r.updateComplete));
    return rows;
  };

  it('spends a status colour on exactly the three non-resting rows', async () => {
    const rows = await rowsOf(await mount());
    const colored = rows.filter(
      (r) => r.shadowRoot?.querySelector('[data-testid="sv3-session-row-status"]') !== null,
    );
    // The budget as a NEGATIVE assertion: seven of the ten rows are resting and must be silent.
    expect(colored).toHaveLength(3);
    expect(colored.map((r) => r.status).sort()).toEqual(['act-now', 'broken', 'in-motion']);
    expect(rows.filter((r) => r.status === 'resting')).toHaveLength(7);
    for (const row of rows.filter((r) => r.status === 'resting')) {
      expect(row.shadowRoot?.querySelector('[data-testid="sv3-session-row-status"]')).toBeNull();
      // A resting row's slot carries its timestamp instead — information, not colour.
      expect(
        row.shadowRoot?.querySelector('[data-testid="sv3-session-row-meta"]')?.textContent?.trim(),
      ).toBe(row.meta);
    }
  });

  it('runs the duty-cycled ping on the in-motion row only', async () => {
    const rows = await rowsOf(await mount());
    const pinging = rows.filter((r) => r.shadowRoot?.querySelector('.sv3-anim-status-ping'));
    expect(pinging).toHaveLength(1);
    expect(pinging[0]?.status).toBe('in-motion');
  });

  it('covers every visual state across the fixture set', async () => {
    const rows = await rowsOf(await mount());
    expect(rows.filter((r) => r.active)).toHaveLength(1);
    expect(rows.filter((r) => r.selected)).toHaveLength(1);
    expect(rows.filter((r) => r.unread).length).toBeGreaterThanOrEqual(1);
    expect(rows.filter((r) => r.receded).length).toBeGreaterThanOrEqual(1);
    expect(rows.filter((r) => r.inflight)).toHaveLength(1);
    // Active and selected are different rows, so the precedence is visible rather than theoretical.
    expect(rows.find((r) => r.active)).not.toBe(rows.find((r) => r.selected));
  });

  it('puts every state on the HOST as an attribute, which is what the fills key off', () => {
    // A state carried only as a property would leave `:host([active])` never matching — the row
    // would render correctly and be styled as if it were resting.
    const row = new Sv3SessionRow();
    document.body.appendChild(row);
    row.active = true;
    row.selected = true;
    row.receded = true;
    row.unread = true;
    row.inflight = true;
    row.status = 'broken';
    return row.updateComplete.then(() => {
      for (const attr of ['active', 'selected', 'receded', 'unread', 'inflight']) {
        expect(row.hasAttribute(attr), `${attr} is not reflected`).toBe(true);
      }
      expect(row.getAttribute('status')).toBe('broken');
      row.remove();
    });
  });

  it('makes each row a real button and marks the active one for assistive tech', async () => {
    const rows = await rowsOf(await mount());
    const buttons = rows.map((r) => r.shadowRoot?.querySelector('button'));
    expect(buttons.every((b) => b?.tagName === 'BUTTON')).toBe(true);
    expect(buttons.every((b) => b?.getAttribute('type') === 'button')).toBe(true);
    const current = rows.filter(
      (r) => r.shadowRoot?.querySelector('button')?.getAttribute('aria-current') === 'true',
    );
    expect(current).toHaveLength(1);
    expect(current[0]?.active).toBe(true);
  });

  it('keeps the group labels out of the tab order', async () => {
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const labels = [
      ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
    ];
    expect(labels).toHaveLength(SIDEBAR_GROUPS.length);
    for (const label of labels) {
      expect(label.tagName).toBe('DIV');
      expect(label.hasAttribute('tabindex')).toBe(false);
      expect(label.querySelector('button, a, input')).toBeNull();
    }
  });
});

describe('the window never scrolls; one inner region does', () => {
  it('clips the window host and the main column', () => {
    const styles = own(SearchV3View);
    const hostRule = styles.slice(styles.indexOf(':host {'), styles.indexOf('jf-sv3-sidebar {'));
    expect(hostRule).toContain('overflow: hidden');
    const columnRule = styles.slice(styles.indexOf('.column {'));
    expect(columnRule).toContain('overflow: hidden');
  });

  it('clips every region host, so no region can become a second scroller', () => {
    for (const ctor of [Sv3Sidebar, Sv3Main]) {
      const styles = own(ctor);
      const hostRule = styles.slice(styles.indexOf(':host {'), styles.indexOf('}'));
      expect(hostRule).toContain('overflow: hidden');
    }
  });

  it('puts the ONE scroller inside the content surface', async () => {
    const el = await mount();
    const main = await region(el, 'jf-sv3-main');
    const scroller = main.shadowRoot?.querySelector('[data-testid="sv3-main-scroller"]');
    expect(scroller).toBeTruthy();
    expect(scroller?.classList.contains('sv3-scroller')).toBe(true);

    // ...and it is the only one: no other region declares a scrolling overflow.
    for (const ctor of [SearchV3View, Sv3Sidebar, Sv3Topbar, Sv3Composer]) {
      const styles = own(ctor);
      expect(styles).not.toContain('overflow-y: auto');
      expect(styles).not.toContain('overflow: auto');
      expect(styles).not.toContain('overflow: scroll');
    }
    // The content surface's own host is clipped; only its inner scroller scrolls.
    expect(own(Sv3Main)).not.toContain('overflow-y: auto');
  });

  it('adopts the scroller mixin exactly once across the rendered window', async () => {
    const el = await mount();
    let found = 0;
    for (const tag of ['jf-sv3-sidebar', 'jf-sv3-topbar', 'jf-sv3-main', 'jf-sv3-composer']) {
      const r = await region(el, tag);
      found += r.shadowRoot?.querySelectorAll('.sv3-scroller').length ?? 0;
    }
    found += el.shadowRoot?.querySelectorAll('.sv3-scroller').length ?? 0;
    expect(found).toBe(1);
  });
});

describe('the surface is registered as a hidden developer route', () => {
  it('declares core.search-v3-surface as DEVELOPER/DEEPLINK with no rail entry', () => {
    const surfaces = createCorePluginManifest().capabilities.surfaces ?? [];
    const v3 = surfaces.find((s) => s.id === 'core.search-v3-surface');
    expect(v3).toBeDefined();
    expect(v3?.mountTag).toBe('jf-sv3-window');
    expect(v3?.audience).toBe('DEVELOPER');
    expect(v3?.placement).toBe('DEEPLINK');
    expect(v3?.placement).not.toBe('RAIL');
  });

  it('loads lazily, like every other deeplink-only surface', () => {
    expect(isLazySurface('jf-sv3-window')).toBe(true);
  });

  it('is in the component vocabulary, with every region it composes', () => {
    for (const tag of [
      'jf-sv3-window',
      'jf-sv3-topbar',
      'jf-sv3-sidebar',
      'jf-sv3-session-row',
      'jf-sv3-main',
      'jf-sv3-composer',
    ]) {
      expect(COMPONENT_TAGS).toContain(tag);
    }
  });
});
