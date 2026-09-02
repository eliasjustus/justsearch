/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 885 item 21b — the ladder's arithmetic and its seven-day boundary. */
@DisplayName("IngestionRetryLadder")
final class IngestionRetryLadderTest {

  private static final long MINUTE = 60_000L;
  private static final long HOUR = 60 * MINUTE;
  private static final long DAY = 24 * HOUR;

  @Test
  @DisplayName("the ladder is 1 min, 10 min, 1 h, 6 h, 24 h, then 24 h forever")
  void ladderSteps() {
    assertEquals(List.of(MINUTE, 10 * MINUTE, HOUR, 6 * HOUR, DAY), IngestionRetryLadder.steps());
    assertEquals(MINUTE, IngestionRetryLadder.backoffMs(1));
    assertEquals(10 * MINUTE, IngestionRetryLadder.backoffMs(2));
    assertEquals(HOUR, IngestionRetryLadder.backoffMs(3));
    assertEquals(6 * HOUR, IngestionRetryLadder.backoffMs(4));
    assertEquals(DAY, IngestionRetryLadder.backoffMs(5));
    assertEquals(DAY, IngestionRetryLadder.backoffMs(6), "past the end, the last step repeats");
    assertEquals(DAY, IngestionRetryLadder.backoffMs(500));
    assertEquals(MINUTE, IngestionRetryLadder.backoffMs(0), "index 0 is clamped to the first step");
  }

  @Test
  @DisplayName("the pre-item cap is genuinely gone")
  void ladderOutgrowsTheOldSeventeenMinuteCap() {
    long oldCap = 1000L * (1L << 10); // ~17 minutes: the pre-885 ceiling
    assertTrue(
        IngestionRetryLadder.backoffMs(3) > oldCap,
        "by the third failure the ladder must already exceed the old cap");
  }

  @Test
  @DisplayName("a first failure is never exhausted; a run older than 7 days always is")
  void exhaustionBoundary() {
    long now = 1_000_000_000_000L;
    assertFalse(
        IngestionRetryLadder.exhausted(0L, now), "no run in progress means this IS the first one");
    assertFalse(IngestionRetryLadder.exhausted(now - 6 * DAY, now));
    assertFalse(IngestionRetryLadder.exhausted(now - 7 * DAY + 1, now));
    assertTrue(IngestionRetryLadder.exhausted(now - 7 * DAY, now), "exactly 7 days is exhausted");
    assertTrue(IngestionRetryLadder.exhausted(now - 30 * DAY, now));
    assertEquals(7 * DAY, IngestionRetryLadder.MAX_RETRY_WINDOW_MS);
  }

  @Test
  @DisplayName("no retry is ever scheduled past the boundary")
  void retryTimeIsClampedToTheBoundary() {
    long now = 1_000_000_000_000L;
    long firstFailed = now - 6 * DAY - 20 * HOUR; // 4 hours of window left

    long next = IngestionRetryLadder.nextRetryAtMs(firstFailed, 5, now, 0L);
    assertEquals(
        firstFailed + 7 * DAY,
        next,
        "a 24 h step with 4 h of window left must clamp to the boundary, not overshoot it");
    assertTrue(next > now, "the clamped retry is still in the future");

    // Early in the run the step applies unclamped, jitter included.
    long early = IngestionRetryLadder.nextRetryAtMs(now, 1, now, 250L);
    assertEquals(now + MINUTE + 250L, early);

    // A first failure (no run yet) measures the window from now.
    assertEquals(now + MINUTE, IngestionRetryLadder.nextRetryAtMs(0L, 1, now, 0L));
  }

  @Test
  @DisplayName("negative jitter is ignored rather than pulling a retry into the past")
  void negativeJitterIsIgnored() {
    long now = 1_000_000_000_000L;
    assertEquals(now + MINUTE, IngestionRetryLadder.nextRetryAtMs(now, 1, now, -5_000L));
  }
}
