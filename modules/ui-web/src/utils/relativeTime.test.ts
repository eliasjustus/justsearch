import { describe, it, expect } from 'vitest';
import { formatRelativeIso } from './relativeTime';

describe('formatRelativeIso', () => {
  const now = new Date('2026-05-07T12:00:00.000Z');

  it('returns empty string for empty / invalid input', () => {
    expect(formatRelativeIso('', now)).toBe('');
    expect(formatRelativeIso('not-a-date', now)).toBe('');
  });

  it('< 30s → "just now"', () => {
    expect(formatRelativeIso('2026-05-07T11:59:50.000Z', now)).toBe('just now');
  });

  it('1 minute ago bucket', () => {
    const out = formatRelativeIso('2026-05-07T11:59:00.000Z', now);
    // Intl.RelativeTimeFormat may produce "1 minute ago" or locale-specific; just check basic shape
    expect(out).toMatch(/minute|min/i);
  });

  it('hour bucket', () => {
    const out = formatRelativeIso('2026-05-07T07:00:00.000Z', now);
    expect(out).toMatch(/hour|hr/i);
  });

  it('day bucket', () => {
    const out = formatRelativeIso('2026-05-04T12:00:00.000Z', now);
    expect(out).toMatch(/day|yesterday/i);
  });

  it('>=7d falls back to ISO date', () => {
    expect(formatRelativeIso('2026-04-20T12:00:00.000Z', now)).toBe('2026-04-20');
  });

  it('future timestamp under 30s renders as "just now"', () => {
    expect(formatRelativeIso('2026-05-07T12:00:10.000Z', now)).toBe('just now');
  });
});
