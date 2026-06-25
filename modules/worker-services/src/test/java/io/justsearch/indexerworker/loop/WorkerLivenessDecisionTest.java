package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.loop.WorkerLivenessDecision.HeadLiveness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Truth table for the Worker heartbeat-suicide decision (tempdoc 630). The headline regression is
 * {@link #staleBeatButHeadAliveDoesNotDie()}: a stale heartbeat with Head still alive (the OS
 * suspend/resume case) must NOT trigger the suicide-pact.
 */
final class WorkerLivenessDecisionTest {

  private static final long GRACE_MS = 15_000L;
  private static final long STALE_MS = 5_000L;
  private static final long NOW = 1_000_000L;

  /** Convenience: a heartbeat that is {@code ageMs} old relative to {@link #NOW}. */
  private static long beatAged(long ageMs) {
    return NOW - ageMs;
  }

  @Test
  @DisplayName("explicit shutdown always dies, regardless of grace/heartbeat/Head")
  void shutdownAlwaysDies() {
    // Even within grace, fresh beat, Head alive — shutdown wins.
    assertTrue(
        WorkerLivenessDecision.shouldDie(
            true, 1_000L, GRACE_MS, beatAged(0), NOW, STALE_MS, HeadLiveness.ALIVE));
  }

  @Test
  @DisplayName("within the startup grace, never dies (even stale + Head dead)")
  void withinGraceNeverDies() {
    assertFalse(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS - 1, GRACE_MS, beatAged(60_000), NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("uptime exactly AT the grace boundary is PAST grace (strict <): stale + DEAD dies")
  void uptimeAtGraceBoundaryIsPastGrace() {
    // Kills the line-69 ConditionalsBoundary mutant (< vs <=): at uptime == startupGraceMs the worker
    // is past grace, so a stale beat + dead Head must die. (withinGraceNeverDies uses grace-1, which
    // does not distinguish the boundary.)
    assertTrue(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS, GRACE_MS, beatAged(60_000), NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("no heartbeat ever written (<=0) never dies")
  void noHeartbeatNeverDies() {
    assertFalse(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS + 1, GRACE_MS, 0L, NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("fresh heartbeat (not stale) never dies, even if Head looks dead")
  void freshBeatNeverDies() {
    // Beat is 1s old; threshold 5s → not stale.
    assertFalse(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS + 10_000, GRACE_MS, beatAged(1_000), NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("staleness exactly at threshold is NOT stale (strict >), never dies")
  void staleAtThresholdBoundaryDoesNotDie() {
    assertFalse(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS + 10_000, GRACE_MS, beatAged(STALE_MS), NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("THE tempdoc 630 fix: stale beat but Head ALIVE (resume) does NOT die")
  void staleBeatButHeadAliveDoesNotDie() {
    // Past grace, beat is 1 hour old (as after a suspend), but Head's PID is alive.
    assertFalse(
        WorkerLivenessDecision.shouldDie(
            false,
            GRACE_MS + 3_600_000,
            GRACE_MS,
            beatAged(3_600_000),
            NOW,
            STALE_MS,
            HeadLiveness.ALIVE));
  }

  @Test
  @DisplayName("stale beat + Head DEAD (real orphan) dies")
  void staleBeatAndHeadDeadDies() {
    assertTrue(
        WorkerLivenessDecision.shouldDie(
            false, GRACE_MS + 10_000, GRACE_MS, beatAged(60_000), NOW, STALE_MS, HeadLiveness.DEAD));
  }

  @Test
  @DisplayName("stale beat + Head UNKNOWN (no PID signal) dies — pre-630 legacy behavior preserved")
  void staleBeatAndHeadUnknownDies() {
    assertTrue(
        WorkerLivenessDecision.shouldDie(
            false,
            GRACE_MS + 10_000,
            GRACE_MS,
            beatAged(60_000),
            NOW,
            STALE_MS,
            HeadLiveness.UNKNOWN));
  }
}
