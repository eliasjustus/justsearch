/**
 * 569 Move 8 — the interaction-statechart engine.
 */
import { describe, it, expect } from 'vitest';
import type { Effect } from '../effect.js';
import {
  validateStatechart,
  evaluateTransition,
  createMachine,
  type InteractionStatechart,
} from './index.js';

const CONFIRM_DELETE: InteractionStatechart = {
  id: 'confirm.delete',
  initial: 'idle',
  states: [
    { id: 'idle', transitions: [{ on: 'REQUEST', target: 'confirming' }] },
    {
      id: 'confirming',
      transitions: [
        {
          on: 'CONFIRM',
          target: 'done',
          guard: 'typed == true',
          effects: [
            { kind: 'invoke-operation', operationId: 'data.delete-all' },
            { kind: 'toast', message: 'Deleted', severity: 'success' },
          ],
        },
        { on: 'CANCEL', target: 'idle' },
      ],
    },
    { id: 'done', transitions: [] },
  ],
};

describe('validateStatechart', () => {
  it('accepts a well-formed statechart over the authorable vocabulary', () => {
    const res = validateStatechart(CONFIRM_DELETE);
    expect(res.ok).toBe(true);
  });

  it('569 §14 — accepts the new presentation + search intent effects as authorable', () => {
    const res = validateStatechart({
      id: 'appearance-flow',
      initial: 'idle',
      states: [
        {
          id: 'idle',
          transitions: [
            { on: 'PICK_DARK', target: 'idle', effects: [{ kind: 'set-appearance', theme: 'dark' }] },
            { on: 'MODE', target: 'idle', effects: [{ kind: 'set-ui-mode', mode: 'advanced' }] },
            {
              on: 'SKIN',
              target: 'idle',
              effects: [{ kind: 'apply-presentation', presentationId: 'builtin.settings' }],
            },
            {
              on: 'SAVE',
              target: 'idle',
              effects: [{ kind: 'save-settings', settings: { ui: { theme: 'dark' } } }],
            },
            { on: 'QUERY', target: 'idle', effects: [{ kind: 'set-search-query', query: 'x' }] },
            { on: 'FILTER', target: 'idle', effects: [{ kind: 'set-search-filter', fromMs: 1 }] },
          ],
        },
      ],
    });
    expect(res.ok).toBe(true);
  });

  it('rejects a transition with a code/handler field (unrepresentable behavior)', () => {
    const res = validateStatechart({
      id: 'x',
      initial: 'a',
      states: [{ id: 'a', transitions: [{ on: 'GO', target: 'a', handler: '() => evil()' }] }],
    });
    expect(res.ok).toBe(false);
    expect((res as { errors: readonly string[] }).errors.join(' ')).toContain('no slot for it');
  });

  it('rejects a non-authorable effect kind (kernel-only / unknown)', () => {
    const res = validateStatechart({
      id: 'x',
      initial: 'a',
      states: [
        {
          id: 'a',
          transitions: [
            { on: 'GO', effects: [{ kind: 'data-result', operationId: 'o', resultKey: 'k', result: 1 }] },
          ],
        },
      ],
    });
    expect(res.ok).toBe(false);
    expect((res as { errors: readonly string[] }).errors.join(' ')).toContain('not an AUTHORABLE effect');
  });

  it('rejects an initial / target that is not a declared state', () => {
    expect(validateStatechart({ id: 'x', initial: 'missing', states: [{ id: 'a' }] }).ok).toBe(false);
    expect(
      validateStatechart({
        id: 'x',
        initial: 'a',
        states: [{ id: 'a', transitions: [{ on: 'GO', target: 'nowhere' }] }],
      }).ok,
    ).toBe(false);
  });
});

describe('evaluateTransition', () => {
  it('takes the matching transition; returns null when none match', () => {
    expect(evaluateTransition(CONFIRM_DELETE, 'idle', 'REQUEST')?.target).toBe('confirming');
    expect(evaluateTransition(CONFIRM_DELETE, 'idle', 'UNKNOWN')).toBeNull();
  });

  it('honours the guard expression', () => {
    expect(evaluateTransition(CONFIRM_DELETE, 'confirming', 'CONFIRM', { typed: false })).toBeNull();
    const ok = evaluateTransition(CONFIRM_DELETE, 'confirming', 'CONFIRM', { typed: true });
    expect(ok?.target).toBe('done');
    expect(ok?.effects).toHaveLength(2);
  });
});

describe('InteractionMachine', () => {
  it('advances state and dispatches the transition effects (in order)', () => {
    const fired: Effect[] = [];
    const m = createMachine(CONFIRM_DELETE, (e) => fired.push(e));
    expect(m.state).toBe('idle');

    expect(m.send('REQUEST')).toEqual([]); // no effects on this edge
    expect(m.state).toBe('confirming');

    expect(m.send('CONFIRM', { typed: false })).toEqual([]); // guard fails → no-op
    expect(m.state).toBe('confirming');

    const fx = m.send('CONFIRM', { typed: true });
    expect(m.state).toBe('done');
    expect(fx).toHaveLength(2);
    expect(fired.map((e) => e.kind)).toEqual(['invoke-operation', 'toast']);
  });

  it('notifies subscribers on state change (immediate + each transition)', () => {
    const seen: string[] = [];
    const m = createMachine(CONFIRM_DELETE, () => {});
    const unsub = m.subscribe((s) => seen.push(s));
    m.send('REQUEST');
    m.send('CANCEL');
    unsub();
    m.send('REQUEST'); // not observed after unsub
    expect(seen).toEqual(['idle', 'confirming', 'idle']);
  });
});
