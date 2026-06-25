// @vitest-environment happy-dom

/**
 * §32 U1 — autonomy substrate tests.
 */

import { beforeEach, describe, it, expect } from 'vitest';
import {
  getAutonomyLevel,
  setAutonomyLevel,
  agentInvocationDisposition,
  becauseLine,
  listAutonomyLevels,
  subscribeAutonomy,
  type AutonomyLevel,
  __resetAutonomyForTest,
} from './index.js';

beforeEach(() => {
  globalThis.localStorage?.clear();
  __resetAutonomyForTest();
});

describe('§32 U1 — autonomy substrate', () => {
  it('defaults to assist', () => {
    expect(getAutonomyLevel()).toBe('assist');
  });

  it('set/get and persists to localStorage', () => {
    setAutonomyLevel('watch');
    expect(getAutonomyLevel()).toBe('watch');
    expect(
      globalThis.localStorage.getItem('justsearch.autonomy.level.v1'),
    ).toBe('watch');
  });

  it('disposition: watch proposes all; assist proposes only backend ops; auto dispatches all', () => {
    // watch → propose every agent effect, backend op or not.
    expect(agentInvocationDisposition('toast', 'watch')).toBe('propose');
    expect(agentInvocationDisposition('invoke-operation', 'watch')).toBe(
      'propose',
    );
    // assist → propose backend operations; dispatch pure-FE effects.
    expect(agentInvocationDisposition('toast', 'assist')).toBe('dispatch');
    expect(agentInvocationDisposition('navigate', 'assist')).toBe('dispatch');
    expect(agentInvocationDisposition('invoke-operation', 'assist')).toBe(
      'propose',
    );
    // auto → dispatch everything (the backend lattice is the sole gate).
    expect(agentInvocationDisposition('toast', 'auto')).toBe('dispatch');
    expect(agentInvocationDisposition('invoke-operation', 'auto')).toBe(
      'dispatch',
    );
  });

  it('becauseLine (543-fwd #2): HIGH is always confirmed regardless of level', () => {
    for (const level of ['watch', 'assist', 'auto'] as AutonomyLevel[]) {
      expect(becauseLine('HIGH', level)).toBe(
        'HIGH-risk action — always needs your confirmation.',
      );
    }
  });

  it('becauseLine (543-fwd #2): names the dial decision per level × risk', () => {
    expect(becauseLine('LOW', 'watch')).toBe(
      'Watch mode — every action needs your confirmation.',
    );
    expect(becauseLine('MEDIUM', 'watch')).toBe(
      'Watch mode — every action needs your confirmation.',
    );
    expect(becauseLine('LOW', 'assist')).toBe(
      'Assist mode — read-only (LOW) actions run automatically.',
    );
    expect(becauseLine('MEDIUM', 'assist')).toBe(
      'Assist mode — write (MEDIUM) actions need your confirmation.',
    );
    expect(becauseLine('LOW', 'auto')).toBe(
      'Auto mode — LOW-risk actions run automatically.',
    );
    expect(becauseLine('MEDIUM', 'auto')).toBe(
      'Auto mode — MEDIUM-risk actions run automatically.',
    );
  });

  it('becauseLine (561 P-D1): the BACKEND verdict overrides the dial-derived sentence', () => {
    // When the wire carries the authoritative gateBehavior, the explanation names THAT decision —
    // regardless of risk + dial level (the single-authority collapse).
    expect(becauseLine('LOW', 'auto', 'typed_confirm')).toBe(
      'Higher-risk action — needs your typed confirmation.',
    );
    expect(becauseLine('HIGH', 'watch', 'auto')).toBe(
      'The system will run this automatically (low-risk, trusted).',
    );
    expect(becauseLine('MEDIUM', 'assist', 'inline_confirm')).toBe(
      'Needs a quick confirmation before it runs.',
    );
    expect(becauseLine('MEDIUM', 'auto', 'deny')).toBe(
      'Blocked by policy — this action cannot run.',
    );
    // Absent verdict → falls back to the dial-derived sentence (back-compat).
    expect(becauseLine('LOW', 'assist')).toBe(
      'Assist mode — read-only (LOW) actions run automatically.',
    );
  });

  it('lists the three levels', () => {
    expect([...listAutonomyLevels()]).toEqual(['watch', 'assist', 'auto']);
  });

  it('notifies subscribers on change only', () => {
    let n = 0;
    const unsub = subscribeAutonomy(() => {
      n += 1;
    });
    setAutonomyLevel('watch'); // change → notify
    setAutonomyLevel('watch'); // no-op → no notify
    setAutonomyLevel('auto'); // change → notify
    unsub();
    expect(n).toBe(2);
  });
});
