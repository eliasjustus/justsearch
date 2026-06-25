// @vitest-environment node
/**
 * format — the one shared value formatter (594 §17.2). Pins the canonical zero-case (`'0 B'`) that the
 * three previously-divergent `formatBytes` had drifted on (`'0'` vs `'0 B'`).
 */
import { describe, it, expect } from 'vitest';
import { formatBytes, formatCount } from './format';

describe('formatBytes', () => {
  it('zero / absent → the single canonical "0 B"', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(null)).toBe('0 B');
    expect(formatBytes(undefined)).toBe('0 B');
  });
  it('scales B / KB / MB / GB', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(53_477_376)).toBe('51.0 MB');
    expect(formatBytes(8 * 1024 * 1024 * 1024)).toBe('8.00 GB');
  });
});

describe('formatCount', () => {
  it('locale-groups integers', () => {
    expect(formatCount(12_340)).toBe(new Intl.NumberFormat().format(12340));
  });
});
