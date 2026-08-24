/**
 * @vitest-environment happy-dom
 *
 * NavigationHandler tests — slice 492 tier-3 substrate.
 *
 * Covers:
 *   - State distribution to registered StoreAdapters (the formerly-
 *     URLHydrator.applyState body, now owned by the handler).
 *   - Validation gate (invalid state → warn + skip restore; surface
 *     still activates).
 *   - Surface mounting (setActiveSurface called; pushAddress writes URL;
 *     activateProjection mounts the URL projector).
 *   - Unknown-surface path (skips activation; warn).
 *   - push gating (popstate-style push:false skips pushAddress).
 *   - Handler-tier invariants (idempotency, cancel-in-flight).
 *   - **Regression** for the state-bearing nav intent state-drop the
 *     pre-substrate `Shell.activateSurface(surfaceId, state)` exhibited.
 *     The handler MUST distribute state regardless of source.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createNavigationHandler } from './navigationHandler.js';
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
  deactivateProjection,
} from './URLProjector.js';
import type { StateSnapshot } from './types.js';

function buildAdapter(
  storeId: string,
): {
  adapter: StoreAdapter;
  restored: StateSnapshot[];
} {
  const restored: StateSnapshot[] = [];
  const adapter: StoreAdapter = {
    storeId,
    serialize: () => ({}),
    restore: (snap) => {
      restored.push({ ...snap });
    },
    subscribe: () => () => undefined,
  };
  return { adapter, restored };
}

beforeEach(() => {
  __resetStoreRegistryForTest();
  __resetSurfaceSchemasForTest();
  deactivateProjection();
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  __resetStoreRegistryForTest();
  __resetSurfaceSchemasForTest();
  deactivateProjection();
});

describe('NavigationHandler — state distribution', () => {
  it('distributes state to registered stores per surface stateSchema', async () => {
    const { adapter, restored } = buildAdapter('search');
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({
        type: 'object',
        properties: { query: { type: 'string' } },
      }),
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });

    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { query: 'hello' },
    });

    expect(restored).toEqual([{ query: 'hello' }]);
  });

  it('groups state by adapter when multiple bindings reference the same store', async () => {
    const { adapter, restored } = buildAdapter('search.filters');
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({
        type: 'object',
        properties: {
          modifiedFromMs: { type: 'integer' },
          modifiedToMs: { type: 'integer' },
        },
      }),
      bindings: [
        { schemaPath: '/modifiedFromMs', storeId: 'search.filters', storeKey: 'modifiedFromMs' },
        { schemaPath: '/modifiedToMs', storeId: 'search.filters', storeKey: 'modifiedToMs' },
      ],
    });

    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { modifiedFromMs: 1000, modifiedToMs: 2000 },
    });

    expect(restored).toHaveLength(1);
    expect(restored[0]).toEqual({ modifiedFromMs: 1000, modifiedToMs: 2000 });
  });

  it('skips restore but still activates surface when schema validation fails', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { adapter, restored } = buildAdapter('search.filters');
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({
        type: 'object',
        properties: { modifiedFromMs: { type: 'integer' } },
      }),
      bindings: [
        { schemaPath: '/modifiedFromMs', storeId: 'search.filters', storeKey: 'modifiedFromMs' },
      ],
    });

    let activated: string | null = null;
    const handler = createNavigationHandler({
      setActiveSurface: (id) => {
        activated = id;
      },
      isKnownSurface: () => true,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.search-surface',
      state: { modifiedFromMs: 'banana' as unknown as string },
    });

    expect(restored).toEqual([]);
    expect(activated).toBe('core.search-surface');
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it(
    'REGRESSION: state-bearing Navigation intent distributes state ' +
      'regardless of source (the latent state-drop the substrate completes)',
    async () => {
      // Pre-slice-492, Shell.activateSurface(surfaceId, state) accepted a
      // `state` parameter but used it only for pushAddress. Any source that
      // dispatched a Navigation intent with state (URL paste; backend-
      // broadcast over /api/intent/stream; future voice/MCP/plugin
      // emitters) had its state silently dropped at the dispatch boundary.
      // The handler tier fixes this by construction: state distribution is
      // the FIRST step of handle(), unconditional on source.
      const { adapter, restored } = buildAdapter('search');
      registerStore(adapter);
      registerSurfaceStateSchema('core.search-surface', {
        schema: JSON.stringify({
          type: 'object',
          properties: { query: { type: 'string' } },
        }),
        bindings: [
          { schemaPath: '/query', storeId: 'search', storeKey: 'query' },
        ],
      });
      const handler = createNavigationHandler({
        setActiveSurface: () => undefined,
        isKnownSurface: () => true,
      });

      // The address is what an LLM emission (slice 491 MarkdownUrlExtractor)
      // would produce: a Navigation with state={query:'hello'}. Before this
      // slice, the search box stayed at default; after this slice, the
      // store receives `restore({query:'hello'})`.
      await handler.handle({
        kind: 'navigate',
        target: 'core.search-surface',
        state: { query: 'hello' },
      });

      expect(restored).toEqual([{ query: 'hello' }]);
    },
  );
});

describe('NavigationHandler — surface mounting', () => {
  it('calls setActiveSurface and activates the projector for known surfaces', async () => {
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({ type: 'object', properties: {} }),
      bindings: [],
    });
    let activated: string | null = null;
    const handler = createNavigationHandler({
      setActiveSurface: (id) => {
        activated = id;
      },
      isKnownSurface: () => true,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.search-surface',
      state: {},
    });

    expect(activated).toBe('core.search-surface');
    expect(__activeSurfaceIdForTest()).toBe('core.search-surface');
  });

  it('skips activation and fires onUnknownSurface for unknown ids', async () => {
    const unknownSpy = vi.fn();
    let activated: string | null = null;
    const handler = createNavigationHandler({
      setActiveSurface: (id) => {
        activated = id;
      },
      isKnownSurface: () => false,
      onUnknownSurface: unknownSpy,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.ghost-surface',
      state: {},
    });

    expect(activated).toBeNull();
    expect(unknownSpy).toHaveBeenCalledWith('core.ghost-surface');
  });

  it('default onUnknownSurface logs a console.error assertion (tempdoc 499 §4.7)', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => false,
    });

    await handler.handle({
      kind: 'navigate',
      target: 'core.ghost-surface',
      state: {},
    });

    expect(errorSpy).toHaveBeenCalled();
    expect(errorSpy.mock.calls[0]![0]).toContain('BUG');
    errorSpy.mockRestore();
  });
});

describe('NavigationHandler — push gating (options)', () => {
  it('calls history.pushState by default', async () => {
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({ type: 'object', properties: {} }),
      bindings: [],
    });
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
    });
    await handler.handle({
      kind: 'navigate',
      target: 'core.search-surface',
      state: {},
    });
    expect(pushSpy).toHaveBeenCalledTimes(1);
    expect(pushSpy.mock.calls[0]![2]).toContain(
      'justsearch://surface/core.search-surface',
    );
    pushSpy.mockRestore();
  });

  it('skips history.pushState when options.push is false (popstate flow)', async () => {
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({ type: 'object', properties: {} }),
      bindings: [],
    });
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
    });
    await handler.handle(
      { kind: 'navigate', target: 'core.search-surface', state: {} },
      { push: false },
    );
    expect(pushSpy).not.toHaveBeenCalled();
    // The projector still writes replaceState as part of its own
    // activation — the URL is canonicalized without growing history.
    expect(__activeSurfaceIdForTest()).toBe('core.search-surface');
    pushSpy.mockRestore();
  });
});

describe('NavigationHandler — handler-tier invariants', () => {
  it('determinism per address: two handle()s converge to the same final state', async () => {
    const { adapter, restored } = buildAdapter('search');
    registerStore(adapter);
    registerSurfaceStateSchema('core.search-surface', {
      schema: JSON.stringify({
        type: 'object',
        properties: { query: { type: 'string' } },
      }),
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
    });

    const addr = {
      kind: 'navigate' as const,
      target: 'core.search-surface',
      state: { query: 'hello' },
    };
    await handler.handle(addr);
    await handler.handle(addr);

    // Both calls produce the same restore payload. The second is a
    // no-op-equivalent at the store level (restore overwrites with the
    // same value). Note: history is non-idempotent — two pushState
    // calls land in the back-stack. See the JSDoc "Determinism per
    // address" invariant for the precise scope.
    expect(restored).toEqual([{ query: 'hello' }, { query: 'hello' }]);
  });

  it(
    'last-writer-wins for overlapping Navigations (synchronous serialization)',
    async () => {
      const { adapter: searchAdapter, restored } = buildAdapter('search');
      registerStore(searchAdapter);
      registerSurfaceStateSchema('core.search-surface', {
        schema: JSON.stringify({
          type: 'object',
          properties: { query: { type: 'string' } },
        }),
        bindings: [
          { schemaPath: '/query', storeId: 'search', storeKey: 'query' },
        ],
      });
      const setActiveCalls: string[] = [];
      const handler = createNavigationHandler({
        setActiveSurface: (id) => {
          setActiveCalls.push(id);
        },
        isKnownSurface: () => true,
      });

      // Fire two handles in immediate succession. JavaScript's single-
      // threaded execution serializes them; the second's effects land
      // on top of the first. Both restores are observed (so the order
      // is FIRST then SECOND); the final activeId reflects the LAST
      // dispatched.
      await Promise.all([
        handler.handle({
          kind: 'navigate',
          target: 'core.search-surface',
          state: { query: 'first' },
        }),
        handler.handle({
          kind: 'navigate',
          target: 'core.search-surface',
          state: { query: 'second' },
        }),
      ]);

      expect(restored).toEqual([{ query: 'first' }, { query: 'second' }]);
      expect(setActiveCalls).toEqual(['core.search-surface', 'core.search-surface']);
      expect(__activeSurfaceIdForTest()).toBe('core.search-surface');
    },
  );
});

describe('NavigationHandler — MODAL placement (tempdoc 855 §11.1)', () => {
  const MODAL_ID = 'core.settings-surface';
  const STAGE_ID = 'core.search-surface';

  /** Placement lookup with exactly one MODAL target — everything else takes the normal path. */
  const placementOf = (id: string): string => (id === MODAL_ID ? 'MODAL' : 'RAIL');

  it('does NOT call setActiveSurface for a MODAL target (the Stage must not mount it)', async () => {
    const setActive = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: setActive,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal: () => undefined,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(setActive).not.toHaveBeenCalled();
  });

  it(
    'does NOT activate the projector — the CURRENT surface keeps its URL projection ' +
      '(the activateProjection teardown trap)',
    async () => {
      registerSurfaceStateSchema(STAGE_ID, {
        schema: JSON.stringify({ type: 'object', properties: {} }),
        bindings: [],
      });
      const handler = createNavigationHandler({
        setActiveSurface: () => undefined,
        isKnownSurface: () => true,
        getPlacement: placementOf,
        openModal: () => undefined,
      });

      // The stage surface owns the projector...
      await handler.handle({ kind: 'navigate', target: STAGE_ID, state: {} });
      expect(__activeSurfaceIdForTest()).toBe(STAGE_ID);

      // ...and opening the MODAL surface must leave it owning it. activateProjection tears the
      // current projection down BEFORE its own schema check, so calling it here would reset this
      // to null and silently kill the stage surface's live URL sync.
      await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });
      expect(__activeSurfaceIdForTest()).toBe(STAGE_ID);
    },
  );

  it('still pushes the address (the window URL is bookmarkable; Back is meaningful)', async () => {
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal: () => undefined,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(pushSpy).toHaveBeenCalledTimes(1);
    expect(pushSpy.mock.calls[0]![2]).toContain(`justsearch://surface/${MODAL_ID}`);
    pushSpy.mockRestore();
  });

  it('honors push:false for a MODAL target (the popstate flow must not grow history)', async () => {
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} }, { push: false });

    expect(pushSpy).not.toHaveBeenCalled();
    // 855 §11.1 D4 — nothing was pushed, so the opener is told so: closing must NOT history.back().
    expect(openModal).toHaveBeenCalledWith(MODAL_ID, {}, { pushed: false });
    pushSpy.mockRestore();
  });

  it('opens the window with the target id, its state, and pushed:true', async () => {
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: { category: 'privacy' } });

    expect(openModal).toHaveBeenCalledWith(MODAL_ID, { category: 'privacy' }, { pushed: true });
  });

  // D3 — the duplicate-entry defect: a repeat navigation to the address we are already on used to
  // stack a SECOND settings entry, so the window's history.back() landed on the settings address
  // again and Escape appeared to re-open the window.
  it('does NOT push a duplicate entry when the current address already targets the MODAL surface', async () => {
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });
    const pushSpy = vi.spyOn(window.history, 'pushState');

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(pushSpy).not.toHaveBeenCalled();
    expect(openModal).toHaveBeenCalledTimes(2);
    expect(openModal.mock.calls[0]![2]).toEqual({ pushed: true });
    expect(openModal.mock.calls[1]![2]).toEqual({ pushed: false });
    pushSpy.mockRestore();
  });

  it('reports pushed:false for a boot/deep-link entry (the location ALREADY is the MODAL address)', async () => {
    window.history.replaceState(null, '', `/#justsearch://surface/${MODAL_ID}`);
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(pushSpy).not.toHaveBeenCalled();
    expect(openModal).toHaveBeenCalledWith(MODAL_ID, {}, { pushed: false });
    pushSpy.mockRestore();
  });

  it('DOES push when the current address targets a DIFFERENT surface', async () => {
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: STAGE_ID, state: {} });
    const pushSpy = vi.spyOn(window.history, 'pushState');

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(pushSpy).toHaveBeenCalledTimes(1);
    expect(openModal).toHaveBeenCalledWith(MODAL_ID, {}, { pushed: true });
    pushSpy.mockRestore();
  });

  it('still distributes state for a MODAL target that declares a schema (forward-safety)', async () => {
    const { adapter, restored } = buildAdapter('settings');
    registerStore(adapter);
    registerSurfaceStateSchema(MODAL_ID, {
      schema: JSON.stringify({
        type: 'object',
        properties: { category: { type: 'string' } },
      }),
      bindings: [{ schemaPath: '/category', storeId: 'settings', storeKey: 'category' }],
    });
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal: () => undefined,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: { category: 'privacy' } });

    expect(restored).toEqual([{ category: 'privacy' }]);
  });

  it('skips the MODAL branch for an unknown target (recovery still wins)', async () => {
    const openModal = vi.fn();
    const unknownSpy = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: () => undefined,
      isKnownSurface: () => false,
      getPlacement: () => 'MODAL',
      openModal,
      onUnknownSurface: unknownSpy,
    });

    await handler.handle({ kind: 'navigate', target: 'core.ghost-surface', state: {} });

    expect(openModal).not.toHaveBeenCalled();
    expect(unknownSpy).toHaveBeenCalledWith('core.ghost-surface');
  });

  it('REGRESSION: a non-MODAL target keeps the full normal path', async () => {
    registerSurfaceStateSchema(STAGE_ID, {
      schema: JSON.stringify({ type: 'object', properties: {} }),
      bindings: [],
    });
    const setActive = vi.fn();
    const openModal = vi.fn();
    const pushSpy = vi.spyOn(window.history, 'pushState');
    const handler = createNavigationHandler({
      setActiveSurface: setActive,
      isKnownSurface: () => true,
      getPlacement: placementOf,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: STAGE_ID, state: {} });

    expect(setActive).toHaveBeenCalledWith(STAGE_ID);
    expect(__activeSurfaceIdForTest()).toBe(STAGE_ID);
    expect(pushSpy).toHaveBeenCalledTimes(1);
    expect(openModal).not.toHaveBeenCalled();
    pushSpy.mockRestore();
  });

  it('REGRESSION: an absent getPlacement leaves every target on the normal path', async () => {
    const setActive = vi.fn();
    const openModal = vi.fn();
    const handler = createNavigationHandler({
      setActiveSurface: setActive,
      isKnownSurface: () => true,
      openModal,
    });

    await handler.handle({ kind: 'navigate', target: MODAL_ID, state: {} });

    expect(setActive).toHaveBeenCalledWith(MODAL_ID);
    expect(openModal).not.toHaveBeenCalled();
  });
});
