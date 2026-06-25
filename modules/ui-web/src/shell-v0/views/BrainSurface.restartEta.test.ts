/**
 * Tempdoc 518 Appendix F W3.1 — restart-ETA badge unit tests.
 *
 * Covers the four shape branches of formatRestartEtaSub:
 *  - no data (undefined/null/-1) → generic fallback
 *  - seconds-only → "~Ns"
 *  - whole minutes → "~Nm"
 *  - mixed → "~Nm Ms"
 */

import { describe, expect, it, vi } from 'vitest';
import { formatRestartEtaSub } from './BrainSurface';

describe('formatRestartEtaSub', () => {
  it('falls back to generic copy when no prior duration', () => {
    expect(formatRestartEtaSub(undefined)).toBe('AI is initializing.');
    expect(formatRestartEtaSub(null)).toBe('AI is initializing.');
    expect(formatRestartEtaSub(-1)).toBe('AI is initializing.');
  });

  it('reports seconds when under 60s', () => {
    expect(formatRestartEtaSub(15_000)).toBe('AI is initializing. Usually takes ~15s.');
    expect(formatRestartEtaSub(500)).toBe('AI is initializing. Usually takes ~1s.'); // clamps to >=1s
    expect(formatRestartEtaSub(59_400)).toBe('AI is initializing. Usually takes ~59s.'); // rounds down, still <60s
  });

  it('rolls into minutes at the 60s boundary', () => {
    expect(formatRestartEtaSub(59_900)).toBe('AI is initializing. Usually takes ~1m.'); // rounds to 60s, formatted as 1m
  });

  it('reports whole minutes when duration is a multiple of 60s', () => {
    expect(formatRestartEtaSub(120_000)).toBe('AI is initializing. Usually takes ~2m.');
    expect(formatRestartEtaSub(180_000)).toBe('AI is initializing. Usually takes ~3m.');
  });

  it('reports mixed minutes + seconds', () => {
    expect(formatRestartEtaSub(75_000)).toBe('AI is initializing. Usually takes ~1m 15s.');
    expect(formatRestartEtaSub(135_000)).toBe('AI is initializing. Usually takes ~2m 15s.');
  });

  // Tempdoc 601 §20 — with a live load-start stamp, show the MEASURED elapsed + the typical (the §18
  // "show both" mapping on BrainSurface). A count-up, never a countdown.
  describe('live elapsed (601 §20)', () => {
    it('shows elapsed + typical once past the >2s gate', () => {
      vi.useFakeTimers();
      try {
        const now = new Date('2026-01-01T00:00:00Z').getTime();
        vi.setSystemTime(now);
        expect(formatRestartEtaSub(6_000, now - 12_000)).toBe('AI is initializing — 12s (usually ~6s)');
        expect(formatRestartEtaSub(6_000, now - 90_000)).toBe('AI is initializing — 1m 30s (usually ~6s)');
      } finally {
        vi.useRealTimers();
      }
    });

    it('shows elapsed only when there is no prior duration (unknown arm — no fabricated typical)', () => {
      vi.useFakeTimers();
      try {
        const now = new Date('2026-01-01T00:00:00Z').getTime();
        vi.setSystemTime(now);
        expect(formatRestartEtaSub(-1, now - 12_000)).toBe('AI is initializing — 12s');
        expect(formatRestartEtaSub(undefined, now - 12_000)).toBe('AI is initializing — 12s');
      } finally {
        vi.useRealTimers();
      }
    });

    it('stays on the static copy below the >2s gate (so existing call sites are unchanged)', () => {
      vi.useFakeTimers();
      try {
        const now = new Date('2026-01-01T00:00:00Z').getTime();
        vi.setSystemTime(now);
        expect(formatRestartEtaSub(6_000, now - 1_000)).toBe('AI is initializing. Usually takes ~6s.');
        expect(formatRestartEtaSub(6_000, null)).toBe('AI is initializing. Usually takes ~6s.');
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
