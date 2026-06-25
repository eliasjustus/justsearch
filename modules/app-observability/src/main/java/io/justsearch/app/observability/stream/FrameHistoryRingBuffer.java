/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded per-stream ring buffer of recent {@link SseEnvelope} frames for resume.
 *
 * <p>Per slice 436 §B.4: a reconnecting consumer with a recent {@code resumeToken}
 * receives only frames newer than the token's seq; outside the window the controller
 * emits {@code reset + snapshot} instead. This buffer holds the recent-frames window.
 *
 * <p>Eviction is monotonic: appending past capacity drops the oldest frame.
 * {@link #framesSince(long)} returns the chronological tail of frames whose
 * {@code seq > sinceSeq}; if {@code sinceSeq} is older than the oldest retained frame,
 * the caller (controller) interprets this as "outside resume window" and emits a reset.
 *
 * <p>Default capacity matches slice 436 §B.4's heuristic: {@code MAX_FRAME_RATE *
 * HEARTBEAT_INTERVAL_S * 10 ≈ 9000} frames at 30fps × 30s × 10. This is a per-stream
 * default; streams with different cadence override.
 */
public final class FrameHistoryRingBuffer {

  /** Default capacity (frames) per slice 436 §B.4. */
  public static final int DEFAULT_CAPACITY = 9000;

  private final int capacity;
  private final Deque<SseEnvelope> frames = new ArrayDeque<>();

  public FrameHistoryRingBuffer() {
    this(DEFAULT_CAPACITY);
  }

  public FrameHistoryRingBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
    }
    this.capacity = capacity;
  }

  public int capacity() {
    return capacity;
  }

  public synchronized int size() {
    return frames.size();
  }

  /** Appends {@code frame}; if at capacity, evicts the oldest. */
  public synchronized void append(SseEnvelope frame) {
    Objects.requireNonNull(frame, "frame");
    if (frames.size() >= capacity) {
      frames.pollFirst();
    }
    frames.addLast(frame);
  }

  /**
   * Returns the chronological tail of frames whose {@code seq > sinceSeq}. Returns an
   * empty list if no frames are newer than {@code sinceSeq}.
   *
   * <p>If the caller's {@code sinceSeq} is older than the oldest retained frame's seq,
   * the buffer doesn't have the gap — the caller should interpret this as "outside resume
   * window" and emit a {@code reset + snapshot} sequence. Use {@link #oldestSeqOrZero()}
   * to detect this case.
   */
  public synchronized List<SseEnvelope> framesSince(long sinceSeq) {
    if (frames.isEmpty()) {
      return List.of();
    }
    List<SseEnvelope> out = new ArrayList<>();
    for (SseEnvelope f : frames) {
      if (f.seq() > sinceSeq) {
        out.add(f);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Returns the seq of the oldest retained frame, or 0 if the buffer is empty. Callers use
   * this to detect "resume token predates the buffer" — when {@code sinceSeq <
   * oldestSeqOrZero()}, the caller emits a reset.
   */
  public synchronized long oldestSeqOrZero() {
    return frames.isEmpty() ? 0L : frames.peekFirst().seq();
  }
}
