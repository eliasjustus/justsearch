// SPDX-License-Identifier: Apache-2.0
/**
 * Origin-contact liveness authority — tempdoc 649.
 *
 * The ONE place that answers "is the backend reachable?" from the most recent POSITIVE CONTACT of
 * ANY channel — a `/api/status` / `/api/inference/status` poll success OR any frame/heartbeat on any
 * open `EnvelopeStream`. This is the multi-source-positive-contact sibling of the per-record liveness
 * authorities (`isInFlightLive` heartbeat-lease, `isAiInstallLive` polled-state) and is registered as
 * the `connection` domain in `governance/inflight-liveness-projections.v1.json`.
 *
 * WHY this exists (tempdoc 649): under load the cheap polls get starved behind the browser's
 * 6-per-host connection limit (the always-on SSE streams hold the sockets), so a poll-only
 * reachability signal wrongly flips to "Backend disconnected" while the backend is provably alive and
 * the streams are still heartbeating (15s). Reachability must therefore be EARNED by positive
 * evidence from any channel, and is kept SEPARATE from data-freshness (the poll-specific staleness in
 * `aiStateStore`). Going unreachable requires the absence of ALL positive contact.
 *
 * Layering: this module deliberately imports NO signal library, so the framework-agnostic
 * `EnvelopeStream` substrate can call {@link bumpOriginContact} on every frame without acquiring a
 * `state/` signal dependency and without triggering any listener fan-out. The reactive store reads the
 * stamp via {@link getLastOriginContactMs} on its existing staleness tick.
 */

import { STREAM_WATCHDOG_STALE_MS } from '../../api/generated/stream-liveness-constants.js';

/**
 * Epoch-ms of the most recent positive contact with the backend origin, across ALL channels. `null`
 * until the first contact. Module-global by design: it is a process-wide fact (one backend origin),
 * mirroring how the pollers track a single shared last-success.
 */
let lastOriginContactMs: number | null = null;

/**
 * Record a positive contact NOW. Called by the poll callbacks (on a successful poll) and by
 * `EnvelopeStream` on every received frame/heartbeat and on a clean open. Intentionally side-effect
 * minimal — it bumps the stamp and nothing else (no listener notify), so it is safe to call from the
 * SSE frame hot path.
 */
export function bumpOriginContact(now: number = Date.now()): void {
  lastOriginContactMs = now;
}

/** The most recent positive-contact stamp (epoch ms), or `null` if no contact has occurred yet. */
export function getLastOriginContactMs(): number | null {
  return lastOriginContactMs;
}

/**
 * THE reachability derivation: is the backend reachable? True iff a positive contact occurred within
 * the freshness window. The window defaults to {@link STREAM_WATCHDOG_STALE_MS} (the generated
 * single-source live-stream watchdog window, 40s = >2× the 15s heartbeat) — so reachability tolerates
 * a couple of missed heartbeats plus jitter before declaring the origin unreachable, and a `null`
 * stamp (no contact yet) is NOT reachable. The 15s poll-staleness window (data freshness) is a
 * separate, shorter concern owned by `aiStateStore` — do not conflate them.
 */
export function isOriginReachable(
  lastContactMs: number | null,
  now: number = Date.now(),
  windowMs: number = STREAM_WATCHDOG_STALE_MS,
): boolean {
  return lastContactMs !== null && now - lastContactMs < windowMs;
}

/** Test-only: clear the contact stamp so each test starts from "no contact yet". */
export function __resetOriginContactForTest(): void {
  lastOriginContactMs = null;
}
