/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for the {@code gpu.utilization.percent} TIMESERIES Resource —
 * slice 3a.1.4b cohort follow-up.
 *
 * <p>Mirrors {@link JobQueueDepthMetricChangeRegistry}.
 */
public final class GpuUtilizationMetricChangeRegistry {

  public static final StreamId STREAM_ID = StreamId.surface("metric-gpu-utilization-percent");

  private final SseStreamChannel channel;

  public GpuUtilizationMetricChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  public long currentSeq() {
    return channel.currentSeq();
  }

  public SseStreamChannel channel() {
    return channel;
  }

  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  public void broadcast(TimeseriesSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    channel.publish(SseFrameKind.UPDATE, snapshot);
  }
}
