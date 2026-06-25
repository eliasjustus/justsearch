/**
 * Tests for the binding language (569 surface-composition DSL tier) + region resolution.
 * Confirms the grammar is expressive enough for visibility predicates and safe by
 * construction (no calls/assignment; malformed → fail-closed).
 */
import { describe, expect, it } from 'vitest';
import { evaluateBinding } from './bindingExpr.js';
import { resolveVisibleRegions } from './surfaceComposition.js';

describe('binding expression evaluator', () => {
  it('member access + equality', () => {
    expect(evaluateBinding('data.advanced == true', { data: { advanced: true } })).toBe(true);
    expect(evaluateBinding('data.advanced == true', { data: { advanced: false } })).toBe(false);
  });

  it('numeric comparison', () => {
    expect(evaluateBinding('data.count > 0', { data: { count: 3 } })).toBe(true);
    expect(evaluateBinding('data.count > 0', { data: { count: 0 } })).toBe(false);
  });

  it('boolean logic, precedence, and parentheses', () => {
    expect(
      evaluateBinding('data.a && (data.b || data.c)', { data: { a: true, b: false, c: true } }),
    ).toBe(true);
    expect(evaluateBinding('!data.a', { data: { a: false } })).toBe(true);
  });

  it('string equality', () => {
    expect(evaluateBinding("data.mode == 'advanced'", { data: { mode: 'advanced' } })).toBe(true);
    expect(evaluateBinding("data.mode == 'advanced'", { data: { mode: 'simple' } })).toBe(false);
  });

  it('unknown path resolves to undefined → false', () => {
    expect(evaluateBinding('data.missing', {})).toBe(false);
  });

  it('malformed expression fails closed (false)', () => {
    expect(evaluateBinding('data.a &&', { data: { a: true } })).toBe(false);
    expect(evaluateBinding('=== bad', {})).toBe(false);
  });

  it('cannot call functions or assign (not in the grammar) — fails closed', () => {
    expect(evaluateBinding('alert(1)', {})).toBe(false);
    expect(evaluateBinding('x = 1', {})).toBe(false);
  });
});

describe('resolveVisibleRegions (DSL composition)', () => {
  const layout = {
    regions: [
      { id: 'b', order: 2 },
      { id: 'a', order: 1 },
      { id: 'hidden', order: 0, visibleWhen: 'data.show == true' },
    ],
  };

  it('hides regions whose visibleWhen is false, orders the rest', () => {
    const out = resolveVisibleRegions(layout, { data: { show: false } });
    expect(out.map((r) => r.id)).toEqual(['a', 'b']);
  });

  it('shows a region when its visibleWhen is true', () => {
    const out = resolveVisibleRegions(layout, { data: { show: true } });
    expect(out.map((r) => r.id)).toEqual(['hidden', 'a', 'b']);
  });
});
