/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.intent;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@link IntentEnvelopeEvent}s on the always-on intent stream.
 *
 * <p>Per tempdoc 487 §4.3 (the {@code SseIntentForwardingTransport} realization): the
 * registry wraps each backend-emitted intent envelope in the universal SSE envelope
 * (slice 436), retains it in the per-stream ring buffer for resume, and fans out to
 * envelope-shaped listeners. The SSE controller ({@code IntentStreamController})
 * subscribes via {@link #subscribe(Consumer)}.
 *
 * <p>Mirrors {@code AdvisoryChangeRegistry}'s shape; the differences
 * are payload type ({@link IntentEnvelopeEvent}) and stream id
 * ({@link #STREAM_ID} = {@code system:intent-envelopes}).
 *
 * <p><strong>No dedupe at the registry layer.</strong> The tempdoc §4.3 dedup story
 * lives at the FE reducer (LRU keyed on {@link IntentEnvelopeEvent#id()}), not server-side.
 * Server-side dedup would defeat the legitimate use-case of an LLM emitting the same URL
 * twice in one response on purpose; only the *replay* case needs dedup, and that is a
 * client-side concern because the same server emission has a single stable id.
 *
 * <p><strong>No snapshot lifecycle.</strong> The intent stream is event-only — there is
 * no "current state" to snapshot on subscribe. {@code IntentStreamController} sends only
 * {@code connected} on subscribe (no {@code snapshot}); on reconnect-miss the substrate
 * emits {@code reset} and the FE clears its dedup LRU and resumes live forwarding. This
 * is the platform's first event-only always-on stream and a deliberate divergence from
 * the four existing state-shaped always-on streams.
 */
public final class IntentEnvelopeChangeRegistry {

  /** Stable StreamId for the intent-envelope stream. */
  public static final StreamId STREAM_ID = StreamId.system("intent-envelopes");

  private final SseStreamChannel channel;

  /** Default constructor: fresh channel backed by {@link #STREAM_ID}. */
  public IntentEnvelopeChangeRegistry() {
    this(new SseStreamChannel(STREAM_ID));
  }

  /** Test/bootstrap constructor: injected channel. */
  public IntentEnvelopeChangeRegistry(SseStreamChannel channel) {
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
   * Broadcasts an intent envelope. Assigns the next monotonic seq, appends the frame to
   * the ring buffer (for replay on FE reconnect), and delivers to every active
   * envelope-listener.
   */
  public void broadcast(IntentEnvelopeEvent event) {
    Objects.requireNonNull(event, "event");
    channel.publish(SseFrameKind.UPDATE, event);
  }
}
