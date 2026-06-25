// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.9 polish — Sparkline tests.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import './Sparkline.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

async function mount(values: ReadonlyArray<number>): Promise<HTMLElement> {
  const el = document.createElement('jf-sparkline') as HTMLElement & {
    values: ReadonlyArray<number>;
    updateComplete: Promise<unknown>;
  };
  el.values = values;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('Sparkline', () => {
  it('renders empty svg for empty values', async () => {
    const el = await mount([]);
    const svg = el.shadowRoot!.querySelector('svg');
    expect(svg).toBeTruthy();
    expect(svg!.querySelector('polyline')).toBeNull();
  });

  it('renders empty svg for single-value input', async () => {
    const el = await mount([5]);
    expect(el.shadowRoot!.querySelector('polyline')).toBeNull();
  });

  it('renders a polyline for multi-point series', async () => {
    const el = await mount([1, 3, 5, 2, 7]);
    const line = el.shadowRoot!.querySelector('polyline');
    expect(line).toBeTruthy();
    const pts = line!.getAttribute('points')!;
    expect(pts.split(' ').length).toBe(5);
  });

  it('renders endpoint circle at the last point', async () => {
    const el = await mount([1, 2, 3]);
    const circle = el.shadowRoot!.querySelector('circle.endpoint');
    expect(circle).toBeTruthy();
  });

  it('normalizes y-axis between min and max', async () => {
    const el = await mount([10, 20, 30]); // min=10 max=30
    const line = el.shadowRoot!.querySelector('polyline')!;
    const pts = line.getAttribute('points')!.split(' ');
    // First point at min (y = h), last point at max (y = 0).
    const [, firstY] = pts[0]!.split(',').map(Number);
    const [, lastY] = pts[pts.length - 1]!.split(',').map(Number);
    expect(firstY).toBeCloseTo(12, 0); // default height
    expect(lastY).toBeCloseTo(0, 0);
  });
});
