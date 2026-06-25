// SPDX-License-Identifier: Apache-2.0
/**
 * Registry primitive — Tempdoc 543 §28.W7 (partial closure for §13.6 #1).
 *
 * 9 contribution registries (CommandRegistry, ContextActionRegistry,
 * EmptyStateRegistry, InspectorTabRegistry, KeybindingRegistry,
 * SelectionActionRegistry, StatusBarRegistry, TemplateCatalog,
 * WalkthroughRegistry) historically each declared the same shape:
 *
 *   const items = new Map<string, T>();
 *   const listeners = new Set<() => void>();
 *   function notify() { for (const l of listeners) try { l() } catch {} }
 *   function register(item) { items.set(item.id, item); notify(); }
 *   function unregister(id) { ... }
 *   function list() { ... }
 *   function subscribe(l) { ... }
 *   function __resetForTest() { ... }
 *
 * This factory consolidates that boilerplate to a single primitive.
 * Each call-site replaces the duplicated Map + notify + register/
 * unregister/list/subscribe with one createRegistry<T>() call,
 * keeping its public function names intact (so consumers don't break).
 *
 * What this does NOT do: it does not collapse the 9 separate
 * INTERFACES into one. Per §28.4 / §29.5, contract-level consolidation
 * is a sweeping refactor deferred to a future structural slice. This
 * primitive only removes the ~600-line boilerplate duplication.
 */

import { notifyAll } from './notify.js';

export interface Registry<T extends { readonly id: string }> {
  /** Add or replace by id. Notifies listeners on register. */
  readonly register: (item: T) => void;
  /**
   * Remove by id. Returns true if an entry was removed (and notified),
   * false otherwise.
   */
  readonly unregister: (id: string) => boolean;
  /** Insertion-order snapshot. Callers can sort/filter as needed. */
  readonly list: () => readonly T[];
  /** Get one by id, or undefined. */
  readonly get: (id: string) => T | undefined;
  /** Subscribe to register/unregister notifications. */
  readonly subscribe: (listener: () => void) => () => void;
  /** Test-only reset of items + listeners. */
  readonly __resetForTest: () => void;
  /**
   * Escape hatch — direct access to the underlying Map. Callers that
   * need O(1) id lookup or need to splice the map directly (rare; the
   * `register`/`unregister`/`get` APIs cover the common cases) can use
   * this. Mutations through this handle do NOT notify listeners — pair
   * with `__notify()` when you batch mutate.
   */
  readonly _map: Map<string, T>;
  /**
   * Trigger the listener-notification cycle. Callers that bulk-mutate
   * `_map` directly use this to fire a single notification after the
   * batch.
   */
  readonly __notify: () => void;
}

export interface CreateRegistryOptions<T extends { readonly id: string }> {
  /**
   * Optional onRegister hook — runs AFTER the item lands in the map
   * but BEFORE listeners fire. Use for cross-substrate side effects
   * (e.g., auto-register profile factory). Throws propagate; the
   * caller's try/catch decides rollback semantics.
   */
  readonly onRegister?: (item: T) => void;
  /**
   * Optional onUnregister hook — runs AFTER the item is removed,
   * BEFORE listeners fire. Symmetric to onRegister.
   */
  readonly onUnregister?: (id: string, item: T) => void;
}

/**
 * Construct a registry. Returns the typed API surface; consumers
 * destructure or re-export to keep their existing names:
 *
 *   const r = createRegistry<StatusBarItem>();
 *   export const registerStatusBarItem = r.register;
 *   export const unregisterStatusBarItem = r.unregister;
 *   export const listStatusBarItems = r.list;
 *   export const onStatusBarChange = r.subscribe;
 *   export const __resetForTest = r.__resetForTest;
 */
export function createRegistry<T extends { readonly id: string }>(
  options: CreateRegistryOptions<T> = {},
): Registry<T> {
  const items = new Map<string, T>();
  const listeners = new Set<() => void>();

  const notify = (): void => notifyAll(listeners);

  return {
    register(item: T): void {
      items.set(item.id, item);
      if (options.onRegister) options.onRegister(item);
      notify();
    },
    unregister(id: string): boolean {
      const item = items.get(id);
      if (item === undefined) return false;
      items.delete(id);
      if (options.onUnregister) options.onUnregister(id, item);
      notify();
      return true;
    },
    list(): readonly T[] {
      return Array.from(items.values());
    },
    get(id: string): T | undefined {
      return items.get(id);
    },
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    __resetForTest(): void {
      items.clear();
      listeners.clear();
    },
    _map: items,
    __notify: notify,
  };
}
