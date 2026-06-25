// @vitest-environment happy-dom

/**
 * 569 Move 5 — data-only actions (operationRef). An Action may reference an operation
 * as DATA instead of a closure `handler`; the kernel synthesizes the invoke-operation
 * effect. This is what lets a surface body / Presentation Declaration author actions
 * without a closure (actions are op-id references; the truth/presentation cut).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  registerAction,
  invokeAction,
  __resetActionsForTest,
  type Action,
} from './index.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

beforeEach(() => {
  __resetActionsForTest();
});

describe('Action operationRef — data-only actions (Move 5)', () => {
  it('rejects an action with neither handler nor operationRef', () => {
    expect(() =>
      registerAction({
        id: 'x.none',
        title: 'None',
        provenance: CORE_PROVENANCE,
      } as Action),
    ).toThrow(/exactly one of handler/);
  });

  it('rejects an action with both handler and operationRef', () => {
    expect(() =>
      registerAction({
        id: 'x.both',
        title: 'Both',
        handler: () => ({ kind: 'noop' as const }),
        operationRef: { operationId: 'core.reindex' },
        provenance: CORE_PROVENANCE,
      }),
    ).toThrow(/exactly one of handler/);
  });

  it('synthesizes the invoke-operation effect, merging invocation args over declared args', async () => {
    registerAction({
      id: 'x.reindex',
      title: 'Reindex',
      operationRef: { operationId: 'core.reindex', args: { full: true } },
      provenance: CORE_PROVENANCE,
    });
    const effect = await invokeAction('x.reindex', { scope: 'docs' });
    expect(effect.kind).toBe('invoke-operation');
    if (effect.kind === 'invoke-operation') {
      expect(effect.operationId).toBe('core.reindex');
      expect(effect.args).toEqual({ full: true, scope: 'docs' });
    }
  });
});
