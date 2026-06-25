// @vitest-environment happy-dom

/**
 * Tests for the V1.1 PluginRegistry contract. V1 covered: install
 * idempotency, registerError isolation, surface-port dispatch
 * semantics, capability filtering. V1.1 (slice 3a.1.6) adds:
 * uninstall round-trip, contractVersion validation, tagNamespace
 * validation, unregister-error isolation.
 */

import { describe, expect, it, vi } from 'vitest';
import { PluginRegistry, audienceFloorForTier } from './PluginRegistry.js';
import {
  getSurface,
  __resetForTest as __resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  getResource,
  __resetForTest as __resetResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import {
  PLUGIN_CONTRACT_VERSION,
  type PluginManifest,
  type PluginSurfaceContribution,
} from './plugin-types.js';
import {
  getViewFactory,
  __resetViewFactoryRegistryForTest,
} from '../router/viewFactoryRegistry.js';
import { displayTier } from '../primitives/provenance.js';

function makeManifest(
  id: string,
  overrides?: Partial<PluginManifest>,
): PluginManifest {
  return {
    id,
    version: '1.0.0',
    displayName: id,
    capabilities: {},
    register: () => {},
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: id,
    ...overrides,
  };
}

describe('PluginRegistry — install', () => {
  it('records installed plugins keyed by id', () => {
    const reg = new PluginRegistry();
    reg.install(makeManifest('alpha'));
    reg.install(makeManifest('beta'));
    expect(reg.has('alpha')).toBe(true);
    expect(reg.has('beta')).toBe(true);
    expect(reg.list()).toHaveLength(2);
  });

  it('rejects duplicate ids with a clear error', () => {
    const reg = new PluginRegistry();
    reg.install(makeManifest('alpha'));
    expect(() => reg.install(makeManifest('alpha'))).toThrow(
      /already installed/i,
    );
  });

  it('captures register-throw without breaking the registry', () => {
    const reg = new PluginRegistry();
    const manifest = makeManifest('boom', {
      register: () => {
        throw new Error('register failed');
      },
    });
    reg.install(manifest);
    const entry = reg.get('boom');
    expect(entry?.registerError).not.toBeNull();
    expect(entry?.registerError?.message).toMatch(/register failed/);
    // .has() returns false for a register-failed plugin (it's installed
    // but skipped for dispatch).
    expect(reg.has('boom')).toBe(false);
    // Subsequent installs still work.
    reg.install(makeManifest('alpha'));
    expect(reg.has('alpha')).toBe(true);
  });
});

describe('PluginRegistry — ConversationShape runner (tempdoc 560 §28.G)', () => {
  const shapeManifest = (id: string, shapes: Array<{ id: string; viewTag?: string }>) =>
    makeManifest(id, {
      register: () => ({
        conversationShapes: shapes.map((s) => ({
          contribution: s.viewTag ? { id: s.id, viewTag: s.viewTag } : { id: s.id },
        })),
      }),
    });

  it('registers a view factory for a TRUSTED plugin shape with a viewTag', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('shaper', [{ id: 'vendor.shaper.demo', viewTag: 'shaper-view' }]),
      'TRUSTED_PLUGIN',
    );
    expect(reg.get('shaper')?.registerError ?? null).toBeNull();
    expect(getViewFactory('vendor.shaper.demo')).toBeDefined();
  });

  it('registers nothing for a declaration-only shape (no viewTag)', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('shaper', [{ id: 'vendor.shaper.declonly' }]),
      'TRUSTED_PLUGIN',
    );
    expect(getViewFactory('vendor.shaper.declonly')).toBeUndefined();
  });

  it('DROPS an UNTRUSTED shape that mounts its own (non-jf-*) element, install still succeeds (§4.4)', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('shaper', [{ id: 'vendor.shaper.demo', viewTag: 'shaper-view' }]),
      'UNTRUSTED_PLUGIN',
    );
    expect(reg.get('shaper')?.registerError ?? null).toBeNull();
    expect(getViewFactory('vendor.shaper.demo')).toBeUndefined();
  });

  it('ALLOWS an UNTRUSTED shape that mounts the constrained jf-* vocabulary', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('shaper', [{ id: 'vendor.shaper.jf', viewTag: 'jf-free-chat-view' }]),
      'UNTRUSTED_PLUGIN',
    );
    expect(getViewFactory('vendor.shaper.jf')).toBeDefined();
  });

  it('refuses a core.* shape (view-hijack guard) as a registerError; the core factory is untouched', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('evil', [{ id: 'core.unified-chat', viewTag: 'evil-view' }]),
      'TRUSTED_PLUGIN',
    );
    expect(reg.get('evil')?.registerError?.message).toMatch(/must be namespaced 'vendor\.\*'/);
    expect(getViewFactory('core.unified-chat')).toBeUndefined();
  });

  it('does not let one plugin hijack another plugin already-owned vendor shape factory', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('alpha', [{ id: 'vendor.shared.shape', viewTag: 'alpha-view' }]),
      'TRUSTED_PLUGIN',
    );
    const victim = getViewFactory('vendor.shared.shape');
    reg.install(
      shapeManifest('beta', [{ id: 'vendor.shared.shape', viewTag: 'beta-view' }]),
      'TRUSTED_PLUGIN',
    );
    // beta installs fine, but the shared shape's factory is NOT replaced (cross-owner refusal).
    expect(reg.get('beta')?.registerError ?? null).toBeNull();
    expect(getViewFactory('vendor.shared.shape')).toBe(victim);
  });

  it('withdraws a plugin shape view factory on uninstall (symmetric teardown)', () => {
    __resetViewFactoryRegistryForTest();
    const reg = new PluginRegistry();
    reg.install(
      shapeManifest('shaper', [{ id: 'vendor.shaper.demo', viewTag: 'shaper-view' }]),
      'TRUSTED_PLUGIN',
    );
    expect(getViewFactory('vendor.shaper.demo')).toBeDefined();
    reg.uninstall('shaper');
    expect(getViewFactory('vendor.shaper.demo')).toBeUndefined();
  });
});

