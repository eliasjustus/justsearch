// @vitest-environment happy-dom

/**
 * The Search v3 shell (tempdoc 822 slice 1) — the window mounts, all five regions are present,
 * the scroll policy holds, and the surface is registered exactly the way a hidden dev route is.
 *
 * No stores are mocked because the slice-1 shell consumes none: it is fixture-first on purpose, so
 * these cases measure geometry and registration and nothing else.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Topbar } from './Sv3Topbar.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { createCorePluginManifest } from '../../plugin-api/CorePlugin.js';
import { isLazySurface } from '../lazySurfaceRegistry.js';
import { COMPONENT_TAGS } from '../../renderers/component-vocabulary.generated.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import {
  COMPOSER_SCOPES,
  HERO_HEADLINE,
  MAIN_ROWS,
  SIDEBAR_GROUPS,
  SIDEBAR_ROWS,
} from './fixtures.js';
import { SV3_MORPH_ROOT_ATTR, sv3MorphSheetAdopted } from './sv3-composer-morph.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/**
 * happy-dom implements no View Transitions, so each case that exercises the animated path installs
 * its own stub and the shared teardown removes it.
 */
type ViewTransitionStub = (cb: () => Promise<void> | void) => { finished: Promise<unknown> };

const stubViewTransition = (impl: ViewTransitionStub): void => {
  (document as unknown as Record<string, unknown>).startViewTransition = impl;
};

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/**
 * Every case starts from a document holding no window: the morph sheet is ref-counted against
 * connected windows, so a window left mounted by an earlier case would mask the release.
 */
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  document.documentElement.removeAttribute(SV3_MORPH_ROOT_ATTR);
  delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
  vi.restoreAllMocks();
});

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

  it('fills the content surface with enough rows to exercise the scroller, once docked', async () => {
    const el = await mount();
    // The window opens on the empty hero, so the results arrive with the docked state.
    await (el as SearchV3View).setComposerState('docked');
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(
      MAIN_ROWS.length,
    );
    expect(MAIN_ROWS.length).toBeGreaterThanOrEqual(12);
    expect(main.shadowRoot?.querySelector('h2')?.textContent).toBe('Results');
  });

  it('gives the composer a field, scope controls and a send control', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-input"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-send"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelectorAll('[data-testid="sv3-composer-scope"]')).toHaveLength(
      COMPOSER_SCOPES.length,
    );
  });
});

/**
 * Donor §5.9 (slice 3): the composer's two forms and the morph between them. The state is the
 * WINDOW's, not the composer's, because it decides what the content region holds.
 */
