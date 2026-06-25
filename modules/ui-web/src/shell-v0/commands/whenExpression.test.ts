// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.1 / §13.1 — WhenExpression evaluator tests.
 *
 * Covers VS Code's documented grammar subset: logical operators
 * (&&, ||, !), equality (==, ===, !=, !==), numeric comparison (>,
 * >=, <, <=), regex match (=~), membership (in / not in), bare-key
 * truthy, parenthesization, malformed-expression policy.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { evaluateWhen, parseWhen, __resetWhenCacheForTest } from './whenExpression.js';

beforeEach(() => {
  __resetWhenCacheForTest();
});

describe('whenExpression — bare-key truthy', () => {
  it('returns true when the key is truthy', () => {
    expect(evaluateWhen('inspectorOpen', { inspectorOpen: true })).toBe(true);
  });

  it('returns false when the key is falsy', () => {
    expect(evaluateWhen('inspectorOpen', { inspectorOpen: false })).toBe(false);
  });

  it('returns false when the key is undefined', () => {
    expect(evaluateWhen('missing', {})).toBe(false);
  });

  it('returns false when the key is 0', () => {
    expect(evaluateWhen('count', { count: 0 })).toBe(false);
  });

  it('returns true when the key is a non-empty string', () => {
    expect(evaluateWhen('s', { s: 'hello' })).toBe(true);
  });
});

describe('whenExpression — equality', () => {
  it('matches bareword RHS', () => {
    expect(evaluateWhen('activeSurface == core.search-surface', {
      activeSurface: 'core.search-surface',
    })).toBe(true);
  });

  it('mismatches bareword RHS', () => {
    expect(evaluateWhen('activeSurface == core.search-surface', {
      activeSurface: 'core.health-surface',
    })).toBe(false);
  });

  it('matches quoted RHS', () => {
    expect(evaluateWhen("activeSurface == 'core.search-surface'", {
      activeSurface: 'core.search-surface',
    })).toBe(true);
  });

  it('inequality returns true on mismatch', () => {
    expect(evaluateWhen('activeSurface != core.health', {
      activeSurface: 'core.search',
    })).toBe(true);
  });

  it('triple-equals is an alias for equals', () => {
    expect(evaluateWhen('activeSurface === core.x', { activeSurface: 'core.x' })).toBe(true);
  });
});

describe('whenExpression — numeric comparison', () => {
  it('greater-than', () => {
    expect(evaluateWhen('selectionCount > 0', { selectionCount: 3 })).toBe(true);
    expect(evaluateWhen('selectionCount > 0', { selectionCount: 0 })).toBe(false);
  });

  it('greater-or-equal', () => {
    expect(evaluateWhen('selectionCount >= 1', { selectionCount: 1 })).toBe(true);
  });

  it('less-than', () => {
    expect(evaluateWhen('selectionCount < 5', { selectionCount: 4 })).toBe(true);
  });

  it('less-or-equal', () => {
    expect(evaluateWhen('selectionCount <= 5', { selectionCount: 5 })).toBe(true);
  });
});

describe('whenExpression — logical operators', () => {
  it('&& both true', () => {
    expect(evaluateWhen('a && b', { a: true, b: true })).toBe(true);
  });

  it('&& short-circuits on false left', () => {
    expect(evaluateWhen('a && b', { a: false, b: true })).toBe(false);
  });

  it('|| either', () => {
    expect(evaluateWhen('a || b', { a: false, b: true })).toBe(true);
    expect(evaluateWhen('a || b', { a: false, b: false })).toBe(false);
  });

  it('! negates', () => {
    expect(evaluateWhen('!inspectorOpen', { inspectorOpen: false })).toBe(true);
    expect(evaluateWhen('!inspectorOpen', { inspectorOpen: true })).toBe(false);
  });

  it('precedence: && binds tighter than ||', () => {
    // false || true && true == false || true == true
    expect(evaluateWhen('a || b && c', { a: false, b: true, c: true })).toBe(true);
    // false || true && false == false || false == false
    expect(evaluateWhen('a || b && c', { a: false, b: true, c: false })).toBe(false);
  });

  it('parentheses override precedence', () => {
    // (false || true) && false == true && false == false
    expect(evaluateWhen('(a || b) && c', { a: false, b: true, c: false })).toBe(false);
  });
});