describe('PluginRegistry — surface port dispatch', () => {
  it('dispatches the first plugin that registered the port', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('health.condition', () => {
            const el = document.createElement('div');
            el.textContent = 'alpha';
            return el;
          }),
      }),
    );
    const result = reg.dispatchSurfacePort('health.condition');
    expect(result?.textContent).toBe('alpha');
  });

  it('returns null when no plugin registered the port', () => {
    const reg = new PluginRegistry();
    expect(reg.dispatchSurfacePort('unregistered.port')).toBeNull();
  });

  it('skips a handler that throws and tries the next', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => {
            throw new Error('alpha failed');
          }),
      }),
    );
    reg.install(
      makeManifest('beta', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => {
            const el = document.createElement('div');
            el.textContent = 'beta';
            return el;
          }),
      }),
    );
    const result = reg.dispatchSurfacePort('p');
    expect(result?.textContent).toBe('beta');
  });

  it('skips a handler that returns null and tries the next', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => null),
      }),
    );
    reg.install(
      makeManifest('beta', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => {
            const el = document.createElement('div');
            el.textContent = 'beta';
            return el;
          }),
      }),
    );
    const result = reg.dispatchSurfacePort('p');
    expect(result?.textContent).toBe('beta');
  });

  it('passes the port id and payload through to handlers', () => {
    const reg = new PluginRegistry();
    const captured: Array<{ portId: string; payload?: unknown }> = [];
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', (ctx) => {
            captured.push(ctx);
            return null;
          }),
      }),
    );
    reg.dispatchSurfacePort('p', { foo: 'bar' });
    expect(captured).toHaveLength(1);
    expect(captured[0]?.portId).toBe('p');
    expect(captured[0]?.payload).toEqual({ foo: 'bar' });
  });
});

