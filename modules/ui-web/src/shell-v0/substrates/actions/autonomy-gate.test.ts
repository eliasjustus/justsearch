// @vitest-environment happy-dom

/**
 * §32 U1 — invokeAndApply consults the Autonomy Dial.
 */

import { beforeEach, describe, it, expect } from 'vitest';
import { registerAction, invokeAndApply, __resetActionsForTest } from './index.js';
import { listJournal, __resetJournalForTest } from '../effects/index.js';
import {
  listPending,
  getPendingCount,
  __resetPendingForTest,
} from '../pending-effects/index.js';
import {
  setAutonomyLevel,
  __resetAutonomyForTest,
} from '../autonomy/index.js';
import { makePluginProvenance } from '../../primitives/provenance.js';

const PROV = makePluginProvenance('agent-test', '1.0.0', 'TRUSTED_PLUGIN');

beforeEach(() => {
  __resetActionsForTest();
  __resetJournalForTest();
  __resetPendingForTest();
  globalThis.localStorage?.clear();
  __resetAutonomyForTest();
  registerAction({
    id: 'test.agent-op',
    title: 'Test Agent Op',
    handler: () => ({ kind: 'toast', message: 'agent did it' }),
    provenance: PROV,
  });
  registerAction({
    id: 'test.agent-invoke-op',
    title: 'Test Agent Backend Op',
    handler: () => ({
      kind: 'invoke-operation' as const,
      operationId: 'core.index-gc',
      args: {},
    }),
    provenance: PROV,
  });
});

describe('§32 U1 — autonomy gate in invokeAndApply', () => {
  it('watch + agent → PROPOSES (queued, not dispatched)', async () => {
    setAutonomyLevel('watch');
    await invokeAndApply('test.agent-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(1);
    expect(listPending()[0]!.originator).toBe('agent');
    expect(listJournal()).toHaveLength(0); // not dispatched
  });

  it('assist + agent + pure-FE effect (toast) → DISPATCHES (journaled, not queued)', async () => {
    setAutonomyLevel('assist');
    await invokeAndApply('test.agent-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(0);
    expect(listJournal()).toHaveLength(1);
  });

  it('assist + agent + backend op (invoke-operation) → PROPOSES (the assist↔auto distinction)', async () => {
    setAutonomyLevel('assist');
    await invokeAndApply('test.agent-invoke-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(1);
    expect(listPending()[0]!.effect.kind).toBe('invoke-operation');
    expect(listPending()[0]!.originator).toBe('agent');
    expect(listJournal()).toHaveLength(0); // not dispatched — waits for approval
  });

  it('auto + agent + backend op → DISPATCHES (backend lattice is the sole gate)', async () => {
    setAutonomyLevel('auto');
    await invokeAndApply('test.agent-invoke-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(0);
    expect(listJournal()).toHaveLength(1);
  });

  it('watch + agent + backend op → PROPOSES (max oversight)', async () => {
    setAutonomyLevel('watch');
    await invokeAndApply('test.agent-invoke-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(1);
    expect(listJournal()).toHaveLength(0);
  });

  it('watch + user → DISPATCHES (the dial only gates agent invocations)', async () => {
    setAutonomyLevel('watch');
    await invokeAndApply('test.agent-op', {}, null, undefined, 'user');
    expect(getPendingCount()).toBe(0);
    expect(listJournal()).toHaveLength(1);
  });

  it('auto + agent → DISPATCHES (guards the disposition table from drift)', async () => {
    setAutonomyLevel('auto');
    await invokeAndApply('test.agent-op', {}, null, undefined, 'agent');
    expect(getPendingCount()).toBe(0);
    expect(listJournal()).toHaveLength(1);
  });
});
