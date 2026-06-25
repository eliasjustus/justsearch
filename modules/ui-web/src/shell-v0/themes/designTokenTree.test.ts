// @vitest-environment happy-dom

/**
 * 478 §4.E — DesignTokenTree validator + compiler tests.
 */

import { describe, it, expect, vi } from 'vitest';
import {
  validateDesignTokenTree,
  compileTokenTreeToCss,
  fetchAndCompileTokenTree,
  KNOWN_TOKEN_NAMES,
  SEED_TOKEN_NAMES,
  type DesignTokenTree,
} from './designTokenTree.js';

describe('SEED_TOKEN_NAMES (tempdoc 567 §8 #4)', () => {
  it('is the p-*/h-* subset and every member is a known token', () => {
    expect(SEED_TOKEN_NAMES.size).toBeGreaterThan(0);
    for (const name of SEED_TOKEN_NAMES) {
      expect(name.startsWith('p-') || name.startsWith('h-')).toBe(true);
      expect(KNOWN_TOKEN_NAMES.has(name)).toBe(true);
    }
  });

  it('contains the hue + primitive seeds and excludes derived tokens', () => {
    expect(SEED_TOKEN_NAMES.has('h-teal')).toBe(true);
    expect(SEED_TOKEN_NAMES.has('p-glass')).toBe(true);
    // derived/semantic tokens are NOT authorable seeds
    expect(SEED_TOKEN_NAMES.has('surface-1')).toBe(false);
    expect(SEED_TOKEN_NAMES.has('accent-tint')).toBe(false);
    expect(SEED_TOKEN_NAMES.has('text-primary')).toBe(false);
  });
});

describe('validateDesignTokenTree', () => {
  it('accepts a minimal valid tree', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'Test',
      tokens: { 'surface-1': '#3b4252' },
    });
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.tree.id).toBe('core.test');
      expect(r.tree.tokens['surface-1']).toBe('#3b4252');
    }
  });

  it('rejects non-object input', () => {
    const r = validateDesignTokenTree('hello');
    expect(r.ok).toBe(false);
  });

  it('rejects schemaVersion != 1', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 2,
      id: 'core.test',
      displayName: 'X',
      tokens: {},
    });
    expect(r.ok).toBe(false);
  });

  it('rejects malformed id', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'BadId!',
      displayName: 'X',
      tokens: {},
    });
    expect(r.ok).toBe(false);
  });

  it('rejects empty displayName', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: '',
      tokens: {},
    });
    expect(r.ok).toBe(false);
  });

  it('rejects unknown token names', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: { 'not-a-real-token': '#fff' },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors.some((e) => e.includes('not a known token'))).toBe(true);
    }
  });

  it('rejects non-string token values', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: { 'surface-1': 42 },
    });
    expect(r.ok).toBe(false);
  });

  it('rejects token values containing braces (CSS escape attempt)', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: {
        'surface-1': '#fff; } *::before { content: "pwned"; } :root { --x',
      },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors.some((e) => e.toLowerCase().includes('brace'))).toBe(true);
    }
  });

  it('rejects token values containing angle brackets', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: { 'surface-1': '<script>' },
    });
    expect(r.ok).toBe(false);
  });

  it('accepts optional fields when present', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      description: 'A test theme',
      version: '1.0.0',
      author: 'me',
      tokens: { 'surface-1': '#3b4252' },
    });
    expect(r.ok).toBe(true);
  });

  it('KNOWN_TOKEN_NAMES contains common tokens', () => {
    expect(KNOWN_TOKEN_NAMES.has('surface-1')).toBe(true);
    expect(KNOWN_TOKEN_NAMES.has('accent-tint')).toBe(true);
    expect(KNOWN_TOKEN_NAMES.has('text-primary')).toBe(true);
    // Sanity: not just everything
    expect(KNOWN_TOKEN_NAMES.has('arbitrary-name')).toBe(false);
  });
});

