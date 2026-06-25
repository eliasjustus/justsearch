/**
 * Tempdoc 565 §13/§19 — the scroll-window math behind the spine's viewport indicator. Pure + pinned
 * here; the live scroll-listener + overlay rendering is validated in the browser.
 */
import { describe, it, expect } from 'vitest';
import { viewportWindow } from './scrollViewport.js';

describe('viewportWindow', () => {
  it('returns null when the content is not scrollable (fits / unmeasured)', () => {
    expect(viewportWindow(0, 600, 600)).toBeNull(); // exactly fits
    expect(viewportWindow(0, 800, 600)).toBeNull(); // viewport taller than content
    expect(viewportWindow(0, 600, 0)).toBeNull(); // unmeasured content
    expect(viewportWindow(0, 0, 600)).toBeNull(); // unmeasured viewport
  });

  it('at the top: window starts at 0, height = viewport/content', () => {
    const w = viewportWindow(0, 300, 1200)!;
    expect(w.topFrac).toBeCloseTo(0, 5);
    expect(w.botFrac).toBeCloseTo(0.25, 5);
  });

  it('scrolled to the bottom: window ends at 1', () => {
    const w = viewportWindow(1200 - 300, 300, 1200)!; // scrollTop = scrollHeight - clientHeight
    expect(w.botFrac).toBeCloseTo(1, 5);
    expect(w.topFrac).toBeCloseTo(0.75, 5);
  });

  it('in the middle: window centred on the scroll fraction', () => {
    const w = viewportWindow(450, 300, 1200)!;
    expect(w.topFrac).toBeCloseTo(0.375, 5);
    expect(w.botFrac).toBeCloseTo(0.625, 5);
  });

  it('clamps over-scroll into [0,1]', () => {
    const w = viewportWindow(5000, 300, 1200)!; // scrollTop past the end
    expect(w.topFrac).toBe(1);
    expect(w.botFrac).toBe(1);
    const w2 = viewportWindow(-50, 300, 1200)!;
    expect(w2.topFrac).toBe(0);
  });
});
