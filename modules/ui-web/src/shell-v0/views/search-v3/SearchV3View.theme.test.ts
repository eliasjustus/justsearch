// @vitest-environment happy-dom

/**
 * The window's light/dark seam (tempdoc 852 S4, parity ledger row 14).
 *
 * `sv3-tokens.css.ts` has carried a COMPLETE authored light palette behind `:host([theme='light'])`
 * since slice 1, and until this slice nothing ever set the attribute — so a reader whose app was in
 * light mode got this window's dark set inside it. That is not a taste difference: the 2026-08-19
 * measured closure audit recorded it as F-06, the unwired sv3 light seam.
 *
 * What is pinned here:
 *  1. **The mirror, both directions and at runtime** — including the OS flip while the reader has
 *     chosen "Follow OS", which is a writer the settings path never goes through.
 *  2. **The window does not decide** — it reads the app's one appearance authority. A second writer
 *     would be a surface disagreeing with the app about which mode it is in.
 *  3. **The attribute actually re-points the palette** — a RENDERED reading, not a re-statement of
 *     the stylesheet: the same element's resolved colour differs between the two modes, and the two
 *     sets sit on opposite sides of mid-grey. A `theme` attribute that reflected correctly while the
 *     light block was, say, deleted or misspelled would pass every DOM assertion above and fail here.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { applyAppearance, getAppearanceMode } from '../../state/themeState.js';
import { sv3Tokens } from './sv3-tokens.css.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/** The OS preference, controllable — and the `change` listener the resolver binds to it. */
let osPrefersDark = true;
const osListeners: Array<() => void> = [];

function stubMatchMedia(): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    get matches() {
      return query.includes('dark') ? osPrefersDark : !osPrefersDark;
    },
    addEventListener: (_type: string, listener: () => void) => {
      osListeners.push(listener);
    },
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => true,
  }));
}

/** Flip the OS preference and notify, the way a real `MediaQueryList` does. */
function flipOsTo(dark: boolean): void {
  osPrefersDark = dark;
  for (const listener of [...osListeners]) listener();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  osPrefersDark = true;
  osListeners.length = 0;
  stubMatchMedia();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body: null }));
  document.documentElement.removeAttribute('data-theme');
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  document.documentElement.removeAttribute('data-theme');
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/** Let the attribute observer deliver (MutationObserver records arrive on a microtask). */
async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 4; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

/* ── 1. The mirror ───────────────────────────────────────────────────────────────────────────── */

describe('the window wears the mode the app is in', () => {
  it('reads the app light/dark on mount, before its first paint', async () => {
    await applyAppearance({ theme: 'light' });
    const el = await mount();
    // Set in the CONSTRUCTOR, so the attribute is already right on the first render rather than one
    // frame later — the flash the app's own pre-paint script exists to prevent.
    expect(el.getAttribute('theme')).toBe('light');

    document.body.removeChild(el);
    await applyAppearance({ theme: 'dark' });
    const second = await mount();
    expect(second.getAttribute('theme')).toBe('dark');
  });

  it('follows a change made while it is mounted, in BOTH directions', async () => {
    await applyAppearance({ theme: 'dark' });
    const el = await mount();
    expect(el.getAttribute('theme')).toBe('dark');

    await applyAppearance({ theme: 'light' });
    await settle(el);
    expect(el.getAttribute('theme')).toBe('light');

    await applyAppearance({ theme: 'dark' });
    await settle(el);
    expect(el.getAttribute('theme')).toBe('dark');
  });

  it('follows an OS flip at runtime while the reader is on Follow OS', async () => {
    osPrefersDark = false;
    await applyAppearance({ theme: 'system' });
    const el = await mount();
    expect(el.getAttribute('theme')).toBe('light');

    // The OS goes dark with no user action and no settings write: the app's resolver re-stamps the
    // attribute and this window has to come with it. This is the writer the settings path never
    // reaches, and the one a "read it once at mount" implementation would silently miss.
    flipOsTo(true);
    await settle(el);
    expect(el.getAttribute('theme')).toBe('dark');
    expect(getAppearanceMode()).toBe('dark');
  });

  it('stops following once it is unmounted, and re-reads when it comes back', async () => {
    await applyAppearance({ theme: 'dark' });
    const el = await mount();
    document.body.removeChild(el);

    await applyAppearance({ theme: 'light' });
    await new Promise<void>((r) => setTimeout(r, 0));
    // A retained instance re-attaches into the mode the app is in NOW, not the one it left.
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.getAttribute('theme')).toBe('light');
  });

  it('never writes the app appearance — the mirror runs one way only', async () => {
    await applyAppearance({ theme: 'light' });
    const el = await mount();
    el.setAttribute('theme', 'dark');
    await settle(el);
    // The window carrying the wrong attribute is a bug in the window; the app's own state is what
    // must not have moved, because a surface that wrote it would fight the settings screen.
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(getAppearanceMode()).toBe('light');
  });
});

