/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/**
 * Tempdoc 885 item 21b — the backoff ladder for {@link IngestionRetryPolicy#RETRY_WITH_BACKOFF}
 * outcomes, and the bound that ends it.
 *
 * <p><b>What this replaces.</b> The queue used {@code 1s * 2^(attempts-1)} capped at 2^10 s
 * (~17 minutes). Combined with a hard {@code MAX_ATTEMPTS = 3} that transient failures counted
 * against, a file on a network share that was unreachable for twenty minutes was marked
 * permanently {@code FAILED} and never looked at again. The ladder below spans days instead of
 * minutes, so a transient outage of any realistic length is simply waited out.
 *
 * <p><b>Why there is still a bound.</b> Unbounded retry with backoff has no terminal state, so a
 * file that will never be readable stays "pending" forever and is indistinguishable from one that
 * is about to succeed. The owner decision is 7 days from the FIRST failure, then a visible terminal
 * state ({@code RETRY_EXHAUSTED}) that a rescan or a file change resets — a fact a user can act on
 * rather than a queue entry that quietly never resolves.
 *
 * <p>Pure and clock-injected: every method takes its {@code nowMs}, so the seven-day boundary is
 * testable without waiting seven days.
 */
public final class IngestionRetryLadder {

  /** 1 min, 10 min, 1 h, 6 h, 24 h. The last step repeats for every later failure. */
  private static final long[] BACKOFF_STEPS_MS = {
    60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L
  };

  /** The ladder as an immutable list — the shape a test or a doc can read without mutating it. */
  public static java.util.List<Long> steps() {
    return java.util.List.of(
        BACKOFF_STEPS_MS[0],
        BACKOFF_STEPS_MS[1],
        BACKOFF_STEPS_MS[2],
        BACKOFF_STEPS_MS[3],
        BACKOFF_STEPS_MS[4]);
  }

  /** The retry window, measured from the first failure of the current failure run. */
  public static final long MAX_RETRY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;

  private IngestionRetryLadder() {}

  /**
   * The backoff for the {@code failureIndex}-th failure of a run (1-based). Indexes past the end of
   * the ladder repeat its last step, so the retry cadence settles at once a day rather than growing
   * without limit — the bound below is what ends the run, not the step size.
   */
  public static long backoffMs(int failureIndex) {
    int idx = Math.max(1, failureIndex);
    int step = Math.min(idx, BACKOFF_STEPS_MS.length) - 1;
    return BACKOFF_STEPS_MS[step];
  }

  /**
   * Has the failure run outlived the retry window?
   *
   * @param firstFailedAtMs epoch-ms of the first failure in the run, or {@code <= 0} when this IS
   *     the first failure (never exhausted — a first failure always gets a retry)
   */
  public static boolean exhausted(long firstFailedAtMs, long nowMs) {
    if (firstFailedAtMs <= 0L) {
      return false;
    }
    return nowMs - firstFailedAtMs >= MAX_RETRY_WINDOW_MS;
  }

  /**
   * When the {@code failureIndex}-th failure should be retried, clamped so no retry is ever
   * scheduled past the seven-day boundary. Clamping (rather than letting the step overshoot) is
   * what makes "bounded at 7 days" exact instead of "bounded at 7 days plus one step".
   *
   * @param firstFailedAtMs epoch-ms of the first failure in the run; {@code <= 0} means {@code
   *     nowMs} is the first failure
   * @param jitterMs non-negative anti-stampede jitter supplied by the caller (kept out of here so
   *     this class stays pure)
   */
  public static long nextRetryAtMs(
      long firstFailedAtMs, int failureIndex, long nowMs, long jitterMs) {
    long origin = firstFailedAtMs > 0L ? firstFailedAtMs : nowMs;
    long proposed = nowMs + backoffMs(failureIndex) + Math.max(0L, jitterMs);
    long boundary = origin + MAX_RETRY_WINDOW_MS;
    return Math.min(proposed, boundary);
  }
}
