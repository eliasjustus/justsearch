/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the rate estimator's one hard property: it is honest or it is silent.
 *
 * <p>Every test here drives an INJECTED nanosecond clock. That is not only for speed — it is the
 * property itself: the estimator must never consult a wall clock, so a whole 20 s window can be
 * traversed in a test that spends no real time at all. A test that had to sleep would be evidence
 * the clock seam is not actually load-bearing.
 */
final class AcquisitionRateTest {

  /** A hand-cranked monotonic clock. Nothing here advances unless a test says so. */
  private static final class FakeClock {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    long get() {
      return nanos.get();
    }

    void advanceSeconds(double seconds) {
      nanos.addAndGet((long) (seconds * 1_000_000_000d));
    }

    /** Nanosecond-precision advance, for pinning a boundary that a whole second would blur past. */
    void advanceNanos(long deltaNanos) {
      nanos.addAndGet(deltaNanos);
    }
  }

  private static AcquisitionRate rateOf(FakeClock clock) {
    return AcquisitionRate.withDefaults(clock::get);
  }

  /**
   * Wide enough that window, span, and stall arithmetic never becomes the accidental deciding
   * factor in a test built to pin a different boundary.
   */
  private static final long HUGE_NANOS = TimeUnit.DAYS.toNanos(1);

