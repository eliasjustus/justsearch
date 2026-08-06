// @vitest-environment happy-dom
import { describe, it, expect, afterEach } from 'vitest';
import {
  trackTemplate,
  composeGridStyles,
  isShortViewport,
  subscribeShortViewport,
  shortViewportMedia,
  SHORT_VIEWPORT_MAX_HEIGHT_PX,
  SHORT_VIEWPORT_QUERY,
  type ZoneDecl,
} from './compositionLayout.js';
import { CONVERSATION_ZONES } from '../views/unifiedChatRequest.js';
import { unifiedChatBodyStyles } from '../views/unifiedChatStyles.js';
import { isWideLayout, reportLayoutWidth } from '../state/responsiveState.js';

/** A stand-in for a mounted surface reporting its measured width to the breakpoint authority. */
const REPORTER = {};

// A generator FIXTURE modelled on the agent-window zone-set (the §13.9 re-zone:
// the reading column FLANKED by a content-sized spine gutter (col 2) + rail (col 4) that COLLAPSE when
// unmounted, with CAPPED outer margins so the group sits fuller-width).
const ZONES: readonly ZoneDecl[] = [
  { track: 'minmax(0, 8rem)', wideOnly: true },
  { selector: '.run-spine', track: 'auto', col: 2, wideOnly: true },
  { selector: '.conversation', track: 'minmax(0, 50rem)', col: 3 },
  { selector: '.evidence-rail', track: 'fit-content(20rem)', col: 4, wideOnly: true },
  { track: 'minmax(0, 8rem)', wideOnly: true },
];

describe('compositionLayout — §13 Pillar B generator', () => {
  it('generates the flanked-column grid (§13.9 re-zone — content-sized collapsing gutters, capped margins)', () => {
    // narrow: only the non-wideOnly reading zone → one column.
    expect(trackTemplate(ZONES, 'narrow')).toBe('minmax(0, 1fr)');
    // wide: 8rem margin · auto spine gutter · 50rem conversation · ≤20rem rail · 8rem margin.
    expect(trackTemplate(ZONES, 'wide')).toBe(
      'minmax(0, 8rem) auto minmax(0, 50rem) fit-content(20rem) minmax(0, 8rem)',
    );
  });

  it('emits the grid frame CSS: display:grid, the breakpoint, the gap, and per-zone placements', () => {
    const cssText = composeGridStyles(ZONES, {
      container: '.conversation-zone',
      containerName: 'chat-surface',
      breakpoint: '64rem',
      gap: '1.5rem',
    }).cssText;
    expect(cssText).toContain('display: grid');
    expect(cssText).toContain('grid-template-columns: minmax(0, 1fr);'); // narrow track
    expect(cssText).toContain('gap: 1.5rem');
    expect(cssText).toContain('@container chat-surface (min-width: 64rem)');
    expect(cssText).toContain(
      'grid-template-columns: minmax(0, 8rem) auto minmax(0, 50rem) fit-content(20rem) minmax(0, 8rem)',
    ); // wide track
    // the three placed zones (spine col 2, conversation col 3, rail col 4) get a grid-column;
    // the two pure-margin zones (no selector, cols 1 & 5) do not.
    expect(cssText).toContain('grid-column: 2');
    expect(cssText).toContain('grid-column: 3');
    expect(cssText).toContain('grid-column: 4');
    expect(cssText).not.toContain('grid-column: 1');
    expect(cssText).not.toContain('grid-column: 5');
  });

  it('narrow drops every wide-only zone (the reading column alone)', () => {
    // The narrow template drops every wide-only zone (they render `nothing` narrow); the reading zone
    // remains. (Wide-mode empty-zone collapse is a property of the content-sized tracks — asserted live.)
    const narrowZoneCount = trackTemplate(ZONES, 'narrow').split(' minmax').length;
    expect(narrowZoneCount).toBe(1);
  });
});

