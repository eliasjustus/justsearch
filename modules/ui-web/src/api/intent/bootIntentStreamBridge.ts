// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 487 §4.3 — boot-time SSE-to-IntentRouter bridge for the always-on
 * `/api/intent/stream` channel.
 *
 * Wires backend-originated `intent.envelope` UPDATE frames into the FE
 * `IntentRouter.dispatch` entry. Pattern mirrors
 * `api/contract/contractEventsBridge.ts` (the slice-436 capabilities stream
 * boot) but with three deliberate divergences specific to event-only streams:
 *
 *  1. **Stable-id dedup, not seq-counter dedup.** Replay-on-reconnect via
 *     the 9000-frame ring buffer can re-emit the same envelope after a
 *     network blip; the FE LRU keyed on `payload.id` prevents
 *     double-dispatch of (e.g.) a destructive operation. Modeled on
 *     `updateSeq` counter pattern but keyed on the stable id from the
 *     server-assigned envelope id.
 *
 *  2. **No snapshot consumption.** The intent stream is event-only
 *     (tempdoc §4.3): the controller skips the `snapshot` lifecycle frame,
 *     so this bridge never receives one. On reconnect-miss the substrate
 *     emits `reset` alone; we clear the LRU and continue.
 *
 *  3. **Direct dispatch into IntentRouter.** Unlike the contract-events
 *     bridge (which decodes payloads and dispatches via a separate event
 *     bus), this bridge unwraps the `Intent` envelope and feeds the
 *     existing `intentRouter.dispatch(intent)` — preserving slice 489 §10's
 *     "exactly one path to surface activation" commitment by routing
 *     remote envelopes through the same code path local intents take.
 */

import type { IntentRouter } from '../../shell-v0/router/intentRouter.js';
import type { Intent } from '../../shell-v0/router/types.js';
import { EnvelopeStream } from '../../shell-v0/streaming/EnvelopeStream.js';
import type { SseEnvelope } from '../../shell-v0/streaming/envelope-types.js';

/** Bounded LRU of seen envelope ids, sized to match the server ring buffer (9000). */
const DEDUP_LRU_SIZE = 9000;

interface IntentEnvelopePayload {
  readonly kind?: string;
  readonly id?: string;
  readonly intent?: Intent;
  // Other fields (provenance, sourceId) are recorded server-side; not consumed here.
}

/**
 * Boot the intent-stream bridge.
 *
 * Idempotent: a second call while the bridge is running is a no-op.
 *
 * @param apiBase Absolute API base (e.g., `http://127.0.0.1:33221`).
 * @param intentRouter The FE IntentRouter that receives remote envelopes.
 * @param options.eventSourceFactory Test seam.
 * @returns A teardown handle. Calling it stops the underlying EventSource
 *     and clears the dedup LRU.
 */
export function bootIntentStreamBridge(
  apiBase: string,
  intentRouter: IntentRouter,
  options: {
    eventSourceFactory?: (url: string) => EventSource;
  } = {},
): () => void {
  if (bridgeStream) {
    return stopIntentStreamBridge;
  }
  const url = `${apiBase.replace(/\/$/, '')}/api/intent/stream`;

  // Bounded LRU of seen envelope ids. Uses an ordered Map: insertion order is the
  // dedup order; eviction trims oldest entries when size exceeds DEDUP_LRU_SIZE.
  const seenIds = new Map<string, true>();

  const stream = new EnvelopeStream<{ lastPayload: IntentEnvelopePayload | null }>({
    url,
    initialState: { lastPayload: null },
    reducer: (state, env: SseEnvelope) => {
      // LIFECYCLE: kind=reset signals replay-window-miss; clear the LRU so we accept
      // any subsequent (potentially re-emitted) ids fresh. Other lifecycle kinds
      // (connected, heartbeat, closing) are no-ops for state.
      if (env.frameKind === 'LIFECYCLE') {
        const payload = env.payload as { kind?: string } | null;
        if (payload?.kind === 'reset') {
          seenIds.clear();
        }
        return state;
      }
      // UPDATE: record the most recent payload so the subscriber observes it.
      return { lastPayload: env.payload as IntentEnvelopePayload | null };
    },
    eventSourceFactory: options.eventSourceFactory,
  });

  bridgeUnsubscribe = stream.subscribe((snapshot) => {
    const payload = snapshot.payload.lastPayload;
    if (!payload) return;
    if (payload.kind !== 'intent.envelope') return;
    if (!payload.id) return;
    if (seenIds.has(payload.id)) return; // dedup hit — silently drop the replay.
    seenIds.set(payload.id, true);
    // LRU trim: oldest insertion is the first key in the Map iteration order.
    while (seenIds.size > DEDUP_LRU_SIZE) {
      const oldest = seenIds.keys().next().value;
      if (oldest === undefined) break;
      seenIds.delete(oldest);
    }
    if (!payload.intent) return;
    void intentRouter.dispatch(payload.intent);
  });

  stream.start();
  bridgeStream = stream;
  return stopIntentStreamBridge;
}

/** Singleton state (module-scoped — at most one intent-stream bridge per FE process). */
let bridgeStream: EnvelopeStream<{
  lastPayload: IntentEnvelopePayload | null;
}> | null = null;
let bridgeUnsubscribe: (() => void) | null = null;

/** Stop the bridge. Idempotent. */
export function stopIntentStreamBridge(): void {
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
