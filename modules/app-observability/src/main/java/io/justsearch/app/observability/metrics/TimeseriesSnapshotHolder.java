/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-value in-memory holder for the current sliding-window {@link TimeseriesSnapshot}
 * of a TIMESERIES Resource (slice 3a.1.4 Phase 5).
 *
 * <p>Mirrors the {@code RuntimeContextHolder} pattern (slice 440): the head's authoritative
 * replica of the current snapshot. TIMESERIES Resources stream replace-on-frame (the
 * values[] array represents the whole current window); a snapshot fetch reads from this
 * holder; SSE consumers receive the snapshot on connect plus replace broadcasts on
 * subsequent producer-driven updates.
 *
 * <p>{@link #set} is unconditional — the producer (e.g.,
 * {@code JobQueueDepthMetricProducer}) is responsible for deciding when to replace +
 * broadcast. Decoupling holder mutation from broadcast mirrors the
 * {@code ConditionStore}/{@code HealthEventChangeRegistry} discipline used elsewhere.
 *
 * <p>Initial state is {@code null} until the first producer tick lands a snapshot — the
 * snapshot endpoint should treat null as 503 / "no data yet" since the RRD store needs at
 * least 2 samples to assemble a meaningful window.
 */
public final class TimeseriesSnapshotHolder {

  private final AtomicReference<TimeseriesSnapshot> current = new AtomicReference<>();

  /** Returns the current snapshot. {@code null} until the first producer tick. */
  public TimeseriesSnapshot current() {
    return current.get();
  }

  /** Replaces the current snapshot unconditionally. Returns the previous (or {@code null}). */
  public TimeseriesSnapshot set(TimeseriesSnapshot next) {
    return current.getAndSet(next);
  }
}
