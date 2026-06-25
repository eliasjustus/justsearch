// @vitest-environment happy-dom

/**
 * Tempdoc 543 §21.E — PendingEffect substrate tests.
 */

import { beforeEach, describe, it, expect, vi } from 'vitest';
import {
  proposeEffect,
  acceptPending,
  rejectPending,
  listPending,
  getPending,
  getPendingCount,
  subscribePending,
  __resetPendingForTest,
} from './index.js';
import {
  listJournal,
  listJournalByOriginator,
  previewEffect,
  recordEffect,
  summarizeAgentActivity,
  __resetJournalForTest,
} from '../effects/index.js';
import { applyEffect } from '../actions/index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

beforeEach(() => {
  __resetPendingForTest();
  __resetJournalForTest();
});

describe('PendingEffect substrate (§21.E)', () => {
  it('proposeEffect registers a Pending and does NOT dispatch', () => {
    const apply = vi.fn();
    const id = proposeEffect(
      { kind: 'navigate', to: '#a' },
      CORE_PROVENANCE,
      'agent',
    );
    expect(id).toBe(1);
    expect(getPendingCount()).toBe(1);
    expect(apply).not.toHaveBeenCalled();
    const journal = listJournal();
    expect(journal).toHaveLength(0); // nothing recorded yet
    const p = getPending(id);
    expect(p?.effect).toEqual({ kind: 'navigate', to: '#a' });
    expect(p?.originator).toBe('agent');
  });

  it('acceptPending dispatches AND records a pendingOutcome=accepted entry', () => {
    const apply = vi.fn();
    const id = proposeEffect(
      { kind: 'open-modal', modalId: 'confirm' },
      CORE_PROVENANCE,
      'agent',
    );
    const entry = acceptPending(id, apply);
    expect(entry).not.toBeNull();
    expect(entry!.pendingOutcome).toBe('accepted');
    expect(entry!.originator).toBe('agent');
    expect(apply).toHaveBeenCalledTimes(1);
    expect(apply).toHaveBeenCalledWith(
      { kind: 'open-modal', modalId: 'confirm' },
      CORE_PROVENANCE,
    );
    expect(getPendingCount()).toBe(0);
    expect(listJournal()).toHaveLength(1);
  });

  // §32 R-P3 (S10): pins the load-bearing two-entry invariant. The production
  // callers (PendingEffectQueue / AgentActivityPanel) accept via
  // `(e, p) => applyEffect(e, p)` — omitting the originator arg, so the
  // dispatch entry defaults to 'user' and the lifecycle marker is 'agent'.
  // summarizeAgentActivity therefore counts an accepted agent pending ONCE.
  // If a future change "dedups" by re-attributing the dispatch entry to the
  // pending's originator, the digest would double-count it — this test fails
  // loudly instead of the bug shipping silently (guards against the comment
  // in acceptPending being ignored; audit-without-test → audit-with-test).
  it('R-P3: real-applyEffect accept yields [user dispatch, agent marker] → digest counts once', () => {
    const id = proposeEffect(
      { kind: 'toast', message: 'agent did a thing' },
      CORE_PROVENANCE,
      'agent',
    );
    acceptPending(id, (e, p) => applyEffect(e, p));
    const journal = listJournal();
    expect(journal).toHaveLength(2);
    expect(journal.map((e) => e.originator)).toEqual(['user', 'agent']);
    expect(summarizeAgentActivity(0).total).toBe(1);
  });

  // §32 #2 safety guard: the autonomy dial's "destructive never auto-fires"
  // invariant rests on accepting a proposed AGENT op re-dispatching it as a
  // USER action (originator='user' → BUTTON transport → backend re-gates,
  // and BUTTON×HIGH is still TYPED_CONFIRM). This pins the FE half of that
  // chain — accepting a proposed agent invoke-operation must emit a
  // jf-invoke-operation event carrying originator='user'. If a future change
  // preserved the 'agent' originator on accept, an accepted destructive op
  // would reach the backend as AGENT_LOOP/UNTRUSTED and this fails loudly.
  it('§32 #2: accepting a proposed agent invoke-operation re-dispatches as originator=user', () => {
    const events: Array<Record<string, unknown>> = [];
    const listener = (e: Event) =>
      events.push((e as CustomEvent).detail as Record<string, unknown>);
    document.addEventListener('jf-invoke-operation', listener);
    try {
      const id = proposeEffect(
        { kind: 'invoke-operation', operationId: 'core.index-gc', args: {} },
        CORE_PROVENANCE,
        'agent',
      );
      acceptPending(id, (e, p) => applyEffect(e, p));
      expect(events).toHaveLength(1);
      expect(events[0]!.operationId).toBe('core.index-gc');
      expect(events[0]!.originator).toBe('user');
    } finally {
      document.removeEventListener('jf-invoke-operation', listener);
    }
  });

  it('rejectPending drops Pending without dispatching, records outcome=rejected', () => {
    const apply = vi.fn();
    const id = proposeEffect(
      { kind: 'navigate', to: '#x' },
      CORE_PROVENANCE,
      'agent',
    );
    const ok = rejectPending(id);
    expect(ok).toBe(true);
    expect(apply).not.toHaveBeenCalled();
    expect(getPendingCount()).toBe(0);
    const journal = listJournal();
    expect(journal).toHaveLength(1);
    expect(journal[0]!.pendingOutcome).toBe('rejected');
    expect(journal[0]!.originator).toBe('agent');
  });

  it('acceptPending returns null for unknown id', () => {
    expect(acceptPending(999, vi.fn())).toBeNull();
  });

  it('rejectPending returns false for unknown id', () => {
    expect(rejectPending(999)).toBe(false);
  });

  it('listPending returns proposals in registration order', () => {
    proposeEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, 'agent');
    proposeEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE, 'agent');
    proposeEffect({ kind: 'navigate', to: '#c' }, CORE_PROVENANCE, 'system');
    const list = listPending();
    expect(list).toHaveLength(3);
    expect(list.map((p) => p.id)).toEqual([1, 2, 3]);
    expect(list.map((p) => p.originator)).toEqual(['agent', 'agent', 'system']);
  });

  it('subscribePending notifies on proposed / accepted / rejected', () => {
    const events: string[] = [];
    const off = subscribePending((e) => events.push(e.kind));
    const id1 = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const id2 = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE);
    acceptPending(id1, vi.fn());
    rejectPending(id2);
    off();
    expect(events).toEqual(['proposed', 'proposed', 'accepted', 'rejected']);
  });

  it('proposeEffect supports optional rationale', () => {
    const id = proposeEffect(
      { kind: 'noop' },
      CORE_PROVENANCE,
      'agent',
      { rationale: 'AI suggests this because X' },
    );
    expect(getPending(id)?.rationale).toBe('AI suggests this because X');
  });

  it('default originator for proposeEffect is "agent"', () => {
    const id = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE);
    expect(getPending(id)?.originator).toBe('agent');
  });
});

