/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * How often, how far apart, and over which transport a failed download attempt is re-tried.
 *
 * <p>Round 16's install failures were <em>time-correlated</em>: the environment reset new
 * connections to the release CDN in bursts, and the product's two attempts (BITS, then curl ~0.8 s
 * later) both landed inside the same degraded window — measured 82 % conditional failure for the
 * "fallback". Spacing is therefore the load-bearing parameter, not attempt count: 3 s / 9 s / 27 s
 * makes the last attempt close to an independent trial.
 *
 * <p><b>Transport escalation.</b> Each attempt runs at a tier, so a retry is not a verbatim repeat:
 *
 * <ol start="0">
 *   <li>tier 0 — BITS, then curl (the historical chain)
 *   <li>tier 1 — curl only (skips BITS' multi-second poll budget)
 *   <li>tier 2 — curl only, forced {@code --http1.1}
 *   <li>tier 3 — curl only
 * </ol>
 *
 * <p><b>{@code startTier} seam.</b> A repair pass may begin at a higher tier than 0 via {@link
 * #withStartTier(int)}, so a file that wedged under one transport is re-attempted under a different
 * one rather than under the identical chain that already failed. Attempt {@code i} (0-based) runs
 * tier {@code min(startTier + i, 3)}; the value is clamped into {@code [0, 3]}.
 *
 * <p><b>Transfer bound per asset.</b> This retry loop wraps {@link ResumableFetch}'s resume cycle,
 * and that cycle restarts from zero once when RESUMED bytes fail integrity — so one attempt costs 1
 * or 2 transfers. An attempt only continues to the next attempt after spending a second transfer if
 * that restarted transfer failed the <em>transport</em> (a FRESH verification failure ends the fetch
 * immediately, and is never retried). The theoretical cap is therefore {@code 2 * maxAttempts} = 8
 * transfers per asset, reachable only if every attempt both finds resumable-looking bytes on disk
 * and fails integrity on them. The measured compound path — resume, integrity failure, restart from
 * zero, transport drop, then three more attempts — costs 5, pinned by {@code
 * ResumableFetchTest#resumeRestartFollowedByTransportRetriesStaysWithinTheMeasuredTransferBound}.
 */
public final class TransportRetryPolicy {

  /** Highest transport tier {@link #tierForAttempt(int)} will hand out. */
  public static final int MAX_TRANSPORT_TIER = 3;

  /**
   * Cancellation-poll granularity for the between-attempt wait. Cancelling an install only raises a
   * flag — the install thread is never interrupted — so the wait is slept in slices this long with
   * the flag checked between them. Worst-case cancel latency is one slice instead of the whole 27 s
   * backoff.
   */
  public static final long CANCEL_POLL_SLICE_MS = 250L;

  private static final int DEFAULT_MAX_ATTEMPTS = 4;
  private static final long DEFAULT_BASE_DELAY_MS = 3_000L;
  private static final long DEFAULT_DELAY_MULTIPLIER = 3L;
  private static final double DEFAULT_JITTER_FRACTION = 0.30d;

  /** Sleeps between attempts. Injected so tests exercise the policy without spending wall clock. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final int maxAttempts;
  private final long baseDelayMs;
  private final long delayMultiplier;
  private final double jitterFraction;
  private final int startTier;
  private final Sleeper sleeper;
  private final DoubleSupplier random;
  private final boolean sliceSleep;

  private TransportRetryPolicy(
      int maxAttempts,
      long baseDelayMs,
      long delayMultiplier,
      double jitterFraction,
      int startTier,
      Sleeper sleeper,
      DoubleSupplier random,
      boolean sliceSleep) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.baseDelayMs = Math.max(0L, baseDelayMs);
    this.delayMultiplier = Math.max(1L, delayMultiplier);
    this.jitterFraction = Math.max(0d, Math.min(1d, jitterFraction));
    this.startTier = Math.max(0, Math.min(MAX_TRANSPORT_TIER, startTier));
    this.sleeper = sleeper;
    this.random = random;
    this.sliceSleep = sliceSleep;
  }

  /** The shipped policy: 4 attempts, 3 s / 9 s / 27 s apart with +/-30 % jitter, starting at tier 0. */
  public static TransportRetryPolicy defaultPolicy() {
    return new TransportRetryPolicy(
        DEFAULT_MAX_ATTEMPTS,
        DEFAULT_BASE_DELAY_MS,
        DEFAULT_DELAY_MULTIPLIER,
        DEFAULT_JITTER_FRACTION,
        0,
        Thread::sleep,
        () -> ThreadLocalRandom.current().nextDouble(),
        true);
  }

  /** A copy that starts its escalation at {@code tier} (clamped to {@code [0, 3]}). */
  public TransportRetryPolicy withStartTier(int tier) {
    return new TransportRetryPolicy(
        maxAttempts, baseDelayMs, delayMultiplier, jitterFraction, tier, sleeper, random, sliceSleep);
  }

  /** A copy whose between-attempt wait is handled by {@code newSleeper} (tests: record, don't wait). */
  public TransportRetryPolicy withSleeper(Sleeper newSleeper) {
    return new TransportRetryPolicy(
        maxAttempts,
        baseDelayMs,
        delayMultiplier,
        jitterFraction,
        startTier,
        newSleeper,
        random,
        sliceSleep);
  }

  /** A copy whose jitter draw comes from {@code newRandom} (tests: pin the delay exactly). */
  public TransportRetryPolicy withRandom(DoubleSupplier newRandom) {
    return new TransportRetryPolicy(
        maxAttempts,
        baseDelayMs,
        delayMultiplier,
        jitterFraction,
        startTier,
        sleeper,
        newRandom,
        sliceSleep);
  }

  /** A copy allowing {@code attempts} transport attempts in total (1 = no retries). */
  public TransportRetryPolicy withMaxAttempts(int attempts) {
    return new TransportRetryPolicy(
        attempts, baseDelayMs, delayMultiplier, jitterFraction, startTier, sleeper, random, sliceSleep);
  }

  /**
   * A copy that hands a whole wait to its sleeper in ONE call instead of {@link
   * #CANCEL_POLL_SLICE_MS} slices. Test seam only: it lets a test assert the nominal spacing (3 s /
   * 9 s / 27 s) directly on a recording sleeper. Production always slices, so cancellation stays
   * responsive.
   */
  public TransportRetryPolicy withUnslicedSleep() {
    return new TransportRetryPolicy(
        maxAttempts, baseDelayMs, delayMultiplier, jitterFraction, startTier, sleeper, random, false);
  }

  /** Total transport attempts allowed for one file, including the first. */
  public int maxAttempts() {
    return maxAttempts;
  }

  /** The tier this policy starts its escalation at. */
  public int startTier() {
    return startTier;
  }

  /** Transport tier for attempt {@code attemptIndex} (0-based), saturating at the highest tier. */
  public int tierForAttempt(int attemptIndex) {
    return Math.min(MAX_TRANSPORT_TIER, startTier + Math.max(0, attemptIndex));
  }

  /**
   * Wait before attempt {@code attemptIndex} (0-based; only called for {@code attemptIndex >= 1}):
   * {@code base * multiplier^(attemptIndex-1)}, scaled by a jitter factor in {@code [1-j, 1+j]}.
   */
  public long delayMsBeforeAttempt(int attemptIndex) {
    if (attemptIndex <= 0) return 0L;
    long delay = baseDelayMs;
    for (int i = 1; i < attemptIndex; i++) {
      delay *= delayMultiplier;
    }
    double draw = random == null ? 0.5d : random.getAsDouble();
    double factor = 1d + (draw * 2d - 1d) * jitterFraction;
    return Math.max(0L, Math.round(delay * factor));
  }

  /** Sleeps {@code millis}; returns false when interrupted (the caller must abandon the retry). */
  public boolean sleep(long millis) {
    return sleep(millis, null);
  }

  /**
   * Sleeps {@code millis} in {@link #CANCEL_POLL_SLICE_MS} slices, checking {@code cancelled}
   * between them. Returns false when the wait was cut short — interrupted, or cancelled — in which
   * case the caller must abandon the retry instead of starting another attempt.
   */
  public boolean sleep(long millis, BooleanSupplier cancelled) {
    if (sleeper == null) return !cancelRequested(cancelled);
    long remaining = millis;
    while (remaining > 0L) {
      if (cancelRequested(cancelled)) return false;
      long slice = sliceSleep ? Math.min(remaining, CANCEL_POLL_SLICE_MS) : remaining;
      try {
        sleeper.sleep(slice);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      remaining -= slice;
    }
    return !cancelRequested(cancelled);
  }

  private static boolean cancelRequested(BooleanSupplier cancelled) {
    return cancelled != null && cancelled.getAsBoolean();
  }
}
