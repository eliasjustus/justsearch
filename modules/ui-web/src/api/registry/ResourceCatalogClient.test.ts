/**
 * Slice 3a.1.9 — ResourceCatalogClient tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Resource, ResourceCatalog } from '../types/registry';
import { makePluginProvenance } from '../../shell-v0/primitives/provenance.js';
import {
  __resetForTest,
  __seedForTest,
  bootResourceRegistry,
  getResource,
  listResources,
  listResourcesByCategory,
  mergePluginResourceContributions,
  onCatalogChange,
  removePluginResourceContributions,
} from './ResourceCatalogClient';

function tabularEntry(id: string): Resource {
  return {
    id,
    presentation: {
      labelKey: `registry-resource.${id}.label`,
      descriptionKey: `registry-resource.${id}.description`,
      iconHint: null,
      category: null,
    },
    schema: `https://ssot.justsearch/v1/schemas/${id}.v1.json`,
    category: 'TABULAR',
    subscriptionMode: 'SSE_STREAM',
    endpoint: `/api/${id}/stream`,
    kind: `${id}-table`,
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: 'id',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function stateEntry(id: string): Resource {
  return { ...tabularEntry(id), category: 'STATE', primaryKey: '' };
}

function catalogOf(...entries: Resource[]): ResourceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Resource',
    entries,
  };
}

describe('ResourceCatalogClient', () => {
  beforeEach(() => {
    __resetForTest();
  });
  afterEach(() => {
    __resetForTest();
  });

  describe('lookup API', () => {
    it('returns undefined for unknown id before boot', () => {
      expect(getResource('core.unknown')).toBeUndefined();
    });

    it('returns the seeded entry by id', () => {
      __seedForTest(catalogOf(tabularEntry('core.indexing-jobs')));
      const r = getResource('core.indexing-jobs');
      expect(r?.category).toBe('TABULAR');
      expect(r?.primaryKey).toBe('id');
    });

    it('listResources returns all entries', () => {
      __seedForTest(catalogOf(tabularEntry('a'), stateEntry('b')));
      expect(listResources().map((r) => r.id).sort()).toEqual(['a', 'b']);
    });

    it('listResourcesByCategory filters', () => {
      __seedForTest(catalogOf(tabularEntry('t1'), stateEntry('s1'), tabularEntry('t2')));
      expect(listResourcesByCategory('TABULAR').map((r) => r.id).sort()).toEqual(['t1', 't2']);
      expect(listResourcesByCategory('STATE').map((r) => r.id)).toEqual(['s1']);
      // HISTORY has no shipped Resource entry at slice 448 phase 6 close.
      expect(listResourcesByCategory('HISTORY')).toEqual([]);
    });
  });

  describe('boot fetch', () => {
    it('populates the catalog on 200', async () => {
      const catalog = catalogOf(tabularEntry('core.indexing-jobs'));
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(catalog),
        headers: new Headers({ ETag: 'W/"abc"' }),
      });

      await bootResourceRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
      expect(getResource('core.indexing-jobs')).toBeDefined();
      expect(fetchImpl).toHaveBeenCalledWith(
        'http://localhost/api/registry/resources',
        expect.objectContaining({ headers: expect.any(Object) }),
      );
    });

    it('handles non-200 gracefully (catalog stays empty)', async () => {
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: () => Promise.resolve({}),
        headers: new Headers(),
      });
      await bootResourceRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
      expect(listResources()).toEqual([]);
    });

    it('handles network error gracefully', async () => {
      const fetchImpl = vi.fn().mockRejectedValue(new Error('boom'));
      await bootResourceRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
      expect(listResources()).toEqual([]);
    });

    it('skips fetch when baseUrl is empty', async () => {
      const fetchImpl = vi.fn();
      await bootResourceRegistry('', fetchImpl as unknown as typeof fetch);
      expect(fetchImpl).not.toHaveBeenCalled();
    });
  });

  describe('change listeners', () => {
    it('fires after seed', () => {
      const listener = vi.fn();
      onCatalogChange(listener);
      __seedForTest(catalogOf(tabularEntry('a')));
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it('unsubscribe stops further calls', () => {
      const listener = vi.fn();
      const off = onCatalogChange(listener);
      off();
      __seedForTest(catalogOf(tabularEntry('a')));
      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe('plugin contributions (slice 447-impl-C)', () => {
    it('merge adds plugin Resources with provenance stamped', () => {
      __seedForTest(catalogOf(tabularEntry('core.indexing-jobs')));
      const { provenance: _omitted, ...withoutProv } = tabularEntry('vendor.acme.metrics');
      mergePluginResourceContributions([
        {
          pluginId: 'vendor.acme',
          contribution: withoutProv,
          provenance: makePluginProvenance('vendor.acme', '1.2.3', 'TRUSTED_PLUGIN'),
        },
      ]);
      const merged = getResource('vendor.acme.metrics');
      expect(merged).toBeDefined();
      expect(merged?.provenance.tier).toBe('TRUSTED_PLUGIN');
      expect(merged?.provenance.contributorId).toBe('vendor.acme');
      expect(merged?.provenance.version).toBe('1.2.3');
      // Core entries unchanged.
      expect(getResource('core.indexing-jobs')?.provenance.tier).toBe('CORE');
    });

    it('merge replaces existing entry with same id', () => {
      __seedForTest(catalogOf(tabularEntry('shared.id')));
      const { provenance: _omitted, ...withoutProv } = stateEntry('shared.id');
      mergePluginResourceContributions([
        {
          pluginId: 'vendor.acme',
          contribution: withoutProv,
          provenance: makePluginProvenance('vendor.acme', '0.0.0', 'UNTRUSTED_PLUGIN'),
        },
      ]);
      // Plugin contribution wins for the colliding id.
      expect(getResource('shared.id')?.category).toBe('STATE');
      expect(getResource('shared.id')?.provenance.contributorId).toBe('vendor.acme');
    });

    it('remove drops only the plugin\'s entries', () => {
      __seedForTest(catalogOf(tabularEntry('core.indexing-jobs')));
      const { provenance: _o1, ...c1 } = tabularEntry('vendor.acme.a');
      const { provenance: _o2, ...c2 } = tabularEntry('vendor.acme.b');
      mergePluginResourceContributions([
        { pluginId: 'vendor.acme', contribution: c1, provenance: makePluginProvenance('vendor.acme', '0.0.0', 'TRUSTED_PLUGIN') },
        { pluginId: 'vendor.acme', contribution: c2, provenance: makePluginProvenance('vendor.acme', '0.0.0', 'TRUSTED_PLUGIN') },
      ]);
      expect(listResources()).toHaveLength(3);
      removePluginResourceContributions('vendor.acme');
      const remaining = listResources();
      expect(remaining).toHaveLength(1);
      expect(remaining[0]?.id).toBe('core.indexing-jobs');
    });

    it('listeners fire after merge + remove', () => {
      const listener = vi.fn();
      onCatalogChange(listener);
      const { provenance: _o, ...c } = tabularEntry('vendor.acme.x');
      mergePluginResourceContributions([
        { pluginId: 'vendor.acme', contribution: c, provenance: makePluginProvenance('vendor.acme', '0.0.0', 'TRUSTED_PLUGIN') },
      ]);
      expect(listener).toHaveBeenCalledTimes(1);
      removePluginResourceContributions('vendor.acme');
      expect(listener).toHaveBeenCalledTimes(2);
    });

    it('remove with no matching pluginId does not fire listeners', () => {
      __seedForTest(catalogOf(tabularEntry('core.x')));
      const listener = vi.fn();
      onCatalogChange(listener);
      removePluginResourceContributions('vendor.unknown');
      expect(listener).not.toHaveBeenCalled();
    });
  });
});