/** The declared MINIMUM of a track sizing function (`minmax(<min>, …)`, else the track itself). */
function trackMinimum(track: string): string {
  const m = /^minmax\(\s*([^,]+),/.exec(track);
  return (m?.[1] ?? track).trim();
}

/** A track minimum expressed in rem; `0` (and any non-rem minimum) reads as 0rem. */
function trackMinimumRem(track: string): number {
  const m = /^([\d.]+)rem$/.exec(trackMinimum(track));
  return m ? Number(m[1]) : 0;
}

// Sandbox round 7 — with the document preview open at a viewport just over the wide breakpoint the
// reading column collapsed to ~one word per line while both flanking zones held full width. Asserted
// against the REAL CONVERSATION_ZONES (not the fixture above) because the defect WAS the declaration.
describe('CONVERSATION_ZONES — the reading column is floored in its own grid track', () => {
  const zoneFor = (selector: string) =>
    CONVERSATION_ZONES.find((z) => z.selector === selector);

  it('flanks the reading column with two zones that reserve their own min-width floors', () => {
    // The precondition that makes the floor load-bearing: if these zones ever stop being declared,
    // this suite is asserting a floor against nothing and should be revisited rather than trusted.
    expect(zoneFor('.evidence-rail')).toBeDefined();
    expect(zoneFor('.document-pane')).toBeDefined();
  });

  it('declares a NON-ZERO track minimum for .conversation (an item-side min-width cannot substitute)', () => {
    // Browser-measured (chromium, 1050px viewport, rail + pane mounted): `minmax(0, 50rem)` sized
    // the track to 102px; moving `min-width: 24rem` onto the ITEM left the track at 102px and
    // overflowed a 384px item across the rail. Only a track-level floor widens the track itself.
    const conversation = zoneFor('.conversation');
    expect(conversation).toBeDefined();
    expect(trackMinimum(conversation!.track)).not.toBe('0');
    expect(trackMinimumRem(conversation!.track)).toBeGreaterThanOrEqual(24);
  });

  it("keeps the reading column's floor no smaller than the document-pane's (primary is not the first to yield)", () => {
    // The pane's own floor is `min-width: 24rem` (unifiedChatStyles.ts, wide breakpoint); the
    // reading column must not be declared narrower than the secondary surface crowding it.
    expect(trackMinimumRem(zoneFor('.conversation')!.track)).toBeGreaterThanOrEqual(24);
  });

  it('changes only the floor — the reading measure cap stays 50rem', () => {
    expect(zoneFor('.conversation')!.track).toBe('minmax(24rem, 50rem)');
  });

  it('leaves the narrow single-column stack unaffected by the wide-mode floor', () => {
    // trackTemplate's narrow branch hardcodes `minmax(0, 1fr)` per non-wideOnly zone and ignores
    // the declared track, so a wide-mode floor is a no-op narrow (verified, not assumed).
    expect(trackTemplate(CONVERSATION_ZONES, 'narrow')).toBe('minmax(0, 1fr)');
  });
});

// Sandbox round 8 — a permanent horizontal overflow whenever the reading pane and the conversation
// were both mounted at a 1040px window. The declared track minimums are a budget on the CONTAINER's
// width, but the breakpoint that decided whether to spend that budget was a `@media` query on the
// VIEWPORT — a strictly larger number, because the Shell rail and the surface's own padding come off
// it first. These assert the two halves that make the class of error unrepresentable: the query is
// evaluated against the surface box, and the budget fits inside the width at which it commits.
describe('798 — the wide layout commits only at a width its declared minimums fit in', () => {
  const REM_PX = 16;
  const GAP_REM = 1.5;
  const BREAKPOINT_REM = 64;
  // Shell.ts `:host([data-rail-expanded]) { grid-template-columns: 11rem 1fr; }`
  const RAIL_EXPANDED_REM = 11;
  // unifiedChatBodyStyles `:host { padding: 1rem; box-sizing: border-box; }` — both inline sides.
  const SURFACE_PADDING_REM = 2;
  /** The reproduction's window width. */
  const DEFECT_VIEWPORT_PX = 1040;

  /**
   * The largest `min-width: <n>rem` the surface stylesheet declares on `.document-pane` — the pane's
   * item-side floor, which the grid charges to the pane's `fit-content` track. Read from the CSS
   * rather than restated here so moving the floor moves this budget with it.
   */
  function documentPaneFloorRem(): number {
    const re = /\.document-pane\s*\{[^}]*min-width:\s*([\d.]+)rem/g;
    let max = 0;
    for (const m of unifiedChatBodyStyles.cssText.matchAll(re)) max = Math.max(max, Number(m[1]));
    return max;
  }

  /** A track's declared minimum in rem (`minmax(<min>, …)`, else 0 for content-sized tracks). */
  const zoneMinRem = (selector: string) =>
    trackMinimumRem(CONVERSATION_ZONES.find((z) => z.selector === selector)!.track);

  it('evaluates the breakpoint against the SURFACE box, not the viewport', () => {
    const cssText = composeGridStyles(CONVERSATION_ZONES, {
      container: '.conversation-zone',
      containerName: 'chat-surface',
      breakpoint: '64rem',
      gap: '1.5rem',
    }).cssText;
    expect(cssText).toContain('@container chat-surface (min-width: 64rem)');
    expect(cssText).not.toContain('@media');
    // A `@container` query with no container established is inert — it would silently never match and
    // the surface would be stuck narrow forever. The named container has to be declared, and on the
    // `.answer-plane` wrapper specifically: NOT on :host, whose layout containment would re-anchor the
    // fixed-position <jf-citation-hover-card> appended to the same shadow root.
    const plane = /\.answer-plane\s*\{([^}]*)\}/.exec(unifiedChatBodyStyles.cssText);
    expect(plane).not.toBeNull();
    expect(plane![1]).toContain('container-type: inline-size');
    expect(plane![1]).toContain('container-name: chat-surface');
    expect(/:host\s*\{[^}]*container-type/.test(unifiedChatBodyStyles.cssText)).toBe(false);
  });

  it('the reading column + reading pane + gutters fit inside the container width that commits them', () => {
    // Six declared tracks always charge five gutters, including across the collapsed ones.
    const gutterRem = (CONVERSATION_ZONES.length - 1) * GAP_REM;
    const paneFloorRem = documentPaneFloorRem();
    expect(paneFloorRem).toBeGreaterThan(0); // the floor is real, not a parse miss
    const budgetRem = zoneMinRem('.conversation') + paneFloorRem + gutterRem;
    // The load-bearing inequality. Pre-fix this compared 55.5rem against a 65rem VIEWPORT and passed
    // while the surface itself had only 52rem — 888px of tracks in an 832px box.
    expect(budgetRem).toBeLessThanOrEqual(BREAKPOINT_REM);
  });

  it('does not commit the wide layout at the 1040px window that overflowed', () => {
    const surfacePx =
      DEFECT_VIEWPORT_PX - (RAIL_EXPANDED_REM + SURFACE_PADDING_REM) * REM_PX;
    expect(surfacePx).toBe(832);
    reportLayoutWidth(REPORTER, surfacePx);
    expect(isWideLayout()).toBe(false);
    reportLayoutWidth(REPORTER, null);
  });

  it('does commit once the surface itself reaches the breakpoint', () => {
    reportLayoutWidth(REPORTER, BREAKPOINT_REM * REM_PX);
    expect(isWideLayout()).toBe(true);
    reportLayoutWidth(REPORTER, null);
  });

  it('the narrowest reported surface governs, and a withdrawal cannot clear a live neighbour', () => {
    // The split stage mounts two surfaces; a half-width one must not talk the shared decision into a
    // layout that does not fit it, and its unmount must not reset the decision for the other.
    const other = {};
    reportLayoutWidth(REPORTER, 1600);
    reportLayoutWidth(other, 700);
    expect(isWideLayout()).toBe(false);
    reportLayoutWidth(other, null);
    expect(isWideLayout()).toBe(true);
    reportLayoutWidth(REPORTER, null);
  });
});