/* ── 2. The attribute actually re-points the palette ─────────────────────────────────────────── */

/**
 * WHY THIS IS COMPUTED FROM THE TOKEN GRAPH rather than read off `getComputedStyle`: the test DOM
 * applies no `:host` rule from an adopted constructed stylesheet, so every colour on the window host
 * and its subtree computes to the empty string — a comparison between two of those would pass while
 * the light block was deleted. The repo already settles this the same way for palette contrast
 * (`themes/builtinPaletteContrast.test.ts`: *"getComputedStyle … unavailable in a unit test, so we
 * resolve … here"*). One level of `var()` indirection plus the oklch lightness is all these four
 * declarations use, so the resolver below is the whole engine this claim needs.
 */
const tokens = sv3Tokens.cssText;

/** The last declaration of `name` inside the block opened by `selector`. */
function declIn(selector: string, name: string): string {
  const start = tokens.indexOf(selector);
  if (start < 0) throw new Error(`no ${selector} block in the sheet`);
  const open = tokens.indexOf('{', start);
  const end = tokens.indexOf('\n  }', open);
  const block = tokens.slice(open, end < 0 ? undefined : end);
  const found = [...block.matchAll(new RegExp(`${name}:\\s*([^;]+);`, 'g'))].at(-1);
  if (!found) throw new Error(`${selector} declares no ${name}`);
  return (found[1] ?? '').trim();
}

/** The oklch LIGHTNESS a role resolves to, through at most one primitive indirection. */
function lightnessOf(selector: string, role: string): number {
  let value = declIn(selector, role);
  const indirect = /var\((--[\w-]+)\)/.exec(value);
  // A role defined as a primitive resolves in the T0 block, which is on `:host` for both modes.
  if (indirect?.[1]) value = declIn(':host {', indirect[1]);
  const oklch = /oklch\(\s*([\d.]+)%/.exec(value);
  if (!oklch?.[1]) throw new Error(`${role} in ${selector} is not an oklch literal: ${value}`);
  return Number(oklch[1]);
}

describe('the attribute the window writes is the one the light palette keys on', () => {
  it('names the light set at exactly the attribute value the host reflects', async () => {
    await applyAppearance({ theme: 'light' });
    const el = await mount();
    // The JOIN, asserted as a join: the reflected attribute and the sheet's selector are one fact
    // split across two files, and a rename on either side would silently unwire the palette again.
    expect(el.getAttribute('theme')).toBe('light');
    expect(tokens).toContain(":host([theme='light'])");
  });

  it('inverts the palette polarity — ink and ground swap sides between the two modes', () => {
    const darkGround = lightnessOf(':host {', '--background');
    const darkInk = lightnessOf(':host {', '--foreground');
    const lightGround = lightnessOf(":host([theme='light'])", '--background');
    const lightInk = lightnessOf(":host([theme='light'])", '--foreground');

    // Dark: near-black ground under near-white ink. Light: the same relation, inverted. A light block
    // that merely restated the dark values — or was dropped — fails here rather than rendering an
    // unreadable window nothing in the suite could see.
    expect(darkGround).toBeLessThan(darkInk);
    expect(lightGround).toBeGreaterThan(lightInk);
    // Each mode keeps a real separation, so the inversion is not two near-identical greys.
    expect(darkInk - darkGround).toBeGreaterThan(50);
    expect(lightGround - lightInk).toBeGreaterThan(50);
  });
});
