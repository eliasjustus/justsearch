/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for the {@code metric:worker.job_queue.depth} TIMESERIES
 * Resource (slice 3a.1.4 Phase 5).
 *
 * <p>Per slice 444a (typed Category substrate) + slice 436 (universal SSE envelope) + slice
 * 3a.1.4 §B.10: each broadcast carries the current sliding window as a
 * {@link TimeseriesSnapshot}. Subscribers receive an UPDATE envelope whose payload IS the
 * snapshot — TIMESERIES is replace-on-frame (the values[] array represents the whole
 * window; not a delta).
 *
 * <p>Mirrors the {@code RuntimeContextChangeRegistry} pattern (slice 440): wraps a
 * per-stream {@link SseStreamChannel} so every broadcast is wrapped in the universal
 * envelope shape and retained in the channel's ring buffer for resume.
 *
 * <p>StreamId convention: {@code surface:metric:worker.job_queue.depth}. Future TIMESERIES
 * Resources for other metrics each get their own change registry + StreamId.
 */
public final class JobQueueDepthMetricChangeRegistry {

  /**
   * Stable StreamId for the job-queue-depth metric stream.
   *
   * <p>StreamId regex enforces {@code ^(registry|surface|system):[a-z][a-z0-9-]*$} — trailing
   * segment is dashed-lowercase, no colons or dots. The Resource id (per
   * {@link io.justsearch.agent.api.registry.OperationRef}) follows the same convention.
   */
  public static final StreamId STREAM_ID =
      StreamId.surface("metric-worker-job-queue-depth");

  private final SseStreamChannel channel;

  public JobQueueDepthMetricChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  /** Returns the current monotonic seq cursor (catalog version analog). */
  public long currentSeq() {
    return channel.currentSeq();
  }

  /** Returns the underlying channel for controller-side per-connection writer wiring. */
  public SseStreamChannel channel() {
    return channel;
  }

  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  /**
   * Broadcasts a snapshot replacement. The {@link TimeseriesSnapshot} IS the payload —
   * TIMESERIES Resources stream replace-on-frame, not deltas.
   */
  public void broadcast(TimeseriesSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    channel.publish(SseFrameKind.UPDATE, snapshot);
  }
}
