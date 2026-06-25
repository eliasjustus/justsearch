package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Truth table for {@link ResumeDetector} (tempdoc 630 latency-hardening slice). */
final class ResumeDetectorTest {

  private static final long INTERVAL = 10_000L; // 10s poll
  private static final long FACTOR = 3L; // fire only if a tick took > 30s

  @Test
  @DisplayName("first tick (no prior) is never a resume")
  void firstTickNeverResumes() {
    assertEquals(0L, ResumeDetector.resumeGapMs(0L, 1_000_000L, INTERVAL, FACTOR));
    assertEquals(0L, ResumeDetector.resumeGapMs(-1L, 1_000_000L, INTERVAL, FACTOR));
  }

  @Test
  @DisplayName("a normal-cadence tick is not a resume")
  void normalTickIsNotResume() {
    // ~10s gap (with jitter) — well under the 30s threshold.
    assertEquals(0L, ResumeDetector.resumeGapMs(1_000_000L, 1_010_500L, INTERVAL, FACTOR));
  }

  @Test
  @DisplayName("gap exactly at the threshold is NOT a resume (strict >)")
  void atThresholdIsNotResume() {
    // gap == interval*factor == 30s exactly.
    assertEquals(0L, ResumeDetector.resumeGapMs(1_000_000L, 1_030_000L, INTERVAL, FACTOR));
  }

  @Test
  @DisplayName("gap just past the threshold is a resume, returning the gap")
  void justPastThresholdIsResume() {
    long gap = ResumeDetector.resumeGapMs(1_000_000L, 1_030_001L, INTERVAL, FACTOR);
    assertEquals(30_001L, gap);
  }

  @Test
  @DisplayName("a long suspend (1 hour) is a resume, returning the full gap")
  void longSuspendIsResume() {
    long oneHour = 3_600_000L;
    long gap = ResumeDetector.resumeGapMs(1_000_000L, 1_000_000L + oneHour, INTERVAL, FACTOR);
    assertEquals(oneHour, gap);
  }
}
