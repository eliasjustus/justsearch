// @vitest-environment happy-dom

/**
 * TimeseriesSparkline render tests — slice 3a.1.4 Phase 4.
 *
 * Behavior contract:
 *
 *  - Consumes a `TimeseriesSnapshot` wire payload; delegates SVG
 *    rendering to the polyline primitive.
 *  - Renders nothing when snapshot is undefined or values has < 2 entries.
 *  - Sets `role="img"` on the host so screen readers announce the
 *    synthesized accessible name.
 *  - Synthesizes an aria-label from snapshot fields (count, unit,
 *    current, peak) — labels match a stable pattern.
 *  - Composes the consumer-supplied `label` prefix into the aria-label.
 *  - Skips peak in the label when min == max (flat line).
 */

import { describe, expect, it } from 'vitest';
import './TimeseriesSparkline.js';
import type { TimeseriesSparkline } from './TimeseriesSparkline.js';
import type { TimeseriesSnapshot } from '../../api/generated/index.js';

function makeSnapshot(values: number[], unit = ''): TimeseriesSnapshot {
  return {
    resourceId: 'metric:test',
    windowMs: 30 * 60 * 1000,
    sampleIntervalMs: 30 * 1000,
    unit,
    values,
    startedAt: '2026-05-05T00:00:00Z',
    endedAt: '2026-05-05T00:30:00Z',
    catalogVersion: 1,
  };
}

async function mount(
  props: Partial<TimeseriesSparkline>,
): Promise<TimeseriesSparkline> {
  const el = document.createElement('jf-timeseries-sparkline') as TimeseriesSparkline;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('TimeseriesSparkline render', () => {
  it('renders nothing when snapshot is undefined', async () => {
    const el = await mount({});
    expect(el.shadowRoot?.querySelector('jf-timeseries-polyline')).toBeNull();
    el.remove();
  });

  it('renders nothing when snapshot.values is empty', async () => {
    const el = await mount({ snapshot: makeSnapshot([]) });
    expect(el.shadowRoot?.querySelector('jf-timeseries-polyline')).toBeNull();
    el.remove();
  });

  it('renders nothing when snapshot.values has a single point', async () => {
    const el = await mount({ snapshot: makeSnapshot([42]) });
    expect(el.shadowRoot?.querySelector('jf-timeseries-polyline')).toBeNull();
    el.remove();
  });

  it('delegates rendering to <jf-timeseries-polyline> for >= 2 values', async () => {
    const el = await mount({
      snapshot: makeSnapshot([1, 2, 3], 'count'),
    });
    const polyline = el.shadowRoot?.querySelector('jf-timeseries-polyline');
    expect(polyline).not.toBeNull();
    const v = (polyline as unknown as { values?: readonly number[] }).values;
    expect(v).toEqual([1, 2, 3]);
    el.remove();
  });

  it('sets role="img" on the host', async () => {
    const el = await mount({ snapshot: makeSnapshot([1, 2, 3]) });
    expect(el.getAttribute('role')).toBe('img');
    el.remove();
  });

  it('synthesizes an aria-label including count, unit, current, peak', async () => {
    const el = await mount({
      snapshot: makeSnapshot([10, 20, 30, 50], 'count'),
      label: 'Pending jobs',
    });
    const aria = el.getAttribute('aria-label') ?? '';
    expect(aria).toContain('Pending jobs');
    expect(aria).toContain('4 samples');
    expect(aria).toContain('count');
    expect(aria).toContain('current 50');
    expect(aria).toContain('peak 50');
    el.remove();
  });

  it('omits "peak" from aria-label when all values are equal (range = 0)', async () => {
    const el = await mount({
      snapshot: makeSnapshot([5, 5, 5]),
      label: 'Idle metric',
    });
    const aria = el.getAttribute('aria-label') ?? '';
    expect(aria).toContain('current 5');
    expect(aria).not.toContain('peak');
    el.remove();
  });

  it('omits unit clause when snapshot.unit is absent', async () => {
    const el = await mount({ snapshot: makeSnapshot([1, 2, 3]) });
    const aria = el.getAttribute('aria-label') ?? '';
    expect(aria).toContain('3 samples');
    expect(aria).not.toContain(' in ');
    el.remove();
  });

  it('omits label prefix when label is empty', async () => {
    const el = await mount({ snapshot: makeSnapshot([1, 2, 3], 'count') });
    const aria = el.getAttribute('aria-label') ?? '';
    expect(aria.startsWith('trend:')).toBe(true);
    el.remove();
  });

  it('formats integer values without decimals; non-integer values with 2 decimals', async () => {
    const el = await mount({
      snapshot: makeSnapshot([1.5, 2.75, 3], 'rate/s'),
    });
    const aria = el.getAttribute('aria-label') ?? '';
    expect(aria).toContain('current 3'); // last value is integer
    expect(aria).toContain('peak 3');
    el.remove();

    const el2 = await mount({
      snapshot: makeSnapshot([1.5, 2.75, 3.14], 'rate/s'),
    });
    const aria2 = el2.getAttribute('aria-label') ?? '';
    expect(aria2).toContain('current 3.14');
    expect(aria2).toContain('peak 3.14');
    el2.remove();
  });

  it('updates aria-label when snapshot changes', async () => {
    const el = await mount({
      snapshot: makeSnapshot([1, 2, 3], 'count'),
    });
    const before = el.getAttribute('aria-label') ?? '';
    expect(before).toContain('current 3');

    el.snapshot = makeSnapshot([10, 20], 'count');
    await el.updateComplete;

    const after = el.getAttribute('aria-label') ?? '';
    expect(after).toContain('current 20');
    expect(before).not.toBe(after);
    el.remove();
  });
});
