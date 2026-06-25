/**
 * Slice 3a.1.9 — OperationCatalogClient tests.
 * Smoke coverage; the file mirrors ResourceCatalogClient structurally,
 * so the deeper logic tests are over there.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Operation, OperationCatalog } from '../types/registry';
import {
  __resetForTest,
  __seedForTest,
  bootOperationRegistry,
  getOperation,
  listOperations,
} from './OperationCatalogClient';

function op(id: string): Operation {
  return {
    id,
    presentation: {
      labelKey: `ops.${id}.label`,
      descriptionKey: `ops.${id}.description`,
      iconHint: null,
      category: null,
    },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: {
      risk: 'LOW',
      confirm: { kind: 'NONE' },
      audit: 'METADATA_ONLY',
      undoSupported: false,
    },
    availability: {},
    lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'],
    audience: 'USER',
    consumers: [],
  };
}

function catalogOf(...entries: Operation[]): OperationCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Operation',
    entries,
  };
}

describe('OperationCatalogClient', () => {
  beforeEach(() => {
    __resetForTest();
  });
  afterEach(() => {
    __resetForTest();
  });

  it('seeded entries become discoverable', () => {
    __seedForTest(catalogOf(op('core.cancel-indexing-job'), op('core.retry-indexing-job')));
    expect(getOperation('core.cancel-indexing-job')?.policy.risk).toBe('LOW');
    expect(listOperations()).toHaveLength(2);
  });

  it('returns undefined for unknown id', () => {
    expect(getOperation('core.unknown')).toBeUndefined();
  });

  it('boot fetches with ETag awareness', async () => {
    const catalog = catalogOf(op('core.x'));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(catalog),
      headers: new Headers({ ETag: '"e1"' }),
    });
    await bootOperationRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
    expect(getOperation('core.x')).toBeDefined();
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://localhost/api/registry/operations',
      expect.any(Object),
    );
  });

  it('handles 304 by retaining cached entries', async () => {
    __seedForTest(catalogOf(op('core.cached')));
    __resetForTest();
    // Seed via storage by re-seeding (tests run in jsdom with localStorage).
    __seedForTest(catalogOf(op('core.cached')));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 304,
      json: () => Promise.resolve({}),
      headers: new Headers(),
    });
    await bootOperationRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
    expect(getOperation('core.cached')).toBeDefined();
  });

  it('handles fetch failure gracefully', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('boom'));
    await bootOperationRegistry('http://localhost', fetchImpl as unknown as typeof fetch);
    expect(listOperations()).toEqual([]);
  });
});