// Tempdoc 814 §D6 — the BLOCK-axis breakpoint authority. Chrome accreted to ~60% of a ~790px window
// because no height-based query existed anywhere in the surface: every band's own maximum was
// individually defensible and nothing owned the sum. These assert the one number has one home, that
// both its halves (CSS `@media`, JS `matchMedia`) are spelled from it, and that the surface stylesheet
// actually consumes it.
describe('814 — the one block-axis breakpoint', () => {
  /** happy-dom's viewport IS controllable, and its 768px default sits BELOW this breakpoint. */
  function setViewportHeight(px: number): void {
    (
      window as unknown as { happyDOM: { setViewport: (v: { height: number }) => void } }
    ).happyDOM.setViewport({ height: px });
  }

  afterEach(() => setViewportHeight(768));

  it('spells the media condition from the one constant', () => {
    expect(SHORT_VIEWPORT_MAX_HEIGHT_PX).toBe(820);
    expect(SHORT_VIEWPORT_QUERY).toBe('(max-height: 820px)');
    // A VIEWPORT media query, not a container query (§B.12): the surface box is `container-type:
    // inline-size` and cannot query its own block size.
    expect(shortViewportMedia.cssText).toBe('@media (max-height: 820px)');
    expect(shortViewportMedia.cssText).not.toContain('@container');
  });

  it('reads the live viewport on both sides of the breakpoint', () => {
    setViewportHeight(700);
    expect(isShortViewport()).toBe(true);
    setViewportHeight(1000);
    expect(isShortViewport()).toBe(false);
    // Precision: the breakpoint is inclusive at exactly 820 and clear one pixel above it.
    setViewportHeight(SHORT_VIEWPORT_MAX_HEIGHT_PX);
    expect(isShortViewport()).toBe(true);
    setViewportHeight(SHORT_VIEWPORT_MAX_HEIGHT_PX + 1);
    expect(isShortViewport()).toBe(false);
  });

  it('fires a subscriber once immediately with the current value, and stops after unsubscribe', () => {
    setViewportHeight(700);
    const seen: boolean[] = [];
    const unsub = subscribeShortViewport((short) => seen.push(short));
    expect(seen).toEqual([true]);
    unsub();
    setViewportHeight(1000);
    expect(seen).toEqual([true]);
  });

  it('is CONSUMED by the surface stylesheet — the document pane yields its floor below it', () => {
    // The F5 close (734 round 8): the 24rem floor + fixed chrome exceeds a 768-tall window and the
    // composer, bottom of the flex column, paid. Asserted against the real stylesheet so deleting the
    // yield fails here rather than silently reopening the defect.
    const cssText = unifiedChatBodyStyles.cssText;
    expect(cssText).toContain('@media (max-height: 820px)');
    const short = /@media \(max-height: 820px\)\s*\{([\s\S]*?)\n {4}\}/.exec(cssText);
    expect(short).not.toBeNull();
    expect(short![1]).toContain('.document-pane');
    const yielded = /min-height:\s*([\d.]+)rem/.exec(short![1] ?? '');
    expect(yielded).not.toBeNull();
    // The floor yields — it does not merely restate the 24rem it is overriding.
    expect(Number(yielded?.[1])).toBeLessThan(24);
  });
});
