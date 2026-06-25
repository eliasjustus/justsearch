/**
 * storeRegistry tests — slice 489 §5/§6 wiring layer.
 */

import { describe, expect, it, beforeEach } from 'vitest';
import {
  __resetStoreRegistryForTest,
  getStore,
  registerStore,
  registeredStoreIds,
  type StoreAdapter,
} from './storeRegistry.js';
import type { StateSnapshot } from './types.js';

const NOOP_ADAPTER = (storeId: string): StoreAdapter => ({
  storeId,
  serialize: () => ({}) as StateSnapshot,
  restore: () => {
    /* noop */
  },
  subscribe: () => () => {
    /* noop */
  },
});

describe('storeRegistry', () => {
  beforeEach(() => {
    __resetStoreRegistryForTest();
  });

  it('returns undefined for unregistered storeId', () => {
    expect(getStore('unknown')).toBeUndefined();
  });

  it('returns the registered adapter', () => {
    const adapter = NOOP_ADAPTER('search');
    registerStore(adapter);
    expect(getStore('search')).toBe(adapter);
  });

  it('re-registration replaces the prior adapter', () => {
    const first = NOOP_ADAPTER('search');
    const second = NOOP_ADAPTER('search');
    registerStore(first);
    registerStore(second);
    expect(getStore('search')).toBe(second);
  });

  it('registeredStoreIds returns insertion order', () => {
    registerStore(NOOP_ADAPTER('a'));
    registerStore(NOOP_ADAPTER('b'));
    registerStore(NOOP_ADAPTER('c'));
    expect(registeredStoreIds()).toEqual(['a', 'b', 'c']);
  });

  it('reset clears the registry', () => {
    registerStore(NOOP_ADAPTER('search'));
    __resetStoreRegistryForTest();
    expect(getStore('search')).toBeUndefined();
    expect(registeredStoreIds()).toEqual([]);
  });
});
