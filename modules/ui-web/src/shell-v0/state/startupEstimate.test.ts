/**
 * Tempdoc 601 — formatStartupEstimate: the ONE model-load estimate formatter.
 * Pins the `<0 → unknown` (null) arm and the seconds/minutes rounding shared by the
 * affordance-reason projector and the Brain surface (so the number is not forked).
 */
import { describe, expect, it, vi } from 'vitest';
import { formatStartupEstimate, humanizeSeconds, elapsedSecondsSince } from './startupEstimate.js';

describe('formatStartupEstimate (tempdoc 601)', () => {
  it('returns null for the unknown arm (-1, undefined, null) — no fabricated number', () => {
    expect(formatStartupEstimate(-1)).toBeNull();
    expect(formatStartupEstimate(undefined)).toBeNull();
    expect(formatStartupEstimate(null)).toBeNull();
  });

  it('rounds sub-minute durations to ~Ns (floor of 1s)', () => {
    expect(formatStartupEstimate(11000)).toBe('~11s');
    expect(formatStartupEstimate(10888)).toBe('~11s'); // rounds
    expect(formatStartupEstimate(0)).toBe('~1s'); // Math.max(1, …): 0ms is a real (instant) prior, not unknown
    expect(formatStartupEstimate(400)).toBe('~1s');
    expect(formatStartupEstimate(59000)).toBe('~59s');
  });

  it('formats minute-plus durations as ~Nm / ~Nm Ns', () => {
    expect(formatStartupEstimate(60000)).toBe('~1m');
    expect(formatStartupEstimate(65000)).toBe('~1m 5s');
    expect(formatStartupEstimate(125000)).toBe('~2m 5s');
  });
});

describe('humanizeSeconds (tempdoc 601 §19)', () => {
  it('formats sub-minute as Ns (no prefix), honoring an optional floor', () => {
    expect(humanizeSeconds(0)).toBe('0s'); // default floor 0
    expect(humanizeSeconds(12)).toBe('12s');
    expect(humanizeSeconds(59)).toBe('59s');
    expect(humanizeSeconds(0.4, { floor: 1 })).toBe('1s'); // floor lifts a sub-1 value to 1s
  });

  it('formats minute-plus as Nm / Nm Ns (no prefix)', () => {
    expect(humanizeSeconds(60)).toBe('1m');
    expect(humanizeSeconds(90)).toBe('1m 30s');
    expect(humanizeSeconds(125)).toBe('2m 5s');
  });
});

describe('elapsedSecondsSince (tempdoc 601 §20)', () => {
  it('returns 0 when there is no stamp (null)', () => {
    expect(elapsedSecondsSince(null)).toBe(0);
  });

  it('returns measured whole seconds since the stamp (a count-up)', () => {
    vi.useFakeTimers();
    try {
      const now = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(now);
      expect(elapsedSecondsSince(now - 12_000)).toBe(12);
      expect(elapsedSecondsSince(now - 1_400)).toBe(1); // rounds
      expect(elapsedSecondsSince(now)).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
