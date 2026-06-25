/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai;

/**
 * The polled-state liveness law (tempdoc 575 §17 Face C).
 *
 * <p>Install and pack-import are <em>polled-state</em> liveness models: the backend owns a {@code
 * state} + an {@code updatedAtEpochMs} timestamp, and the FE polls it. Unlike the indexing worker
 * (which has a heartbeat-lease + the {@code recoverStuckJobs} reaper), these had <em>no backstop</em>
 * — a wedged "running" owner would be polled forever. This is the one shared decision both services'
 * lazy reaper consumes: a "running" status whose owner stopped beating (no progress past the stale
 * window) is a dead owner to reclaim. Pure + injectable-clock so it is unit-testable without
 * constructing a service (the backend analogue of the FE {@code isInFlightLive} authority).
 */
public final class PolledStateLiveness {

  private PolledStateLiveness() {}

  /**
   * @return true iff {@code state} is "running" but its owner is stale — i.e. it has a positive
   *     {@code updatedAtEpochMs} that is older than {@code staleMs} relative to {@code nowMs}. A
   *     non-running state, or a never-touched ({@code <= 0}) timestamp, is never stale-running.
   */
  public static boolean isStaleRunning(
      String state, long updatedAtEpochMs, long nowMs, long staleMs) {
    return "running".equals(state)
        && updatedAtEpochMs > 0
        && nowMs - updatedAtEpochMs > staleMs;
  }
}
