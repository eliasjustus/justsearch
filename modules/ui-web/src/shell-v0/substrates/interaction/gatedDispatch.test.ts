// @vitest-environment happy-dom

/**
 * 569 Fix B — the statechart's effects pass the 550 trust seam (Autonomy Dial) just like
 * invokeAndApply: user-origin dispatches + journals; an agent-origin effect the dial wants reviewed
 * is PROPOSED (queued), not fired — so a statechart cannot escalate privilege.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { createGatedEffectDispatcher } from './gatedDispatch.js';
import { createMachine, type InteractionStatechart } from './index.js';
import { listJournal, __resetJournalForTest } from '../effects/index.js';
import { listPending, __resetPendingForTest } from '../pending-effects/index.js';
import { setAutonomyLevel } from '../autonomy/index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

const CHART: InteractionStatechart = {
  id: 'test.gated',
  initial: 'idle',
  states: [
    {
      id: 'idle',
      transitions: [
        {
          on: 'GO',
          target: 'idle',
          effects: [{ kind: 'invoke-operation', operationId: 'core.thing' }],
        },
      ],
    },
  ],
};

beforeEach(() => {
  __resetJournalForTest();
  __resetPendingForTest();
  setAutonomyLevel('assist'); // assist proposes backend invoke-operation for agents
});

describe('createGatedEffectDispatcher', () => {
  it('user-origin: dispatches + journals (no proposal)', () => {
    const m = createMachine(CHART, createGatedEffectDispatcher(CORE_PROVENANCE, 'user'));
    m.send('GO');
    expect(listJournal().some((e) => e.effect.kind === 'invoke-operation')).toBe(true);
    expect(listPending().length).toBe(0);
  });

  it('agent-origin: a backend op is PROPOSED for review, not fired (cannot escalate)', () => {
    const m = createMachine(CHART, createGatedEffectDispatcher(CORE_PROVENANCE, 'agent'));
    m.send('GO');
    expect(listPending().length).toBe(1); // queued at the verdict seam
    expect(listJournal().some((e) => e.effect.kind === 'invoke-operation')).toBe(false);
  });

  it('the DEFAULT machine dispatcher is the gated one and behaves as user-origin', () => {
    const m = createMachine(CHART); // default dispatcher
    m.send('GO');
    expect(listJournal().some((e) => e.effect.kind === 'invoke-operation')).toBe(true);
    expect(listPending().length).toBe(0);
  });
});
