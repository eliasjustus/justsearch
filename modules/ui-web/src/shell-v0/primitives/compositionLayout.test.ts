// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { trackTemplate, composeGridStyles, type ZoneDecl } from './compositionLayout.js';
import { CONVERSATION_ZONES } from '../views/unifiedChatRequest.js';

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
      breakpoint: '64rem',
      gap: '1.5rem',
    }).cssText;
    expect(cssText).toContain('display: grid');
    expect(cssText).toContain('grid-template-columns: minmax(0, 1fr);'); // narrow track
    expect(cssText).toContain('gap: 1.5rem');
    expect(cssText).toContain('@media (min-width: 64rem)');
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
