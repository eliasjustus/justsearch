/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@code core.condition-recovery-index} STATE replace-stream
 * changes.
 *
 * <p>Per slice 447 §X.3.4 + §X.11.5 follow-up Phase 4: the index is a derived view of
 * {@link ConditionStore}. On each ConditionStore mutation, the bridge in
 * {@code HeadAssembly} rebuilds the index via {@link ConditionRecoveryIndexBuilder}
 * and broadcasts the new snapshot through this registry.
 *
 * <p>STATE Resource discipline (replace-only): each broadcast carries the full new
 * {@link ConditionRecoveryIndex}, not a delta. Subscribers receive an UPDATE envelope
 * whose payload IS the new index.
 */
public final class ConditionRecoveryIndexChangeRegistry {

  /** Stable StreamId for the condition-recovery-index stream. */
  public static final StreamId STREAM_ID = StreamId.system("condition-recovery-index");

  private final SseStreamChannel channel;

  public ConditionRecoveryIndexChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  /** Returns the current monotonic seq cursor. */
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

  /** Broadcasts a state change. The ConditionRecoveryIndex IS the payload (STATE replace-only). */
  public void broadcast(ConditionRecoveryIndex index) {
    Objects.requireNonNull(index, "index");
    channel.publish(SseFrameKind.UPDATE, index);
  }
}
