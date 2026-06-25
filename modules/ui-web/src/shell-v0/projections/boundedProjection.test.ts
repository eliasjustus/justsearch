/**
 * Tempdoc 550 thesis III(b): the bounded-projection primitives that keep high-cardinality
 * glance/timeline projections from rendering 1:1.
 */
import { describe, it, expect } from 'vitest';
import { countByKey, capWithOverflow, collapseBursts } from './boundedProjection';

describe('countByKey', () => {
  it('counts items per key', () => {
    const m = countByKey(['a', 'b', 'a', 'a', 'b'], (s) => s);
    expect(m.get('a')).toBe(3);
    expect(m.get('b')).toBe(2);
  });
  it('is empty for an empty input', () => {
    expect(countByKey([], (s: string) => s).size).toBe(0);
  });
});

describe('capWithOverflow', () => {
  it('keeps the first cap items and reports the overflow', () => {
    const { shown, overflow } = capWithOverflow([1, 2, 3, 4, 5], 3);
    expect(shown).toEqual([1, 2, 3]);
    expect(overflow).toBe(2);
  });
  it('has zero overflow when under the cap', () => {
    const { shown, overflow } = capWithOverflow([1, 2], 8);
    expect(shown).toEqual([1, 2]);
    expect(overflow).toBe(0);
  });
  it('rejects a negative cap', () => {
    expect(() => capWithOverflow([1], -1)).toThrow();
  });
});

describe('collapseBursts', () => {
  interface Row {
    id: string;
    coll: string | null;
  }
  const summarize = (key: string, items: readonly Row[]) => ({
    summary: true as const,
    key,
    count: items.length,
  });

  it('collapses a run of adjacent same-key rows into one summary', () => {
    const rows: Row[] = [
      { id: '1', coll: 'default' },
      { id: '2', coll: 'default' },
      { id: '3', coll: 'default' },
    ];
    const out = collapseBursts(rows, { keyOf: (r) => r.coll, summarize });
    expect(out).toEqual([{ summary: true, key: 'default', count: 3 }]);
  });

  it('leaves a single row (run < minRun) individual', () => {
    const rows: Row[] = [{ id: '1', coll: 'default' }];
    const out = collapseBursts(rows, { keyOf: (r) => r.coll, summarize });
    expect(out).toEqual([{ id: '1', coll: 'default' }]);
  });

  it('passes through rows whose keyOf returns null, never grouping them', () => {
    const rows: Row[] = [
      { id: 'a', coll: null },
      { id: 'b', coll: null },
    ];
    const out = collapseBursts(rows, { keyOf: (r) => r.coll, summarize });
    expect(out).toEqual(rows);
  });

  it('preserves order and only collapses adjacent runs (non-key rows break runs)', () => {
    const rows: Row[] = [
      { id: 'x', coll: 'a' },
      { id: 'y', coll: 'a' },
      { id: 'op', coll: null }, // breaks the run
      { id: 'z', coll: 'a' }, // a new, separate run of length 1
    ];
    const out = collapseBursts(rows, { keyOf: (r) => r.coll, summarize });
    expect(out).toEqual([
      { summary: true, key: 'a', count: 2 },
      { id: 'op', coll: null },
      { id: 'z', coll: 'a' },
    ]);
  });

  it('keeps two different adjacent keys as separate runs', () => {
    const rows: Row[] = [
      { id: '1', coll: 'a' },
      { id: '2', coll: 'a' },
      { id: '3', coll: 'b' },
      { id: '4', coll: 'b' },
    ];
    const out = collapseBursts(rows, { keyOf: (r) => r.coll, summarize });
    expect(out).toEqual([
      { summary: true, key: 'a', count: 2 },
      { summary: true, key: 'b', count: 2 },
    ]);
  });
});