describe('PluginRegistry — capability filtering', () => {
  it('filters plugins by capability predicate', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        capabilities: { customElementTags: ['x-foo'] },
      }),
    );
    reg.install(
      makeManifest('beta', {
        capabilities: { surfacePorts: ['health.condition'] },
      }),
    );
    reg.install(makeManifest('gamma'));

    const tagPlugins = reg.byCapability(
      (c) => (c.customElementTags?.length ?? 0) > 0,
    );
    expect(tagPlugins.map((p) => p.manifest.id)).toEqual(['alpha']);

    const portPlugins = reg.byCapability(
      (c) => (c.surfacePorts?.length ?? 0) > 0,
    );
    expect(portPlugins.map((p) => p.manifest.id)).toEqual(['beta']);
  });

  it('excludes register-failed plugins from capability queries', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        capabilities: { customElementTags: ['x-foo'] },
        register: () => {
          throw new Error('boom');
        },
      }),
    );
    expect(
      reg.byCapability((c) => (c.customElementTags?.length ?? 0) > 0),
    ).toEqual([]);
  });
});

// ============================================================================
// V1.1 (slice 3a.1.6): uninstall + contractVersion + tagNamespace.
// ============================================================================

describe('PluginRegistry — uninstall (V1.1)', () => {
  it('removes the plugin and surface-port handlers; subsequent dispatches return null', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () =>
            document.createElement('div'),
          ),
      }),
    );
    expect(reg.dispatchSurfacePort('p')).not.toBeNull();
    expect(reg.uninstall('alpha')).toBe(true);
    expect(reg.dispatchSurfacePort('p')).toBeNull();
    expect(reg.has('alpha')).toBe(false);
  });

  it('returns false when no plugin with that id is installed', () => {
    const reg = new PluginRegistry();
    expect(reg.uninstall('ghost')).toBe(false);
  });

  it('calls unregister with the host API when the plugin declares one', () => {
    const reg = new PluginRegistry();
    const unregister = vi.fn();
    reg.install(
      makeManifest('alpha', {
        register: () => {},
        unregister,
      }),
    );
    reg.uninstall('alpha');
    expect(unregister).toHaveBeenCalledTimes(1);
    expect(unregister).toHaveBeenCalledWith(
      expect.objectContaining({
        registration: expect.objectContaining({
          registerSurfacePort: expect.any(Function),
        }),
        installedTagNamespace: 'alpha',
      }),
    );
  });

  it('treats absent unregister as a no-op (V1 plugin upgrade path)', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () =>
            document.createElement('div'),
          ),
      }),
    );
    expect(() => reg.uninstall('alpha')).not.toThrow();
    expect(reg.has('alpha')).toBe(false);
  });

  it('catches unregister errors and still cleans up registry state', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () =>
            document.createElement('div'),
          ),
        unregister: () => {
          throw new Error('teardown boom');
        },
      }),
    );
    expect(() => reg.uninstall('alpha')).not.toThrow();
    expect(reg.has('alpha')).toBe(false);
    expect(reg.dispatchSurfacePort('p')).toBeNull();
  });

  it('uninstall removes only the targeted plugin, leaving other plugins intact', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => {
            const el = document.createElement('div');
            el.textContent = 'alpha';
            return el;
          }),
      }),
    );
    reg.install(
      makeManifest('beta', {
        register: (host) =>
          host.registration.registerSurfacePort('p', () => {
            const el = document.createElement('div');
            el.textContent = 'beta';
            return el;
          }),
      }),
    );
    reg.uninstall('alpha');
    expect(reg.has('beta')).toBe(true);
    // Beta's handler still fires on the shared port.
    expect(reg.dispatchSurfacePort('p')?.textContent).toBe('beta');
  });
});

describe('PluginRegistry — install validation (V1.1)', () => {
  it('rejects manifests with a tagNamespace that does not equal id', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(makeManifest('alpha', { tagNamespace: 'beta' })),
    ).toThrow(/tagNamespace.*must equal id/i);
  });

  it('rejects manifests missing contractVersion', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersion: '' as unknown as string,
        }),
      ),
    ).toThrow(/contractVersion/i);
  });

  it('rejects manifests targeting a future major contractVersion', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(makeManifest('alpha', { contractVersion: '2.0' })),
    ).toThrow(/Major version mismatch/i);
  });

  it('rejects manifests targeting an older major contractVersion', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(makeManifest('alpha', { contractVersion: '0.9' })),
    ).toThrow(/Major version mismatch/i);
  });

  it('rejects manifests targeting a higher minor than the host', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(makeManifest('alpha', { contractVersion: '1.99' })),
    ).toThrow(/newer host than is running/i);
  });

  it('accepts manifests targeting the same major + lower minor', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(makeManifest('alpha', { contractVersion: '1.0' })),
    ).not.toThrow();
    expect(reg.has('alpha')).toBe(true);
  });

  it('rejects manifests with malformed contractVersion', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(
        makeManifest('alpha', { contractVersion: 'not-a-version' }),
      ),
    ).toThrow(/malformed contractVersion/i);
  });

  it('exposes installedTagNamespace on the host API matching manifest.id', () => {
    const reg = new PluginRegistry();
    let observed = '';
    reg.install(
      makeManifest('alpha', {
        register: (host) => {
          observed = host.installedTagNamespace;
        },
      }),
    );
    expect(observed).toBe('alpha');
  });
});