describe('the composer has two anatomies and one way between them', () => {
  const composerOf = (el: Mounted): Promise<Mounted> => region(el, 'jf-sv3-composer');

  it('opens on the empty hero: headline up, content region empty', async () => {
    const el = await mount();
    expect(el.getAttribute('composer-state')).toBe('hero');
    const composer = await composerOf(el);
    expect(composer.getAttribute('state')).toBe('hero');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-headline"]')?.textContent)
      .toBe(HERO_HEADLINE);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(0);
  });

  it('docks into the band, dropping the headline and revealing the results', async () => {
    const el = await mount();
    await (el as SearchV3View).setComposerState('docked');
    const composer = await composerOf(el);
    expect(el.getAttribute('composer-state')).toBe('docked');
    expect(composer.getAttribute('state')).toBe('docked');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-headline"]')).toBeNull();
    // Both anatomies keep the field, the controls and the action — one component, two states.
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-input"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelectorAll('[data-testid="sv3-composer-scope"]')).toHaveLength(
      COMPOSER_SCOPES.length,
    );
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(
      MAIN_ROWS.length,
    );
  });

  it('keeps the send control disabled until the field carries something to send', async () => {
    const el = await mount();
    const composer = await composerOf(el);
    const send = composer.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-composer-send"]',
    );
    expect(send?.disabled).toBe(true);
    // ...and the placeholder is the overlaid element, shown for exactly the same condition.
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-placeholder"]'))
      .toBeTruthy();

    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = '   ';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    // Whitespace is not content: the control stays refused.
    expect(
      composer.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
        ?.disabled,
    ).toBe(true);

    field!.value = 'northfield lease';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    expect(
      composer.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
        ?.disabled,
    ).toBe(false);
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-placeholder"]')).toBeNull();
  });

  it('docks on send and returns to the hero on Escape', async () => {
    const el = await mount();
    const composer = await composerOf(el);
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'northfield lease';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await el.updateComplete;
    expect(el.getAttribute('composer-state')).toBe('docked');

    field!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await el.updateComplete;
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  /**
   * The compaction is only half done if the label evaporates and leaves a blank box behind. The donor
   * compacts a control INTO its glyph (`ComposerControlIcon`, `ComposerControl.tsx:37`), so the glyph
   * has to be real, and the name the text was carrying has to move somewhere assistive tech can read.
   */
  it('compacts each scope control into a real glyph that keeps its name', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const chips = (): HTMLElement[] => [
      ...(composer.shadowRoot?.querySelectorAll<HTMLElement>(
        '[data-testid="sv3-composer-scope"]',
      ) ?? []),
    ];

    // Hero: the visible text IS the accessible name, so an aria-label would only duplicate it.
    expect(chips()).toHaveLength(COMPOSER_SCOPES.length);
    for (const chip of chips()) expect(chip.hasAttribute('aria-label')).toBe(false);
    for (const [i, chip] of chips().entries()) {
      const glyph = chip.querySelector('svg.scope-glyph');
      expect(glyph, `scope ${i} renders no glyph`).toBeTruthy();
      // A real stroke glyph, not the placeholder swatch it replaced.
      expect(glyph?.querySelector('path, circle, ellipse, polyline')).toBeTruthy();
      expect(glyph?.getAttribute('stroke')).toBe('currentColor');
      expect(chip.textContent?.trim()).toContain(COMPOSER_SCOPES[i]?.label);
    }

    await (el as SearchV3View).setComposerState('docked');
    await composer.updateComplete;
    // Docked: the text is width-collapsed, so the name moves onto the control itself.
    for (const [i, chip] of chips().entries()) {
      expect(chip.getAttribute('aria-label'), `scope ${i} lost its name when compacted`).toBe(
        COMPOSER_SCOPES[i]?.label,
      );
      expect(chip.querySelector('svg.scope-glyph')).toBeTruthy();
    }
  });

  it('routes an external write of the dev attribute through the same morph', async () => {
    const el = await mount();
    const seen: string[] = [];
    stubViewTransition((cb) => {
      seen.push('transition');
      return { finished: Promise.resolve(cb()) };
    });
    el.setAttribute('composer-state', 'docked');
    await el.updateComplete;
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(seen).toEqual(['transition']);
    expect((el as SearchV3View).composerState).toBe('docked');
  });
});

/**
 * The containment obligation, made observable. `::view-transition-*` rules are document-level and a
 * sheet in `document.adoptedStyleSheets` outlives whoever added it, so the window's lifecycle — not
 * good intentions — is what keeps this hidden dev surface from changing how the shipped app animates.
 */
