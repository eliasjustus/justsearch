/**
 * Tempdoc 565 §19 / 559 Authority VI (the FILL half) — the pure density decision. (The ResizeObserver
 * measurement integration is layout-driven and validated live; the decision it feeds on is pure and
 * pinned here, the same split as adaptiveBar.test.ts.)
 */
import { describe, it, expect } from 'vitest';
import {
  representationFor,
  GLYPH_LEGIBLE_PX,
  DETAIL_PX,
  DENSITY_LADDER,
} from './adaptiveDensity.js';

describe('representationFor — density projected from the measured box', () => {
  it('a box too small for a legible glyph → minimal (a dot, never an illegible char)', () => {
    expect(representationFor(5)).toBe('minimal'); // gutter ambient/secondary (~5–6px)
    expect(representationFor(GLYPH_LEGIBLE_PX - 1)).toBe('minimal');
  });

  it('a glyph-legible box → compact (the char reads)', () => {
    expect(representationFor(GLYPH_LEGIBLE_PX)).toBe('compact'); // the in-body trace (~11.5px)
    expect(representationFor(DETAIL_PX - 1)).toBe('compact');
  });

  it('a detail-sized box → full', () => {
    expect(representationFor(DETAIL_PX)).toBe('full');
    expect(representationFor(64)).toBe('full'); // the tool-card
  });

  it('legibility CAPS the declared intent — a full declared on a tiny box still renders minimal', () => {
    // The backstop that makes an illegible-at-scale glyph unrepresentable (565 §19.2).
    expect(representationFor(5, 'full')).toBe('minimal');
    expect(representationFor(5, 'compact')).toBe('minimal');
  });

  it('the declared density is a CEILING — a big box never exceeds it (the gutter stays dots)', () => {
    // The spine gutter declares `minimal`; even though a 40px box could afford `full`, it stays minimal.
    expect(representationFor(40, 'minimal')).toBe('minimal');
    expect(representationFor(40, 'compact')).toBe('compact');
  });

  it('an unmeasured box (jsdom/SSR/first-paint) honours the declared density verbatim', () => {
    expect(representationFor(0)).toBe('full'); // default declared
    expect(representationFor(0, 'compact')).toBe('compact');
    expect(representationFor(-1, 'minimal')).toBe('minimal');
    expect(representationFor(NaN, 'compact')).toBe('compact');
  });

  it('the ladder is ordered minimal < compact < full', () => {
    expect(DENSITY_LADDER).toEqual(['minimal', 'compact', 'full']);
  });
});
