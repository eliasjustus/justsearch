/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 885 item 3: the duty-cycle arithmetic. Deterministic — the clocks and the sleep are
 * injected, so no assertion here depends on wall-clock timing.
 */
final class IndexingPacingTest {

  /** Controllable clock pair; a "sleep" advances both and is recorded instead of performed. */
  private static final class FakeTime implements IndexingPacing.Sleeper {
    private long ms;
    private long nanos;
    private final List<Long> sleeps = new ArrayList<>();
    private Runnable onSleep = () -> {};

    @Override
    public void sleepMs(long millis) {
      sleeps.add(millis);
      advance(millis);
      onSleep.run();
    }

    void advance(long millis) {
      ms += millis;
      nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }

    long totalSlept() {
      return sleeps.stream().mapToLong(Long::longValue).sum();
    }
  }

  private static IndexingPacing pacing(FakeTime t, ForegroundLoad load, int dutyPct, long cooldown) {
    return new IndexingPacing(load, dutyPct, cooldown, () -> t.ms, () -> t.nanos, t);
  }

  @Test
  @DisplayName("no foreground work in flight: pace() never sleeps and duty reads 100%")
  void idleForegroundDoesNotThrottle() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);

    p.pace(); // establishes this thread's work clock
    t.advance(250);
    p.pace();

    assertTrue(t.sleeps.isEmpty(), "an uncontended loop must not yield at all");
    assertEquals(0L, p.pacedIntervalsTotal());
    assertEquals(100L, p.observedDutyPct());
  }

  @Test
  @DisplayName("one in-flight foreground call: 100 ms of work yields 400 ms — a 20% duty cycle")
  void inFlightForegroundYieldsToTheConfiguredDuty() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);

    p.pace();
    load.started();
    t.advance(100); // the work slice

    p.pace();

    assertEquals(400L, t.totalSlept(), "yield = worked * (100 - duty) / duty");
    assertEquals(1L, p.pacedIntervalsTotal());
    assertEquals(400L, p.yieldedMsTotal());
  }

  @Test
  @DisplayName("the duty cycle never fully stops work: a contended interval still works its share")
  void contendedIntervalStillPerformsTheConfiguredShare() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);
    load.started();

    p.pace(); // first call: no work slice yet
    for (int i = 0; i < 5; i++) {
      t.advance(100); // 100 ms of real indexing work
      p.pace();
    }

    // 500 ms worked, 2000 ms yielded => exactly the configured 20%, and strictly positive work.
    assertEquals(20L, p.observedDutyPct());
    assertTrue(t.totalSlept() > 0, "a contended interval must yield");
    assertEquals(
        2000L, t.totalSlept(), "and must yield exactly the complement of the configured duty");
  }

  @Test
  @DisplayName("dutyPct=100 disables throttling even with foreground in flight")
  void fullDutyDisablesThrottling() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 100, 500L);
    load.started();

    p.pace();
    t.advance(500);
    p.pace();

    assertTrue(t.sleeps.isEmpty());
    assertEquals(100, IndexingPacing.unthrottled().dutyPct());
  }

  @Test
  @DisplayName("cooldown keeps a burst of short queries contended, and expires on its own")
  void cooldownBridgesTheGapBetweenQueries() {
    FakeTime t = new FakeTime();
    t.advance(10_000); // a non-zero epoch so "never seen" (0) stays distinguishable
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);

    assertFalse(p.foregroundBusy(), "no foreground call has ever happened");

    load.started();
    load.finished();
    assertEquals(0, load.inFlight());
    assertTrue(p.foregroundBusy(), "still contended inside the cooldown window");

    t.advance(499);
    assertTrue(p.foregroundBusy());
    t.advance(1);
    assertFalse(p.foregroundBusy(), "cooldown expired at exactly cooldownMs");
  }

  @Test
  @DisplayName("a long work slice cannot accrue unbounded yield debt")
  void workSliceAndDebtAreBounded() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);

    p.pace();
    load.started();
    t.advance(60_000); // e.g. a thread parked between cycles — not 60 s of indexing

    p.pace();

    assertEquals(
        IndexingPacing.MAX_DEBT_MS,
        t.totalSlept(),
        "the work slice is capped at MAX_WORK_SLICE_MS and the debt at MAX_DEBT_MS");
    assertTrue(
        t.sleeps.stream().allMatch(s -> s <= IndexingPacing.MAX_SLEEP_CHUNK_MS),
        "each individual sleep stays short so a drain is noticed promptly");
  }

  @Test
  @DisplayName("the yield is abandoned as soon as foreground work drains")
  void yieldEndsWhenForegroundDrains() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    // Cooldown 0 so the drain is observable at the gauge alone.
    IndexingPacing p = pacing(t, load, 20, 0L);
    p.pace();
    load.started();
    t.advance(100); // 400 ms of debt

    // The search completes during the first sleep chunk.
    t.onSleep = load::finished;
    p.pace();

    assertEquals(1, t.sleeps.size(), "one chunk, then the gauge drained and the yield stopped");
  }

  @Test
  @DisplayName("an interrupt during the yield returns immediately with the flag re-set")
  void interruptDuringYieldIsPropagated() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p =
        new IndexingPacing(
            load,
            20,
            500L,
            () -> t.ms,
            () -> t.nanos,
            millis -> {
              throw new InterruptedException("test");
            });
    p.pace();
    load.started();
    t.advance(100);

    p.pace();

    assertTrue(Thread.interrupted(), "the interrupt flag must survive pace()");
  }

  @Test
  @DisplayName("paceAndContinue paces but never aborts the caller's walk")
  void paceAndContinueNeverAborts() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    IndexingPacing p = pacing(t, load, 20, 500L);
    load.started();

    assertFalse(p.paceAndContinue());
    t.advance(100);
    assertFalse(p.paceAndContinue());
    assertTrue(t.totalSlept() > 0, "it still paced");
  }

  @Test
  @DisplayName("an out-of-range duty is clamped rather than disabling or freezing indexing")
  void dutyIsClamped() {
    FakeTime t = new FakeTime();
    ForegroundLoad load = new ForegroundLoad(() -> t.ms);
    assertEquals(1, pacing(t, load, 0, 500L).dutyPct());
    assertEquals(1, pacing(t, load, -5, 500L).dutyPct());
    assertEquals(100, pacing(t, load, 1000, 500L).dutyPct());
    assertEquals(0L, pacing(t, load, 20, -1L).cooldownMs());
  }
}
