// @vitest-environment happy-dom

/**
 * Tempdoc 543 §25.β3 — elicit substrate tests.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  elicit,
  resolveElicit,
  cancelElicit,
  listPendingElicits,
  getPendingElicitCount,
  __resetElicitForTest,
} from './index.js';

beforeEach(() => {
  __resetElicitForTest();
});

afterEach(() => {
  __resetElicitForTest();
});

describe('elicit substrate (§25.β3)', () => {
  it('elicit() returns a Promise pending until resolve', async () => {
    const promise = elicit({
      title: 'Rename',
      schema: { type: 'object', properties: { name: { type: 'string' } } },
    });
    expect(getPendingElicitCount()).toBe(1);
    const pending = listPendingElicits();
    expect(pending[0]!.title).toBe('Rename');
    const id = pending[0]!.id;
    resolveElicit(id, { name: 'new-name' });
    const value = await promise;
    expect(value).toEqual({ name: 'new-name' });
    expect(getPendingElicitCount()).toBe(0);
  });

  it('cancelElicit resolves with null', async () => {
    const promise = elicit({
      title: 'Choose',
      schema: { type: 'object' },
    });
    const id = listPendingElicits()[0]!.id;
    cancelElicit(id);
    const value = await promise;
    expect(value).toBeNull();
  });

  it('resolveElicit returns false for unknown id', () => {
    expect(resolveElicit(999, {})).toBe(false);
  });

  it('cancelElicit returns false for unknown id', () => {
    expect(cancelElicit(999)).toBe(false);
  });

  it('dispatches jf-elicit-request CustomEvent on call', async () => {
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-elicit-request', handler);
    try {
      const promise = elicit({
        title: 'X',
        schema: { type: 'object' },
        description: 'desc',
        initialData: { a: 1 },
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail.title).toBe('X');
      expect(events[0]!.detail.description).toBe('desc');
      expect(events[0]!.detail.initialData).toEqual({ a: 1 });
      // Clean up the in-flight elicit so the test doesn't hang.
      cancelElicit(events[0]!.detail.id);
      await promise;
    } finally {
      document.removeEventListener('jf-elicit-request', handler);
    }
  });

  it('supports concurrent elicits (FIFO listing)', async () => {
    const p1 = elicit({ title: 'A', schema: { type: 'object' } });
    const p2 = elicit({ title: 'B', schema: { type: 'object' } });
    expect(getPendingElicitCount()).toBe(2);
    const ids = listPendingElicits().map((r) => r.id);
    expect(ids).toEqual([1, 2]);
    resolveElicit(ids[0]!, { which: 'A' });
    expect(getPendingElicitCount()).toBe(1);
    resolveElicit(ids[1]!, { which: 'B' });
    expect(getPendingElicitCount()).toBe(0);
    expect(await p1).toEqual({ which: 'A' });
    expect(await p2).toEqual({ which: 'B' });
  });

  it('__resetElicitForTest resolves in-flight promises with null', async () => {
    const promise = elicit({ title: 'X', schema: { type: 'object' } });
    __resetElicitForTest();
    expect(await promise).toBeNull();
    expect(getPendingElicitCount()).toBe(0);
  });
});

describe('Action handler ctx.elicit integration (§25.β3)', () => {
  it('handler can await ctx.elicit and use the returned value in its Effect', async () => {
    const { registerAction, invokeAndApply, __resetActionsForTest } =
      await import('../actions/index.js');
    const { CORE_PROVENANCE } = await import('../../primitives/provenance.js');
    __resetActionsForTest();

    registerAction({
      id: 'test.action.rename',
      title: 'Rename',
      provenance: CORE_PROVENANCE,
      handler: async (_args, _addressable, ctx) => {
        if (!ctx) return { kind: 'noop' as const };
        const value = await ctx.elicit({
          title: 'Rename',
          schema: { type: 'object' },
        });
        if (value === null) return { kind: 'noop' as const };
        const v = value as { name?: string };
        return {
          kind: 'toast' as const,
          message: `renamed to ${v.name ?? 'unknown'}`,
        };
      },
    });

    // Kick the action — it will register an elicit request and await.
    const invokePromise = invokeAndApply('test.action.rename');
    // The elicit is now pending; resolve it.
    const ids = listPendingElicits().map((r) => r.id);
    expect(ids).toHaveLength(1);
    resolveElicit(ids[0]!, { name: 'new-name' });

    const effect = await invokePromise;
    expect(effect.kind).toBe('toast');
    expect((effect as { message: string }).message).toBe('renamed to new-name');
  });
});