  @Test
  @Timeout(10)
  @DisplayName("a steady transfer over the window yields the measured rate and a matching horizon")
  void measuresRateAndRemainingOverTheWindow() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    // 1 MB/s for 10 s, sampled every second.
    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 10; i++) {
      clock.advanceSeconds(1);
      bytes += 1_000_000L;
      rate.sample(bytes);
    }

    AcquisitionRate.Estimate estimate = rate.estimate(20_000_000L);
    assertTrue(estimate.rateKnown(), "ten samples over ten seconds is enough to measure");
    assertEquals(1_000_000d, estimate.bytesPerSecond(), 1_000d, "measured 1 MB/s");
    assertTrue(estimate.remainingKnown());
    assertEquals(
        10L, estimate.remainingSeconds(), "10 MB left at 1 MB/s is ten seconds, not a guess");
  }

  @Test
  @Timeout(10)
  @DisplayName("too few samples is unknown, not zero")
  void tooFewSamplesIsUnknown() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    rate.sample(0L);
    clock.advanceSeconds(5);
    rate.sample(5_000_000L);

    AcquisitionRate.Estimate estimate = rate.estimate(10_000_000L);
    assertFalse(estimate.rateKnown(), "two points are a line, not a trend");
    assertFalse(estimate.remainingKnown());
    assertEquals(AcquisitionRate.Estimate.UNKNOWN, estimate);
    assertNotEquals(0L, estimate.remainingSeconds(), "a UI printing 0s would be the lie this avoids");
  }

  @Test
  @Timeout(10)
  @DisplayName("a window too short to measure is unknown, however many samples it holds")
  void tooShortASpanIsUnknown() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    // Five samples inside a single second — the default minimum span is two.
    long bytes = 0;
    for (int i = 0; i < 5; i++) {
      rate.sample(bytes += 100_000L);
      clock.advanceSeconds(0.2);
    }

    assertEquals(AcquisitionRate.Estimate.UNKNOWN, rate.estimate(10_000_000L));
  }

  @Test
  @Timeout(10)
  @DisplayName("a window over which no bytes arrived is unknown, not a rate of zero")
  void stalledWindowIsUnknownNotZero() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    // Progress reported repeatedly, but the byte count never moves.
    for (int i = 0; i < 6; i++) {
      rate.sample(4_000_000L);
      clock.advanceSeconds(1);
    }

    AcquisitionRate.Estimate estimate = rate.estimate(10_000_000L);
    assertFalse(estimate.rateKnown(), "zero bytes per second divides into an infinite horizon");
    assertEquals(AcquisitionRate.Estimate.UNKNOWN, estimate);
  }

  @Test
  @Timeout(10)
  @DisplayName("an estimate goes unknown once the transfer stops reporting")
  void goesUnknownAfterTheStallWindow() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 6; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 1_000_000L);
    }
    assertTrue(rate.estimate(20_000_000L).rateKnown(), "precondition: it knew a moment ago");

    clock.advanceSeconds(
        TimeUnit.NANOSECONDS.toSeconds(AcquisitionRate.DEFAULT_STALL_NANOS) + 1d);

    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(20_000_000L),
        "the last measured rate describes a transfer that is no longer running");
  }

  @Test
  @Timeout(10)
  @DisplayName("an unknown total yields a known rate and an explicitly unknown horizon")
  void unknownTotalKeepsTheRateButNotTheHorizon() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 5; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 2_000_000L);
    }

    AcquisitionRate.Estimate estimate = rate.estimate(0L);
    assertTrue(estimate.rateKnown());
    assertEquals(2_000_000d, estimate.bytesPerSecond(), 2_000d);
    assertFalse(estimate.remainingKnown(), "no total means no honest horizon");
  }

  @Test
  @Timeout(10)
  @DisplayName("a restart from zero ends the series instead of being smoothed into it")
  void backwardsProgressResetsTheWindow() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 6; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 5_000_000L);
    }
    assertTrue(rate.estimate(100_000_000L).rateKnown(), "precondition");

    // The fetch discarded a corrupt partial and restarted this file from zero.
    clock.advanceSeconds(1);
    rate.sample(0L);

    assertEquals(1, rate.sampleCount(), "the pre-restart baseline no longer describes this series");
    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(100_000_000L),
        "carrying the old baseline through the drop would report a rate that never happened");
  }

  @Test
  @Timeout(10)
  @DisplayName("a completed set reports zero seconds left — a value, not the unknown sentinel")
  void completedSetReportsZeroRemaining() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 5; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 2_000_000L);
    }

    AcquisitionRate.Estimate estimate = rate.estimate(10_000_000L);
    assertTrue(estimate.remainingKnown(), "zero left is knowledge, not absence of it");
    assertEquals(0L, estimate.remainingSeconds());
  }

  @Test
  @Timeout(10)
  @DisplayName("the window slides: only recent samples decide the rate")
  void slidingWindowForgetsOldSamples() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    // A very fast opening burst, then a long slow tail. Once the burst falls out of the window the
    // reported rate must describe the tail, not the average since the start.
    long bytes = 0;
    rate.sample(bytes);
    clock.advanceSeconds(1);
    rate.sample(bytes += 100_000_000L);

    for (int i = 0; i < 40; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 1_000_000L);
    }

    AcquisitionRate.Estimate estimate = rate.estimate(1_000_000_000L);
    assertTrue(estimate.rateKnown());
    assertEquals(
        1_000_000d,
        estimate.bytesPerSecond(),
        50_000d,
        "the opening burst is outside the window and must not still be inflating the rate");
  }

  @Test
  @Timeout(10)
  @DisplayName("reset returns the estimator to unknown")
  void resetReturnsToUnknown() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    long bytes = 0;
    rate.sample(bytes);
    for (int i = 0; i < 5; i++) {
      clock.advanceSeconds(1);
      rate.sample(bytes += 1_000_000L);
    }
    assertTrue(rate.estimate(20_000_000L).rateKnown(), "precondition");

    rate.reset();

    assertEquals(0, rate.sampleCount());
    assertEquals(AcquisitionRate.Estimate.UNKNOWN, rate.estimate(20_000_000L));
  }

  @Test
  @Timeout(10)
  @DisplayName("the estimator speaks at exactly minSamples and stays silent one sample short")
  void minSamplesBoundaryIsExact() {
    FakeClock clock = new FakeClock();
    // minSpanNanos and stallNanos held wide open so minSamples is the only thing under test.
    AcquisitionRate rate = new AcquisitionRate(clock::get, HUGE_NANOS, 1L, 4, HUGE_NANOS);

    long bytes = 0;
    for (int i = 0; i < 3; i++) { // one short of the configured minimum of four
      rate.sample(bytes += 1_000_000L);
      clock.advanceSeconds(1);
    }
    assertEquals(3, rate.sampleCount(), "precondition: three samples recorded");
    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(10_000_000L),
        "three samples is one short of the configured minimum of four — must stay silent");

    rate.sample(bytes += 1_000_000L); // the fourth sample reaches minSamples exactly
    assertEquals(4, rate.sampleCount());
    assertTrue(
        rate.estimate(10_000_000L).rateKnown(),
        "exactly minSamples samples must be enough to speak, not one more");
  }

  @Test
  @Timeout(10)
  @DisplayName("silence begins exactly one nanosecond past the stall horizon, not a moment before")
  void stallBoundaryIsExact() {
    FakeClock clock = new FakeClock();
    long stallNanos = TimeUnit.SECONDS.toNanos(1);
    // windowNanos held wide open and minSpanNanos trivial so the stall check is isolated.
    AcquisitionRate rate = new AcquisitionRate(clock::get, HUGE_NANOS, 1L, 2, stallNanos);

    rate.sample(0L);
    clock.advanceSeconds(1);
    rate.sample(1_000_000L); // the newest sample; nothing else touches the clock before it

    clock.advanceNanos(stallNanos);
    assertTrue(
        rate.estimate(10_000_000L).rateKnown(),
        "exactly stallNanos after the newest sample must still count as live");

    clock.advanceNanos(1L);
    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(10_000_000L),
        "one nanosecond past the stall horizon must go silent — the last rate is no longer honest");
  }

  @Test
  @Timeout(10)
  @DisplayName("a span exactly at minSpanNanos is measurable; one nanosecond short is noise")
  void minSpanBoundaryIsExact() {
    FakeClock clock = new FakeClock();
    long minSpan = TimeUnit.SECONDS.toNanos(1);
    AcquisitionRate rate = new AcquisitionRate(clock::get, HUGE_NANOS, minSpan, 2, HUGE_NANOS);

    // One nanosecond short of the minimum span: still unknown.
    rate.sample(0L);
    clock.advanceNanos(minSpan - 1);
    rate.sample(1_000_000L);
    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(10_000_000L),
        "a span one nanosecond short of the minimum is noise, not a rate");

    // Fresh series: exactly at the minimum span is measurable.
    rate.reset();
    rate.sample(0L);
    clock.advanceNanos(minSpan);
    rate.sample(1_000_000L);
    assertTrue(
        rate.estimate(10_000_000L).rateKnown(),
        "a span exactly at the minimum must be measurable, not treated as noise");
  }

  @Test
  @Timeout(10)
  @DisplayName("a zero-length span is unknown even when the configured minimum span is zero")
  void zeroSpanIsUnknownEvenWithNoConfiguredMinimum() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = new AcquisitionRate(clock::get, HUGE_NANOS, 0L, 2, HUGE_NANOS);

    // Two samples land on the exact same clock tick: the span between them is zero. Dividing by
    // that zero span is exactly what this guard exists to prevent.
    rate.sample(0L);
    rate.sample(1_000_000L);
    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN,
        rate.estimate(10_000_000L),
        "a zero span cannot support a rate no matter how small the configured minimum is");

    // One nanosecond of separation is enough once the configured minimum allows it.
    rate.reset();
    rate.sample(0L);
    clock.advanceNanos(1L);
    rate.sample(1_000_000L);
    assertTrue(
        rate.estimate(10_000_000L).rateKnown(),
        "one nanosecond of span is measurable once the configured minimum allows it");
  }

  @Test
  @Timeout(10)
  @DisplayName("an equal reading is not a rewind; only a strictly lower one clears the series")
  void equalReadingDoesNotResetButOneByteLowerDoes() {
    FakeClock clock = new FakeClock();
    AcquisitionRate rate = rateOf(clock);

    rate.sample(1_000_000L);
    clock.advanceSeconds(1);
    rate.sample(2_000_000L);
    clock.advanceSeconds(1);
    assertEquals(2, rate.sampleCount(), "precondition");

    // The exact same cumulative count again (e.g. a duplicate progress tick) must not look like a
    // rewind: cumulativeBytes < lastBytes is strict, and equal is not less.
    rate.sample(2_000_000L);
    assertEquals(
        3, rate.sampleCount(), "an equal reading is not a rewind and must extend the series");

    // One byte lower than the last reading is a genuine rewind and must clear it.
    clock.advanceSeconds(1);
    rate.sample(1_999_999L);
    assertEquals(
        1,
        rate.sampleCount(),
        "one byte below the last reading is a genuine rewind and must clear the series");
  }

  @Test
  @Timeout(10)
  @DisplayName("a sample exactly at the window's trailing edge survives; one nanosecond older is evicted")
  void windowCutoffBoundaryIsExact() {
    FakeClock clock = new FakeClock();
    long windowNanos = TimeUnit.SECONDS.toNanos(5);
    AcquisitionRate rate = new AcquisitionRate(clock::get, windowNanos, 0L, 2, HUGE_NANOS);

    rate.sample(0L); // oldest, at t0
    clock.advanceNanos(windowNanos); // now = t0 + window
    rate.sample(1_000_000L); // cutoff = now - window = t0, exactly the oldest sample's timestamp
    assertEquals(
        2,
        rate.sampleCount(),
        "a sample exactly at the cutoff is still inside the window and must not be evicted");

    clock.advanceNanos(1L); // now = t0 + window + 1ns; cutoff = t0 + 1ns
    rate.sample(2_000_000L);
    assertEquals(
        2,
        rate.sampleCount(),
        "the oldest sample, now one nanosecond older than the cutoff, must be evicted");
  }

  @Test
  @Timeout(10)
  @DisplayName("retention holds at exactly the cap and evicts down to it, not past it")
  void retentionCapBoundaryIsExact() {
    FakeClock clock = new FakeClock();
    // windowNanos held wide open so only the retention cap — not the sliding window — can evict.
    AcquisitionRate rate = new AcquisitionRate(clock::get, HUGE_NANOS, 0L, 2, HUGE_NANOS);

    long bytes = 0;
    // 512 mirrors AcquisitionRate's private MAX_SAMPLES; it has no public accessor to reference.
    for (int i = 0; i < 512; i++) {
      rate.sample(bytes += 1_000L);
      clock.advanceNanos(1L);
    }
    assertEquals(512, rate.sampleCount(), "exactly the cap must not trigger eviction");

    rate.sample(bytes += 1_000L);
    assertEquals(
        512,
        rate.sampleCount(),
        "one sample past the cap must evict exactly enough to return to the cap, not below it");
  }

  // ── reHorizon (tempdoc 840 Phase 4) ────────────────────────────────────────
  // A staged install measures one slice per stage, so the run-level horizon is the SAME rate divided
  // into a different remainder. The property that matters is that re-horizoning cannot manufacture
  // knowledge the rate does not have.

  @Test
  @Timeout(10)
  @DisplayName("reHorizon keeps the measured rate and re-divides it into the given remainder")
  void reHorizonReDividesTheSameRate() {
    AcquisitionRate.Estimate stageWide = new AcquisitionRate.Estimate(1_000d, 5L);

    AcquisitionRate.Estimate runWide = stageWide.reHorizon(20_000L);

    assertEquals(1_000d, runWide.bytesPerSecond(), 1e-9, "the rate is measured, not re-derived");
    assertEquals(20L, runWide.remainingSeconds(), "20 000 B at 1 000 B/s is 20 s, not the stage's 5");
  }

  @Test
  @Timeout(10)
  @DisplayName("an UNKNOWN rate re-horizons to UNKNOWN — never to a fabricated 0")
  void reHorizonOfUnknownStaysUnknown() {
    AcquisitionRate.Estimate runWide = AcquisitionRate.Estimate.UNKNOWN.reHorizon(20_000L);

    assertFalse(runWide.rateKnown(), "a horizon cannot be more knowable than the rate under it");
    assertFalse(runWide.remainingKnown());
    assertEquals(-1L, runWide.remainingSeconds(), "the sentinel, not 0 — 0s would read as 'done'");
    assertNotEquals(0d, runWide.bytesPerSecond(), "0 B/s is the plausible lie this class prevents");
  }

  @Test
  @Timeout(10)
  @DisplayName("a known rate with an unknown remainder keeps the rate and drops only the horizon")
  void reHorizonWithUnknownRemainderKeepsTheRate() {
    AcquisitionRate.Estimate runWide = new AcquisitionRate.Estimate(2_500d, 4L).reHorizon(-1L);

    assertTrue(runWide.rateKnown(), "the rate is still measured even when the total size is not");
    assertEquals(2_500d, runWide.bytesPerSecond(), 1e-9);
    assertFalse(runWide.remainingKnown());
  }

  @Test
  @Timeout(10)
  @DisplayName("nothing left to move is 0 seconds — a value, not the unknown sentinel")
  void reHorizonOfZeroRemainderIsZeroSeconds() {
    AcquisitionRate.Estimate runWide = new AcquisitionRate.Estimate(1_000d, 3L).reHorizon(0L);

    assertTrue(runWide.remainingKnown(), "0 is genuinely nothing left, and must read as known");
    assertEquals(0L, runWide.remainingSeconds());
  }
}
