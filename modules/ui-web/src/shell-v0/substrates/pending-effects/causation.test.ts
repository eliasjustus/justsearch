// @vitest-environment happy-dom

/**
 * §32 S3 — causation chaining (R-E3 / R-P1) + confidence (R-E2) tests.
 */

import { beforeEach, describe, it, expect } from 'vitest';
import {
  proposeEffect,
  proposeEffectSequence,
  acceptSequence,
  getPending,
  __resetPendingForTest,
} from './index.js';
import {
  listJournal,
  getCausationChain,
  __resetJournalForTest,
} from '../effects/index.js';
import { applyEffect } from '../actions/index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

beforeEach(() => {
  __resetPendingForTest();
  __resetJournalForTest();
});

describe('§32 R-E2 — PendingEffect confidence', () => {
  it('proposeEffect stores confidence on the Pending', () => {
    const id = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'agent', {
      confidence: 0.3,
    });
    expect(getPending(id)?.confidence).toBe(0.3);
  });

  it('confidence is absent when not supplied', () => {
    const id = proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'agent');
    expect(getPending(id)?.confidence).toBeUndefined();
  });
});

describe('§32 R-E3 — causation chaining through acceptSequence', () => {
  it('accepted sequence entries chain via causation; getCausationChain walks to root', () => {
    const { sequenceId } = proposeEffectSequence(
      [
        { effect: { kind: 'navigate', to: '#a' } },
        { effect: { kind: 'navigate', to: '#b' } },
        { effect: { kind: 'navigate', to: '#c' } },
      ],
      CORE_PROVENANCE,
    );
    const accepted = acceptSequence(sequenceId, (effect, invokedBy) => {
      applyEffect(effect, invokedBy);
    });
    expect(accepted).toBe(3);

    // The 'accepted'-outcome entries carry the causation chain (each step
    // caused by the previous accepted entry).
    const acc = listJournal().filter((e) => e.pendingOutcome === 'accepted');
    expect(acc).toHaveLength(3);
    expect(acc[0]!.causation).toBeUndefined(); // root: no parent
    expect(acc[1]!.causation).toBe(acc[0]!.id);
    expect(acc[2]!.causation).toBe(acc[1]!.id);

    // getCausationChain from the last entry → ancestors oldest-first.
    const chain = getCausationChain(acc[2]!.id);
    expect(chain.map((e) => e.id)).toEqual([acc[0]!.id, acc[1]!.id]);
  });

  it('getCausationChain is empty for an entry with no causation parent', () => {
    const { sequenceId } = proposeEffectSequence(
      [{ effect: { kind: 'noop' } }],
      CORE_PROVENANCE,
    );
    acceptSequence(sequenceId, (e, p) => applyEffect(e, p));
    const acc = listJournal().filter((e) => e.pendingOutcome === 'accepted');
    expect(getCausationChain(acc[0]!.id)).toHaveLength(0);
  });
});
