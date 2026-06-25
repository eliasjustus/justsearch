/**
 * Multi-Provider Dispatch tests — Tempdoc 543 §13.3.2.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { nothing } from 'lit';
import {
  registerAggregateStrategy,
  dispatchAggregateStrategy,
  dispatchAggregateStrategies,
  renderAggregateMulti,
  setDispatchPolicy,
  getDispatchPolicy,
  __clearAggregateRegistry,
  __clearDispatchPolicies,
  __resetAggregateSubstrateForTest,
  type DispatchPolicy,
  type StrategyHost,
} from './aggregateRegistry.js';
import type { Operation } from '../../api/types/registry.js';

const HOST: StrategyHost = { apiBase: '' };

// Minimal Operation stub for strategy invocations.
const STUB_OP = { id: 'core.test', label: 'test' } as unknown as Operation;

beforeEach(() => {
  __clearAggregateRegistry();
  __clearDispatchPolicies();
});

describe('DispatchPolicy registry (§13.3.2)', () => {
  it('default policy is winner for unconfigured contexts', () => {
    expect(getDispatchPolicy('button')).toBe<DispatchPolicy>('winner');
    expect(getDispatchPolicy('list-item')).toBe<DispatchPolicy>('winner');
  });

  it('setDispatchPolicy overrides per context', () => {
    setDispatchPolicy('button', 'merge');
    expect(getDispatchPolicy('button')).toBe<DispatchPolicy>('merge');
    expect(getDispatchPolicy('list-item')).toBe<DispatchPolicy>('winner');
  });

  it('__clearDispatchPolicies resets', () => {
    setDispatchPolicy('button', 'merge');
    __clearDispatchPolicies();
    expect(getDispatchPolicy('button')).toBe<DispatchPolicy>('winner');
  });
});

describe('dispatchAggregateStrategies (multi)', () => {
  it('returns all matching strategies in rank-desc order', () => {
    const s1 = vi.fn(() => ({ _tag: 's1' } as never));
    const s2 = vi.fn(() => ({ _tag: 's2' } as never));
    const s3 = vi.fn(() => ({ _tag: 's3' } as never));
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: s1,
      source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 10,
      strategy: s2,
      source: { plugin: 'p1' },
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 5,
      strategy: s3,
      source: { plugin: 'p2' },
    });
    const out = dispatchAggregateStrategies('Operation', 'button');
    expect(out).toHaveLength(3);
    expect(out[0]).toBe(s2); // rank 10
    expect(out[1]).toBe(s3); // rank 5
    expect(out[2]).toBe(s1); // rank 1
  });

  it('returns empty for unregistered cell', () => {
    expect(dispatchAggregateStrategies('Operation', 'button')).toEqual([]);
  });

  it('filters by both aggregate and context', () => {
    const s1 = vi.fn(() => null);
    const s2 = vi.fn(() => null);
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: s1,
      source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'list-item',
      rank: 1,
      strategy: s2,
      source: 'core',
    });
    expect(dispatchAggregateStrategies('Operation', 'button')).toEqual([s1]);
    expect(dispatchAggregateStrategies('Operation', 'list-item')).toEqual([s2]);
  });
});

describe('renderAggregateMulti policy semantics', () => {
  it('"winner" policy: returns at most one result', () => {
    setDispatchPolicy('button', 'winner');
    const s1 = vi.fn(() => 'r1' as never);
    const s2 = vi.fn(() => 'r2' as never);
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: s1, source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 10,
      strategy: s2, source: { plugin: 'p' },
    });
    const out = renderAggregateMulti('Operation', 'button', STUB_OP, {} as never, HOST);
    expect(out).toHaveLength(1);
    expect(out[0]).toBe('r2');
    // 'winner' stops walking after first non-null result.
    expect(s1).not.toHaveBeenCalled();
  });

  it('"merge" policy: returns all non-empty results in rank order', () => {
    setDispatchPolicy('button', 'merge');
    const s1 = vi.fn(() => 'r1' as never);
    const s2 = vi.fn(() => 'r2' as never);
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: s1, source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 10,
      strategy: s2, source: { plugin: 'p' },
    });
    const out = renderAggregateMulti('Operation', 'button', STUB_OP, {} as never, HOST);
    expect(out).toEqual(['r2', 'r1']);
  });

  it('"rank-first-non-empty" walks past null/nothing to find first content', () => {
    setDispatchPolicy('button', 'rank-first-non-empty');
    const nullStrategy = vi.fn(() => null);
    const nothingStrategy = vi.fn(() => nothing as unknown as null);
    const realStrategy = vi.fn(() => 'real' as never);
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 20,
      strategy: nullStrategy, source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 15,
      strategy: nothingStrategy, source: { plugin: 'p1' },
    });
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: realStrategy, source: { plugin: 'p2' },
    });
    const out = renderAggregateMulti('Operation', 'button', STUB_OP, {} as never, HOST);
    expect(out).toEqual(['real']);
    expect(nullStrategy).toHaveBeenCalled();
    expect(nothingStrategy).toHaveBeenCalled();
    expect(realStrategy).toHaveBeenCalled();
  });

  it('empty registry → empty array', () => {
    setDispatchPolicy('button', 'merge');
    expect(
      renderAggregateMulti('Operation', 'button', STUB_OP, {} as never, HOST),
    ).toEqual([]);
  });
});

describe('Back-compat — single-winner dispatchAggregateStrategy unchanged', () => {
  it('still returns the highest-ranked single strategy', () => {
    const low = vi.fn(() => 'low' as never);
    const high = vi.fn(() => 'high' as never);
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: low, source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 10,
      strategy: high, source: { plugin: 'p' },
    });
    expect(dispatchAggregateStrategy('Operation', 'button')).toBe(high);
  });
});

describe('Tempdoc 543 §20.7 C2 — back-compat reset helpers', () => {
  it('__clearAggregateRegistry preserves dispatch policies', () => {
    setDispatchPolicy('button', 'merge');
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: () => 'r' as never, source: 'core',
    });
    __clearAggregateRegistry();
    // Entries cleared, but policy survives — back-compat with
    // pre-Slice 6 callers that never touched policy state.
    expect(dispatchAggregateStrategies('Operation', 'button')).toEqual([]);
    expect(getDispatchPolicy('button')).toBe<DispatchPolicy>('merge');
  });

  it('__resetAggregateSubstrateForTest clears both entries AND policies', () => {
    setDispatchPolicy('button', 'merge');
    registerAggregateStrategy({
      aggregate: 'Operation', context: 'button', rank: 1,
      strategy: () => 'r' as never, source: 'core',
    });
    __resetAggregateSubstrateForTest();
    expect(dispatchAggregateStrategies('Operation', 'button')).toEqual([]);
    expect(getDispatchPolicy('button')).toBe<DispatchPolicy>('winner');
  });
});
