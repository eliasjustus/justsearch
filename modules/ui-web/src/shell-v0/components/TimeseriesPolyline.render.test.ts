// @vitest-environment happy-dom

/**
 * TimeseriesPolyline render tests — slice 3a.1.4 Phase 4.
 *
 * Behavior contract derived from §B.8 anchor block (verbatim from the
 * React reference component `Sparkline.tsx`):
 *
 *  - Returns nothing for empty / single-value input.
 *  - Two-or-more values render an SVG with a polyline.
 *  - All-equal values produce a flat midline (height/2 for every point).
 *  - Coordinates are inverted on Y so larger values render higher.
 *  - Default dimensions: width=80, height=18, strokeWidth=1.25.
 *  - Stroke color follows host's CSS `color` via `currentColor`.
 */

import { describe, expect, it } from 'vitest';
import './TimeseriesPolyline.js';
import type { TimeseriesPolyline } from './TimeseriesPolyline.js';

async function mount(
  props: Partial<TimeseriesPolyline>,
): Promise<TimeseriesPolyline> {
  const el = document.createElement('jf-timeseries-polyline') as TimeseriesPolyline;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('TimeseriesPolyline render', () => {
  it('renders nothing when values is undefined', async () => {
    const el = await mount({});
    expect(el.shadowRoot?.querySelector('svg')).toBeNull();
    el.remove();
  });

  it('renders nothing when values is empty', async () => {
    const el = await mount({ values: [] });
    expect(el.shadowRoot?.querySelector('svg')).toBeNull();
    el.remove();
  });

  it('renders nothing when values has a single point', async () => {
    const el = await mount({ values: [42] });
    expect(el.shadowRoot?.querySelector('svg')).toBeNull();
    el.remove();
  });

  it('renders an svg + polyline for >= 2 points', async () => {
    const el = await mount({ values: [1, 2, 3, 4] });
    const svg = el.shadowRoot?.querySelector('svg');
    expect(svg).not.toBeNull();
    const polyline = el.shadowRoot?.querySelector('polyline');
    expect(polyline).not.toBeNull();
    const points = polyline?.getAttribute('points') ?? '';
    expect(points.split(' ')).toHaveLength(4);
  });

  it('flatlines mid-height when all values are equal (range = 0)', async () => {
    const el = await mount({ values: [5, 5, 5], width: 40, height: 20 });
    const points =
      el.shadowRoot?.querySelector('polyline')?.getAttribute('points') ?? '';
    const ys = points.split(' ').map((p) => Number(p.split(',')[1]));
    expect(ys).toEqual([10, 10, 10]); // height/2 = 10
    el.remove();
  });

  it('inverts Y so larger values render higher (Y=0 is top)', async () => {
    const el = await mount({ values: [0, 100], width: 40, height: 20 });
    const points =
      el.shadowRoot?.querySelector('polyline')?.getAttribute('points') ?? '';
    const coords = points
      .split(' ')
      .map((p) => p.split(',').map(Number)) as [number, number][];
    // min (0) maps to height (20, the bottom); max (100) maps to 0 (top)
    expect(coords[0]).toEqual([0, 20]);
    expect(coords[1]).toEqual([40, 0]);
    el.remove();
  });

  it('default dimensions match the §B.8 anchor (width=80, height=18, strokeWidth=1.25)', async () => {
    const el = await mount({ values: [1, 2, 3] });
    const svg = el.shadowRoot?.querySelector('svg');
    expect(svg?.getAttribute('width')).toBe('80');
    expect(svg?.getAttribute('height')).toBe('18');
    const polyline = el.shadowRoot?.querySelector('polyline');
    expect(polyline?.getAttribute('stroke-width')).toBe('1.25');
    el.remove();
  });

  it('default stroke is currentColor (theme-driven)', async () => {
    const el = await mount({ values: [1, 2, 3] });
    const polyline = el.shadowRoot?.querySelector('polyline');
    expect(polyline?.getAttribute('stroke')).toBe('currentColor');
    el.remove();
  });

  it('honors custom width / height / stroke-width', async () => {
    const el = await mount({
      values: [1, 2, 3],
      width: 100,
      height: 30,
      strokeWidth: 2,
    });
    const svg = el.shadowRoot?.querySelector('svg');
    expect(svg?.getAttribute('width')).toBe('100');
    expect(svg?.getAttribute('height')).toBe('30');
    expect(svg?.getAttribute('viewBox')).toBe('0 0 100 30');
    const polyline = el.shadowRoot?.querySelector('polyline');
    expect(polyline?.getAttribute('stroke-width')).toBe('2');
    el.remove();
  });

  it('marks the inner svg aria-hidden (host wrapper carries the accessible name)', async () => {
    const el = await mount({ values: [1, 2, 3] });
    const svg = el.shadowRoot?.querySelector('svg');
    expect(svg?.getAttribute('aria-hidden')).toBe('true');
    el.remove();
  });

  it('handles negative values correctly (min becomes the floor of Y)', async () => {
    const el = await mount({ values: [-50, 0, 50], width: 20, height: 20 });
    const points =
      el.shadowRoot?.querySelector('polyline')?.getAttribute('points') ?? '';
    const coords = points
      .split(' ')
      .map((p) => p.split(',').map(Number)) as [number, number][];
    // min = -50 → bottom (Y=20); max = 50 → top (Y=0); 0 → midpoint (Y=10)
    expect(coords[0]).toEqual([0, 20]);
    expect(coords[1]).toEqual([10, 10]);
    expect(coords[2]).toEqual([20, 0]);
    el.remove();
  });
});
