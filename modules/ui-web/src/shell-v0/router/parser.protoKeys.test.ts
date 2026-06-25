// @vitest-environment happy-dom

/**
 * Regression test for F17 (see docs/tempdocs/547): the query-param parsers
 * (`parseQueryParamsAsState` / `parseQueryParamsAsArgs`) created the output
 * bag as `{}` and used `if (key in out)` to detect a repeated key. But
 * `key in {}` is TRUE for inherited Object.prototype members — so a param
 * named `toString` / `constructor` / `valueOf` / etc. took the repeated-key
 * branch on its first occurrence and was wrongly wrapped as
 * `[<inherited member>, value]`, and `?__proto__=x` mutated the bag's
 * prototype. parseUrl feeds on LLM responses + user input, so these keys are
 * reachable. Fixed with an own-property check (Object.hasOwnProperty.call).
 */

import { describe, it, expect } from 'vitest';
import { parseUrl } from './parser.js';

describe('parser — prototype-colliding query keys (F17)', () => {
  it('treats a key named like an Object.prototype member as a normal value', () => {
    const addr = parseUrl('justsearch://surface/core.search?toString=hi');
    expect(addr?.kind).toBe('navigate');
    const state = (addr as { state: Record<string, unknown> }).state;
    // Pre-fix: `'toString' in {}` is true (inherited), so 'hi' was wrongly
    // wrapped as [Function, 'hi'] via the repeated-key branch.
    expect(state['toString']).toBe('hi');
  });

  it('does not corrupt the bag prototype via a __proto__ key', () => {
    const addr = parseUrl('justsearch://surface/core.search?__proto__=evil');
    const state = (addr as { state: Record<string, unknown> }).state;
    // Pre-fix did `out['__proto__'] = [...]`, mutating the prototype.
    expect(Array.isArray(Object.getPrototypeOf(state))).toBe(false);
  });

  it('still groups genuinely repeated keys into an array (control)', () => {
    const addr = parseUrl('justsearch://surface/core.search?tag=a&tag=b');
    const state = (addr as { state: Record<string, unknown> }).state;
    expect(state['tag']).toEqual(['a', 'b']);
  });
});
