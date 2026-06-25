// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C0) — registry of typed view factories keyed by
 * ConversationShapeRef. Mirrors the Surface catalog client's `entriesById` map
 * for typed views.
 *
 * <p>Typed views register here at module import time:
 *
 * <pre>{@code
 *   // modules/ui-web/src/shell-v0/views/NavigateView.ts
 *   import { registerViewFactory } from '../router/viewFactoryRegistry.js';
 *   // ... define NavigateView class ...
 *   customElements.define('jf-navigate-view', NavigateView);
 *   registerViewFactory('core.navigate-chat', 'jf-navigate-view');
 * }</pre>
 *
 * <p>The C5 cross-module CI script (`scripts/ci/check-shape-view-coverage.mjs`)
 * string-matches `registerViewFactory(` callsites in `modules/ui-web/src/**`
 * against the {@code /api/registry/shapes} catalog JSON; a USER-audience
 * shape with no registration fails the check.
 *
 * @see ViewFactory
 * @see stampViewFactory
 */

import type { ConversationShapeRef } from '../../api/types/conversation-shape.js';
import { type ViewFactory, stampViewFactory } from './view-factory.js';

const factories: Map<ConversationShapeRef, ViewFactory<ConversationShapeRef>> = new Map();
const listeners: Set<() => void> = new Set();
// Tempdoc 560 §28.G — per-shape ownership. Core registrations omit an owner (undefined); a plugin
// passes its id. A plugin may not register/replace a shape another owner already holds (cross-plugin
// view hijack), and `unregisterViewFactory` withdraws a plugin's shapes on uninstall (the "future
// unregister" this module anticipated).
const owners: Map<ConversationShapeRef, string | undefined> = new Map();

function fireRegistryChange(): void {
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow
    }
  }
}

/**
 * Register a typed view factory for a shape. The shape's view is mounted by
 * the custom element registered under {@code mountTag}.
 *
 * <p>Idempotent for the SAME owner: re-registering replaces the prior factory
 * (necessary for HMR hot-swaps + the plugin approve→reload cycle). A register
 * by a DIFFERENT owner than the current holder is REFUSED (the existing factory
 * is preserved, a warning is logged, and the existing factory is returned) — a
 * plugin cannot hijack a `core.*` view nor another plugin's `vendor.*` shape.
 *
 * @param ownerId the registering plugin's id; omit for host/core registrations.
 */
export function registerViewFactory(
  shapeRef: ConversationShapeRef,
  mountTag: string,
  ownerId?: string,
): ViewFactory<ConversationShapeRef> {
  if (factories.has(shapeRef)) {
    const currentOwner = owners.get(shapeRef);
    if (currentOwner !== ownerId) {
      // Cross-owner collision — preserve the incumbent, refuse the replace.
      console.warn(
        `[viewFactoryRegistry] refusing to register shape '${shapeRef}' for owner ` +
          `'${ownerId ?? '(core)'}' — already owned by '${currentOwner ?? '(core)'}'. ` +
          `A view factory may only be (re)registered by its owner.`,
      );
      return factories.get(shapeRef)!;
    }
  }
  const factory = stampViewFactory(shapeRef, mountTag);
  factories.set(shapeRef, factory);
  owners.set(shapeRef, ownerId);
  fireRegistryChange();
  return factory;
}

/**
 * Withdraw a previously-registered view factory (e.g. on plugin uninstall). When {@code ownerId} is
 * given, the removal only applies if it matches the recorded owner (a plugin cannot withdraw a shape it
 * does not own); omit it for an unconditional host removal. Fires the change listeners when something
 * was actually removed.
 */
export function unregisterViewFactory(shapeRef: ConversationShapeRef, ownerId?: string): boolean {
  if (!factories.has(shapeRef)) return false;
  if (ownerId !== undefined && owners.get(shapeRef) !== ownerId) return false;
  factories.delete(shapeRef);
  owners.delete(shapeRef);
  fireRegistryChange();
  return true;
}

/** Synchronous lookup. `undefined` when no factory has been registered. */
export function getViewFactory(
  shapeRef: ConversationShapeRef,
): ViewFactory<ConversationShapeRef> | undefined {
  return factories.get(shapeRef);
}

/** Snapshot of all registered (shapeRef, factory) pairs. */
export function listViewFactories(): Array<[ConversationShapeRef, ViewFactory<ConversationShapeRef>]> {
  return Array.from(factories.entries());
}

/** Subscribe to registry changes (register + future unregister). */
export function onViewFactoryRegistryChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// ============================================================
// Test-only helpers
// ============================================================

/** Reset the registry between tests. */
export function __resetViewFactoryRegistryForTest(): void {
  factories.clear();
  owners.clear();
  listeners.clear();
}

/**
 * Test-only: inject a factory directly into the registry, bypassing
 * `stampViewFactory`. Used by ChatShapeMount's brand-forgery test (slice 491
 * F3) to verify that `mountView` rejects forged factories — i.e., objects
 * with `__viewBrand` set (by stealing it from a real factory) but NOT in
 * the module-private `VALID_VIEW_FACTORIES` WeakSet.
 *
 * Do not call from production code; the brand verification exists precisely
 * to prevent the registry from holding non-WeakSet factories at runtime.
 */
export function __setViewFactoryForTest(
  shapeRef: ConversationShapeRef,
  factory: ViewFactory<ConversationShapeRef>,
): void {
  factories.set(shapeRef, factory);
}
