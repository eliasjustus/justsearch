// @vitest-environment happy-dom

/**
 * 478 §4.A — SurfaceFactory tests.
 *
 * Verifies the architectural keystone: catalog-minted factory
 * encapsulates dispatch; consumers can't bypass to construct
 * elements directly from arbitrary tag strings.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  __seedForTest,
  __resetForTest,
  getSurface,
  listSurfaces,
  mergePluginSurfaceContributions,
  mountSurface,
  removePluginSurfaceContributions,
} from './SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../types/surface.js';
import { makePluginProvenance } from '../../shell-v0/primitives/provenance.js';

class TestElement extends HTMLElement {}
class TestPluginElement extends HTMLElement {}

const FIXTURE: SurfaceCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'Surface',
  entries: [
    {
      id: 'core.test-surface',
      presentation: { labelKey: 'test', descriptionKey: 'test.desc' },
      audience: 'USER',
      placement: 'RAIL',
      consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
      mountTag: 'jf-sf-test',
      provenance: { tier: 'CORE', contributorId: 'core', version: '1' },
    } satisfies Surface,
  ],
};

describe('SurfaceFactory — minting at catalog boundary', () => {
  beforeEach(() => {
    __resetForTest();
    if (!customElements.get('jf-sf-test')) {
      customElements.define('jf-sf-test', TestElement);
    }
    if (!customElements.get('plugin-sf-test-elem')) {
      customElements.define('plugin-sf-test-elem', TestPluginElement);
    }
  });

  afterEach(() => {
    __resetForTest();
  });

  it('factory is stamped on every Surface returned by getSurface', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface');
    expect(surface).toBeDefined();
    expect(surface!.factory).toBeDefined();
    expect(typeof surface!.factory!.mount).toBe('function');
    expect(typeof surface!.factory!.__dispatchBrand).toBe('symbol');
  });

  it('factory is stamped on every Surface returned by listSurfaces', () => {
    __seedForTest(FIXTURE);
    const all = listSurfaces();
    for (const surface of all) {
      expect(surface.factory).toBeDefined();
    }
  });

  it('factory.mount() returns an instance of the registered class', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    const el = surface.factory!.mount();
    expect(el).toBeInstanceOf(TestElement);
    expect(el).toBeInstanceOf(HTMLElement);
  });

  it('factory.mount() applies api-base when provided', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    const el = surface.factory!.mount({ apiBase: 'http://test:9999' });
    expect(el.getAttribute('api-base')).toBe('http://test:9999');
  });

  it('factory.mount() throws when the registered class is missing', () => {
    const ghost: SurfaceCatalog = {
      ...FIXTURE,
      entries: [
        {
          ...FIXTURE.entries[0]!,
          id: 'core.ghost-surface',
          mountTag: 'jf-sf-not-registered',
        },
      ],
    };
    __seedForTest(ghost);
    const surface = getSurface('core.ghost-surface')!;
    expect(() => surface.factory!.mount()).toThrow(/not registered/);
  });

  it('plugin contributions also get factory stamped at merge time', () => {
    __seedForTest(FIXTURE);
    mergePluginSurfaceContributions([
      {
        pluginId: 'plugin-sf-test',
        contribution: {
          id: 'plugin-sf-test.surface',
          mountTag: 'plugin-sf-test-elem',
          labelKey: 'p.label',
          descriptionKey: 'p.desc',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {},
        },
        effectiveAudience: 'USER',
        provenance: makePluginProvenance('plugin-sf-test', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    const surface = getSurface('plugin-sf-test.surface');
    expect(surface).toBeDefined();
    expect(surface!.factory).toBeDefined();
    const el = surface!.factory!.mount();
    expect(el).toBeInstanceOf(TestPluginElement);
  });

  it('factory survives plugin uninstall→reinstall (re-stamped)', () => {
    __seedForTest(FIXTURE);
    mergePluginSurfaceContributions([
      {
        pluginId: 'plugin-sf-test',
        contribution: {
          id: 'plugin-sf-test.surface',
          mountTag: 'plugin-sf-test-elem',
          labelKey: 'p.label',
          descriptionKey: 'p.desc',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {},
        },
        effectiveAudience: 'USER',
        provenance: makePluginProvenance('plugin-sf-test', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    const first = getSurface('plugin-sf-test.surface');
    expect(first?.factory).toBeDefined();
    removePluginSurfaceContributions('plugin-sf-test');
    expect(getSurface('plugin-sf-test.surface')).toBeUndefined();
    // Re-add
    mergePluginSurfaceContributions([
      {
        pluginId: 'plugin-sf-test',
        contribution: {
          id: 'plugin-sf-test.surface',
          mountTag: 'plugin-sf-test-elem',
          labelKey: 'p.label',
          descriptionKey: 'p.desc',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {},
        },
        effectiveAudience: 'USER',
        provenance: makePluginProvenance('plugin-sf-test', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    const second = getSurface('plugin-sf-test.surface');
    expect(second?.factory).toBeDefined();
    expect(second?.factory).not.toBe(first?.factory); // fresh mint
  });

  /**
   * Architectural property: the SurfaceFactory's __dispatchBrand
   * is a module-private Symbol. Consumers cannot construct a
   * SurfaceFactory because they cannot reach the symbol.
   *
   * This test verifies the brand exists and is a Symbol — it
   * doesn't test "can't construct" because TypeScript doesn't
   * enforce structural typing for Symbols. The actual enforcement
   * is at the type level: any consumer trying to build a
   * SurfaceFactory needs the SurfaceFactory type, but the brand
   * field requires the catalog's private symbol.
   */
  it('factory carries a Symbol-typed dispatch brand', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    expect(typeof surface.factory!.__dispatchBrand).toBe('symbol');
    // The same Symbol instance for every catalog entry (module-private).
    const all = listSurfaces();
    const brands = new Set(all.map((s) => s.factory!.__dispatchBrand));
    expect(brands.size).toBe(1);
  });

  it('mountSurface() succeeds for catalog-minted factories', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    const el = mountSurface(surface, { apiBase: 'http://test:9999' });
    expect(el).toBeInstanceOf(TestElement);
    expect(el!.getAttribute('api-base')).toBe('http://test:9999');
  });

  it('mountSurface() returns null for surfaces without factory', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    // Construct a Surface variant without the catalog-minted factory.
    const noFactorySurface = { ...surface, factory: undefined };
    expect(mountSurface(noFactorySurface)).toBeNull();
  });

  it('mountSurface() rejects forged factories with wrong brand', () => {
    __seedForTest(FIXTURE);
    const surface = getSurface('core.test-surface')!;
    // Construct a fake factory with a fake brand symbol.
    const forgedFactory = {
      __dispatchBrand: Symbol('not-the-real-brand'),
      mount: () => document.createElement('div'),
    };
    const forgedSurface = { ...surface, factory: forgedFactory };
    expect(() => mountSurface(forgedSurface)).toThrow(/__dispatchBrand/);
  });

  /**
   * Reviewer-pass finding: the original "wrong brand" test only
   * exercised the easy case (different Symbol). A consumer with
   * access to ANY catalog-minted factory can READ
   * `factory.__dispatchBrand` (it's a public field) and forge
   * a new factory with the same Symbol. The Symbol-identity
   * check passes; the WeakSet membership check rejects.
   *
   * This is the actual attack vector the doc-comment claims to
   * defend against.
   */
  it('mountSurface() rejects forged factories with STOLEN brand (reviewer-pass)', () => {
    __seedForTest(FIXTURE);
    const realSurface = getSurface('core.test-surface')!;
    // Steal the brand from the real factory.
    const stolenBrand = realSurface.factory!.__dispatchBrand;
    expect(typeof stolenBrand).toBe('symbol');
    // Forge a factory with the stolen brand. Type system accepts
    // it (it has __dispatchBrand: symbol + mount); runtime
    // WeakSet check should reject.
    const forgedFactory = {
      __dispatchBrand: stolenBrand,
      mount: () => document.createElement('div'),
    };
    const forgedSurface = { ...realSurface, factory: forgedFactory };
    // The Symbol identity check passes here, so the failure must
    // come from VALID_FACTORIES.has(factory) returning false.
    expect(() => mountSurface(forgedSurface)).toThrow(
      /not in VALID_FACTORIES|forged/,
    );
  });
});

