// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { formatRelative } from './relativeTime.js';

const NOW = 1_700_000_000_000;
const SEC = 1000;
const MIN = 60 * SEC;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe('formatRelative', () => {
  it('returns "just now" for sub-minute deltas', () => {
    expect(formatRelative(NOW, NOW)).toBe('just now');
    expect(formatRelative(NOW - 30 * SEC, NOW)).toBe('just now');
    expect(formatRelative(NOW - 59 * SEC, NOW)).toBe('just now');
  });

  it('returns "Nm ago" for minute-scale deltas', () => {
    expect(formatRelative(NOW - 1 * MIN, NOW)).toBe('1m ago');
    expect(formatRelative(NOW - 17 * MIN, NOW)).toBe('17m ago');
    expect(formatRelative(NOW - 59 * MIN, NOW)).toBe('59m ago');
  });

  it('returns "Nh ago" for hour-scale deltas', () => {
    expect(formatRelative(NOW - 1 * HOUR, NOW)).toBe('1h ago');
    expect(formatRelative(NOW - 5 * HOUR, NOW)).toBe('5h ago');
    expect(formatRelative(NOW - 23 * HOUR, NOW)).toBe('23h ago');
  });

  it('returns "Nd ago" for day-scale deltas', () => {
    expect(formatRelative(NOW - 1 * DAY, NOW)).toBe('1d ago');
    expect(formatRelative(NOW - 30 * DAY, NOW)).toBe('30d ago');
  });

  it('renders future timestamps as "just now" defensively', () => {
    expect(formatRelative(NOW + 5 * MIN, NOW)).toBe('just now');
  });

  it('boundary: exactly 60s -> "1m ago"', () => {
    expect(formatRelative(NOW - 60 * SEC, NOW)).toBe('1m ago');
  });
});
