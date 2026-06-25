/**
 * surfaceSchemas tests — slice 489 §5 FE-side schema registry.
 */

import { describe, expect, it, beforeEach, vi } from 'vitest';
import { __resetStoreRegistryForTest, registerStore } from './storeRegistry.js';
import {
  __resetSurfaceSchemasForTest,
  getSurfaceStateSchema,
  registerSurfaceStateSchema,
  resolveSurfaceStateSchema,
} from './surfaceSchemas.js';

describe('surfaceSchemas', () => {
  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
  });

  it('register + get returns the registered schema', () => {
    const schema = {
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/q', storeId: 'search', storeKey: 'query' }],
    };
    registerSurfaceStateSchema('core.search-surface', schema);
    expect(getSurfaceStateSchema('core.search-surface')).toBe(schema);
  });

  it('get returns undefined for unregistered surface', () => {
    expect(getSurfaceStateSchema('core.unknown')).toBeUndefined();
  });

  it('resolve pairs schema bindings with registered adapters', () => {
    const adapter = {
      storeId: 'search',
      serialize: () => ({}),
      restore: () => {
        /* noop */
      },
      subscribe: () => () => {
        /* noop */
      },
    };
    registerStore(adapter);
    const resolved = resolveSurfaceStateSchema({
      schema: '{"type":"object"}',
      bindings: [{ schemaPath: '/query', storeId: 'search', storeKey: 'query' }],
    });
    expect(resolved.adapters).toEqual([adapter]);
    expect(resolved.fields).toEqual([
      { schemaPath: '/query', storeKey: 'query', adapter },
    ]);
  });

  it('resolve drops bindings for unregistered storeIds (with warning)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {
      /* swallow */
    });
    const resolved = resolveSurfaceStateSchema({
      schema: '{"type":"object"}',
      bindings: [
        { schemaPath: '/known', storeId: 'search', storeKey: 'query' },
        { schemaPath: '/unknown', storeId: 'absent', storeKey: 'whatever' },
      ],
    });
    expect(resolved.fields).toEqual([]); // both dropped — 'search' isn't registered either
    expect(resolved.adapters).toEqual([]);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('resolve deduplicates adapters when multiple bindings reference one store', () => {
    const adapter = {
      storeId: 'search.filters',
      serialize: () => ({}),
      restore: () => {
        /* noop */
      },
      subscribe: () => () => {
        /* noop */
      },
    };
    registerStore(adapter);
    const resolved = resolveSurfaceStateSchema({
      schema: '{"type":"object"}',
      bindings: [
        { schemaPath: '/from', storeId: 'search.filters', storeKey: 'modifiedFromMs' },
        { schemaPath: '/to', storeId: 'search.filters', storeKey: 'modifiedToMs' },
      ],
    });
    expect(resolved.adapters).toEqual([adapter]);
    expect(resolved.fields).toHaveLength(2);
  });
});
