// @vitest-environment happy-dom

/**
 * Tempdoc 560 §24 — tokenIntrospection: value sanitization (Fix 1) + value-aware widget (Fix 5).
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  applyTokenPreview,
  isColorLiteral,
  listTokens,
} from './tokenIntrospection.js';
import { KNOWN_TOKEN_NAMES } from './designTokenTree.js';

const STYLE_ID = 'jf-test-token-preview';
const known = () => [...KNOWN_TOKEN_NAMES][0]!;

afterEach(() => document.getElementById(STYLE_ID)?.remove());

describe('isColorLiteral — value-aware widget rule (Fix 5)', () => {
  it('accepts hex + self-contained color functions', () => {
    for (const v of ['#fff', '#1a2b3c', '#1a2b3cff', 'rgb(1, 2, 3)', 'rgba(1,2,3,.5)', 'hsl(200 50% 40%)', 'oklch(0.7 0.1 200)']) {
      expect(isColorLiteral(v)).toBe(true);
    }
  });

  it('rejects channel triplets, var()-refs, and unset (the corruption-prone cases)', () => {
    for (const v of ['87, 60, 40', 'rgb(var(--p-glass))', 'var(--accent)', '', '(unset)', 'red']) {
      expect(isColorLiteral(v)).toBe(false);
    }
  });

  it('rejects values with breakout chars (defense in depth)', () => {
    expect(isColorLiteral('rgb(1,2,3)} html{x')).toBe(false);
  });
});

describe('applyTokenPreview — sanitization (Fix 1)', () => {
  it('injects a scoped @layer style for a known token + safe value', () => {
    applyTokenPreview(new Map([[known(), '#abcdef']]), STYLE_ID);
    const el = document.getElementById(STYLE_ID);
    expect(el?.textContent).toContain('@layer user-theme');
    expect(el?.textContent).toContain(`--${known()}: #abcdef;`);
  });

  it('throws on an unknown token and writes nothing', () => {
    expect(() => applyTokenPreview(new Map([['nope-not-a-token', 'red']]), STYLE_ID)).toThrow(
      /unknown token/,
    );
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('throws on a brace/angle-bracket value and writes nothing (no rule injection)', () => {
    expect(() =>
      applyTokenPreview(new Map([[known(), 'red} html{display:none']]), STYLE_ID),
    ).toThrow(/break out of the :root rule|brace/);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('clears the preview on an empty map', () => {
    applyTokenPreview(new Map([[known(), 'blue']]), STYLE_ID);
    expect(document.getElementById(STYLE_ID)).not.toBeNull();
    applyTokenPreview(new Map(), STYLE_ID);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });
});

describe('listTokens — value-aware widget downgrade (Fix 5)', () => {
  it('returns every known token, and never offers a color picker for an unset value', () => {
    // In the test DOM the custom properties are unset, so isColorLiteral('') is false → every
    // color-category token is downgraded to a text widget (the corruption-safe default).
    const tokens = listTokens();
    expect(tokens.length).toBe(KNOWN_TOKEN_NAMES.size);
    expect(tokens.every((t) => t.widgetType !== 'color')).toBe(true);
  });
});
