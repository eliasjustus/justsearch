// @vitest-environment happy-dom

import 'ses';

/**
 * 478 §4.I — PluginContribution tests.
 *
 * Verifies the typed contribution record path: register() returns
 * a PluginContribution; the registry applies it atomically.
 *
 * Backward-compat: register() returning void preserves V1.5 alpha
 * imperative behavior — covered by existing PluginRegistry.test.ts.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PluginRegistry,
  type PluginContribution,
  type PluginManifest,
  PLUGIN_CONTRACT_VERSION,
  type SurfacePortHandler,
} from './index.js';

class TestElement extends HTMLElement {}

function makeManifest(opts: {
  id: string;
  contribution: PluginContribution | (() => PluginContribution);
}): PluginManifest {
  const register = vi.fn((host) => {
    void host;
    if (typeof opts.contribution === 'function') {
      return opts.contribution();
    }
    return opts.contribution;
  });
  return {
    id: opts.id,
    version: '1.0.0',
    displayName: opts.id,
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: opts.id,
    capabilities: {},
    register,
  };
}

describe('PluginContribution — register returns typed declaration', () => {
  let registry: PluginRegistry;

  beforeEach(() => {
    registry = new PluginRegistry();
  });

  afterEach(() => {
    // No-op
  });

  it('contribution.customElements declarations get registered with namespace prefix', () => {
    const manifest = makeManifest({
      id: 'cont-a',
      contribution: {
        customElements: [{ tagSuffix: 'panel', klass: TestElement }],
      },
    });
    registry.install(manifest);
    expect(customElements.get('cont-a-panel')).toBe(TestElement);
  });

  it('contribution.customElements rejects invalid tag suffix', () => {
    const manifest = makeManifest({
      id: 'cont-b',
      contribution: {
        // Capital letters not allowed in custom-element names
        customElements: [{ tagSuffix: 'BadName', klass: TestElement }],
      },
    });
    registry.install(manifest);
    // The plugin lands with an error; element should NOT be registered.
    expect(registry.get('cont-b')?.registerError).not.toBeNull();
    expect(customElements.get('cont-b-BadName')).toBeUndefined();
  });

  it('contribution.surfacePorts handlers are registered atomically', () => {
    const handler: SurfacePortHandler = vi.fn(() => {
      return document.createElement('div');
    });
    const manifest = makeManifest({
      id: 'cont-c',
      contribution: {
        surfacePorts: [{ portId: 'cont-c.port', handler }],
      },
    });
    registry.install(manifest);
    const result = registry.dispatchSurfacePort('cont-c.port', { x: 1 });
    expect(result).toBeInstanceOf(HTMLElement);
    expect(handler).toHaveBeenCalledWith(
      expect.objectContaining({ portId: 'cont-c.port', payload: { x: 1 } }),
    );
  });

  it('contribution.translations land in plugin-scoped catalog', async () => {
    const { localizeResourceKey, __resetForTest } = await import(
      '../../i18n/resourceCatalog.js'
    );
    __resetForTest();
    const manifest = makeManifest({
      id: 'cont-d',
      contribution: {
        translations: {
          en: { 'cont-d.label': 'Hello D' },
        },
      },
    });
    registry.install(manifest);
    expect(localizeResourceKey('cont-d.label')).toBe('Hello D');
    // Uninstall removes the entire scope (478 §4.F).
    registry.uninstall('cont-d');
    expect(localizeResourceKey('cont-d.label')).toBe('cont-d.label');
  });

  // -------------------------------------------------------------------------
  // Slice 447-followup-live-wiring §X.12.8 Item 1.2 — V1.5.1 polish for the
  // three plugin-overlay merge function pairs (Surface / Resource / Recovery).
  // Each contribution path must land its entries in the corresponding catalog
  // at install time and clear them at uninstall time.
  // -------------------------------------------------------------------------

  it('contribution.surfaceContributions lands in SurfaceCatalog at install', async () => {
    const { getSurface, __resetForTest: resetSurfaces } = await import(
      '../../api/registry/SurfaceCatalogClient.js'
    );
    resetSurfaces();
    const manifest = makeManifest({
      id: 'cont-surf',
      contribution: {
        surfaceContributions: [
          {
            contribution: {
              id: 'cont-surf.dashboard',
              mountTag: 'cont-surf-dashboard',
              labelKey: 'cont-surf.dashboard.label',
              descriptionKey: 'cont-surf.dashboard.desc',
              audience: 'USER',
              placement: 'RAIL',
            },
          },
        ],
      },
    });
    registry.install(manifest);
    const surface = getSurface('cont-surf.dashboard');
    expect(surface).toBeDefined();
    expect(surface?.mountTag).toBe('cont-surf-dashboard');
    expect(surface?.provenance.contributorId).toBe('cont-surf');
    expect(surface?.provenance.tier).toBe('TRUSTED_PLUGIN');
  });

  it('uninstall removes surfaceContributions from SurfaceCatalog', async () => {
    const { getSurface, __resetForTest: resetSurfaces } = await import(
      '../../api/registry/SurfaceCatalogClient.js'
    );
    resetSurfaces();
    const manifest = makeManifest({
      id: 'cont-surf-rm',
      contribution: {
        surfaceContributions: [
          {
            contribution: {
              id: 'cont-surf-rm.x',
              mountTag: 'cont-surf-rm-x',
              labelKey: 'k',
              descriptionKey: 'k',
              audience: 'USER',
              placement: 'STAGE',
            },
          },
        ],
      },
    });
    registry.install(manifest);
    expect(getSurface('cont-surf-rm.x')).toBeDefined();
    registry.uninstall('cont-surf-rm');
    expect(getSurface('cont-surf-rm.x')).toBeUndefined();
  });

  it('contribution.resourceContributions lands in ResourceCatalog at install', async () => {
    const { getResource, __resetForTest: resetResources } = await import(
      '../../api/registry/ResourceCatalogClient.js'
    );
    resetResources();
    const manifest = makeManifest({
      id: 'cont-res',
      contribution: {
        resourceContributions: [
          {
            contribution: {
              id: 'cont-res.dataset',
              kind: 'STATE',
              category: 'WORKLOAD_OBSERVATION',
              presentation: { labelKey: 'k', descriptionKey: 'k' },
              endpoint: '/api/cont-res/dataset',
              subscriptionMode: 'ONE_SHOT',
              schema: '#/definitions/CustomDataset',
              metadata: {},
              consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
              audience: 'USER',
              entries: { fingerprintMode: 'SCHEMA_OWNED' },
              privacy: { tier: 'PRIVATE', resolver: null },
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
            } as any,
            version: '1.0.0',
          },
        ],
      },
    });
    registry.install(manifest);
    const res = getResource('cont-res.dataset');
    expect(res).toBeDefined();
    expect(res?.provenance.contributorId).toBe('cont-res');
    expect(res?.provenance.tier).toBe('TRUSTED_PLUGIN');
  });

  it('uninstall removes resourceContributions from ResourceCatalog', async () => {
    const { getResource, __resetForTest: resetResources } = await import(
      '../../api/registry/ResourceCatalogClient.js'
    );
    resetResources();
    const manifest = makeManifest({
      id: 'cont-res-rm',
      contribution: {
        resourceContributions: [
          {
            contribution: {
              id: 'cont-res-rm.dataset',
              kind: 'STATE',
              category: 'WORKLOAD_OBSERVATION',
              presentation: { labelKey: 'k', descriptionKey: 'k' },
              endpoint: '/api/cont-res-rm/dataset',
              subscriptionMode: 'ONE_SHOT',
              schema: '#/definitions/CustomDataset',
              metadata: {},
              consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
              audience: 'USER',
              entries: { fingerprintMode: 'SCHEMA_OWNED' },
              privacy: { tier: 'PRIVATE', resolver: null },
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
            } as any,
          },
        ],
      },
    });
    registry.install(manifest);
    expect(getResource('cont-res-rm.dataset')).toBeDefined();
    registry.uninstall('cont-res-rm');
    expect(getResource('cont-res-rm.dataset')).toBeUndefined();
  });

  it('contribution.recoveryOverlays own-namespace contribution lands and is consulted', async () => {
    const {
      getOverlayRecovery,
      __resetForTest: resetOverlays,
    } = await import('../../api/registry/RecoveryOverlayClient.js');
    resetOverlays();
    const manifest = makeManifest({
      id: 'vendor.contov',
      contribution: {
        recoveryOverlays: [
          {
            conditionId: 'vendor.contov.broken',
            subject: 'svc',
            operationRef: 'vendor.contov.fix',
          },
        ],
      },
    });
    registry.install(manifest);
    expect(getOverlayRecovery('vendor.contov.broken', 'svc')).toBe(
      'vendor.contov.fix',
    );
  });

  it('contribution.recoveryOverlays TRUSTED_PLUGIN may override a CORE condition', async () => {
    const {
      getOverlayRecovery,
      __resetForTest: resetOverlays,
    } = await import('../../api/registry/RecoveryOverlayClient.js');
    resetOverlays();
    const manifest = makeManifest({
      id: 'cont-trust',
      contribution: {
        recoveryOverlays: [
          {
            conditionId: 'core.indexing.broken',
            subject: 'worker',
            operationRef: 'cont-trust.fix',
          },
        ],
      },
    });
    registry.install(manifest);
    expect(getOverlayRecovery('core.indexing.broken', 'worker')).toBe(
      'cont-trust.fix',
    );
  });

  it('uninstall removes recoveryOverlays the plugin contributed', async () => {
    const {
      getOverlayRecovery,
      __resetForTest: resetOverlays,
    } = await import('../../api/registry/RecoveryOverlayClient.js');
    resetOverlays();
    const manifest = makeManifest({
      id: 'cont-ov-rm',
      contribution: {
        recoveryOverlays: [
          {
            conditionId: 'vendor.cont-ov-rm.broken',
            subject: 'x',
            operationRef: 'vendor.cont-ov-rm.fix',
          },
        ],
      },
    });
    registry.install(manifest);
    expect(getOverlayRecovery('vendor.cont-ov-rm.broken', 'x')).toBe(
      'vendor.cont-ov-rm.fix',
    );
    registry.uninstall('cont-ov-rm');
    expect(getOverlayRecovery('vendor.cont-ov-rm.broken', 'x')).toBeUndefined();
  });

  it('contribution can declare multiple sub-contributions in one record', () => {
    const handler: SurfacePortHandler = () => null;
    // Use fresh classes — HTML spec disallows the same class being
    // associated with multiple tag names, so reusing TestElement
    // (already registered by an earlier test) would throw.
    class ContEPanel extends HTMLElement {}
    class ContESidebar extends HTMLElement {}
    const manifest = makeManifest({
      id: 'cont-e',
      contribution: {
        customElements: [
          { tagSuffix: 'panel', klass: ContEPanel },
          { tagSuffix: 'sidebar', klass: ContESidebar },
        ],
        surfacePorts: [{ portId: 'cont-e.port', handler }],
        translations: {
          en: { 'cont-e.label': 'Hello E' },
        },
      },
    });
    registry.install(manifest);
    expect(customElements.get('cont-e-panel')).toBe(ContEPanel);
    expect(customElements.get('cont-e-sidebar')).toBe(ContESidebar);
    expect(registry.get('cont-e')).toBeDefined();
    expect(registry.get('cont-e')?.registerError).toBeNull();
  });

  it('returning void preserves V1.5 alpha imperative behavior', () => {
    // Plugin's register doesn't return a contribution; it imperatively
    // calls customElements.define + host.registerSurfacePort. Both
    // paths land the same observable state.
    let elementRegistered = false;
    const manifest: PluginManifest = {
      id: 'legacy-shape',
      version: '1.0.0',
      displayName: 'Legacy Shape',
      contractVersion: PLUGIN_CONTRACT_VERSION,
      tagNamespace: 'legacy-shape',
      capabilities: {},
      register(host) {
        host.registration.registerSurfacePort('legacy.port', () => null);
        if (!customElements.get('legacy-shape-elem')) {
          class LegacyElem extends HTMLElement {}
          customElements.define('legacy-shape-elem', LegacyElem);
          elementRegistered = true;
        }
        // No return value
      },
    };
    registry.install(manifest);
    expect(elementRegistered).toBe(true);
    expect(registry.get('legacy-shape')).toBeDefined();
    expect(registry.get('legacy-shape')?.registerError).toBeNull();
  });

  it('contribution returning is atomic with register error', () => {
    // If register throws AFTER returning a contribution... actually,
    // return short-circuits exceptions, so this test verifies that
    // a register that THROWS instead of returning leaves no state.
    const manifest = makeManifest({
      id: 'cont-throw',
      contribution: () => {
        throw new Error('plugin author error');
      },
    });
    registry.install(manifest);
    // Plugin landed with error; no contribution applied.
    expect(registry.get('cont-throw')?.registerError).not.toBeNull();
    expect(customElements.get('cont-throw-anything')).toBeUndefined();
  });
});
