// SPDX-License-Identifier: Apache-2.0
/**
 * NavigationHandler — Intent substrate tier 3 (slice 492).
 *
 * Given a {@link ShellAddressNavigation}, bring observable reality into
 * alignment: distribute state to registered StoreAdapters per the surface's
 * stateSchema, set the active surface, push the URL, and activate the
 * projector. This is the canonical "realize a Navigation intent" site —
 * the body that previously lived as a side-effect of URLHydrator.applyState
 * and as Shell.activateSurface's ignored `state` parameter.
 *
 * Invariants (slice 492):
 *
 *   - **Determinism per address.** Two handle() calls with the same
 *     `ShellAddressNavigation` converge the observable state to the same
 *     final shape: store contents are equal; activeId equals the target;
 *     the URL hash reflects the canonical projection. Note this is
 *     weaker than CQRS-style "idempotency at the effect level":
 *     `pushState` is called once per handle() with `push: true`, so
 *     two handles produce two history entries — both pointing at the
 *     same canonical URL. Callers that want effect-level idempotency
 *     pass `push: false` (the popstate path uses this).
 *   - **Bidirectionality** (redux-first-router precedent). Every handler
 *     write round-trips to the URL via the projector. Guaranteed because
 *     activateProjection subscribes to every store the schema names.
 *   - **Transport purity** (hexagonal precedent). The handler takes a
 *     parsed ShellAddress, never a raw Location or SSE wrapper.
 *
 * Concurrency policy (slice 492 — last-writer-wins by synchronous
 * execution): handle() steps are synchronous (validate → distribute
 * → activate → push → project). JavaScript's single-threaded event
 * loop serializes them; two overlapping handle() calls run in
 * call-order, and the second's effects land on top of the first's.
 * Combined with the determinism property below, this produces the
 * correct user-intent ordering for rapid overlaps (rail-click then
 * popstate then URL paste).
 *
 * A previous draft installed an AbortController + per-step `signal.aborted`
 * checks intending "cancel-in-flight"; that machinery cannot abort
 * anything when steps are synchronous (no event-loop yield between
 * checks) and was removed in the slice-492 critical-analysis follow-up.
 * If a future step becomes async (e.g., per-surface preflight fetch),
 * an AbortController can be re-introduced WITH an `await signal.aborted`
 * checkpoint that actually does something.
 */

import { coerceAndValidate } from './stateValidator.js';
import { getSurfaceStateSchema, resolveSurfaceStateSchema } from './surfaceSchemas.js';
import type { StoreAdapter } from './storeRegistry.js';
import type { ShellAddressNavigation, StateSnapshot } from './types.js';
import { activateProjection, pushAddress } from './URLProjector.js';

/** Callback the Shell exposes for setting its active surface. */
export type SetActiveSurfaceFn = (surfaceId: string) => void;

/** Callback the Shell exposes for checking whether a surfaceId is in the active rail set. */
export type IsKnownSurfaceFn = (surfaceId: string) => boolean;

/** Unknown-surface recovery handler. */
export type OnUnknownSurfaceFn = (surfaceId: string) => void;

export interface NavigationHandlerConfig {
  setActiveSurface: SetActiveSurfaceFn;
  isKnownSurface: IsKnownSurfaceFn;
  /** Optional — defaults to a console.warn. */
  onUnknownSurface?: OnUnknownSurfaceFn;
}

export interface NavigationHandleOptions {
  /**
   * When true (default) the handler pushes a new history entry via
   * `pushAddress`. When false (used by popstate-driven activations), the
   * URL was already updated by the browser and no push is needed.
   */
  push?: boolean;
}

export interface NavigationHandler {
  /** Realize a Navigation intent. */
  handle(addr: ShellAddressNavigation, options?: NavigationHandleOptions): Promise<void>;
}

export function createNavigationHandler(config: NavigationHandlerConfig): NavigationHandler {
  const onUnknown =
    config.onUnknownSurface ??
    ((surfaceId) => {
      // eslint-disable-next-line no-console
      console.error(
        `[NavigationHandler] BUG: received unresolved surfaceId "${surfaceId}". ` +
          'The IntentRouter should have caught this during resolution (tempdoc 499 §4.7).',
      );
    });

  return {
    async handle(
      addr: ShellAddressNavigation,
      options?: NavigationHandleOptions,
    ): Promise<void> {
      if (!config.isKnownSurface(addr.target)) {
        onUnknown(addr.target);
        return;
      }

      // 1. Distribute state to registered StoreAdapters per the schema.
      //    Pre-distribution: validate the snapshot against the surface's
      //    schema; group by adapter; restore each.
      applyState(addr.target, addr.state);

      // 2. Mount the surface (Shell-level activeId).
      config.setActiveSurface(addr.target);

      // 3. Push the URL (gated by push option for popstate-driven activations).
      //
      // Slice 489 T1/N2 — ordering invariant: pushAddress (history.pushState)
      // MUST run BEFORE activateProjection's initial write (which fires a
      // synchronous history.replaceState). Reversed, the initial projection
      // write would overwrite the previous history entry with the new URL,
      // collapsing the back-button stack ("rail click then Back" would skip
      // the prior surface entirely). The same invariant applied when this
      // body lived in Shell.activateSurface pre-slice-492; it lives here now
      // because Navigation realization moved to the handler.
      const push = options?.push !== false;
      if (push) {
        pushAddress({
          kind: 'navigate',
          target: addr.target,
          state: addr.state,
        });
      }

      // 4. Activate the projector so subsequent store changes propagate to the URL.
      activateProjection(addr.target);
    },
  };
}

/**
 * Apply a state snapshot to the surface's registered stores. Bindings that
 * reference an unregistered storeId are silently dropped (per
 * surfaceSchemas.resolveSurfaceStateSchema posture).
 *
 * Validates + coerces the state through the surface's declared schema before
 * distribution. On validation failure: warn + skip restore (stores keep
 * defaults; surface still activates — graceful degradation).
 *
 * Body lifted verbatim (modulo signature) from the deleted
 * URLHydrator.applyState. Behavior is identical to ensure refresh-restore
 * semantics round-trip unchanged.
 */
function applyState(surfaceId: string, state: StateSnapshot): void {
  const schema = getSurfaceStateSchema(surfaceId);
  if (!schema) return;
  const validation = coerceAndValidate(state, schema.schema);
  if (!validation.ok) {
    // eslint-disable-next-line no-console
    console.warn(
      `[NavigationHandler] state validation failed for surfaceId="${surfaceId}":`,
      validation.errors,
    );
    return;
  }
  const validated = validation.value;
  const resolved = resolveSurfaceStateSchema(schema);
  const byStore = new Map<StoreAdapter, StateSnapshot>();
  for (const field of resolved.fields) {
    const value = validated[field.schemaPath.replace(/^\//, '')];
    if (value === undefined) continue;
    const bag = byStore.get(field.adapter) ?? {};
    bag[field.storeKey] = value;
    byStore.set(field.adapter, bag);
  }
  for (const [adapter, bag] of byStore.entries()) {
    try {
      adapter.restore(bag);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn(
        `[NavigationHandler] restore failed for storeId="${adapter.storeId}":`,
        err,
      );
    }
  }
}