describe('PluginRegistry — §11.8 per-sub-API contractVersions', () => {
  it('accepts a plugin declaring host.search at the advertised version', () => {
    const reg = new PluginRegistry();
    reg.setHostContractVersions({
      wire: '1.0',
      'host.search': '1.0',
      'host.data': '1.0',
    });
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.search': '1.0' },
        }),
      ),
    ).not.toThrow();
    expect(reg.has('alpha')).toBe(true);
  });

  it('accepts a plugin declaring a lower minor than the host advertises', () => {
    const reg = new PluginRegistry();
    reg.setHostContractVersions({ 'host.search': '1.5' });
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.search': '1.0' },
        }),
      ),
    ).not.toThrow();
  });

  it('rejects a plugin requiring a higher minor than the host', () => {
    const reg = new PluginRegistry();
    reg.setHostContractVersions({ 'host.search': '1.0' });
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.search': '1.5' },
        }),
      ),
    ).toThrow(/newer host/i);
  });

  it('rejects a plugin requiring a different major than the host', () => {
    const reg = new PluginRegistry();
    reg.setHostContractVersions({ 'host.search': '1.0' });
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.search': '2.0' },
        }),
      ),
    ).toThrow(/Major version mismatch/i);
  });

  it('rejects a plugin declaring a sub-API the host does not advertise', () => {
    const reg = new PluginRegistry();
    reg.setHostContractVersions({ 'host.search': '1.0' });
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.unknown': '1.0' },
        }),
      ),
    ).toThrow(/does not advertise/i);
  });

  it('rejects when plugin declares contractVersions but host has not supplied them', () => {
    const reg = new PluginRegistry();
    expect(() =>
      reg.install(
        makeManifest('alpha', {
          contractVersions: { 'host.search': '1.0' },
        }),
      ),
    ).toThrow(/setHostContractVersions/);
  });

  it('accepts a plugin without contractVersions even when host has none supplied', () => {
    const reg = new PluginRegistry();
    expect(() => reg.install(makeManifest('alpha'))).not.toThrow();
  });
});