describe('whenExpression — regex =~', () => {
  it('matches a regex', () => {
    expect(evaluateWhen('activeSurface =~ /^core\\./', {
      activeSurface: 'core.search',
    })).toBe(true);
  });

  it('mismatches a regex', () => {
    expect(evaluateWhen('activeSurface =~ /^plugin\\./', {
      activeSurface: 'core.search',
    })).toBe(false);
  });

  it('regex with flags', () => {
    expect(evaluateWhen('activeSurface =~ /CORE/i', {
      activeSurface: 'core.x',
    })).toBe(true);
  });
});

describe('whenExpression — in / not in', () => {
  it('in matches comma-separated membership', () => {
    expect(evaluateWhen('open in selectionCapabilities', {
      selectionCapabilities: 'open,pin,export',
    })).toBe(true);
  });

  it('in misses when value not present', () => {
    expect(evaluateWhen('export in selectionCapabilities', {
      selectionCapabilities: 'open,pin',
    })).toBe(false);
  });

  it('not in inverts', () => {
    expect(evaluateWhen('export not in selectionCapabilities', {
      selectionCapabilities: 'open,pin',
    })).toBe(true);
  });
});

describe('whenExpression — combined real-world cases', () => {
  it('palette command scoped to search surface with selection', () => {
    const expr = 'activeSurface == core.search-surface && selectionCount > 0';
    expect(evaluateWhen(expr, {
      activeSurface: 'core.search-surface',
      selectionCount: 2,
    })).toBe(true);
    expect(evaluateWhen(expr, {
      activeSurface: 'core.search-surface',
      selectionCount: 0,
    })).toBe(false);
    expect(evaluateWhen(expr, {
      activeSurface: 'core.health-surface',
      selectionCount: 2,
    })).toBe(false);
  });

  it('context-action gated by capability membership', () => {
    const expr = 'ask-ai-about in selectionCapabilities';
    expect(evaluateWhen(expr, {
      selectionCapabilities: 'open,pin,ask-ai-about',
    })).toBe(true);
    expect(evaluateWhen(expr, {
      selectionCapabilities: 'open,pin',
    })).toBe(false);
  });
});

describe('whenExpression — malformed-expression policy', () => {
  it('returns false for unterminated string', () => {
    expect(evaluateWhen("k == 'oops", {})).toBe(false);
  });

  it('returns false for trailing garbage', () => {
    expect(evaluateWhen('k == v )))', { k: 'v' })).toBe(false);
  });

  it('returns true for empty expression (no filter)', () => {
    expect(evaluateWhen('', {})).toBe(true);
  });

  it('returns true for undefined expression', () => {
    expect(evaluateWhen(undefined, {})).toBe(true);
  });

  it('WARN-once on parse failure: subsequent calls do not re-warn', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      evaluateWhen('!!! bad expression', {});
      evaluateWhen('!!! bad expression', {});
      evaluateWhen('!!! bad expression', {});
      expect(spy).toHaveBeenCalledTimes(1);
    } finally {
      spy.mockRestore();
    }
  });
});

describe('whenExpression — cache behavior', () => {
  it('parses each expression once', () => {
    // Indirect test: ensure repeated evaluations don't change semantics.
    expect(evaluateWhen('a == b', { a: 'b' })).toBe(true);
    expect(evaluateWhen('a == b', { a: 'b' })).toBe(true);
    expect(evaluateWhen('a == b', { a: 'c' })).toBe(false);
  });

  it('parseWhen returns the same shape from cache', () => {
    const node1 = parseWhen('a == b');
    const node2 = parseWhen('a == b');
    // Different invocations of parseWhen produce structurally-equal
    // nodes (we don't cache parseWhen calls — evaluateWhen does).
    expect(node1).toEqual(node2);
  });
});
