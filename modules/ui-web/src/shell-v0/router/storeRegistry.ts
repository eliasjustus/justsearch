// SPDX-License-Identifier: Apache-2.0
/**
 * Store registry — slice 489 §5 / §6 source-of-truth wiring.
 *
 * Maps the abstract {@code storeId} declared in `SurfaceStateSchema.bindings`
 * to a concrete FE store's serialize/restore API. The registry is the only
 * indirection layer between the router and the existing pub-sub stores —
 * the router never imports store modules directly. Adding a new store-as-
 * surface-state-source is one registration, not a router refactor.
 *
 * Each store implementation provides:
 *   - `serialize()` — returns a plain bag matching the schema field types
 *   - `restore(snapshot)` — applies the bag (silently coerces by type)
 *   - `subscribe(callback)` — pub-sub for the URL projector to listen on
 *
 * Per slice 489 §6: state is the source of truth, URL is the projection.
 * The router calls `restore` on URL arrival and `subscribe` to drive
 * `replaceState` on store changes.
 */

import type { StateSnapshot } from './types.js';

export interface StoreAdapter {
  /** Stable abstract identifier the schema's `storeId` resolves to. */
  readonly storeId: string;

  /** Read current store value as a flat bag matching the surface schema. */
  serialize(): StateSnapshot;

  /**
   * Apply a snapshot — replace the store's current state with the values in
   * the bag. Implementations silently coerce types (URL args arrive as
   * strings; the store coerces against its typed shape). Missing keys
   * preserve current defaults.
   */
  restore(snapshot: StateSnapshot): void;

  /**
   * Subscribe to store changes. Listener fires whenever the addressable
   * state may have changed. Returns an unsubscribe handle.
   */
  subscribe(listener: (s: StateSnapshot) => void): () => void;
}

const registry = new Map<string, StoreAdapter>();

/**
 * Register a store adapter. Idempotent — re-registering under the same
 * `storeId` replaces the prior registration (intended for hot-reload + tests).
 */
export function registerStore(adapter: StoreAdapter): void {
  registry.set(adapter.storeId, adapter);
}

/** Look up a registered adapter by abstract storeId. Returns undefined if absent. */
export function getStore(storeId: string): StoreAdapter | undefined {
  return registry.get(storeId);
}

/** Test-only: clear all registrations. */
export function __resetStoreRegistryForTest(): void {
  registry.clear();
}

/** Returns the registered storeIds in insertion order. */
export function registeredStoreIds(): string[] {
  return Array.from(registry.keys());
}
