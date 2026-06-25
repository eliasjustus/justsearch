// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a-1-8e (ship-option a, 2026-05-07) — boot-time SSE-to-contractEvents
 * bridge.
 *
 * Wires `/infra/capabilities/stream` UPDATE frames into the
 * `contractEvents` dispatcher. Reducer-side-effect pattern mirrors
 * `CapabilitiesHandshake.startStream()` (`shell-v0/handshake/CapabilitiesHandshake.ts:161-192`).
 *
 * The bridge runs as its own `EnvelopeStream` singleton — parallel to (not
 * shared with) the React-side `useServerCapabilities` hook's per-component
 * `CapabilitiesHandshake` instance. This avoids refactoring the handshake
 * to a shared singleton (out of scope for this slice). On loopback the
 * extra SSE connection is negligible; future runtime follow-up may
 * unify if SSE-doubling becomes a concern.
 *
 * Discrimination: legacy `CapabilityChangeEvent` payloads (kind: snapshot
 * / added / modified / removed / heartbeat + detail) and new
 * `ContractEventPayload` payloads share UPDATE frames. The bridge filters
 * via {@link isContractEvent} (structural — payload carries at least one
 * of capabilityId/category/consumerId).
 */

import { EnvelopeStream } from '../../shell-v0/streaming/EnvelopeStream.js';
import type { SseEnvelope } from '../../shell-v0/streaming/envelope-types.js';
import {
  decodeContractEvent,
  dispatchContractEvent,
  isContractEvent,
} from './contractEvents.js';
import {
  refreshOperationCatalog,
} from '../registry/OperationCatalogClient.js';
import {
  refreshResourceCatalog,
} from '../registry/ResourceCatalogClient.js';
import { subscribe } from './contractEvents.js';

/** Singleton state. */
let bridgeStream: EnvelopeStream<{ updateSeq: number; lastPayload: unknown }> | null =
  null;
let bridgeUnsubscribe: (() => void) | null = null;
let catalogRefreshUnsubscribe: (() => void) | null = null;

/**
 * Start the bridge. Idempotent: subsequent calls are no-ops while
 * already running. Must be called once at app boot for live
 * `catalog-membership-changed` and `capability-registered` events to
 * reach FE consumers.
 *
 * @param apiBase Absolute API base (e.g., 'http://127.0.0.1:33221'). No
 *   trailing slash; the bridge appends `/infra/capabilities/stream`.
 */
export function bootContractEventBridge(
  apiBase: string,
  options: {
    eventSourceFactory?: (url: string) => EventSource;
  } = {},
): void {
  if (bridgeStream) {
    return;
  }
  const url = `${apiBase.replace(/\/$/, '')}/infra/capabilities/stream`;

  // Reducer mirrors the CapabilitiesHandshake reset-counter pattern:
  // pure update of state when an UPDATE frame arrives; the side-effect
  // dispatch happens on observation.
  const stream = new EnvelopeStream<{
    updateSeq: number;
    lastPayload: unknown;
  }>({
    url,
    initialState: { updateSeq: 0, lastPayload: null },
    reducer: (state, env: SseEnvelope) => {
      if (env.frameKind !== 'UPDATE') return state;
      return { updateSeq: state.updateSeq + 1, lastPayload: env.payload };
    },
    eventSourceFactory: options.eventSourceFactory,
  });

  let lastSeenSeq = 0;
  bridgeUnsubscribe = stream.subscribe((s) => {
    if (s.payload.updateSeq <= lastSeenSeq) return;
    lastSeenSeq = s.payload.updateSeq;
    const event = decodeContractEvent(s.payload.lastPayload);
    if (event && isContractEvent(event)) {
      dispatchContractEvent(event);
    }
  });

  // Wire the catalog-refresh consumer subscription. This discharges
  // slice 3a.1.9 §B.B.E.1's deferred follow-up: catalog clients now
  // receive `catalog-membership-changed` events and refresh on
  // mid-session change, not just on boot fetch.
  catalogRefreshUnsubscribe = subscribe(
    'catalog-membership-changed',
    (event) => {
      if (event.category === 'resource') {
        void refreshResourceCatalog(apiBase);
      } else if (event.category === 'operation') {
        void refreshOperationCatalog(apiBase);
      }
      // Other catalog Categories (severity, reason-code, etc.) join
      // when they get FE clients. The substrate's contract-event
      // channel is shape-stable; new categories add a switch arm.
    },
  );

  stream.start();
  bridgeStream = stream;
}

/** Stop the bridge. Idempotent. */
export function stopContractEventBridge(): void {
  if (catalogRefreshUnsubscribe) {
    catalogRefreshUnsubscribe();
    catalogRefreshUnsubscribe = null;
  }
  if (bridgeUnsubscribe) {
    bridgeUnsubscribe();
    bridgeUnsubscribe = null;
  }
  if (bridgeStream) {
    bridgeStream.stop();
    bridgeStream = null;
  }
}

/** Test-only: assert the bridge is stopped (for cleanup verification). */
export function __isRunningForTest(): boolean {
  return bridgeStream !== null;
}
