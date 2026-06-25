/**
 * EvaluationContext unit tests — Tempdoc 543 §13.2.1.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  registerProjector,
  unregisterProjector,
  getProjector,
  listProjectors,
  subscribeProjectors,
  buildEvaluationContext,
  bumpScopeVersion,
  getScopeVersion,
  __resetProjectorRegistryForTest,
} from './index.js';
import type { Addressable } from '../addressable.js';
import {
  __resetShellContextForTest,
  updateShellContext,
} from '../../state/shellContextState.js';

beforeEach(() => {
  __resetProjectorRegistryForTest();
  __resetShellContextForTest();
});

describe('Projector registry (§13.2.1)', () => {
  it('register/get/unregister round-trip', () => {
    const project = (a: Addressable) => ({ result_id: a.id });
    registerProjector('search-result', project);
    expect(getProjector('search-result')).toBe(project);
    expect(unregisterProjector('search-result')).toBe(true);
    expect(getProjector('search-result')).toBeUndefined();
  });

  it('listProjectors returns registered kinds sorted', () => {
    registerProjector('search-result', () => ({}));
    registerProjector('citation', () => ({}));
    expect(listProjectors()).toEqual(['citation', 'search-result']);
  });

  it('re-registering same kind replaces (idempotent under HMR)', () => {
    const p1 = (_a: Addressable) => ({ v: 1 });
    const p2 = (_a: Addressable) => ({ v: 2 });
    registerProjector('search-result', p1);
    registerProjector('search-result', p2);
    expect(getProjector('search-result')).toBe(p2);
  });

  it('subscribeProjectors fires on register and unregister', () => {
    const listener = vi.fn();
    subscribeProjectors(listener);
    registerProjector('search-result', () => ({}));
    expect(listener).toHaveBeenCalledTimes(1);
    unregisterProjector('search-result');
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('buildEvaluationContext composer (§13.2.1)', () => {
  it('null addressable → Scope only', () => {
    updateShellContext({ audience: 'DEVELOPER' });
    const ctx = buildEvaluationContext({ addressable: null });
    expect(ctx['audience']).toBe('DEVELOPER');
    expect(ctx['result_id']).toBeUndefined();
  });

  it('addressable with no projector → Scope only', () => {
    updateShellContext({ audience: 'OPERATOR' });
    const ctx = buildEvaluationContext({
      addressable: { kind: 'search-result', id: 'a', payload: {} },
    });
    expect(ctx['audience']).toBe('OPERATOR');
    expect(ctx['result_id']).toBeUndefined();
  });

  it('projector facts override Scope on key conflict', () => {
    updateShellContext({ audience: 'USER' });
    registerProjector('search-result', () => ({ audience: 'OVERRIDDEN' }));
    const ctx = buildEvaluationContext({
      addressable: { kind: 'search-result', id: 'a', payload: {} },
    });
    expect(ctx['audience']).toBe('OVERRIDDEN');
  });

  it('environment facts override Scope but not target-facts', () => {
    updateShellContext({ audience: 'USER' });
    registerProjector('search-result', () => ({ now: 'projector-value' }));
    const ctx = buildEvaluationContext({
      addressable: { kind: 'search-result', id: 'a', payload: {} },
      environment: { now: 'env-value', audience: 'env-audience' },
    });
    // env-audience overrides scope; projector's `now` overrides env's `now`.
    expect(ctx['audience']).toBe('env-audience');
    expect(ctx['now']).toBe('projector-value');
  });

  it('honors explicit scope override (test fixture path)', () => {
    const ctx = buildEvaluationContext({
      scope: { customKey: 42 },
      addressable: null,
    });
    expect(ctx['customKey']).toBe(42);
  });
});

describe('Memoization (§13.2.1 perf finding)', () => {
  it('repeated calls within same scope-version hit memo (projector called once)', () => {
    const projector = vi.fn((a: Addressable) => ({ id: a.id }));
    registerProjector('search-result', projector);
    const addr: Addressable = { kind: 'search-result', id: 'doc-1', payload: {} };
    buildEvaluationContext({ addressable: addr });
    buildEvaluationContext({ addressable: addr });
    buildEvaluationContext({ addressable: addr });
    // register call invokes once via bumpScopeVersion clearing cache, then projector hits memo
    expect(projector).toHaveBeenCalledTimes(1);
  });

  it('bumpScopeVersion invalidates memo', () => {
    const projector = vi.fn(() => ({ ok: true }));
    registerProjector('search-result', projector);
    const addr: Addressable = { kind: 'search-result', id: 'doc-1', payload: {} };
    buildEvaluationContext({ addressable: addr });
    expect(projector).toHaveBeenCalledTimes(1);
    bumpScopeVersion();
    buildEvaluationContext({ addressable: addr });
    expect(projector).toHaveBeenCalledTimes(2);
  });

  it('different addressables of same kind cache separately', () => {
    const projector = vi.fn((a: Addressable) => ({ id: a.id }));
    registerProjector('search-result', projector);
    buildEvaluationContext({
      addressable: { kind: 'search-result', id: 'a', payload: {} },
    });
    buildEvaluationContext({
      addressable: { kind: 'search-result', id: 'b', payload: {} },
    });
    expect(projector).toHaveBeenCalledTimes(2);
  });

  it('getScopeVersion advances on bump and on projector register', () => {
    const v0 = getScopeVersion();
    bumpScopeVersion();
    expect(getScopeVersion()).toBe(v0 + 1);
    registerProjector('search-result', () => ({}));
    expect(getScopeVersion()).toBe(v0 + 2);
  });
});