describe('PluginRegistry — surface contributions (slice 449 phase 12)', () => {
  function surface(
    id: string,
    audience: PluginSurfaceContribution['audience'] = 'USER',
  ): PluginSurfaceContribution {
    return {
      id,
      mountTag: `jf-${id.replace('.', '-')}-surface`,
      labelKey: `${id}.label`,
      descriptionKey: `${id}.description`,
      audience,
      placement: 'RAIL',
    };
  }

  it('surfaceContributions returns plugin surfaces with effective audience applied', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        capabilities: { surfaces: [surface('alpha.dashboard', 'USER')] },
      }),
    );
    const got = reg.surfaceContributions();
    expect(got).toHaveLength(1);
    expect(got[0]!.contribution.id).toBe('alpha.dashboard');
    // V1: TRUSTED_PLUGIN floor = USER, so USER stays USER
    expect(got[0]!.effectiveAudience).toBe('USER');
  });

  it('drops an UNTRUSTED plugin surface that mounts its own (non-jf) element — §4.4 PRESENTATION', () => {
    const reg = new PluginRegistry();
    const ownElement = {
      id: 'acme.own',
      mountTag: 'acme-panel', // not a host jf-* vocabulary component
      labelKey: 'acme.own.label',
      descriptionKey: 'acme.own.description',
      audience: 'USER' as const,
      placement: 'RAIL' as const,
    };
    const vocab = surface('acme.vocab', 'USER'); // jf-* mountTag — allowed even untrusted
    reg.install(
      makeManifest('acme', { capabilities: { surfaces: [ownElement, vocab] } }),
      'UNTRUSTED_PLUGIN',
    );
    const ids = reg.surfaceContributions().map((s) => s.contribution.id);
    expect(ids).toContain('acme.vocab'); // host vocabulary kept
    expect(ids).not.toContain('acme.own'); // second presentation authority dropped
  });

  it('keeps a TRUSTED plugin surface that mounts its own element', () => {
    const reg = new PluginRegistry();
    const ownElement = {
      id: 'acme.own',
      mountTag: 'acme-panel',
      labelKey: 'acme.own.label',
      descriptionKey: 'acme.own.description',
      audience: 'USER' as const,
      placement: 'RAIL' as const,
    };
    reg.install(
      makeManifest('acme', { capabilities: { surfaces: [ownElement] } }),
      'TRUSTED_PLUGIN',
    );
    expect(reg.surfaceContributions().map((s) => s.contribution.id)).toContain('acme.own');
  });

  it('skips contributions from plugins that errored during register()', () => {
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('beta', {
        capabilities: { surfaces: [surface('beta.dashboard')] },
        register: () => {
          throw new Error('boom');
        },
      }),
    );
    expect(reg.surfaceContributions()).toHaveLength(0);
  });

  describe('audienceFloorForTier', () => {
    it('CORE / TRUSTED_PLUGIN: USER stays USER', () => {
      expect(audienceFloorForTier('USER', 'CORE')).toBe('USER');
      expect(audienceFloorForTier('USER', 'TRUSTED_PLUGIN')).toBe('USER');
    });

    it('UNTRUSTED_PLUGIN: USER promotes to OPERATOR', () => {
      expect(audienceFloorForTier('USER', 'UNTRUSTED_PLUGIN')).toBe('OPERATOR');
    });

    it('UNTRUSTED_PLUGIN: OPERATOR / DEVELOPER pass through', () => {
      expect(audienceFloorForTier('OPERATOR', 'UNTRUSTED_PLUGIN')).toBe('OPERATOR');
      expect(audienceFloorForTier('DEVELOPER', 'UNTRUSTED_PLUGIN')).toBe('DEVELOPER');
    });

    it('AGENT is unorderable; pass-through across tiers', () => {
      expect(audienceFloorForTier('AGENT', 'CORE')).toBe('AGENT');
      expect(audienceFloorForTier('AGENT', 'UNTRUSTED_PLUGIN')).toBe('AGENT');
    });
  });
});

describe('PluginRegistry — i18n translations (slice 471 V1.5.1)', () => {
  // Use dynamic import so the test can reset the resourceCatalog
  // module state; the registry imports it eagerly so we use the
  // exposed reset helper instead.
  it('install merges plugin translations into the resource catalog', async () => {
    const { __resetForTest, __seedForTest, localizeResourceKey } =
      await import('../../i18n/resourceCatalog.js');
    __resetForTest();
    __seedForTest({});
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        translations: {
          en: {
            'alpha.surface.label': 'Alpha Surface',
            'alpha.surface.description': 'A description',
          },
        },
      }),
    );
    expect(localizeResourceKey('alpha.surface.label')).toBe('Alpha Surface');
    expect(localizeResourceKey('alpha.surface.description')).toBe(
      'A description',
    );
    __resetForTest();
  });

  it('uninstall removes plugin-contributed translations', async () => {
    const { __resetForTest, __seedForTest, localizeResourceKey } =
      await import('../../i18n/resourceCatalog.js');
    __resetForTest();
    __seedForTest({});
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        translations: { en: { 'alpha.k1': 'V1' } },
      }),
    );
    expect(localizeResourceKey('alpha.k1')).toBe('V1');
    reg.uninstall('alpha');
    // After uninstall, key falls back to the raw-key passthrough.
    expect(localizeResourceKey('alpha.k1')).toBe('alpha.k1');
    __resetForTest();
  });

  it('does not overwrite host-fetched catalog entries', async () => {
    const { __resetForTest, __seedForTest, localizeResourceKey } =
      await import('../../i18n/resourceCatalog.js');
    __resetForTest();
    __seedForTest({ 'shared.label': 'Host Label' });
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('alpha', {
        translations: { en: { 'shared.label': 'Plugin Label' } },
      }),
    );
    // Host catalog wins.
    expect(localizeResourceKey('shared.label')).toBe('Host Label');
    __resetForTest();
  });

  it('plugins without translations field still install cleanly', () => {
    const reg = new PluginRegistry();
    expect(() => reg.install(makeManifest('alpha'))).not.toThrow();
    expect(reg.has('alpha')).toBe(true);
  });
});

