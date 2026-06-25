// SPDX-License-Identifier: Apache-2.0
/**
 * Brain install/pack liveness authority (FE) — tempdoc 575 §17 Face C.
 *
 * The polled-state analogue of {@link inFlightLiveness}'s `isInFlightLive`. Brain install/pack are a
 * POLLED-STATE liveness model (the backend owns `state` + `updatedAtEpochMs`; the FE polls it). This
 * is the ONE place that decides whether a "running" install/pack is still live from its backend
 * status timestamp — so a "stalled" presentation cannot be re-implemented per render site (the
 * `inflight-liveness` gate registers this as the brain-install domain's authority).
 *
 * The window here is the FE's EARLY, softer warning (a "stalled" badge while still running). It is
 * deliberately shorter than the backend reaper's `STALE_RUNNING_MS` (which TERMINALIZES the wedged
 * owner on read) — so the user sees "stalled" well before the backstop reclaims it, mirroring how the
 * indexing badge demotes (90s) earlier than the worker reaper (5min).
 */

/** FE "stalled" display window (ms) for a running install/pack with no backend progress. */
export const AI_INSTALL_STALE_MS = 60_000;

/**
 * Is a "running" polled AI install/pack still live? True iff its backend status timestamp
 * (`updatedAtEpochMs`, epoch ms) is positive and within the freshness window. A never-touched
 * timestamp (<= 0) is treated as not-yet-live (do not flag a just-started run as stalled).
 */
export function isAiInstallLive(updatedAtEpochMs: number, now: number = Date.now()): boolean {
  return updatedAtEpochMs > 0 && now - updatedAtEpochMs < AI_INSTALL_STALE_MS;
}
