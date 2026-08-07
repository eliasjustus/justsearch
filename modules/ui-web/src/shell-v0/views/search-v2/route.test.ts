/**
 * The routing laws (tempdoc 818 slice 1) — L1, L2, L10 as executable tests.
 *
 * `route()` is the whole reason the shipped window's affordance/schema desync class cannot recur
 * here: the destination is computed from the visible facts every render, so there is no stored
 * destination to fall out of sync with the draft.
 */
import { describe, it, expect } from 'vitest';
import { RUNGS, applyFlip, route, type RouteContext } from './route.js';

const CTX: RouteContext = { scopePinned: false, schemaAttached: false, runInFlight: false };

describe('818 route()', () => {
  it('L1 — the destination is a pure function of (draft, ctx): same inputs, same outputs', () => {
    const a = route('quarterly renewals', CTX);
    const b = route('quarterly renewals', CTX);
    expect(a).toEqual(b);
    // …and repeated evaluation with an unchanged ctx never drifts.
    for (let i = 0; i < 5; i++) expect(route('quarterly renewals', CTX)).toEqual(a);
  });

  it('L1 — a question-shaped draft routes to ASK; a plain draft routes to SEARCH', () => {
    expect(route('what did we renegotiate?', CTX)).toEqual({ empty: false, primary: 'ask', alt: 'search' });
    expect(route('who signed Northfield', CTX)).toMatchObject({ primary: 'ask' });
    expect(route('renewals ninety days?', CTX)).toMatchObject({ primary: 'ask' });
    expect(route('northfield supplier agreement', CTX)).toEqual({
      empty: false,
      primary: 'search',
      alt: 'ask',
    });
  });

  it('L1 — an attached schema routes to EXTRACT regardless of question shape', () => {
    const withSchema: RouteContext = { ...CTX, schemaAttached: true };
    expect(route('northfield supplier agreement', withSchema)).toMatchObject({ primary: 'extract' });
    expect(route('what are the renewal terms?', withSchema)).toMatchObject({ primary: 'extract' });
  });

  it('L1 — the ⇥ flip is a lens over the derived slots, not a stored destination', () => {
    const r = route('northfield supplier agreement', CTX);
    expect(r.empty).toBe(false);
    if (r.empty) return;
    expect(applyFlip(r, true)).toEqual({ empty: false, primary: r.alt, alt: r.primary });
    // Dropping the lens restores the derived slots exactly — nothing was stored.
    expect(applyFlip(r, false)).toEqual(r);
    expect(applyFlip(applyFlip(r, true), true)).toEqual(r);
  });

  it('L2 — a run in flight claims ONLY the alt slot (STEER); the primary is unaffected', () => {
    const running: RouteContext = { ...CTX, runInFlight: true };
    for (const draft of ['northfield supplier agreement', 'what changed?', 'why did it renew']) {
      const idle = route(draft, CTX);
      const mid = route(draft, running);
      expect(idle.empty).toBe(false);
      expect(mid.empty).toBe(false);
      if (idle.empty || mid.empty) return;
      expect(mid.primary).toBe(idle.primary);
      expect(mid.alt).toBe('steer');
    }
  });

  it('L2 — ASK stays reachable mid-run (a question still routes to ask as the primary)', () => {
    expect(route('what changed?', { ...CTX, runInFlight: true })).toMatchObject({
      primary: 'ask',
      alt: 'steer',
    });
  });

  it('L10 — an empty (or whitespace-only) draft routes nowhere', () => {
    expect(route('', CTX)).toEqual({ empty: true });
    expect(route('   ', CTX)).toEqual({ empty: true });
    expect(route('\n\t ', { ...CTX, schemaAttached: true, runInFlight: true })).toEqual({ empty: true });
  });

  it('the rung register carries a pill label for every rung a route can return', () => {
    for (const rung of ['search', 'ask', 'extract', 'steer'] as const) {
      expect(RUNGS[rung].pill.length).toBeGreaterThan(0);
      expect(RUNGS[rung].label.length).toBeGreaterThan(0);
    }
  });
});