describe('mergePluginSurfaceContributions — splitPairing (tempdoc 521 §22 Phase D)', () => {
  beforeEach(() => {
    __resetForTest();
  });

  it('passes splitPairing through to the catalog Surface entry', () => {
    mergePluginSurfaceContributions([
      {
        pluginId: 'plugin-split-test',
        contribution: {
          id: 'plugin-split-test.left',
          mountTag: 'plugin-sf-test-elem',
          labelKey: 'l.label',
          descriptionKey: 'l.desc',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {},
          splitPairing: { secondary: 'plugin-split-test.right' },
        },
        effectiveAudience: 'USER',
        provenance: makePluginProvenance('plugin-split-test', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    const left = getSurface('plugin-split-test.left');
    expect(left).toBeDefined();
    expect(left!.splitPairing).toEqual({ secondary: 'plugin-split-test.right' });
  });

  it('omits splitPairing on surfaces that do not declare one', () => {
    mergePluginSurfaceContributions([
      {
        pluginId: 'plugin-split-test',
        contribution: {
          id: 'plugin-split-test.no-pair',
          mountTag: 'plugin-sf-test-elem',
          labelKey: 'x.label',
          descriptionKey: 'x.desc',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {},
        },
        effectiveAudience: 'USER',
        provenance: makePluginProvenance('plugin-split-test', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    const surface = getSurface('plugin-split-test.no-pair');
    expect(surface).toBeDefined();
    expect(surface!.splitPairing).toBeUndefined();
  });
});
