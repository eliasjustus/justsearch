// @vitest-environment happy-dom

/**
 * 569 Fix 4 — proof that the interaction statechart drives REAL, journaled effects through the
 * kernel dispatcher (not a stub logger). With the DEFAULT dispatcher (`applyEffect`), sending an
 * event must record the transition's effect in the Effect Journal — i.e. Move 8 is wired to the
 * real effect substrate (undo/replay/audit for free), not an isolated capability.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { createMachine, type InteractionStatechart } from './index.js';
import { listJournal, __resetJournalForTest } from '../effects/index.js';

const TOAST_CHART: InteractionStatechart = {
  id: 'test.toast',
  initial: 'idle',
  states: [
    {
      id: 'idle',
      transitions: [
        { on: 'PING', target: 'idle', effects: [{ kind: 'toast', message: 'pong', severity: 'info' }] },
      ],
    },
  ],
};

beforeEach(() => __resetJournalForTest());

describe('InteractionMachine with the real applyEffect dispatcher', () => {
  it('journals the transition effect (drives the real effect substrate)', () => {
    const m = createMachine(TOAST_CHART); // default dispatcher = applyEffect
    expect(listJournal().length).toBe(0);
    m.send('PING');
    const journal = listJournal();
    expect(journal.some((e) => e.effect.kind === 'toast')).toBe(true);
  });

  it('a guard that fails dispatches nothing (no journal entry)', () => {
    const guarded: InteractionStatechart = {
      id: 'test.guarded',
      initial: 'idle',
      states: [
        {
          id: 'idle',
          transitions: [
            { on: 'GO', target: 'idle', guard: 'ok == true', effects: [{ kind: 'toast', message: 'x' }] },
          ],
        },
      ],
    };
    const m = createMachine(guarded);
    m.send('GO', { ok: false });
    expect(listJournal().length).toBe(0);
  });
});
