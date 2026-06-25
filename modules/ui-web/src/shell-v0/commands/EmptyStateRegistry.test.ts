// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.6 / §13.6 — EmptyStateRegistry tests.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  registerEmptyState,
  unregisterEmptyState,
  listEmptyStates,
  __resetEmptyStateForTest,
} from './EmptyStateRegistry.js';
import { updateShellContext, __resetShellContextForTest } from '../state/shellContextState.js';
import { __resetWhenCacheForTest } from './whenExpression.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../primitives/provenance.js';
const PLUGIN_PROVENANCE = makePluginProvenance('test-plugin', '1.0.0', 'TRUSTED_PLUGIN');

beforeEach(() => {
  __resetEmptyStateForTest();
  __resetShellContextForTest();
  __resetWhenCacheForTest();
});

describe('EmptyStateRegistry — basic register / list', () => {
  it('lists contributions matching the requested context only', () => {
    registerEmptyState({
      id: 'a',
      context: 'palette-no-results',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'a',
    });
    registerEmptyState({
      id: 'b',
      context: 'search-no-results',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'b',
    });
    const palette = listEmptyStates({ context: 'palette-no-results' });
    expect(palette).toHaveLength(1);
    expect(palette[0]!.id).toBe('a');
  });

  it('sorts by priority ascending', () => {
    registerEmptyState({
      id: 'late',
      context: 'x',
      priority: 10,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'late',
    });
    registerEmptyState({
      id: 'early',
      context: 'x',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'early',
    });
    const list = listEmptyStates({ context: 'x' });
    expect(list.map((c) => c.id)).toEqual(['early', 'late']);
  });

  it('unregister removes a contribution', () => {
    registerEmptyState({
      id: 'a',
      context: 'x',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: () => 'a',
    });
    expect(listEmptyStates({ context: 'x' })).toHaveLength(1);
    unregisterEmptyState('a');
    expect(listEmptyStates({ context: 'x' })).toHaveLength(0);
  });
});

describe('EmptyStateRegistry — `when` filter', () => {
  it('hides contributions whose when evaluates false', () => {
    registerEmptyState({
      id: 'scoped',
      context: 'x',
      priority: 1,
      source: 'plugin',
      provenance: PLUGIN_PROVENANCE,
      when: 'activeSurface == core.search-surface',
      render: () => 'scoped',
    });
    updateShellContext({ activeSurface: 'core.health-surface' });
    expect(listEmptyStates({ context: 'x' })).toHaveLength(0);
    updateShellContext({ activeSurface: 'core.search-surface' });
    expect(listEmptyStates({ context: 'x' })).toHaveLength(1);
  });
});

describe('EmptyStateRegistry — render input pass-through', () => {
  it('passes context + query + surface to render', () => {
    let captured: { context?: string; query?: string; surface?: string } = {};
    registerEmptyState({
      id: 'capture',
      context: 'x',
      priority: 1,
      source: 'core',
      provenance: CORE_PROVENANCE,
      render: (input) => {
        captured = { ...input };
        return 'ok';
      },
    });
    const list = listEmptyStates({ context: 'x', query: 'hello', surface: 'core.search-surface' });
    expect(list).toHaveLength(1);
    list[0]!.render({ context: 'x', query: 'hello', surface: 'core.search-surface' });
    expect(captured).toEqual({
      context: 'x',
      query: 'hello',
      surface: 'core.search-surface',
    });
  });
});