describe('Tempdoc 543 §20.7 C1 — multi-axis Provenance producer', () => {
  it('stamps plugin contributions with identity.verified=true (TRUSTED_PLUGIN)', async () => {
    const { listStatusBarItems, __resetForTest: __resetSB } = await import(
      '../commands/StatusBarRegistry.js'
    );
    __resetSB();
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('acme', {
        version: '2.5.0',
        register: () => ({
          statusBarItems: [
            {
              id: 'demo',
              position: 'right',
              priority: 50,
              render: () => 'demo',
            },
          ],
        }),
      }),
    );
    const item = listStatusBarItems('right').find(
      (i) => i.id === 'acme.demo',
    );
    expect(item).toBeDefined();
    expect(item!.provenance?.tier).toBe('TRUSTED_PLUGIN');
    expect(item!.provenance?.contributorId).toBe('acme');
    expect(item!.provenance?.version).toBe('2.5.0');
    expect(item!.provenance?.identity?.verified).toBe(true);
    expect(item!.provenance?.installedAt).toBeDefined();
    __resetSB();
  });

  it('carries manifest.capabilities into provenance.capability', async () => {
    const { listStatusBarItems, __resetForTest: __resetSB } = await import(
      '../commands/StatusBarRegistry.js'
    );
    __resetSB();
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('acme-2', {
        capabilities: {
          surfaces: [],
          // Mark a flag-shaped capability key so the producer picks it up.
          someFeature: true,
        } as unknown as PluginManifest['capabilities'],
        register: () => ({
          statusBarItems: [
            {
              id: 'demo',
              position: 'right',
              priority: 50,
              render: () => 'demo',
            },
          ],
        }),
      }),
    );
    const item = listStatusBarItems('right').find(
      (i) => i.id === 'acme-2.demo',
    );
    expect(item).toBeDefined();
    // Both 'surfaces' and 'someFeature' are present keys on capabilities.
    expect(item!.provenance?.capability).toBeDefined();
    expect((item!.provenance!.capability ?? []).length).toBeGreaterThan(0);
    __resetSB();
  });
});

/**
 * 548 §4.3 — uniform Provenance collapse. The merge-axis catalogs (surface /
 * resource) used to RECONSTRUCT a lossy {tier, contributorId, version} partial at
 * the merge site, dropping identity/capability/installedAt and hardcoding
 * version '0.0.0'. They now store the single Provenance minted once at the
 * install site verbatim. This proves the minted multi-axis value reaches the
 * catalog through the full install path.
 */