describe('JournalEntry.originator + listJournalByOriginator (§21.E D7)', () => {
  it('records originator on entries; defaults to "user"', () => {
    const apply = vi.fn();
    // Direct recordEffect via acceptPending with 'agent' originator.
    const aid = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'agent');
    acceptPending(aid, apply);
    // Direct recordEffect (no Pending) — default originator is 'user'.
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const entries = listJournal();
    expect(entries).toHaveLength(2);
    expect(entries[0]!.originator).toBe('agent');
    expect(entries[1]!.originator).toBe('user');
  });

  it('listJournalByOriginator filters', () => {
    const apply = vi.fn();
    const id1 = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'agent');
    const id2 = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'system');
    acceptPending(id1, apply);
    acceptPending(id2, apply);
    expect(listJournalByOriginator('agent')).toHaveLength(1);
    expect(listJournalByOriginator('system')).toHaveLength(1);
    expect(listJournalByOriginator('user')).toHaveLength(0);
  });
});

describe('§25.δ6 — Effect[] composition (proposeEffectSequence)', () => {
  it('proposeEffectSequence registers N pending; none dispatched', async () => {
    const { proposeEffectSequence, listPendingSequences, getSequenceMembers } =
      await import('./index.js');
    const apply = vi.fn();
    const { sequenceId, pendingIds } = proposeEffectSequence(
      [
        { effect: { kind: 'navigate', to: '#a' }, rationale: 'step 1' },
        { effect: { kind: 'navigate', to: '#b' } },
        { effect: { kind: 'navigate', to: '#c' } },
      ],
      CORE_PROVENANCE,
    );
    expect(pendingIds).toHaveLength(3);
    expect(getSequenceMembers(sequenceId)).toEqual(pendingIds);
    expect(apply).not.toHaveBeenCalled();
    expect(listPendingSequences()).toEqual([sequenceId]);
  });

  it('acceptSequence dispatches all in order, records each accepted', async () => {
    const { proposeEffectSequence, acceptSequence } = await import('./index.js');
    const applied: string[] = [];
    const apply = (e: { kind: string; to?: string }) =>
      applied.push(`${e.kind}:${e.to ?? ''}`);
    const { sequenceId } = proposeEffectSequence(
      [
        { effect: { kind: 'navigate', to: '#a' } },
        { effect: { kind: 'navigate', to: '#b' } },
      ],
      CORE_PROVENANCE,
    );
    const accepted = acceptSequence(sequenceId, apply);
    expect(accepted).toBe(2);
    expect(applied).toEqual(['navigate:#a', 'navigate:#b']);
    expect(listPending()).toHaveLength(0);
    const journal = listJournal();
    const acceptedOutcomes = journal.filter(
      (e) => e.pendingOutcome === 'accepted',
    );
    expect(acceptedOutcomes).toHaveLength(2);
  });

  it('rejectSequence drops all without dispatching', async () => {
    const { proposeEffectSequence, rejectSequence } = await import('./index.js');
    const apply = vi.fn();
    const { sequenceId } = proposeEffectSequence(
      [
        { effect: { kind: 'navigate', to: '#a' } },
        { effect: { kind: 'navigate', to: '#b' } },
      ],
      CORE_PROVENANCE,
    );
    const rejected = rejectSequence(sequenceId);
    expect(rejected).toBe(2);
    expect(apply).not.toHaveBeenCalled();
    expect(listPending()).toHaveLength(0);
    const journal = listJournal();
    const rejectedOutcomes = journal.filter(
      (e) => e.pendingOutcome === 'rejected',
    );
    expect(rejectedOutcomes).toHaveLength(2);
  });

  it('acceptSequence returns 0 for unknown sequence id', async () => {
    const { acceptSequence } = await import('./index.js');
    expect(acceptSequence(999, vi.fn())).toBe(0);
  });

  it('rejectSequence returns 0 for unknown sequence id', async () => {
    const { rejectSequence } = await import('./index.js');
    expect(rejectSequence(999)).toBe(0);
  });
});

describe('previewEffect (§21.E α8 foundation)', () => {
  it('returns the JournalEntry shape WITHOUT appending', () => {
    const before = listJournal().length;
    const preview = previewEffect(
      { kind: 'open-modal', modalId: 'x' },
      CORE_PROVENANCE,
      'agent',
    );
    expect(preview.effect).toEqual({ kind: 'open-modal', modalId: 'x' });
    expect(preview.originator).toBe('agent');
    expect(preview.inverse).toEqual({ kind: 'close-modal', modalId: 'x' });
    expect(listJournal().length).toBe(before); // unchanged
  });
});
