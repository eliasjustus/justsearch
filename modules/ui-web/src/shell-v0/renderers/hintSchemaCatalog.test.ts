/**
 * 569 §19 Phase 6 — the hint→starter-node catalog (the editor palette's source).
 *
 * The catalog is the honesty seam: the editor can only offer elements that BOTH have a starter node
 * here AND are registered/loadable. These tests pin the two invariants the editor relies on: every
 * starter node carries its own `x-ui-renderer` hint (so the dispatcher's tester routes it), and
 * `freshKeyForHint` never collides with an already-used property key.
 */
import { describe, it, expect } from 'vitest';
import {
  listAuthorableHints,
  starterNodeForHint,
  isAuthorableHint,
  freshKeyForHint,
} from './hintSchemaCatalog.js';

describe('hintSchemaCatalog (569 §19 Phase 6)', () => {
  it('lists at least the Settings-composing primitives', () => {
    const hints = listAuthorableHints();
    expect(hints).toContain('option-button-group');
    expect(hints).toContain('toggle-switch');
    expect(hints.length).toBeGreaterThanOrEqual(4);
  });

  it("every starter node's schema property carries its own x-ui-renderer hint", () => {
    for (const hint of listAuthorableHints()) {
      const node = starterNodeForHint(hint);
      expect(node, hint).toBeTruthy();
      expect(node!.schemaProperty['x-ui-renderer'], hint).toBe(hint);
      // …and the uischema Control scopes to the property key it is given.
      const ctl = node!.uischemaControl('myKey') as { type: string; scope: string };
      expect(ctl.type).toBe('Control');
      expect(ctl.scope).toBe('#/properties/myKey');
    }
  });

  it('isAuthorableHint is true for catalog members, false for non-members', () => {
    expect(isAuthorableHint('toggle-switch')).toBe(true);
    expect(isAuthorableHint('search-results')).toBe(false); // surface-coupled, not a primitive
    expect(isAuthorableHint('alpha')).toBe(false); // a test hint
  });

  it('freshKeyForHint avoids collisions with used keys', () => {
    expect(freshKeyForHint('toggle-switch', new Set())).toBe('toggle_switch');
    expect(freshKeyForHint('toggle-switch', new Set(['toggle_switch']))).toBe('toggle_switch_2');
    expect(
      freshKeyForHint('toggle-switch', new Set(['toggle_switch', 'toggle_switch_2'])),
    ).toBe('toggle_switch_3');
  });
});