describe('PluginRegistry — uniform Provenance reaches the merge catalogs (548 §4.3)', () => {
  it('a contributed surface carries the minted multi-axis Provenance, not a lossy reconstruction', () => {
    __resetSurfaceCatalog();
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('vendor.acme', {
        version: '2.4.0',
        capabilities: { customElementTags: ['x-foo'], surfacePorts: ['p.q'] },
        register: () => ({
          surfaceContributions: [
            {
              contribution: {
                id: 'vendor.acme.surface',
                mountTag: 'vendor.acme-elem',
                labelKey: 'l',
                descriptionKey: 'd',
                audience: 'USER',
                placement: 'RAIL',
              },
            },
          ],
        }),
      }),
    );
    const prov = getSurface('vendor.acme.surface')?.provenance;
    expect(prov?.contributorId).toBe('vendor.acme');
    // The OLD reconstruction hardcoded version '0.0.0' and dropped the rest.
    expect(prov?.version).toBe('2.4.0');
    expect(prov?.identity?.verified).toBe(true);
    expect(prov?.capability).toEqual(
      expect.arrayContaining(['customElementTags', 'surfacePorts']),
    );
    // installedAt is an ISO timestamp stamped at install (§25.α7); the old
    // reconstruction omitted it entirely. Assert it is present + non-empty.
    expect(typeof prov?.installedAt).toBe('string');
    expect((prov?.installedAt ?? '').length).toBeGreaterThan(0);
  });

  it('a contributed resource carries the same minted multi-axis Provenance (§4.3 b)', () => {
    __resetResourceCatalog();
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('vendor.res', {
        version: '3.1.4',
        capabilities: { surfacePorts: ['r.s'] },
        register: () => ({
          resourceContributions: [
            {
              contribution: {
                id: 'vendor.res.metrics',
                presentation: {
                  labelKey: 'l',
                  descriptionKey: 'd',
                  iconHint: null,
                  category: null,
                },
                schema: 'https://ssot.justsearch/v1/schemas/vendor.res.metrics.v1.json',
                category: 'TABULAR',
                subscriptionMode: 'SSE_STREAM',
                endpoint: '/api/vendor.res.metrics/stream',
                kind: 'metrics-table',
                history: null,
                recovery: null,
                privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
                itemOperations: [],
                collectionOperations: [],
                primaryKey: 'id',
                audience: 'USER',
                consumers: [],
                role: 'PRODUCT',
              },
            },
          ],
        }),
      }),
    );
    const prov = getResource('vendor.res.metrics')?.provenance;
    // The resource merge path used to rebuild a lossy {tier, contributorId,
    // version} partial; it now stores the one minted multi-axis Provenance.
    expect(prov?.contributorId).toBe('vendor.res');
    expect(prov?.version).toBe('3.1.4');
    expect(prov?.identity?.verified).toBe(true);
    expect(prov?.capability).toEqual(expect.arrayContaining(['surfacePorts']));
    expect(typeof prov?.installedAt).toBe('string');
  });

  it('an UNTRUSTED plugin resource is stamped UNTRUSTED_PLUGIN, not a forged VERIFIED (560 §28.G)', () => {
    // Regression for the provenance-tier mis-stamp: `applyContribution` used to hardcode the stored
    // Provenance to TRUSTED_PLUGIN + identity.verified=true regardless of the plugin's real tier.
    // Resources/aggregates store this Provenance verbatim (no re-derivation at enumeration, unlike
    // surfaces), so a URL-loaded UNTRUSTED plugin's resource would render as VERIFIED in chrome.
    __resetResourceCatalog();
    const reg = new PluginRegistry();
    reg.install(
      makeManifest('vendor.untrusted', {
        version: '0.0.1',
        capabilities: { surfacePorts: ['u.s'] },
        register: () => ({
          resourceContributions: [
            {
              contribution: {
                id: 'vendor.untrusted.metrics',
                presentation: {
                  labelKey: 'l',
                  descriptionKey: 'd',
                  iconHint: null,
                  category: null,
                },
                schema:
                  'https://ssot.justsearch/v1/schemas/vendor.untrusted.metrics.v1.json',
                category: 'TABULAR',
                subscriptionMode: 'SSE_STREAM',
                endpoint: '/api/vendor.untrusted.metrics/stream',
                kind: 'metrics-table',
                history: null,
                recovery: null,
                privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
                itemOperations: [],
                collectionOperations: [],
                primaryKey: 'id',
                audience: 'USER',
                consumers: [],
                role: 'PRODUCT',
              },
            },
          ],
        }),
      }),
      'UNTRUSTED_PLUGIN',
    );
    const prov = getResource('vendor.untrusted.metrics')?.provenance;
    expect(prov?.contributorId).toBe('vendor.untrusted');
    // The mis-stamp: tier was TRUSTED_PLUGIN + verified true regardless of the real tier.
    expect(prov?.tier).toBe('UNTRUSTED_PLUGIN');
    expect(prov?.identity?.verified).toBe(false);
    // displayTier must collapse to UNTRUSTED — never the forged VERIFIED.
    expect(displayTier(prov!)).toBe('UNTRUSTED');
  });
});
