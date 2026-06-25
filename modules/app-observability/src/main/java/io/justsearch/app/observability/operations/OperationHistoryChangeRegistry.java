/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@link OperationHistoryEntry} appends.
 *
 * <p>Per slice 444b (substrate) + slice 436 retrofit (post-impl): the registry now
 * delegates to a per-stream {@link SseStreamChannel} so every broadcast is wrapped in
 * the universal envelope shape.
 *
 * <p>EVENT_STREAM × RING_BUFFER discipline (post-Fix-A reclassification): broadcast
 * carries an UPDATE envelope whose payload is the appended {@link OperationHistoryEntry}.
 * The channel's frame-history ring buffer holds recent UPDATE frames for resume support.
 */
public final class OperationHistoryChangeRegistry {

  /** Stable StreamId for the operation-history stream (per slice 436 §B.3). */
  public static final StreamId STREAM_ID = StreamId.surface("operation-history");

  private final SseStreamChannel channel;

  public OperationHistoryChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  /** Returns the current monotonic seq cursor (replaces the legacy catalogVersion). */
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

  /** Broadcasts an append. The entry IS the payload. */
  public void broadcast(OperationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    channel.publish(SseFrameKind.UPDATE, entry);
  }
}