describe('the document-level morph sheet lives exactly as long as the window does', () => {
  it('adopts on connect and removes on the last disconnect', async () => {
    expect(sv3MorphSheetAdopted()).toBe(false);
    const first = await mount();
    expect(sv3MorphSheetAdopted()).toBe(true);
    // Ref-counted: a second window must not let the first one's teardown strip the sheet.
    const second = await mount();
    first.remove();
    expect(sv3MorphSheetAdopted()).toBe(true);
    second.remove();
    expect(sv3MorphSheetAdopted()).toBe(false);
  });

  it('scopes the morph attribute to the transition and clears it afterwards', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const during: Array<boolean> = [];
    stubViewTransition((cb) => {
      during.push(
        document.documentElement.getAttribute(SV3_MORPH_ROOT_ATTR) === 'true',
        composer.hasAttribute('morphing'),
      );
      return { finished: Promise.resolve(cb()) };
    });
    await (el as SearchV3View).setComposerState('docked');
    // Set for the transition...
    expect(during).toEqual([true, true]);
    // ...and gone after it, so no other transition in the app can be caught by these rules.
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
    expect(composer.hasAttribute('morphing')).toBe(false);
  });

  it('never waits on a frame inside the transition callback, which the browser suspends', async () => {
    // Live measurement caught this: the callback awaited a frame, the browser suspends rendering
    // until the callback settles, so the frame never arrived and the transition hung to its ~4s
    // callback timeout — morph skipped, flag held for four seconds. Reproduce the suspension by
    // making frames unavailable for as long as the callback is pending.
    const el = await mount();
    const realRaf = globalThis.requestAnimationFrame;
    stubViewTransition((cb) => {
      globalThis.requestAnimationFrame = (() => 0) as typeof requestAnimationFrame;
      const finished = Promise.resolve(cb()).finally(() => {
        globalThis.requestAnimationFrame = realRaf;
      });
      return { finished };
    });
    const outcome = await Promise.race([
      (el as SearchV3View).setComposerState('docked').then(() => 'settled'),
      new Promise((resolve) => setTimeout(() => resolve('hung'), 500)),
    ]);
    globalThis.requestAnimationFrame = realRaf;
    expect(outcome).toBe('settled');
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
  });

  it('clears the flag on EVERY path a state change can take', async () => {
    // One escape leaves the shipped app's own transitions under this window's rules indefinitely,
    // so each path is walked rather than argued about.
    const paths: Array<[string, () => void]> = [
      ['view transitions unsupported', () => {}],
      [
        'transition resolves',
        () => stubViewTransition((cb) => ({ finished: Promise.resolve(cb()) })),
      ],
      [
        'transition rejects mid-flight',
        () =>
          stubViewTransition((cb) => {
            void cb();
            return { finished: Promise.reject(new Error('skipped')) };
          }),
      ],
      [
        'transition throws before running the update',
        () =>
          stubViewTransition(() => {
            throw new Error('no transition');
          }),
      ],
      [
        'reduced motion',
        () => {
          stubViewTransition((cb) => ({ finished: Promise.resolve(cb()) }));
          vi.spyOn(window, 'matchMedia').mockImplementation(
            (query: string) =>
              ({
                matches: query.includes('prefers-reduced-motion'),
                media: query,
                onchange: null,
                addListener: () => {},
                removeListener: () => {},
                addEventListener: () => {},
                removeEventListener: () => {},
                dispatchEvent: () => false,
              }) as unknown as MediaQueryList,
          );
        },
      ],
    ];
    for (const [label, arrange] of paths) {
      const el = await mount();
      const composer = await region(el, 'jf-sv3-composer');
      arrange();
      await (el as SearchV3View).setComposerState('docked');
      expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR), label).toBe(false);
      expect(composer.hasAttribute('morphing'), label).toBe(false);
      // The state change itself is never optional, whichever path ran.
      expect((el as SearchV3View).composerState, label).toBe('docked');
      el.remove();
      vi.restoreAllMocks();
      delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
    }
  });

  it('swaps instantly under reduced motion, without reaching the API at all', async () => {
    const el = await mount();
    let started = 0;
    stubViewTransition((cb) => {
      started += 1;
      return { finished: Promise.resolve(cb()) };
    });
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches: query.includes('prefers-reduced-motion'),
          media: query,
          onchange: null,
          addListener: () => {},
          removeListener: () => {},
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
        }) as unknown as MediaQueryList,
    );
    await (el as SearchV3View).setComposerState('docked');
    expect(started).toBe(0);
    // The state change is not optional — only its animation is.
    expect((el as SearchV3View).composerState).toBe('docked');
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
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
