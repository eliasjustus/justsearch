package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 834 §2 — two-tier retention: the byte bound and the evidence slot. */
@DisplayName("FrameRetentionPolicy — byte bound + evidence slot (834 S3a)")
final class FrameRetentionPolicyTest {

  private static final StreamId STREAM = StreamId.surface("test-stream");

  private static SseEnvelope frame(long seq, String name, int payloadChars) {
    return new SseEnvelope(
        STREAM,
        SseFrameKind.UPDATE,
        seq,
        Instant.parse("2026-08-14T12:00:00Z"),
        Map.of("name", name, "text", "x".repeat(payloadChars)),
        "tok-" + seq);
  }

  /** Classifies the run-shaped evidence frames tempdoc 834 §2 names, keyed by frame name. */
  private static final FrameRetentionPolicy.EvidenceClassifier BY_NAME =
      f -> {
        Object name = ((Map<?, ?>) f.payload()).get("name");
        return "rag.citations".equals(name) || "rag.meta".equals(name)
            ? Optional.of(String.valueOf(name))
            : Optional.empty();
      };

  @Test
  @DisplayName("DEFAULT is today's behaviour: 9000 frames, no byte bound, no evidence slot")
  void defaultPolicyIsPre834Behaviour() {
    FrameRetentionPolicy p = FrameRetentionPolicy.DEFAULT;
    assertEquals(FrameHistoryRingBuffer.DEFAULT_CAPACITY, p.maxFrames());
    assertEquals(FrameRetentionPolicy.UNBOUNDED_BYTES, p.maxBytes());
    assertFalse(p.tracksBytes(), "the 18 catalog routes must never pay for the sizer");
    assertFalse(p.evidenceSlotEnabled());
  }

  @Test
  @DisplayName("a buffer under DEFAULT never accounts bytes")
  void defaultBufferDoesNotSize() {
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer();
    buf.append(frame(1, "chunk", 10_000));
    assertEquals(0L, buf.retainedBytes(), "no sizing under an unbounded byte budget");
    assertEquals(1, buf.size());
    assertEquals(0, buf.evidenceSize());
  }

  @Test
  @DisplayName("rejects non-positive bounds")
  void rejectsBadBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameRetentionPolicy(0, 1024, 0, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameRetentionPolicy(10, 0, 0, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrameRetentionPolicy(10, 1024, -1, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY));
    assertThrows(IllegalArgumentException.class, () -> new FrameRetentionPolicy(10, 1024, 0, null));
  }

  @Test
  @DisplayName("the byte bound evicts oldest-first before the frame axis binds")
  void byteBoundEvictsOldestFirst() {
    // Room for ~3 frames of ~1 KiB payload; the frame axis (100) never binds.
    FrameRetentionPolicy policy =
        new FrameRetentionPolicy(
            100, 3 * 2500, 0, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    for (long seq = 1; seq <= 10; seq++) {
      buf.append(frame(seq, "chunk", 1000));
    }

    assertTrue(buf.size() < 10, "the byte bound bound before the frame bound");
    assertTrue(buf.retainedBytes() <= policy.maxBytes(), "retained bytes stay inside the budget");
    assertEquals(
        10L,
        buf.framesSince(0L).get(buf.size() - 1).seq(),
        "the newest frame is always retained");
    assertTrue(buf.oldestSeqOrZero() > 1L, "oldest frames were evicted");
  }

  @Test
  @DisplayName("a single oversized frame degrades to holding that frame alone, never to empty")
  void oversizedFrameIsStillRetained() {
    FrameRetentionPolicy policy =
        new FrameRetentionPolicy(
            100, 512, 0, FrameRetentionPolicy.EvidenceClassifier.NARRATIVE_ONLY);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    buf.append(frame(1, "chunk", 100_000));

    assertEquals(1, buf.size(), "a lone frame over budget is kept, not dropped to nothing");
    assertEquals(1L, buf.oldestSeqOrZero());
  }

  @Test
  @DisplayName("evidence frames are latest-wins per key and do not consume narrative slots")
  void evidenceIsLatestWinsAndSegregated() {
    FrameRetentionPolicy policy = new FrameRetentionPolicy(3, 1_000_000, 4L << 20, BY_NAME);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);

    buf.append(frame(1, "chunk", 10));
    buf.append(frame(2, "rag.citations", 50_000));
    buf.append(frame(3, "chunk", 10));
    buf.append(frame(4, "rag.citations", 50_000)); // replaces seq 2
    buf.append(frame(5, "chunk", 10));
    buf.append(frame(6, "rag.meta", 100));

    assertEquals(3, buf.size(), "3 narrative frames; the ring's 3-frame capacity is intact");
    assertEquals(2, buf.evidenceSize(), "two evidence KEYS, not three evidence frames");

    List<SseEnvelope> replay = buf.framesSince(0L);
    // Evidence first, in seq order, then the narrative ring (834 §2).
    assertEquals(5, replay.size());
    assertEquals(4L, replay.get(0).seq(), "latest rag.citations, not the superseded seq 2");
    assertEquals(6L, replay.get(1).seq(), "rag.meta");
    assertEquals(1L, replay.get(2).seq());
    assertEquals(3L, replay.get(3).seq());
    assertEquals(5L, replay.get(4).seq());
  }

