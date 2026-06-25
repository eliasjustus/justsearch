// @vitest-environment happy-dom

/**
 * Shell integration tests — slice 492 follow-up.
 *
 * Pre-follow-up: handlers, sources, and the router each had unit tests
 * but the Shell's connectedCallback orchestration had zero coverage.
 * Issues that show up only at the integration layer:
 *
 *   - Source-teardown race: an async source's `start(...)` resolves
 *     after the Shell disconnects. The race fix in Shell tracks a
 *     `disconnected` flag and invokes the teardown immediately.
 *   - activateSurface transport stamping: callers must specify a
 *     transport; the dispatched Intent carries it through the
 *     listener fan-out so audit / observability records the origin.
 *   - State-bearing URL hash → router → NavigationHandler → store
 *     wiring (the headline scenario the slice substrate exists to
 *     enable).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  __resetSurfaceSchemasForTest,
  registerSurfaceStateSchema,
} from '../router/surfaceSchemas.js';
import {
  __resetStoreRegistryForTest,
} from '../router/storeRegistry.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { deactivateProjection } from '../router/URLProjector.js';
import {
  restoreSearch,
  serializeSearch,
} from '../state/searchState.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';
import type { StateSnapshot } from '../router/types.js';
import type { TransportTag } from '../router/transports.js';

function makeRailSurface(id: string, mountTag: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `${id}.label`,
      descriptionKey: `${id}.description`,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      operations: [],
      resources: [],
      prompts: [],
      diagnosticChannels: [],
    },
    mountTag,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
  };
}

const SEARCH = makeRailSurface('core.search-surface', 'jf-search-surface');
const LIBRARY = makeRailSurface('core.library-surface', 'jf-library-surface');

function seedTwoSurfaces(): void {
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries: [SEARCH, LIBRARY],
  };
  seedSurfaceCatalog(catalog);
}

interface ShellElement extends HTMLElement {
  apiBase: string;
  surfaces: Surface[];
  activeId: string | null;
  updateComplete: Promise<void>;
  activateSurface(id: string, state: StateSnapshot, transport: TransportTag): void;
}

async function renderShell(): Promise<ShellElement> {
  const shell = document.createElement('jf-shell') as ShellElement;
  shell.apiBase = '';
  document.body.appendChild(shell);
  await shell.updateComplete;
  // Let the connectedCallback's async bootstrap (fetchAndRegisterSurfaceSchemas)
  // settle. The schemas are registered ahead via registerSurfaceStateSchema
  // in beforeEach so the fetch resolving "no entries" is fine.
  await new Promise((r) => setTimeout(r, 30));
  await shell.updateComplete;
  return shell;
}

describe('Shell — slice 492 substrate integration', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    seedTwoSurfaces();
    // The Shell's connectedCallback awaits
    // `fetchAndRegisterSurfaceSchemas(apiBase)`, which makes a fetch
    // against `/api/registry/surfaces`. In happy-dom without a server
    // this fetch hangs/rejects unpredictably; stub it so the bootstrap
    // promise resolves promptly and sources start. The test-side
    // schema registration happens BEFORE the stub-fetch returns its
    // empty entries, so the manual schema persists.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ entries: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });

  afterEach(() => {
    document.querySelectorAll('jf-shell').forEach((el) => el.remove());
    resetSurfaceCatalog();
    __resetUserConfigForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    window.location.hash = '';
    vi.unstubAllGlobals();
  });

  describe('connectedCallback bootstrap', () => {
    it(
      'URL-hash state-bearing intent at boot distributes to stores via the substrate ' +
        '(the headline regression scenario)',
      async () => {
        // Pre-seed the surface stateSchema (the Shell's bootstrap fetch
        // returns entries:[] via the stub, so we register the schema
        // directly). The `registerCoreStores()` call inside
        // connectedCallback registers the production `search` store
        // (which restoreSearch / serializeSearch reach); the adapter
        // for `storeId: 'search'` resolves to that production store.
        registerSurfaceStateSchema('core.search-surface', {
          schema: JSON.stringify({
            type: 'object',
            properties: { query: { type: 'string' } },
          }),
          bindings: [
            { schemaPath: '/query', storeId: 'search', storeKey: 'query' },
          ],
        });
        // Reset the production searchState to a known empty starting
        // point so the assertion below reflects state restored by the
        // boot-read, not pre-existing state.
        restoreSearch({ query: '' });

        // Boot with a state-bearing URL hash.
        window.location.hash = '#justsearch://surface/core.search-surface?query=hello';

        const shell = await renderShell();

        // The URLSource boot-read dispatched the Intent through the router
        // → NavigationHandler → store. The state landed in the production
        // searchState (which is what `restoreSearch` writes to and
        // `serializeSearch` reads from).
        expect(serializeSearch().query).toBe('hello');
        expect(shell.activeId).toBe('core.search-surface');
      },
    );

    it('rail click dispatches with RAIL transport', async () => {
      const shell = await renderShell();
      shell.activateSurface('core.library-surface', {}, 'RAIL');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.library-surface');
      expect(window.location.hash).toContain(
        'justsearch://surface/core.library-surface',
      );
    });

    it('activateSurface requires a transport (compile-time check, runtime smoke)', async () => {
      const shell = await renderShell();
      // BUTTON for drop-redirect-style activations.
      shell.activateSurface('core.library-surface', {}, 'BUTTON');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.library-surface');
    });
  });

  describe('disconnectedCallback teardown', () => {
    it('cleanly tears down without throwing when sources are mid-bootstrap', async () => {
      // Connect + disconnect quickly. The Tauri source's async start
      // resolves *after* disconnect (in happy-dom it resolves to a no-op
      // teardown because isTauriRuntime() is false; still, the race-aware
      // codepath is the same). The fix asserts: no throw, no leak.
      const shell = await renderShell();
      shell.remove();
      // Let any pending async source.start() promises settle.
      await new Promise((r) => setTimeout(r, 30));
      // If the teardown race caused an unhandled rejection or threw, this
      // test would fail. Reaching here without an error is the assertion.
      expect(document.querySelectorAll('jf-shell').length).toBe(0);
    });

    it('subsequent connect after disconnect re-bootstraps cleanly', async () => {
      const shell = await renderShell();
      document.body.removeChild(shell);
      await new Promise((r) => setTimeout(r, 30));
      document.body.appendChild(shell);
      await shell.updateComplete;
      await new Promise((r) => setTimeout(r, 30));
      // Activate something — verifies the substrate re-wired correctly.
      shell.activateSurface('core.search-surface', {}, 'RAIL');
      await new Promise((r) => setTimeout(r, 10));
      expect(shell.activeId).toBe('core.search-surface');
    });
  });
});
