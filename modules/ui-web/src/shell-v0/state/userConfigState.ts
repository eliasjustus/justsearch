// SPDX-License-Identifier: Apache-2.0
/**
 * userConfigState — projection over UserStateDocument.
 *
 * V1.5 alpha shipped this as an ad-hoc per-domain store with its
 * own localStorage key. 478 §4.B refactor (slice 477 follow-on):
 * userConfigState is now a PROJECTION over the consolidated
 * UserStateDocument. The public API is preserved unchanged —
 * existing consumers (Shell.ts, Settings UI, dispatch) don't
 * need any code changes.
 *
 * Persistence consolidation:
 *   - V1.5 alpha: localStorage['justsearch.userConfig']
 *   - V1.5.1 (this refactor): localStorage['justsearch.userState.v1']
 *     under the document's `userConfig` slice
 *   - First-boot migration reads the legacy key and merges it
 *     into the new document; legacy key is left in place
 *     (revertible to V1.5 alpha)
 *
 * The migration is automatic — UserStateDocument's
 * ensureInitialized handles it on first access.
 *
 * Pattern mirrors `state/searchState.ts` for compatibility
 * (subscribe / mutators / getCurrent / __resetForTest).
 */
import type { RendererUserConfig } from '../renderers/userConfig.js';
import {
  getDocument,
  subscribeProjection,
  mutateDocument,
  __resetUserStateForTest,
} from './UserStateDocument.js';

type Listener = (cfg: RendererUserConfig) => void;

/** Snapshot of the current userConfig. */
export function getUserConfig(): RendererUserConfig {
  return getDocument().userConfig;
}

/**
 * Subscribe to userConfig changes. Listener fires once with the
 * current value on subscribe, then on every mutation. Returns an
 * unsubscribe function. Listener errors are swallowed (same posture
 * as searchState) so a single misbehaving subscriber can't break
 * the others.
 */
export function subscribeUserConfig(listener: Listener): () => void {
  return subscribeProjection((doc) => doc.userConfig, listener);
}

/**
 * Slice 471 — set a surface override. The chrome's Stage will
 * dispatch to the override target instead of the canonical surface
 * for the given coreId.
 */
export function setSurfaceOverride(coreId: string, overrideId: string): void {
  mutateDocument((doc) => {
    const next: Record<string, string> = {
      ...(doc.userConfig.surfaceOverride ?? {}),
      [coreId]: overrideId,
    };
    return {
      ...doc,
      userConfig: { ...doc.userConfig, surfaceOverride: next },
    };
  });
}

/**
 * Slice 471 — clear a surface override (revert to core surface).
 * No-op if no override exists for the given coreId.
 */
export function clearSurfaceOverride(coreId: string): void {
  mutateDocument((doc) => {
    if (
      !doc.userConfig.surfaceOverride ||
      !(coreId in doc.userConfig.surfaceOverride)
    ) {
      return doc;
    }
    const next = { ...doc.userConfig.surfaceOverride };
    delete next[coreId];
    return {
      ...doc,
      userConfig: {
        ...doc.userConfig,
        surfaceOverride: Object.keys(next).length > 0 ? next : undefined,
      },
    };
  });
}

/**
 * Slice 471 — clear all surface overrides at once. Used by the
 * provenance UI's "revert all to core" action.
 */
export function clearAllSurfaceOverrides(): void {
  mutateDocument((doc) => {
    if (!doc.userConfig.surfaceOverride) return doc;
    return {
      ...doc,
      userConfig: { ...doc.userConfig, surfaceOverride: undefined },
    };
  });
}

/**
 * Slice 472 — set per-surface visibility. `false` hides the surface
 * from the rail; `true` (or absence) shows it.
 */
export function setSurfaceVisibility(coreId: string, visible: boolean): void {
  mutateDocument((doc) => {
    const next: Record<string, boolean> = {
      ...(doc.userConfig.surfaceVisibility ?? {}),
      [coreId]: visible,
    };
    return {
      ...doc,
      userConfig: { ...doc.userConfig, surfaceVisibility: next },
    };
  });
}

/**
 * Slice 472 — clear per-surface visibility for the given id (revert
 * to default which is visible).
 */
export function clearSurfaceVisibility(coreId: string): void {
  mutateDocument((doc) => {
    if (
      !doc.userConfig.surfaceVisibility ||
      !(coreId in doc.userConfig.surfaceVisibility)
    ) {
      return doc;
    }
    const next = { ...doc.userConfig.surfaceVisibility };
    delete next[coreId];
    return {
      ...doc,
      userConfig: {
        ...doc.userConfig,
        surfaceVisibility:
          Object.keys(next).length > 0 ? next : undefined,
      },
    };
  });
}

/**
 * Slice 472 — set explicit rail order. Empty array clears the
 * override (rail returns to catalog order).
 */
export function setSurfaceOrder(order: string[]): void {
  mutateDocument((doc) => ({
    ...doc,
    userConfig: {
      ...doc.userConfig,
      surfaceOrder: order.length > 0 ? [...order] : undefined,
    },
  }));
}

/**
 * Slice 472 — clear all layout-related overrides at once.
 * Mirrors {@link clearAllSurfaceOverrides}; used by provenance UI's
 * "revert layout to default" action.
 */
export function clearAllLayoutOverrides(): void {
  mutateDocument((doc) => {
    const cfg = doc.userConfig;
    if (
      cfg.surfaceVisibility === undefined &&
      cfg.surfaceOrder === undefined &&
      cfg.activeLayoutId === undefined
    ) {
      return doc;
    }
    return {
      ...doc,
      userConfig: {
        ...cfg,
        surfaceVisibility: undefined,
        surfaceOrder: undefined,
        activeLayoutId: undefined,
      },
    };
  });
}

/**
 * Tempdoc 507 §6 Phase 5 — set the active layout id.
 * Passing undefined clears the override (reverts to default layout).
 */
export function setActiveLayoutId(layoutId: string | undefined): void {
  mutateDocument((doc) => ({
    ...doc,
    userConfig: {
      ...doc.userConfig,
      activeLayoutId: layoutId,
    },
  }));
}

/**
 * Tempdoc 521 §16.7 deeper — set the secondary active surface for the
 * split-stage layout. Passing undefined clears the override (Stage's
 * fallback picks "first non-primary rail surface").
 */
export function setSecondaryActiveSurface(surfaceId: string | undefined): void {
  mutateDocument((doc) => ({
    ...doc,
    userConfig: {
      ...doc.userConfig,
      secondaryActiveSurface: surfaceId,
    },
  }));
}

/** Test-only: reset module state. */
export function __resetUserConfigForTest(): void {
  __resetUserStateForTest();
}

// Forward-compat for tests / debugging that want to subscribe to
// the entire document, not just userConfig.
export { subscribeDocument as __subscribeDocumentForTest } from './UserStateDocument.js';
