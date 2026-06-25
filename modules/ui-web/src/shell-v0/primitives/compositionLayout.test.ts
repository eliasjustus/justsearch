// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest';
import { trackTemplate, composeGridStyles, type ZoneDecl } from './compositionLayout.js';

// The agent-window zone-set (mirrors CONVERSATION_ZONES in UnifiedChatView — the §13.9 re-zone:
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
