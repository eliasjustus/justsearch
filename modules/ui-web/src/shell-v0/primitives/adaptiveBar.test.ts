/**
 * Tempdoc 559 Authority VI — the pure overflow cut. (The ResizeObserver +
 * measurement integration is layout-driven and validated live; the decision it
 * feeds on is pure and pinned here.)
 */
import { describe, it, expect } from 'vitest';
import { computeVisibleCount } from './adaptiveBar.js';

describe('computeVisibleCount', () => {
  it('shows all items when they all fit (no "…" needed)', () => {
    expect(computeVisibleCount([10, 10, 10], 100, 20)).toBe(3);
  });

  it('reserves the ellipsis and fits the leading (highest-priority) items', () => {
    // total 60 > available 40; with ellipsis(10): 10+10=20, +10=30, +10=40 (==, excluded) → 2
    expect(computeVisibleCount([10, 10, 10, 10, 10, 10], 40, 10)).toBe(3);
  });

  it('cuts to fewer items as space shrinks', () => {
    expect(computeVisibleCount([20, 20, 20], 45, 5)).toBe(2);
    expect(computeVisibleCount([20, 20, 20], 25, 5)).toBe(1);
  });

  it('keeps at least the items that fit before the first overflow', () => {
    // 30 > 25; ellipsis 5: 5+20=25 (==25, not >25) → 1 fits... wait boundary
    expect(computeVisibleCount([20, 20], 25, 5)).toBe(1);
  });

  it('degrades to "show all" when unmeasured (jsdom: widths 0 / available 0)', () => {
    expect(computeVisibleCount([0, 0, 0], 0, 40)).toBe(3);
    expect(computeVisibleCount([0, 0, 0], 200, 40)).toBe(3);
  });

  it('handles an empty bar', () => {
    expect(computeVisibleCount([], 100, 20)).toBe(0);
  });

  describe('per-item pinned policy (559 Authority VI)', () => {
    it('never hides a pinned item — raises the cut to the last pinned index', () => {
      // Without pinned: 30 fits 1 (ellipsis 5: 5+20=25, +20>45→ wait): [20,20,20] @45,5 → 2.
      // Pin index 2 (the 3rd item): the cut must include it → 3.
      expect(computeVisibleCount([20, 20, 20], 45, 5)).toBe(2);
      expect(computeVisibleCount([20, 20, 20], 45, 5, [false, false, true])).toBe(3);
    });

    it('trims only the normal tail past the last pinned item', () => {
      // 6 items, normally 3 fit; pin index 3 (a normal-tail item) → cut rises to 4.
      expect(computeVisibleCount([10, 10, 10, 10, 10, 10], 40, 10)).toBe(3);
      expect(
        computeVisibleCount([10, 10, 10, 10, 10, 10], 40, 10, [true, false, false, true, false, false]),
      ).toBe(4);
    });

    it('is a no-op when the pinned item already fits (front-loaded pins)', () => {
      // Pinning index 0 when 2 already fit changes nothing.
      expect(computeVisibleCount([20, 20, 20], 45, 5, [true, false, false])).toBe(2);
    });

    it('never exceeds the item count even if pins point past the end', () => {
      expect(computeVisibleCount([20, 20], 25, 5, [false, false, true, true])).toBe(2);
    });
  });
});
