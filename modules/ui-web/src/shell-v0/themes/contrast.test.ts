/**
 * contrast — Tempdoc 567 §8 / A2 — WCAG contrast utility tests (known-ratio harness).
 */
import { describe, it, expect } from 'vitest';
import {
  parseColor,
  contrastRatio,
  deriveForeground,
  deriveTintedForeground,
  apcaLc,
  APCA_SOFT,
  WCAG_AA,
  WCAG_AAA,
  formatRatio,
} from './contrast.js';

describe('parseColor', () => {
  it('parses 6-digit hex', () => expect(parseColor('#ff8800')).toEqual([255, 136, 0]));
  it('parses 3-digit hex', () => expect(parseColor('#f80')).toEqual([255, 136, 0]));
  it('parses rgb()', () => expect(parseColor('rgb(10, 20, 30)')).toEqual([10, 20, 30]));
  it('parses rgba() (drops alpha)', () =>
    expect(parseColor('rgba(10, 20, 30, 0.5)')).toEqual([10, 20, 30]));
  it('parses a bare "r, g, b" triplet (the p-* channel format)', () =>
    expect(parseColor('255, 255, 255')).toEqual([255, 255, 255]));
  it('returns null for an unparseable value (e.g. oklch)', () =>
    expect(parseColor('oklch(70% 0.1 200)')).toBeNull());
});

describe('contrastRatio (WCAG)', () => {
  it('white on black is 21:1', () =>
    expect(contrastRatio([255, 255, 255], [0, 0, 0])).toBeCloseTo(21, 5));
  it('identical colours are 1:1', () =>
    expect(contrastRatio([100, 100, 100], [100, 100, 100])).toBeCloseTo(1, 5));
  it('#777 on white ≈ 4.48:1', () =>
    expect(contrastRatio([119, 119, 119], [255, 255, 255])).toBeCloseTo(4.48, 1));
  it('is order-independent', () => {
    const a = [30, 40, 50] as const;
    const b = [200, 210, 220] as const;
    expect(contrastRatio(a, b)).toBeCloseTo(contrastRatio(b, a), 10);
  });
});

describe('deriveForeground', () => {
  it('picks white for a dark background, meeting AA', () => {
    const d = deriveForeground([15, 15, 18]);
    expect(d.fg).toBe('#ffffff');
    expect(d.meets).toBe(true);
    expect(d.ratio).toBeGreaterThan(WCAG_AA);
  });
  it('picks black for a light background, meeting AA', () => {
    const d = deriveForeground([248, 249, 252]);
    expect(d.fg).toBe('#000000');
    expect(d.meets).toBe(true);
  });
  it('reports meets=false when even the best fg cannot clear a high floor (AAA on mid-tone)', () => {
    const d = deriveForeground([119, 119, 119], WCAG_AAA);
    expect(d.meets).toBe(false);
  });
});

describe('deriveTintedForeground (§8 deferred → built)', () => {
  // A bright teal fill — pure black clears it with lots of headroom, so tinting applies.
  const teal: readonly [number, number, number] = [0, 170, 160];

  it('returns a tinted (non-black/white) foreground that still clears the tint floor', () => {
    const plain = deriveForeground(teal);
    const tinted = deriveTintedForeground(teal, WCAG_AA, WCAG_AAA);
    // The plain extreme is pure black here; the tinted result must differ (it carries the hue).
    expect(plain.fg).toBe('#000000');
    expect(tinted.fg).not.toBe('#000000');
    expect(tinted.fg).not.toBe('#ffffff');
    // Correct-by-construction: a tinted result always clears the (higher) tint floor → AAA here.
    expect(tinted.ratio).toBeGreaterThanOrEqual(WCAG_AAA - 0.05);
    expect(tinted.meets).toBe(true);
  });

  it('carries the background hue: the tinted ink is a darkened teal (g,b channels lead red)', () => {
    const tinted = deriveTintedForeground(teal);
    const rgb = parseColor(tinted.fg);
    expect(rgb).not.toBeNull();
    const [r, g, b] = rgb as [number, number, number];
    // teal = low red, higher green/blue; the dark-tinted ink preserves that ordering (not neutral grey).
    expect(g).toBeGreaterThan(r);
    expect(b).toBeGreaterThan(r);
  });

  it('never tints below the floor: a mid-tone bg with no AAA headroom keeps the plain extreme', () => {
    const mid: readonly [number, number, number] = [119, 119, 119];
    const plain = deriveForeground(mid, WCAG_AA);
    const tinted = deriveTintedForeground(mid, WCAG_AA, WCAG_AAA);
    // Pure black/white can't reach AAA over #777, so no tint is spent — identical to the plain result.
    expect(tinted.fg).toBe(plain.fg);
    expect(tinted.ratio).toBeCloseTo(plain.ratio, 5);
  });

  it('the tinted result never drops below the requested floor', () => {
    for (const bg of [
      [0, 170, 160],
      [240, 200, 0],
      [200, 40, 40],
      [40, 90, 220],
    ] as const) {
      const tinted = deriveTintedForeground(bg, WCAG_AA, WCAG_AAA);
      expect(tinted.ratio).toBeGreaterThanOrEqual(WCAG_AA);
    }
  });
});

describe('formatRatio', () => {
  it('rounds to one decimal', () => expect(formatRatio(12.345)).toBe('12.3'));
});

describe('apcaLc (tempdoc 576 §6 B4 — perceptual signal)', () => {
  it('black on white is high |Lc| (well above the soft floor)', () => {
    expect(Math.abs(apcaLc([0, 0, 0], [255, 255, 255]))).toBeGreaterThan(APCA_SOFT);
  });
  it('white on black is high |Lc|', () => {
    expect(Math.abs(apcaLc([255, 255, 255], [0, 0, 0]))).toBeGreaterThan(APCA_SOFT);
  });
  it('equal colours are ~0 Lc', () => {
    expect(Math.abs(apcaLc([128, 128, 128], [128, 128, 128]))).toBeLessThan(1);
  });
  it('a mid-tone saturated fill that clears WCAG-AA can still be APCA-weak (< soft floor)', () => {
    // A mid amber that black text clears at AA but APCA scores weak — the #3 bug class.
    const bg: [number, number, number] = [210, 150, 70];
    expect(contrastRatio([0, 0, 0], bg)).toBeGreaterThanOrEqual(WCAG_AA); // clears the WCAG hard floor
    expect(Math.abs(apcaLc([0, 0, 0], bg))).toBeLessThan(APCA_SOFT); // …yet APCA-weak (the nudge trigger)
  });
});
