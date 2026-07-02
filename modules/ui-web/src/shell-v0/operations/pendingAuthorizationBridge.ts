// SPDX-License-Identifier: Apache-2.0
/**
 * pendingAuthorizationBridge — tempdoc 655.
 *
 * Surfaces a backend-created {@code PendingAuthorization} in the running app even when the
 * frontend never made the request that triggered the gate — concretely, an MCP tool call from
 * an external AI agent. Today's `authorizationBroker` ceremony is otherwise strictly
 * request-scoped: a gated `fetch` gets a 428 with a `pendingId` in THAT SAME response, and
 * `OperationClient.invokeWithConsent` hands it to the broker. An MCP-originated gate has no such
 * in-flight request for the frontend to observe.
 *
 * Pattern mirrors `api/intent/bootIntentStreamBridge.ts` (tempdoc 662's multiplexed-stream
 * subscriber shape) and `substrates/tasks/indexingJobsBridge.ts`'s `startXBridge(apiBase, opts)`
 * boot signature — subscribes the `system:pending-authorizations` streamId (event-only, no
 * snapshot lifecycle — each creation is its own self-contained announcement, same as the intent
 * stream) on the shared `MultiplexedStream`.
 *
 * On each announcement, this reuses the EXISTING ceremony (`authorizationBroker.requestAuthorization`
 * — the same `<jf-authorization-host>` dialog a live REST 428 already uses) rather than a new
 * presentation surface. Two genuinely new steps, both tempdoc 655 fix-pass additions:
 *
 * 1. The broadcast itself deliberately carries NO decision content (no args summary, no
 *    rationale) — only routing info (id, operation id, tiers). Putting that content on a channel
 *    every local subscriber receives would violate the existing privacy posture that scopes it
 *    to the point-to-point 428 response (tempdoc 444b / 550 F3 — see `PendingAuthorizationEvent`'s
 *    doc comment). So this bridge fetches the content itself, by id, via
 *    `OperationClient.peekPending` — the two-step shape mirrors what the REST 428 path gets in
 *    one response, just split into "learn it exists" (broadcast) then "learn what it is" (fetch).
 * 2. Because the frontend never held this pending's original arguments (it wasn't the caller),
 *    approval can't complete by the browser re-`invoke`-ing with a capsule the way
 *    `OperationClient.invokeWithConsent` does for its own gated calls. Instead it calls
 *    `OperationClient.approveAndExecutePending`, which asks the backend to complete the dispatch
 *    itself, server-side, using the pending's own stored args — the design tempdoc 655 settled on
 *    specifically so the MCP tool call never has to block or be retried by the calling agent.
 */

import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';
import { requestAuthorization, type AuthorizationPrompt } from './authorizationBroker.js';
import { getOperationClient } from './OperationClient.js';

/** Wire shape of the SLIMMED `PendingAuthorizationEvent` — routing info only, no decision content. */
interface PendingAuthorizationPayload {
  readonly pendingId?: string;
  readonly operationId?: string;
  readonly sourceTier?: string;
  readonly riskTier?: string;
  readonly gateBehavior?: string;
}

/** Bounded so a long-running session's dedup set can't grow unbounded. */
const HANDLED_LRU_SIZE = 500;

/**
 * Boot the bridge. Idempotent per call site — unlike `bootIntentStreamBridge`, this has no
 * module-level singleton guard because (mirroring `startIndexingJobsBridge`) the caller (Shell)
 * owns a single boot call and the returned teardown handle.
 *
 * @returns Teardown: unsubscribes from the multiplexed stream. No-op (and a no-op start) when
 *     `multiplex` isn't supplied — this stream only exists on the multiplexed transport, unlike
 *     older single-purpose bridges that also supported a standalone `EventSource` fallback.
 */
export function startPendingAuthorizationBridge(
  apiBase: string,
  opts: { multiplex?: MultiplexedStream } = {},
): () => void {
  if (!opts.multiplex) {
    return () => {};
  }
  const multiplex = opts.multiplex;

  // Dedup by pendingId: a reconnect can replay the ring buffer, and a gate that ALSO reached a
  // live REST caller (unlikely for MCP, always true for the browser's own 428s, which are
  // broadcast here too for a uniform signal) would otherwise be presented twice.
  const handledIds = new Map<string, true>();

  const stopSub = multiplex.subscribe<{ lastPayload: PendingAuthorizationPayload | null }>(
    SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS,
    () => ({
      initialState: { lastPayload: null },
      reducer: (state, env: SseEnvelope) => {
        // Event-only stream (no snapshot to consume) — LIFECYCLE frames (connected/heartbeat/
        // reset) carry no state this bridge needs; only UPDATE frames matter.
        if (env.frameKind === 'LIFECYCLE') {
          return state;
        }
        return { lastPayload: env.payload as PendingAuthorizationPayload | null };
      },
    }),
    (snapshot) => {
      const payload = snapshot.payload.lastPayload;
      if (!payload?.pendingId || !payload.operationId) {
        return;
      }
      if (handledIds.has(payload.pendingId)) {
        return;
      }
      handledIds.set(payload.pendingId, true);
      while (handledIds.size > HANDLED_LRU_SIZE) {
        const oldest = handledIds.keys().next().value;
        if (oldest === undefined) break;
        handledIds.delete(oldest);
      }
      void presentAndExecute(apiBase, payload);
    },
  );

  return () => {
    stopSub();
  };
}

async function presentAndExecute(
  apiBase: string,
  payload: PendingAuthorizationPayload,
): Promise<void> {
  const pendingId = payload.pendingId as string;
  const client = getOperationClient(apiBase);

  // Tempdoc 655 fix pass: the broadcast carries no decision content — fetch it by id before
  // presenting. If it 404s, the pending already expired or was consumed elsewhere between the
  // broadcast and this fetch; skip presenting rather than show a broken/empty prompt.
  let detail;
  try {
    detail = await client.peekPending(pendingId);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn('Tempdoc 655: failed to fetch pending authorization detail', err);
    return;
  }
  if (!detail) {
    return;
  }

  const prompt: AuthorizationPrompt = {
    pendingId,
    operationId: detail.operationId,
    gateBehavior: payload.gateBehavior ?? detail.gateBehavior ?? 'TYPED_CONFIRM',
    ...(detail.riskTier ? { riskTier: detail.riskTier } : {}),
    ...(detail.argsSummary ? { argsSummary: detail.argsSummary } : {}),
    ...(detail.rationale ? { purpose: detail.rationale } : {}),
    ...(detail.requestedBy ? { requestedBy: detail.requestedBy } : {}),
  };
  const decision = await requestAuthorization(prompt);
  if (!decision.approved) {
    // Fail-closed default (no host mounted) or an explicit deny — either way, nothing more to
    // do. The pending record itself expires server-side (5-minute TTL) if never approved; there
    // is no separate "reject" round trip to make for an MCP-originated pending.
    return;
  }
  try {
    await client.approveAndExecutePending(pendingId, decision.allowAlways);
  } catch (err) {
    // Best-effort: the approval ceremony already resolved (the human said yes), so there's no
    // live caller awaiting this promise to report a failure to, unlike the REST-triggered path
    // (invokeWithConsent), which throws back into its own call stack.
    // eslint-disable-next-line no-console
    console.warn('Tempdoc 655: pending authorization approved but execution failed', err);
  }
}
