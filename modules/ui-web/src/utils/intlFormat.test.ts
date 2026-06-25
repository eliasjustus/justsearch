import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { pluralize, formatBytes, formatRelativeTime } from './intlFormat';

// All tests use explicit 'en' locale to avoid environment-dependent results.

describe('pluralize', () => {
  it('returns singular form when count is 1', () => {
    expect(pluralize(1, 'file', undefined, 'en')).toBe('file');
    expect(pluralize(1, 'result', undefined, 'en')).toBe('result');
  });

  it('returns default plural (+ s) when count is not 1', () => {
    expect(pluralize(0, 'file', undefined, 'en')).toBe('files');
    expect(pluralize(2, 'file', undefined, 'en')).toBe('files');
    expect(pluralize(5, 'result', undefined, 'en')).toBe('results');
    expect(pluralize(100, 'document', undefined, 'en')).toBe('documents');
  });

  it('returns explicit plural for irregular nouns', () => {
    expect(pluralize(0, 'match', 'matches', 'en')).toBe('matches');
    expect(pluralize(1, 'match', 'matches', 'en')).toBe('match');
    expect(pluralize(5, 'match', 'matches', 'en')).toBe('matches');
  });
});

describe('formatBytes', () => {
  it('returns "0 B" for zero', () => {
    expect(formatBytes(0, 'en')).toBe('0 B');
  });

  it('formats bytes (< 1 KB) without Intl unit', () => {
    expect(formatBytes(512, 'en')).toBe('512 B');
    expect(formatBytes(1, 'en')).toBe('1 B');
  });

  it('formats kilobytes with Intl', () => {
    const result = formatBytes(1536, 'en');
    // 1536 / 1024 = 1.5 → "1.5 kB" (Intl short unit for kilobyte)
    expect(result).toMatch(/1\.5\s*kB/);
  });

  it('formats megabytes with Intl', () => {
    const result = formatBytes(1.5 * 1024 * 1024, 'en');
    expect(result).toMatch(/1\.5\s*MB/);
  });

  it('formats gigabytes with Intl', () => {
    const result = formatBytes(2.3 * 1024 * 1024 * 1024, 'en');
    expect(result).toMatch(/2\.3\s*GB/);
  });

  it('formats terabytes with Intl', () => {
    const result = formatBytes(1.1 * 1024 * 1024 * 1024 * 1024, 'en');
    expect(result).toMatch(/1\.1\s*TB/);
  });

  it('drops decimals for values >= 10', () => {
    const result = formatBytes(15 * 1024 * 1024, 'en');
    // 15 MB, no decimal
    expect(result).toMatch(/15\s*MB/);
    expect(result).not.toContain('.');
  });

  it('handles negative values with Unicode minus', () => {
    const result = formatBytes(-1.5 * 1024 * 1024, 'en');
    expect(result).toContain('\u2212');
    expect(result).toMatch(/MB/);
  });

  it('handles negative bytes', () => {
    expect(formatBytes(-512, 'en')).toBe('\u2212512 B');
  });
});

describe('formatRelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-02-12T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const now = new Date('2026-02-12T12:00:00Z').getTime();

  it('returns undefined for invalid input', () => {
    expect(formatRelativeTime(0, 'en')).toBeUndefined();
    expect(formatRelativeTime(-1, 'en')).toBeUndefined();
    expect(formatRelativeTime(NaN, 'en')).toBeUndefined();
    expect(formatRelativeTime(Infinity, 'en')).toBeUndefined();
  });

  it('returns undefined for future timestamps', () => {
    expect(formatRelativeTime(now + 60_000, 'en')).toBeUndefined();
  });

  it('formats seconds ago', () => {
    const result = formatRelativeTime(now - 30_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/30/);
    expect(result).toMatch(/sec|s/i);
  });

  it('formats minutes ago', () => {
    const result = formatRelativeTime(now - 5 * 60_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/5/);
    expect(result).toMatch(/min|m/i);
  });

  it('formats hours ago', () => {
    const result = formatRelativeTime(now - 3 * 3600_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/3/);
    expect(result).toMatch(/hr|h/i);
  });

  it('formats days ago', () => {
    const result = formatRelativeTime(now - 7 * 86400_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/7/);
    expect(result).toMatch(/day|d/i);
  });

  it('formats months ago', () => {
    const result = formatRelativeTime(now - 60 * 86400_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/mo/i);
  });

  it('formats years ago', () => {
    const result = formatRelativeTime(now - 400 * 86400_000, 'en');
    expect(result).toBeDefined();
    expect(result).toMatch(/yr|y/i);
  });
});
