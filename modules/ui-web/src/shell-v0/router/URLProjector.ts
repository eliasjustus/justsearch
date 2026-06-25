// SPDX-License-Identifier: Apache-2.0
/**
 * URLProjector — slice 489 §6 source-of-truth projection.
 *
 * State is the source of truth; the URL is a projection. The projector
 * subscribes to every store referenced by the active surface's
 * {@code SurfaceStateSchema.bindings} and writes the canonical URL to the
 * browser bar via {@code history.replaceState} when any of them publishes.
 *
 * Per slice 489 §10: the router never sets state; it only reads. The
 * Shell.activateSurface(...) caller is responsible for writing state before
 * activating; the projector then mirrors the resulting state onto the URL.
 *
 * Push vs replace semantics:
 *   - `replaceState` (default): used for incremental edits within a surface
 *     (typing in the search box) — the URL bar tracks the live state without
 *     bloating browser history.
 *   - `pushState`: used when the active surface changes (rail click, palette,
 *     deep-link). The Shell calls `pushAddress(...)` directly at activation
 *     time; the projector's subsequent state-driven writes use replaceState.
 */

import { canonicalize, parseUrl } from './parser.js';
import { getStore } from './storeRegistry.js';
import type { ShellAddressNavigation, StateSnapshot } from './types.js';
import {
  getSurfaceStateSchema,
  resolveSurfaceStateSchema,
  type ResolvedSchema,
} from './surfaceSchemas.js';

interface ActiveProjection {
  surfaceId: string;
  schema: ResolvedSchema;
  unsubscribers: Array<() => void>;
}

let active: ActiveProjection | null = null;

/**
 * Slice 489 T1/G2 — trailing debounce for subscribe-driven URL writes.
 *
 * Without debouncing, typing in the search box fires history.replaceState on
 * every keystroke (visible flicker in the URL bar and a useless write rate
 * during fast typing). The projection model still demands eventual
 * consistency between state and URL — debouncing is the trailing-edge
 * collapse, not a semantic change.
 *
 * Window choice: 75ms. Typical fast-typist inter-keystroke is 80-150ms;
 * burst clusters hit 30-50ms. 75ms coalesces bursts cleanly without making
 * the URL feel stale on a normal-cadence typist (the trailing write lands
 * before the next intentional pause).
 *
 * The explicit final write inside activateProjection stays synchronous —
 * existing tests + the round-7 ordering invariant (pushAddress before
 * replaceState) depend on it.
 */
const URL_WRITE_DEBOUNCE_MS = 75;
let pendingTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * Activate the projector for a given surface. Subscribes to every store the
 * surface's schema names; writes the URL on every store change.
 *
 * Idempotent: re-calling with the same surfaceId is a no-op; re-calling with
 * a different surfaceId tears down the prior subscriptions first.
 */
export function activateProjection(surfaceId: string): void {
  if (active?.surfaceId === surfaceId) return;
  deactivateProjection();

  const schema = getSurfaceStateSchema(surfaceId);
  if (!schema) {
    // Surface has no declared stateSchema — not URL-addressable; nothing to project.
    return;
  }
  const resolved = resolveSurfaceStateSchema(schema);

  const unsubscribers: Array<() => void> = [];
  for (const adapter of resolved.adapters) {
    unsubscribers.push(
      adapter.subscribe(() => {
        scheduleWrite(surfaceId, resolved);
      }),
    );
  }
  active = { surfaceId, schema: resolved, unsubscribers };
  // Adapter.subscribe fires the listener once on subscribe (matches production
  // store posture); those firings scheduled a debounced write. Cancel it
  // because the explicit final write below is synchronous + immediate.
  clearPendingWrite();
  // Initial write — make sure the URL reflects the current store state at
  // activation time.
  writeUrl(surfaceId, resolved);
}

/** Tear down all store subscriptions. Safe to call when no projection is active. */
export function deactivateProjection(): void {
  clearPendingWrite();
  if (!active) return;
  for (const unsub of active.unsubscribers) {
    try {
      unsub();
    } catch {
      // Subscriber teardown errors are non-fatal; ignore.
    }
  }
  active = null;
}

