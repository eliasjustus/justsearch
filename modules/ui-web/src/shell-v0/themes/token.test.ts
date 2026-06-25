/**
 * token() accessor + generated TokenName vocabulary (tempdoc 557 §2.C).
 */
import { describe, it, expect } from 'vitest';
import { token } from './token.js';
import { TOKEN_NAMES } from './token-names.generated.js';

describe('token() accessor', () => {
  it('returns a bare var() reference when no fallback is given', () => {
    expect(token('--accent-tint')).toBe('var(--accent-tint)');
  });
  it('includes the fallback at a genuine boundary', () => {
    expect(token('--accent-tint', '#14b8a6')).toBe('var(--accent-tint, #14b8a6)');
  });
});

describe('generated TokenName vocabulary', () => {
  it('is non-empty and sorted (codegen invariant)', () => {
    expect(TOKEN_NAMES.length).toBeGreaterThan(50);
    const sorted = [...TOKEN_NAMES].sort();
    expect(TOKEN_NAMES).toEqual(sorted);
  });
  it('contains the core semantic tokens and every name is `--`-prefixed', () => {
    expect(TOKEN_NAMES).toContain('--accent-tint');
    expect(TOKEN_NAMES).toContain('--surface-1');
    expect(TOKEN_NAMES).toContain('--text-primary');
    expect(TOKEN_NAMES.every((n) => n.startsWith('--'))).toBe(true);
  });
});
