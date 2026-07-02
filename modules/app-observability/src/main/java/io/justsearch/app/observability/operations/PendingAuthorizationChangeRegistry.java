/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@link PendingAuthorizationEvent}s — tempdoc 655.
 *
 * <p>Mirrors {@link io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry}'s shape:
 * event-only (no snapshot lifecycle — there is no "current pending set" to reconstruct on
 * subscribe, each creation is its own self-contained announcement), one stable {@link #STREAM_ID}.
 *
 * <p>Exists so a caller surface other than the live HTTP request that triggered a confirmation
 * gate (concretely: {@code McpToolSurface}, which has no in-flight frontend request to attach a
 * 428 response to) can still notify the always-on frontend shell that a human approval is now
 * waiting. {@code OperationsController}'s existing 428 path broadcasts here too, so the frontend
 * has one uniform signal regardless of which transport triggered the gate; a dialog already open
 * for a given {@code pendingId} is expected to de-duplicate against a redundant announcement.
 */
public final class PendingAuthorizationChangeRegistry {

  /** Stable StreamId for the pending-authorization stream. */
  public static final StreamId STREAM_ID = StreamId.system("pending-authorizations");

  private final SseStreamChannel channel;

  /** Default constructor: fresh channel backed by {@link #STREAM_ID}. */
  public PendingAuthorizationChangeRegistry() {
    this(new SseStreamChannel(STREAM_ID));
  }

  /** Test/bootstrap constructor: injected channel. */
  public PendingAuthorizationChangeRegistry(SseStreamChannel channel) {
    this.channel = Objects.requireNonNull(channel, "channel");
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

  /**
   * Tempdoc 655 — typed-listener convenience (mirrors {@code HealthEventChangeRegistry
   * #subscribeTyped}). Lets a bootstrap-time consumer (the new {@code authorization.pending}
   * Advisory projector) subscribe without hand-unwrapping the generic envelope.
   */
  public SseStreamChannel.Subscription subscribeTyped(Consumer<PendingAuthorizationEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    return channel.subscribe(
        env -> {
          if (env.payload() instanceof PendingAuthorizationEvent event) {
            listener.accept(event);
          }
        });
  }

  /**
   * Broadcasts a newly-created pending authorization. Assigns the next monotonic seq, appends the
   * frame to the ring buffer (for replay on FE reconnect), and delivers to every active listener.
   */
  public void broadcast(PendingAuthorizationEvent event) {
    Objects.requireNonNull(event, "event");
    channel.publish(SseFrameKind.UPDATE, event);
  }
}
