/**
 * Slice 3a.1.9 §A.7 — resolvePathLazy tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest as resetResolveCache,
  __seedForTest as seedResolveCache,
  resolvePathLazy,
} from './resolvePathLazy';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient';
import type { Resource, ResourceCatalog } from '../../api/types/registry';

function tabularResource(): Resource {
  return {
    id: 'core.indexing-jobs',
    presentation: {
      labelKey: 'k.label',
      descriptionKey: 'k.desc',
      iconHint: null,
      category: null,
    },
    schema: '',
    category: 'TABULAR',
    subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/indexing-jobs/stream',
    kind: 'indexing-jobs-table',
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: {
      pathPolicy: 'HASHED_REQUIRES_RESOLVER',
      loopbackOnly: true,
      resolver: 'core.resolve-path-hash',
    },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: 'pathHash',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function catalogOf(r: Resource): ResourceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Resource',
    entries: [r],
  };
}

describe('resolvePathLazy', () => {
  beforeEach(() => {
    resetResolveCache();
    resetResourceCatalog();
  });
  afterEach(() => {
    resetResolveCache();
    resetResourceCatalog();
  });

  it('returns null for unknown resource', async () => {
    const r = await resolvePathLazy('core.does-not-exist', 'h1');
    expect(r).toBeNull();
  });

  it('returns null for resource with NO_PATHS privacy', async () => {
    const r = tabularResource();
    r.privacy = { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null };
    seedResourceCatalog(catalogOf(r));
    const result = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(result).toBeNull();
  });

  it('returns null for resource missing resolver', async () => {
    const r = tabularResource();
    r.privacy = {
      pathPolicy: 'HASHED_REQUIRES_RESOLVER',
      loopbackOnly: true,
      resolver: null,
    };
    seedResourceCatalog(catalogOf(r));
    const result = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(result).toBeNull();
  });

  it('memoizes seeded resolutions without HTTP', async () => {
    seedResolveCache('core.indexing-jobs', 'h1', '/abs/path.txt');
    const r = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(r).toBe('/abs/path.txt');
  });

  it('invokes the resolver Operation with the primaryKey arg name', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          success: true,
          structuredData: { found: true, path: '/x/y.txt' },
        }),
      headers: new Headers(),
    });
    const result = await resolvePathLazy('core.indexing-jobs', 'h2', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(result).toBe('/x/y.txt');
    expect(fetchImpl).toHaveBeenCalled();
    const call = fetchImpl.mock.calls[0];
    const url = call?.[0] as string;
    const init = call?.[1] as RequestInit;
    expect(url).toContain('/api/operations/core.resolve-path-hash/invoke');
    expect(init?.body).toBeDefined();
    const body = JSON.parse(init.body as string);
    // Arg name uses primaryKey (pathHash); rowKey is the value.
    expect(body.args).toEqual({ pathHash: 'h2' });
  });

  it('returns null on resolver "found: false"', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          success: true,
          structuredData: { found: false },
        }),
      headers: new Headers(),
    });
    const result = await resolvePathLazy('core.indexing-jobs', 'h3', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(result).toBeNull();
  });

  it('memoizes after first successful resolution', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          success: true,
          structuredData: { found: true, path: '/z.txt' },
        }),
      headers: new Headers(),
    });
    await resolvePathLazy('core.indexing-jobs', 'h4', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    await resolvePathLazy('core.indexing-jobs', 'h4', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
