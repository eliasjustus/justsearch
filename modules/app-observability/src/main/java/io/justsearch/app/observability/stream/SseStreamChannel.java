/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-stream coordinator unifying sequence + ring buffer + listener fan-out under the
 * universal SSE envelope shape.
 *
 * <p>Per slice 436: each stream (one per {@link StreamId}) has its own monotonic
 * sequence counter, ring buffer of recent UPDATE frames, and listener set. The channel
 * is the single integration point that stream producers (the various change registries)
 * delegate to.
 *
 * <p>Frame discipline:
 *
 * <ul>
 *   <li>{@link #publish(SseFrameKind, Object)} — broadcast frame: assigns next seq from
 *       the shared tracker, wraps in an envelope, appends to ring (only if
 *       {@link SseFrameKind#UPDATE}), notifies all listeners. Used for catalog/state
 *       updates that all subscribers see. Heartbeat lifecycle frames also typically use
 *       publish (broadcast to all clients).
 *   <li>{@link #nextEnvelope(SseFrameKind, Object)} — per-client frame: same envelope
 *       construction (with shared seq for monotonicity within the wire stream), but
 *       returns the envelope without appending to ring or broadcasting. The caller is
 *       expected to send it directly to a single connected client. Used for connected /
 *       snapshot / closing / reset lifecycle frames that vary per connection.
 * </ul>
 */
public final class SseStreamChannel {

  private final StreamId streamId;
  private final StreamSequenceTracker sequence;
  private final FrameHistoryRingBuffer history;
  private final Set<Consumer<SseEnvelope>> listeners = ConcurrentHashMap.newKeySet();
  private final Clock clock;

  public SseStreamChannel(StreamId streamId) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC());
  }

  public SseStreamChannel(
      StreamId streamId,
      StreamSequenceTracker sequence,
      FrameHistoryRingBuffer history,
      Clock clock) {
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.history = Objects.requireNonNull(history, "history");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public StreamId streamId() {
    return streamId;
  }

  /** Returns the current (most-recently-issued) sequence number. 0 before any frames. */
  public long currentSeq() {
    return sequence.current();
  }

  /**
   * Publishes a frame to all subscribers. Assigns the next monotonic seq, wraps in an
   * envelope, appends to ring (UPDATE only), broadcasts.
   *
   * <p>Listeners that throw are removed inline (mirrors the legacy registry pattern).
   */
  public void publish(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    SseEnvelope envelope = nextEnvelope(frameKind, payload);
    if (frameKind == SseFrameKind.UPDATE) {
      history.append(envelope);
    }
    listeners.removeIf(
        listener -> {
          try {
            listener.accept(envelope);
            return false;
          } catch (RuntimeException e) {
            return true;
          }
        });
  }

  /**
   * Creates and returns an envelope for a per-client frame WITHOUT appending to the ring
   * buffer or broadcasting. The seq is consumed from the shared tracker so the wire seq
   * remains monotonic within a single client connection. Used for connected / snapshot /
   * closing / reset lifecycle frames.
   */
  public SseEnvelope nextEnvelope(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    long seq = sequence.next();
    Instant ts = clock.instant();
    String resumeToken = ResumeTokenCodec.encode(streamId, seq);
    return new SseEnvelope(streamId, frameKind, seq, ts, payload, resumeToken);
  }

  /**
   * Returns frames retained in the ring buffer whose seq > sinceSeq, in chronological
   * order. Empty list if no frames newer than sinceSeq are retained.
   */
  public List<SseEnvelope> framesSince(long sinceSeq) {
    return history.framesSince(sinceSeq);
  }

  /**
   * Returns the seq of the oldest frame still retained in the ring buffer, or 0 if the
   * buffer is empty. Callers use this to detect "resume token predates the buffer."
   */
  public long oldestRetainedSeq() {
    return history.oldestSeqOrZero();
  }

  /** Subscribes a listener; returns a {@link Subscription} for explicit unsubscribe. */
  public Subscription subscribe(Consumer<SseEnvelope> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  @FunctionalInterface
  public interface Subscription {
    void unsubscribe();
  }
}
