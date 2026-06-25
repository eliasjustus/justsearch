package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FrameHistoryRingBuffer")
final class FrameHistoryRingBufferTest {

  private static final StreamId STREAM = StreamId.registry("capabilities");

  private static SseEnvelope frame(long seq) {
    return new SseEnvelope(
        STREAM,
        SseFrameKind.UPDATE,
        seq,
        Instant.parse("2026-04-30T12:00:00Z"),
        Map.of("seq-marker", seq),
        "tok-" + seq);
  }

  @Test
  @DisplayName("default capacity is 9000 frames")
  void defaultCapacity() {
    assertEquals(9000, new FrameHistoryRingBuffer().capacity());
  }

  @Test
  @DisplayName("rejects non-positive capacity")
  void rejectsBadCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new FrameHistoryRingBuffer(0));
    assertThrows(IllegalArgumentException.class, () -> new FrameHistoryRingBuffer(-1));
  }

  @Test
  @DisplayName("framesSince returns chronological tail strictly newer than sinceSeq")
  void framesSinceFiltersStrictlyNewer() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(100);
    for (long s = 1; s <= 5; s++) {
      buf.append(frame(s));
    }

    List<SseEnvelope> tail = buf.framesSince(2L);
    assertEquals(3, tail.size());
    assertEquals(3L, tail.get(0).seq());
    assertEquals(5L, tail.get(2).seq());
  }

  @Test
  @DisplayName("framesSince(0) returns the full buffer")
  void framesSinceZero() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(100);
    buf.append(frame(1));
    buf.append(frame(2));

    List<SseEnvelope> all = buf.framesSince(0L);
    assertEquals(2, all.size());
  }

  @Test
  @DisplayName("framesSince(latest) returns empty")
  void framesSinceLatest() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(100);
    buf.append(frame(1));
    buf.append(frame(2));

    assertTrue(buf.framesSince(2L).isEmpty());
  }

  @Test
  @DisplayName("oldest frame evicted when capacity exceeded")
  void evictsOldest() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(3);
    for (long s = 1; s <= 5; s++) {
      buf.append(frame(s));
    }

    assertEquals(3, buf.size());
    assertEquals(3L, buf.oldestSeqOrZero(), "oldest after eviction is seq=3");
    List<SseEnvelope> all = buf.framesSince(0L);
    assertEquals(3, all.size());
    assertEquals(3L, all.get(0).seq());
    assertEquals(5L, all.get(2).seq());
  }

  @Test
  @DisplayName("oldestSeqOrZero returns 0 when empty")
  void oldestEmpty() {
    assertEquals(0L, new FrameHistoryRingBuffer().oldestSeqOrZero());
  }

  @Test
  @DisplayName("token predates buffer detected via oldestSeqOrZero")
  void tokenPredatesBuffer() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(3);
    for (long s = 100; s <= 102; s++) {
      buf.append(frame(s));
    }

    // sinceSeq=50 predates the oldest retained (100). framesSince returns "all" but
    // caller detects the gap via oldestSeqOrZero > sinceSeq.
    long oldest = buf.oldestSeqOrZero();
    assertTrue(oldest > 50L, "caller detects token predates buffer");
    // and framesSince still returns the full buffer (the controller will discard and reset)
    assertEquals(3, buf.framesSince(50L).size());
  }
}
