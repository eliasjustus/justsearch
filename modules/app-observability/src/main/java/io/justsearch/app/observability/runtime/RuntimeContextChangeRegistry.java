/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.runtime;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@link RuntimeContext} replace-stream changes.
 *
 * <p>Per slice 440 (substrate) + slice 436 retrofit (post-impl): the registry now
 * delegates to a per-stream {@link SseStreamChannel} so every broadcast is wrapped in
 * the universal envelope shape and retained in the channel's ring buffer for resume.
 *
 * <p>STATE Resource discipline (replace-only): each broadcast carries the full new
 * RuntimeContext, not a delta. Subscribers receive an UPDATE envelope whose payload IS
 * the new RuntimeContext.
 */
public final class RuntimeContextChangeRegistry {

  /** Stable StreamId for the runtime-context stream (per slice 436 §B.3). */
  public static final StreamId STREAM_ID = StreamId.system("runtime-context");

  private final SseStreamChannel channel;

  public RuntimeContextChangeRegistry() {
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

  /**
   * Broadcasts a state change. The RuntimeContext IS the payload (STATE replace-only).
   */
  public void broadcast(RuntimeContext context) {
    Objects.requireNonNull(context, "context");
    channel.publish(SseFrameKind.UPDATE, context);
  }
}