describe('compileTokenTreeToCss', () => {
  it('emits :root block with token declarations', () => {
    const tree: DesignTokenTree = {
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: {
        'surface-1': '#3b4252',
        'accent-tint': '#88c0d0',
      },
    };
    const css = compileTokenTreeToCss(tree);
    expect(css).toContain(':root {');
    expect(css).toContain('--surface-1: #3b4252;');
    expect(css).toContain('--accent-tint: #88c0d0;');
    expect(css.endsWith('}')).toBe(true);
  });

  it('includes author + version in header comment when present', () => {
    const tree: DesignTokenTree = {
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      author: 'alice',
      version: '1.0.0',
      tokens: { 'surface-1': '#fff' },
    };
    const css = compileTokenTreeToCss(tree);
    expect(css).toContain('Author: alice');
    expect(css).toContain('Version: 1.0.0');
  });

  it('handles empty tokens map (theme that overrides nothing)', () => {
    const tree: DesignTokenTree = {
      schemaVersion: 1,
      id: 'core.test',
      displayName: 'X',
      tokens: {},
    };
    const css = compileTokenTreeToCss(tree);
    expect(css).toContain(':root {');
    expect(css).toContain('}');
  });
});

describe('fetchAndCompileTokenTree', () => {
  it('returns css + tree on successful fetch + validation', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          schemaVersion: 1,
          id: 'core.test',
          displayName: 'Test',
          tokens: { 'surface-1': '#3b4252' },
        }),
        { status: 200 },
      ),
    );
    const r = await fetchAndCompileTokenTree('/themes/test.json', fetchMock);
    expect(r).not.toBeNull();
    expect(r!.tree.id).toBe('core.test');
    expect(r!.css).toContain('--surface-1: #3b4252');
  });

  it('returns null on network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('boom'));
    const r = await fetchAndCompileTokenTree('/themes/x.json', fetchMock);
    expect(r).toBeNull();
  });

  it('returns null on validation failure', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          schemaVersion: 1,
          id: 'core.test',
          displayName: 'X',
          tokens: { 'unknown-token': '#fff' },
        }),
        { status: 200 },
      ),
    );
    const r = await fetchAndCompileTokenTree('/themes/x.json', fetchMock);
    expect(r).toBeNull();
  });
});

describe('tokensByMode (tempdoc 567 §8 / A3)', () => {
  it('validates per-mode maps (known names + safe values)', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: { 'h-teal': '120' },
      tokensByMode: { light: { 'p-glass': '0, 30, 60' }, dark: { 'p-glass': '20, 20, 20' } },
    });
    expect(r.ok).toBe(true);
  });

  it('rejects an unknown token in a mode map', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: {},
      tokensByMode: { light: { 'nope-token': 'x' } },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/tokensByMode\.light\.nope-token/);
  });

  it('rejects an unsafe value in a mode map', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: {},
      tokensByMode: { dark: { 'p-glass': '} body { evil: 1 }' } },
    });
    expect(r.ok).toBe(false);
  });

  it('rejects an invalid mode key', () => {
    const r = validateDesignTokenTree({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: {},
      tokensByMode: { sepia: { 'p-glass': '1, 2, 3' } },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/not a valid mode/);
  });

  it('compiles :root (shared tokens + dark) and a [data-theme="light"] block', () => {
    const css = compileTokenTreeToCss({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: { 'h-teal': '120' },
      tokensByMode: { dark: { 'p-glass': '20, 20, 20' }, light: { 'p-glass': '0, 30, 60' } },
    });
    expect(css).toMatch(/:root\s*\{[^}]*--h-teal:\s*120/);
    expect(css).toMatch(/:root\s*\{[^}]*--p-glass:\s*20, 20, 20/);
    expect(css).toMatch(/\[data-theme="light"\]\s*\{[^}]*--p-glass:\s*0, 30, 60/);
  });

  it('compiles only :root when no tokensByMode (backward compatible)', () => {
    const css = compileTokenTreeToCss({
      schemaVersion: 1,
      id: 'custom.m',
      displayName: 'M',
      tokens: { 'h-teal': '120' },
    });
    expect(css).toContain(':root {');
    expect(css).not.toContain('[data-theme="light"]');
  });
});