  @Test
  @DisplayName("a superseded evidence frame is not replayed to a cursor that predates it")
  void supersededEvidenceIsNotReplayed() {
    FrameRetentionPolicy policy = new FrameRetentionPolicy(100, 1_000_000, 4L << 20, BY_NAME);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    buf.append(frame(1, "rag.citations", 100));
    buf.append(frame(2, "chunk", 10));
    buf.append(frame(3, "rag.citations", 100));

    List<SseEnvelope> replay = buf.framesSince(2L);
    assertEquals(1, replay.size(), "only the latest citations frame, which is newer than 2");
    assertEquals(3L, replay.get(0).seq());
  }

  @Test
  @DisplayName("the evidence budget evicts the lowest-seq key, never the frame just added")
  void evidenceBudgetEvictsLowestSeqKey() {
    // Budget fits roughly one 10k-char evidence frame.
    FrameRetentionPolicy policy =
        new FrameRetentionPolicy(
            100,
            1_000_000,
            25_000,
            f -> Optional.ofNullable((String) ((Map<?, ?>) f.payload()).get("name")));
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    buf.append(frame(1, "rag.citations", 10_000));
    buf.append(frame(2, "rag.meta", 10_000));

    assertTrue(buf.retainedBytes() <= 25_000 || buf.evidenceSize() == 1);
    assertEquals(1, buf.evidenceSize(), "the older key was evicted");
    assertEquals(2L, buf.framesSince(0L).get(0).seq(), "the just-added key survives");
  }

  @Test
  @DisplayName("oldestSeqOrZero answers from the narrative ring, not from stale evidence")
  void oldestSeqIgnoresStaleEvidence() {
    FrameRetentionPolicy policy = new FrameRetentionPolicy(2, 1_000_000, 4L << 20, BY_NAME);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    buf.append(frame(1, "rag.citations", 100)); // evidence, seq 1
    buf.append(frame(2, "chunk", 10));
    buf.append(frame(3, "chunk", 10));
    buf.append(frame(4, "chunk", 10)); // evicts seq 2

    // A cursor at 1 must NOT be treated as resumable just because evidence seq 1 survives:
    // the narrative gap 2→3 is gone.
    assertEquals(3L, buf.oldestSeqOrZero(), "the narrative ring is the authority");
  }

  @Test
  @DisplayName("with the narrative ring empty, evidence answers oldestSeqOrZero")
  void evidenceOnlyBufferReportsItsOldest() {
    FrameRetentionPolicy policy = new FrameRetentionPolicy(10, 1_000_000, 4L << 20, BY_NAME);
    FrameHistoryRingBuffer buf = new FrameHistoryRingBuffer(policy);
    assertEquals(0L, buf.oldestSeqOrZero(), "empty on both tiers reports 0");

    buf.append(frame(7, "rag.meta", 10));
    assertEquals(7L, buf.oldestSeqOrZero());
  }

  @Test
  @DisplayName("the sizer charges payload string content plus a fixed per-frame overhead")
  void sizerShape() {
    long small = FrameRetentionSizer.retainedBytes(frame(1, "chunk", 0));
    long large = FrameRetentionSizer.retainedBytes(frame(1, "chunk", 1000));

    assertTrue(small >= FrameRetentionSizer.FRAME_OVERHEAD_BYTES, "fixed overhead is charged");
    assertEquals(2000L, large - small, "2 bytes per payload char (UTF-16 worst case)");
  }
}
