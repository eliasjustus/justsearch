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
import { MAIN_ROWS, SIDEBAR_ROWS } from './fixtures.js';

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

  it('shows the group label and the six fixture rows in the sidebar', async () => {
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(
      sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-group-label"]')?.textContent,
    ).toBe('Recent');
    const rows = sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-row"]') ?? [];
    expect(rows).toHaveLength(6);
    expect(rows).toHaveLength(SIDEBAR_ROWS.length);
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
      'jf-sv3-main',
      'jf-sv3-composer',
    ]) {
      expect(COMPONENT_TAGS).toContain(tag);
    }
  });
});
