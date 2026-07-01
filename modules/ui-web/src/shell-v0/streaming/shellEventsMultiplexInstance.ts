// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 662 — the ONE shared `MultiplexedStream` for `/api/shell-events/stream`, as a
 * module-level singleton accessor (mirrors `state/originContact.ts` / `utils/statusPoll.ts`'s
 * shape: one process-wide instance, set once at shell boot, read by any consumer).
 *
 * Most of the 4 migrated consumers (intent bridge, AdvisoryStore, AiActivityDigest) receive
 * the instance via explicit dependency injection (a constructor option / Lit property) —
 * deliberately, so their unit tests construct their OWN isolated `MultiplexedStream` rather
 * than depending on global state. This singleton exists ONLY for the one consumer that
 * cannot be reached by DI: `ActionLedgerView`, mounted by the generic `SurfaceCatalog`
 * dispatcher (`ActivitySurface`) which threads plain string attributes, not object property
 * bindings — there is no clean DI path from Shell's boot site through that generic mounter.
 * `ActionLedgerView` falls back to this getter ONLY when its `multiplex` property was never
 * explicitly bound (tests keep injecting directly and never touch this module).
 */
import type { MultiplexedStream } from './MultiplexedStream.js';

let instance: MultiplexedStream | null = null;

/** Set the shared instance. Called once by `Shell.connectedCallback`. */
export function setSharedShellEventsMultiplex(stream: MultiplexedStream | null): void {
  instance = stream;
}

/** Read the shared instance, or `null` if `Shell` hasn't booted it yet (e.g. SSR/non-browser). */
export function getSharedShellEventsMultiplex(): MultiplexedStream | null {
  return instance;
}

/** Test-only: reset to the unbooted state. */
export function __resetSharedShellEventsMultiplexForTest(): void {
  instance = null;
}
