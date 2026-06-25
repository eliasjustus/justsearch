// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup Track C — Behavioral Pass-8 mirror for
 * (Operation, button).
 *
 * Replaces the prior fake-exhaustiveness test (which only checked
 * that every Operation key appeared in OPERATION_BUTTON_CONSUMED).
 * The behavioral assertion mutates each Operation field in turn and
 * verifies the rendered output reacts according to the declared
 * role: 'visual'/'gate' MUST diff, 'routing'/'elided' MUST NOT.
 */

import { describe, expect, it } from 'vitest';
import {
  OPERATION_BUTTON_ROLES,
  operationButtonStrategy,
} from './operationButton';
import type { Operation } from '../../../api/types/registry';
import { classifiedKeys } from '../assertExhaustive';
import { assertBehavioralPass8 } from '../behavioralPass8';

const REFERENCE_OPERATION: Operation = {
  id: 'core.test-op',
  presentation: {
    labelKey: 'ops.test-op.label',
    descriptionKey: 'ops.test-op.description',
    iconHint: null,
    category: null,
  },
  intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
  policy: {
    risk: 'LOW',
    confirm: { kind: 'NONE' },
    audit: 'NONE',
    undoSupported: false,
  },
  availability: {},
  lineage: { affects: [], supersedes: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  executors: ['UI'],
  audience: 'USER',
  consumers: [],
};

describe('(Operation, button) canonical strategy — Pass-8 mirror', () => {
  it('roles record covers every Operation wire key', () => {
    const wireKeys = Object.keys(REFERENCE_OPERATION).sort();
    const declared = classifiedKeys(OPERATION_BUTTON_ROLES).slice().sort();
    expect(declared).toEqual(wireKeys);
  });

  it('behavioral Pass-8 — roles agree with observed render diffs', () => {
    assertBehavioralPass8({
      reference: REFERENCE_OPERATION,
      roles: OPERATION_BUTTON_ROLES,
      strategy: operationButtonStrategy,
      ctx: {},
      // 'DEVELOPER' clears every audience gate so the baseline renders
      // for every reference audience; the dedicated audience-gate test
      // below verifies the gate independently.
      host: { apiBase: 'http://localhost', viewerAudience: 'DEVELOPER' },
      mutations: {
        // visual — operation-id attribute on the inner jf-op-button
        id: (op) => ({ ...op, id: 'core.mutated' }),
        // visual — policy.confirm.kind drives the confirm-kind
        // attribute forwarded to ActionButton
        policy: (op) => ({
          ...op,
          policy: { ...op.policy, confirm: { kind: 'TYPED', confirmTextKey: 'k' } },
        }),
        // visual — lineage.affects/supersedes append to title tooltip
        lineage: (op) => ({
          ...op,
          lineage: { affects: ['core.affected'], supersedes: [] },
        }),
        // gate — audience flips the visibility check; with viewerAudience
        // DEVELOPER, all audiences render, but mutating from USER to
        // DEVELOPER still doesn't change the *rendered output*. So we
        // must verify the gate triggers a diff: change to AGENT (only
        // visible to AGENT viewer, hidden from DEVELOPER). Hmm, actually
        // DEVELOPER also sees DEVELOPER+OPERATOR but not AGENT.
        audience: (op) => ({ ...op, audience: 'AGENT' }),
        // routing — passed to OpButton by id; the strategy doesn't
        // read presentation/interface/provenance directly.
        presentation: (op) => ({
          ...op,
          presentation: { ...op.presentation, labelKey: 'ops.mutated.label' },
        }),
        intf: (op) => ({
          ...op,
          intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
        }),
        provenance: (op) => ({
          ...op,
          provenance: { ...op.provenance, tier: 'TRUSTED_PLUGIN' },
        }),
        // elided — not consumed at this cell.
        availability: (op) => ({ ...op, availability: { expression: 'always' } }),
        executors: (op) => ({ ...op, executors: ['UI', 'AGENT'] }),
        consumers: (op) => ({
          ...op,
          consumers: [{ consumerId: 'c.test', audience: 'USER' }],
        }),
      },
    });
  });

  it('audience gate denies USER-audience viewers from OPERATOR ops', () => {
    const operatorOnly: Operation = {
      ...REFERENCE_OPERATION,
      audience: 'OPERATOR',
    };
    const host = { apiBase: '', viewerAudience: 'USER' as const };
    const result = operationButtonStrategy(operatorOnly, {}, host);
    expect(typeof result).toBe('symbol');
  });

  it('DEVELOPER viewers clear all audience gates', () => {
    const developerOnly: Operation = {
      ...REFERENCE_OPERATION,
      audience: 'DEVELOPER',
    };
    const host = { apiBase: '', viewerAudience: 'DEVELOPER' as const };
    const result = operationButtonStrategy(developerOnly, {}, host);
    expect(typeof result).not.toBe('symbol');
  });
});
