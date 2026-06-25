/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for the {@code worker.documents.indexed.rate_per_sec}
 * TIMESERIES Resource — slice 3a.1.4b cohort follow-up.
 *
 * <p>Mirrors {@link JobQueueDepthMetricChangeRegistry}; per-Resource StreamId scopes
 * the channel.
 */
public final class DocumentsIndexedRateMetricChangeRegistry {

  public static final StreamId STREAM_ID =
      StreamId.surface("metric-worker-documents-indexed-rate-per-sec");

  private final SseStreamChannel channel;

  public DocumentsIndexedRateMetricChangeRegistry() {
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