function scheduleWrite(surfaceId: string, schema: ResolvedSchema): void {
  if (pendingTimer !== null) clearTimeout(pendingTimer);
  pendingTimer = setTimeout(() => {
    pendingTimer = null;
    // Re-check `active` — deactivateProjection may have fired between schedule
    // and timer firing; the active surface may also have changed.
    if (active?.surfaceId === surfaceId) {
      writeUrl(surfaceId, schema);
    }
  }, URL_WRITE_DEBOUNCE_MS);
}

function clearPendingWrite(): void {
  if (pendingTimer !== null) {
    clearTimeout(pendingTimer);
    pendingTimer = null;
  }
}

/**
 * Flush any pending debounced URL write synchronously. Call this before
 * reading window.location.hash when you need the hash to reflect the
 * latest store state (e.g., bookmark save, copy URL). Without flushing,
 * the hash may lag by up to 75ms after the last store change.
 */
export function flushPendingProjection(): void {
  if (pendingTimer === null) return;
  clearTimeout(pendingTimer);
  pendingTimer = null;
  if (active) {
    writeUrl(active.surfaceId, active.schema);
  }
}

/**
 * Explicitly push a new navigation address as a history entry (used when the
 * active surface itself changes, vs incremental state edits within a surface).
 * The projector then activates for the new surface, which performs its own
 * initial replaceState.
 */
export function pushAddress(addr: ShellAddressNavigation): void {
  const url = canonicalize(addr);
  try {
    window.history.pushState({ surfaceId: addr.target }, '', toRelative(url));
  } catch {
    // history API failure is non-fatal — chrome still navigates in-memory.
  }
}

/**
 * Read the current `window.location` and return the parsed Navigation address,
 * or null if the URL does not encode a justsearch:// navigation. Falls back
 * to scanning for a `?ja=<encoded>` style param (legacy escape hatch — not
 * currently used; reserved for future).
 */
export function currentAddress(): ShellAddressNavigation | null {
  if (typeof window === 'undefined') return null;
  const hash = window.location.hash;
  if (hash && hash.startsWith('#justsearch://')) {
    const parsed = parseUrl(hash.slice(1));
    if (parsed && parsed.kind === 'navigate') return parsed;
  }
  // The chrome runs at http://127.0.0.1:<port>/ — full `justsearch://` URLs
  // only arrive via Tauri deep-link callbacks or programmatic constructors,
  // not from window.location directly. The browser-bar projection writes the
  // canonical form as a hash (`#justsearch://surface/...`) so it survives
  // refresh while staying under the loopback origin's URL.
  return null;
}

// ----- helpers -----

function writeUrl(surfaceId: string, schema: ResolvedSchema): void {
  const state = collectState(schema);
  const addr: ShellAddressNavigation = {
    kind: 'navigate',
    target: surfaceId,
    state,
  };
  const url = canonicalize(addr);
  try {
    // Encode the canonical justsearch:// URL into the hash fragment so the
    // browser bar projection survives refresh under the loopback HTTP origin.
    window.history.replaceState(
      { surfaceId },
      '',
      toRelative(url),
    );
  } catch {
    // history API failure non-fatal.
  }
}

function collectState(schema: ResolvedSchema): StateSnapshot {
  const out: StateSnapshot = {};
  for (const adapter of schema.adapters) {
    const slice = adapter.serialize();
    for (const [k, v] of Object.entries(slice)) {
      if (v !== undefined && v !== null) {
        out[k] = v;
      }
    }
  }
  return out;
}

function toRelative(canonical: string): string {
  // Convert "justsearch://surface/<id>?<args>" to "#justsearch://surface/<id>?<args>"
  // so it lives under the chrome's loopback HTTP origin. The hash never round-
  // trips to the server (no extra request), and refresh preserves it.
  return '#' + canonical;
}

// Test-only helper to read the active projection's surfaceId (vitest).
export function __activeSurfaceIdForTest(): string | null {
  return active?.surfaceId ?? null;
}

/**
 * Test-only helper — flush any pending debounced URL write synchronously.
 * Tests that emit state and assert URL-write side effects in the same tick
 * call this to bypass the trailing debounce.
 */
export function __flushPendingWriteForTest(): void {
  if (pendingTimer === null) return;
  clearTimeout(pendingTimer);
  pendingTimer = null;
  if (active) {
    writeUrl(active.surfaceId, active.schema);
  }
}

/** Look up the registered store-adapter for a `storeId`. Test-friendly export. */
export function __getStoreForTest(storeId: string) {
  return getStore(storeId);
}
