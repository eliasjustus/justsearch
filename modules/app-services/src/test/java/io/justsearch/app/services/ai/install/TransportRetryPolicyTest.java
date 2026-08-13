package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The numbers the retry policy promises. Spacing is the load-bearing property: round 16's failures
 * were time-correlated, so a re-attempt 0.8 s later bought almost nothing.
 */
final class TransportRetryPolicyTest {

  @Test
  void nominalDelaysTripleFromThreeSeconds() {
    TransportRetryPolicy policy = TransportRetryPolicy.defaultPolicy().withRandom(() -> 0.5d);

    assertEquals(4, policy.maxAttempts());
    assertEquals(0L, policy.delayMsBeforeAttempt(0), "the first attempt is not delayed");
    assertEquals(3000L, policy.delayMsBeforeAttempt(1));
    assertEquals(9000L, policy.delayMsBeforeAttempt(2));
    assertEquals(27000L, policy.delayMsBeforeAttempt(3));
  }

  @Test
  void jitterSpreadsEachDelayByThirtyPercentEitherWay() {
    assertEquals(
        2100L, TransportRetryPolicy.defaultPolicy().withRandom(() -> 0.0d).delayMsBeforeAttempt(1));
    assertEquals(
        3900L, TransportRetryPolicy.defaultPolicy().withRandom(() -> 1.0d).delayMsBeforeAttempt(1));
  }

  @Test
  void tiersEscalateThenSaturate() {
    TransportRetryPolicy policy = TransportRetryPolicy.defaultPolicy();

    assertEquals(0, policy.tierForAttempt(0));
    assertEquals(1, policy.tierForAttempt(1));
    assertEquals(2, policy.tierForAttempt(2));
    assertEquals(3, policy.tierForAttempt(3));
    assertEquals(3, policy.tierForAttempt(9), "the escalation saturates, it does not run off the end");
  }

  /** The seam a repair pass consumes: pass n starts at tier n instead of repeating the same chain. */
  @Test
  void startTierShiftsTheEscalationAndIsClamped() {
    assertEquals(2, TransportRetryPolicy.defaultPolicy().withStartTier(2).tierForAttempt(0));
    assertEquals(3, TransportRetryPolicy.defaultPolicy().withStartTier(2).tierForAttempt(1));
    assertEquals(
        TransportRetryPolicy.MAX_TRANSPORT_TIER,
        TransportRetryPolicy.defaultPolicy().withStartTier(99).startTier(),
        "an out-of-range repair pass number must not produce an unknown tier");
    assertEquals(0, TransportRetryPolicy.defaultPolicy().withStartTier(-3).startTier());
  }

  /**
   * Cancel latency. Nothing interrupts the install thread on cancel, so the wait polls the flag
   * between slices: a cancel two slices into a 27 s backoff costs 500 ms, not 27 s.
   */
  @Test
  void aCancelDuringTheWaitIsSeenAfterOneSliceNotAfterTheWholeDelay() {
    List<Long> slices = new ArrayList<>();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    TransportRetryPolicy policy =
        TransportRetryPolicy.defaultPolicy()
            .withSleeper(
                millis -> {
                  slices.add(millis);
                  if (slices.size() == 2) cancelled.set(true);
                });

    assertFalse(policy.sleep(27_000L, cancelled::get), "a cancelled wait must not report success");
    assertEquals(List.of(250L, 250L), slices);
  }

  @Test
  void anUncancelledWaitStillSpendsTheWholeDelayIncludingItsRemainder() {
    List<Long> slices = new ArrayList<>();
    TransportRetryPolicy policy = TransportRetryPolicy.defaultPolicy().withSleeper(slices::add);

    assertTrue(policy.sleep(600L, () -> false));
    assertEquals(List.of(250L, 250L, 100L), slices, "slicing must not shorten the backoff");
  }

  @Test
  void sleepReportsInterruptionSoTheCallerCanAbandonTheRetry() {
    TransportRetryPolicy policy =
        TransportRetryPolicy.defaultPolicy()
            .withSleeper(
                millis -> {
                  throw new InterruptedException("stop");
                });

    assertFalse(policy.sleep(1000L));
    assertTrue(Thread.interrupted(), "the interrupt flag must be restored, not swallowed");
  }
}
