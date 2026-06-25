import { describe, it, expect } from 'vitest';
import { newestFirst } from './eventStreamProjection.js';

describe('newestFirst (tempdoc 571 — shared event-stream ordering)', () => {
  it('returns a newest-first (reversed) view of an arrival-order list', () => {
    expect(newestFirst([1, 2, 3])).toEqual([3, 2, 1]);
  });

  it('does not mutate the input', () => {
    const input = ['a', 'b', 'c'];
    const out = newestFirst(input);
    expect(input).toEqual(['a', 'b', 'c']); // untouched
    expect(out).toEqual(['c', 'b', 'a']);
    expect(out).not.toBe(input);
  });

  it('handles the empty list', () => {
    expect(newestFirst([])).toEqual([]);
  });
});
