package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HistoryPolicy compact-constructor invariants (slice 444a §B.A.3 + §B.A.4)")
final class HistoryPolicyTest {

  // ===== Helpers =====

  private static final Duration ONE_MIN = Duration.ofMinutes(1);
  private static final Duration NINETY_DAYS = Duration.ofDays(90);
  private static final Duration RESUME = Duration.ofMinutes(5);

  // ===== Mode enum smoke =====

  @Test
  @DisplayName("Mode vocabulary is closed at exactly three values")
  void modeVocabularyIsClosed() {
    assertEquals(3, HistoryPolicy.Mode.values().length);
  }

  @Test
  @DisplayName("Mode canonical names present + round-trip")
  void modeCanonicalNamesPresent() {
    assertSame(HistoryPolicy.Mode.RING_BUFFER, HistoryPolicy.Mode.valueOf("RING_BUFFER"));
    assertSame(HistoryPolicy.Mode.DURABLE, HistoryPolicy.Mode.valueOf("DURABLE"));
    assertSame(HistoryPolicy.Mode.EXTERNAL, HistoryPolicy.Mode.valueOf("EXTERNAL"));
  }

  // ===== Null-check tests (one per required field) =====

  @Test
  @DisplayName("null mode rejected")
  void nullModeRejected() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new HistoryPolicy(
                    null, Optional.of(100), Optional.empty(), OnOverflow.EVICT_OLDEST, RESUME));
    assertEquals("mode", ex.getMessage());
  }

  @Test
  @DisplayName("null capacity Optional rejected")
  void nullCapacityRejected() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.RING_BUFFER,
                    null,
                    Optional.empty(),
                    OnOverflow.EVICT_OLDEST,
                    RESUME));
    assertEquals("capacity", ex.getMessage());
  }

  @Test
  @DisplayName("null retention Optional rejected")
  void nullRetentionRejected() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.RING_BUFFER,
                    Optional.of(100),
                    null,
                    OnOverflow.EVICT_OLDEST,
                    RESUME));
    assertEquals("retention", ex.getMessage());
  }

  @Test
  @DisplayName("null onOverflow rejected")
  void nullOnOverflowRejected() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.RING_BUFFER,
                    Optional.of(100),
                    Optional.empty(),
                    null,
                    RESUME));
    assertEquals("onOverflow", ex.getMessage());
  }

  @Test
  @DisplayName("null resumeWindow rejected")
  void nullResumeWindowRejected() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.RING_BUFFER,
                    Optional.of(100),
                    Optional.empty(),
                    OnOverflow.EVICT_OLDEST,
                    null));
    assertEquals("resumeWindow", ex.getMessage());
  }

  // ===== RING_BUFFER invariants =====

  @Test
  @DisplayName("RING_BUFFER without capacity rejected")
  void ringBufferWithoutCapacityRejected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.RING_BUFFER,
                    Optional.empty(),
                    Optional.empty(),
                    OnOverflow.EVICT_OLDEST,
                    RESUME));
    assertEquals("RING_BUFFER mode requires capacity", ex.getMessage());
  }

  @Test
  @DisplayName("RING_BUFFER with capacity accepted")
  void ringBufferWithCapacityAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.RING_BUFFER,
            Optional.of(200),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            RESUME);
    assertEquals(HistoryPolicy.Mode.RING_BUFFER, policy.mode());
    assertEquals(Optional.of(200), policy.capacity());
  }

  // ===== DURABLE invariants =====

  @Test
  @DisplayName("DURABLE without capacity or retention rejected")
  void durableWithoutAnyBoundRejected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.DURABLE,
                    Optional.empty(),
                    Optional.empty(),
                    OnOverflow.EVICT_OLDEST,
                    RESUME));
    assertEquals(
        "DURABLE mode requires at least one of capacity / retention", ex.getMessage());
  }

  @Test
  @DisplayName("DURABLE with capacity only accepted")
  void durableWithCapacityOnlyAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.of(1_000_000),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            RESUME);
    assertEquals(Optional.of(1_000_000), policy.capacity());
    assertEquals(Optional.empty(), policy.retention());
  }

  @Test
  @DisplayName("DURABLE with retention only accepted")
  void durableWithRetentionOnlyAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.empty(),
            Optional.of(NINETY_DAYS),
            OnOverflow.EVICT_OLDEST,
            RESUME);
    assertEquals(Optional.of(NINETY_DAYS), policy.retention());
  }

  @Test
  @DisplayName("DURABLE with both capacity and retention accepted")
  void durableWithBothBoundsAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.of(1_000_000),
            Optional.of(NINETY_DAYS),
            OnOverflow.EVICT_OLDEST,
            RESUME);
    assertEquals(Optional.of(1_000_000), policy.capacity());
    assertEquals(Optional.of(NINETY_DAYS), policy.retention());
  }

  // ===== EXTERNAL invariants =====

  @Test
  @DisplayName("EXTERNAL with BACKPRESSURE rejected (slice 444a §B.A.3)")
  void externalWithBackpressureRejected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryPolicy(
                    HistoryPolicy.Mode.EXTERNAL,
                    Optional.of(500),
                    Optional.empty(),
                    OnOverflow.BACKPRESSURE,
                    ONE_MIN));
    assertEquals(
        "EXTERNAL mode forbids BACKPRESSURE; backpressure is not the Resource's concern",
        ex.getMessage());
  }

  @Test
  @DisplayName("EXTERNAL with EVICT_OLDEST accepted")
  void externalWithEvictOldestAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.EXTERNAL,
            Optional.of(500),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            ONE_MIN);
    assertEquals(HistoryPolicy.Mode.EXTERNAL, policy.mode());
  }

  @Test
  @DisplayName("EXTERNAL with DROP_NEWEST accepted")
  void externalWithDropNewestAccepted() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.EXTERNAL,
            Optional.empty(),
            Optional.empty(),
            OnOverflow.DROP_NEWEST,
            ONE_MIN);
    assertEquals(OnOverflow.DROP_NEWEST, policy.onOverflow());
  }
}
