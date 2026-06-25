/**
 * @vitest-environment happy-dom
 *
 * URLProjector tests — slice 489 §6 state→URL projection.
 *
 * Verifies the projector subscribes to the registered store adapters for the
 * active surface, writes the canonical URL on state changes, and reuses the
 * existing window.history APIs (mocked via vitest's happy-dom env).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetStoreRegistryForTest,
  registerStore,
  type StoreAdapter,
} from './storeRegistry.js';
import {
  __resetSurfaceSchemasForTest,
  registerSurfaceStateSchema,
} from './surfaceSchemas.js';
import {
  __activeSurfaceIdForTest,
  __flushPendingWriteForTest,
  activateProjection,
  deactivateProjection,
  pushAddress,
} from './URLProjector.js';
import type { StateSnapshot } from './types.js';

function buildAdapter(
  storeId: string,
  initial: StateSnapshot,
): {
  adapter: StoreAdapter;
  emit: (s: StateSnapshot) => void;
  current: { value: StateSnapshot };
} {
  const current = { value: { ...initial } };
  const listeners = new Set<(s: StateSnapshot) => void>();
  const adapter: StoreAdapter = {
    storeId,
    serialize: () => ({ ...current.value }),
    restore: (snap) => {
      current.value = { ...snap };
      for (const l of listeners) l({ ...current.value });
    },
    subscribe: (l) => {
      listeners.add(l);
      // Adapters in production fire the listener once on subscribe with
      // the current value (matching subscribeSearch / subscribeFilters
      // posture). Mirror that here.
      l({ ...current.value });
      return () => listeners.delete(l);
    },
  };
  const emit = (s: StateSnapshot) => {
    current.value = { ...s };
    for (const l of listeners) l({ ...current.value });
  };
  return { adapter, emit, current };
}

describe('URLProjector', () => {
  let replaceSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    deactivateProjection();
    replaceSpy = vi
      .spyOn(window.history, 'replaceState')
      .mockImplementation(() => {
        /* swallow */
      });
  });

  afterEach(() => {
    deactivateProjection();
    replaceSpy.mockRestore();
  });

  it('activate is a no-op for surfaces with no declared schema', () => {
    activateProjection('core.no-schema');
    expect(__activeSurfaceIdForTest()).toBeNull();
    expect(replaceSpy).not.toHaveBeenCalled();
  });

  it('activate writes the canonical URL on initial subscribe', () => {
    const { adapter } = buildAdapter('search', { query: 'rust' });
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });

    activateProjection('core.search-surface');

    expect(__activeSurfaceIdForTest()).toBe('core.search-surface');
    expect(replaceSpy).toHaveBeenCalled();
    const lastCall = replaceSpy.mock.calls.at(-1);
    expect(lastCall?.[2]).toBe(
      '#justsearch://surface/core.search-surface?query=rust',
    );
  });

  it('state changes after activation re-emit the URL', () => {
    const { adapter, emit } = buildAdapter('search', { query: '' });
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });

    activateProjection('core.search-surface');
    const callsBefore = replaceSpy.mock.calls.length;
    emit({ query: 'rust ownership' });
    // Slice 489 T1/G2 — subscribe-driven writes are now debounced (75ms
    // trailing window). Flush the pending timer so the assertion sees the
    // trailing write synchronously instead of waiting on real time.
    __flushPendingWriteForTest();

    expect(replaceSpy.mock.calls.length).toBeGreaterThan(callsBefore);
    expect(replaceSpy.mock.calls.at(-1)?.[2]).toBe(
      '#justsearch://surface/core.search-surface?query=rust%20ownership',
    );
  });

  it('rapid emits coalesce into a single trailing write (G2)', () => {
    const { adapter, emit } = buildAdapter('search', { query: '' });
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });

    activateProjection('core.search-surface');
    const callsBefore = replaceSpy.mock.calls.length;

    // Simulate a fast typing burst: five keystrokes in the same synchronous
    // turn. Without debouncing this would produce five replaceState calls
    // (the original keystroke-flicker behavior).
    emit({ query: 'r' });
    emit({ query: 'ru' });
    emit({ query: 'rus' });
    emit({ query: 'rust' });
    emit({ query: 'rust!' });

    // No flush yet — pending timer has not fired. No writes should have
    // landed from the subscribe-driven path.
    expect(replaceSpy.mock.calls.length).toBe(callsBefore);

    __flushPendingWriteForTest();

    // Exactly one trailing write, with the final query value.
    expect(replaceSpy.mock.calls.length).toBe(callsBefore + 1);
    expect(replaceSpy.mock.calls.at(-1)?.[2]).toBe(
      '#justsearch://surface/core.search-surface?query=rust!',
    );
  });

  it('repeat activation for the same surface is a no-op', () => {
    const { adapter } = buildAdapter('search', { query: 'rust' });
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });

    activateProjection('core.search-surface');
    const callsAfterFirst = replaceSpy.mock.calls.length;
    activateProjection('core.search-surface');
    expect(replaceSpy.mock.calls.length).toBe(callsAfterFirst);
  });

  it('activating a different surface tears down prior subscriptions', () => {
    const { adapter: a1, emit: emitA } = buildAdapter('search', { query: 'rust' });
    const { adapter: a2 } = buildAdapter('search.filters', {});
    registerStore(a1);
    registerStore(a2);
    registerSurfaceStateSchema('core.search-surface', {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });
    registerSurfaceStateSchema('core.library-surface', {
      schema: '{"type":"object"}',
      bindings: [
        { schemaPath: '/x', storeId: 'search.filters', storeKey: 'modifiedFromMs' },
      ],
    });

    activateProjection('core.search-surface');
    activateProjection('core.library-surface');
    const callsAfterSwitch = replaceSpy.mock.calls.length;

    emitA({ query: 'new' });
    // After deactivating 'core.search-surface', the search adapter's emit
    // should no longer trigger the projector.
    expect(replaceSpy.mock.calls.length).toBe(callsAfterSwitch);
  });

  it('pushAddress writes via history.pushState', () => {
    const pushSpy = vi
      .spyOn(window.history, 'pushState')
      .mockImplementation(() => {
        /* swallow */
      });
    pushAddress({
      kind: 'navigate',
      target: 'core.library-surface',
      state: { folder: 'docs' },
    });
    expect(pushSpy).toHaveBeenCalled();
    expect(pushSpy.mock.calls.at(-1)?.[2]).toBe(
      '#justsearch://surface/core.library-surface?folder=docs',
    );
    pushSpy.mockRestore();
  });
});
