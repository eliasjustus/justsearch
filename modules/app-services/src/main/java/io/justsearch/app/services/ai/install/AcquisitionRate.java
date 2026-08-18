/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Sliding-window transfer rate and remaining-time estimator for an acquisition run.
 *
 * <p>The install surface today shows a percentage and a byte counter on a multi-GB download and no
 * rate at all, so "is this moving" is answered by watching a number for a while. This is the backend
 * half of that missing answer: byte-progress samples in, bytes-per-second and a remaining-seconds
 * horizon out.
 *
 * <p><b>Honest or silent — never a fabricated number.</b> Every arm that cannot support an estimate
 * returns {@link Estimate#UNKNOWN} rather than a zero the UI would render as {@code 0s}: too few
 * samples, a window too short to measure, a transfer that has stopped reporting, and a window over
 * which no bytes arrived. The unknown sentinel is {@code -1}, matching the convention the
 * presentation half already uses for "no honest basis" ({@code startupEstimate.ts}'s {@code
 * lastStartupDurationMs < 0} arm, which renders no number at all).
 *
 * <p><b>Monotonic only.</b> The clock is an injected {@link LongSupplier} of nanos — never {@code
 * System.currentTimeMillis} — so a wall-clock adjustment mid-download cannot produce a negative span
 * or an absurd rate, and a test can age the window without spending real seconds.
 */
public final class AcquisitionRate {

  /**
   * How far back the rate is measured. Long enough that one slow chunk does not swing the number,
   * short enough that a transport slowdown shows up while the user is still looking.
   */
  public static final long DEFAULT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(20);

  /** Shortest span the window may be measured over; below it the division is noise, not a rate. */
  public static final long DEFAULT_MIN_SPAN_NANOS = TimeUnit.SECONDS.toNanos(2);

  /** Fewest samples that can support an estimate. Two points are a line, not a trend. */
  public static final int DEFAULT_MIN_SAMPLES = 3;

  /**
   * How long after the newest sample the estimate stays valid. Past it the transfer has stopped
   * reporting progress, and the last measured rate describes a transfer that is no longer running.
   */
  public static final long DEFAULT_STALL_NANOS = TimeUnit.SECONDS.toNanos(10);

  /** Upper bound on retained samples, so a chatty transport cannot grow the window unboundedly. */
  private static final int MAX_SAMPLES = 512;

  /**
   * A rate reading and the horizon derived from it.
   *
   * <p>The two are independently knowable: a rate can be measured while the total size is unknown,
   * so {@link #rateKnown()} and {@link #remainingKnown()} are asked separately. A consumer must not
   * read either field without testing its predicate first — {@code -1} is the "nothing honest to
   * say" sentinel, not a value.
   *
   * @param bytesPerSecond measured transfer rate, or {@code -1} when unknown
   * @param remainingSeconds seconds until the set is complete at that rate, or {@code -1} when
   *     unknown ({@code 0} means genuinely nothing left, which is a value and not a sentinel)
   */
  public record Estimate(double bytesPerSecond, long remainingSeconds) {

    /** Nothing honest to say: too few samples, too short a window, or a stalled transfer. */
    public static final Estimate UNKNOWN = new Estimate(-1d, -1L);

    public boolean rateKnown() {
      return bytesPerSecond >= 0d;
    }

    public boolean remainingKnown() {
      return remainingSeconds >= 0L;
    }
  }

  /** One cumulative-byte reading and when it was taken. */
  private record Sample(long atNanos, long bytes) {}

  private final LongSupplier nanoClock;
  private final long windowNanos;
  private final long minSpanNanos;
  private final int minSamples;
  private final long stallNanos;
  private final Deque<Sample> samples = new ArrayDeque<>();
  private long lastBytes = -1L;

  public AcquisitionRate(
      LongSupplier nanoClock,
      long windowNanos,
      long minSpanNanos,
      int minSamples,
      long stallNanos) {
    this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    this.windowNanos = Math.max(1L, windowNanos);
    this.minSpanNanos = Math.max(0L, minSpanNanos);
    this.minSamples = Math.max(2, minSamples);
    this.stallNanos = Math.max(1L, stallNanos);
  }

  /** The production tuning, reading {@code clock} for every sample and every estimate. */
  public static AcquisitionRate withDefaults(LongSupplier nanoClock) {
    return new AcquisitionRate(
        nanoClock,
        DEFAULT_WINDOW_NANOS,
        DEFAULT_MIN_SPAN_NANOS,
        DEFAULT_MIN_SAMPLES,
        DEFAULT_STALL_NANOS);
  }

  /**
   * Records a cumulative byte count for the run.
   *
   * <p>A count that moves BACKWARDS ends the current series rather than being smoothed into it: the
   * fetch restarts a file from zero after an integrity failure, and the bytes before that restart no
   * longer describe the same monotone progression. Continuing the old baseline through the drop
   * would report a rate that never happened.
   */
  public synchronized void sample(long cumulativeBytes) {
    if (cumulativeBytes < 0L) return;
    if (cumulativeBytes < lastBytes) {
      samples.clear();
    }
    lastBytes = cumulativeBytes;
    long now = nanoClock.getAsLong();
    samples.addLast(new Sample(now, cumulativeBytes));
    long cutoff = now - windowNanos;
    while (samples.size() > 1 && samples.peekFirst().atNanos() < cutoff) {
      samples.removeFirst();
    }
    while (samples.size() > MAX_SAMPLES) {
      samples.removeFirst();
    }
  }

  /** Forgets every sample — the next estimate is UNKNOWN again until the window refills. */
  public synchronized void reset() {
    samples.clear();
    lastBytes = -1L;
  }

  /**
   * The current estimate for a set totalling {@code totalBytes}.
   *
   * @param totalBytes the whole set's size; {@code <= 0} means unknown, which yields a known rate
   *     with an unknown horizon rather than a fabricated one
   */
  public synchronized Estimate estimate(long totalBytes) {
    if (samples.size() < minSamples) return Estimate.UNKNOWN;
    Sample newest = samples.peekLast();
    Sample oldest = samples.peekFirst();
    // The transfer stopped reporting: the last measured rate describes a transfer that is no longer
    // running, and printing it would be the most confident lie available.
    if (nanoClock.getAsLong() - newest.atNanos() > stallNanos) return Estimate.UNKNOWN;
    long spanNanos = newest.atNanos() - oldest.atNanos();
    if (spanNanos < minSpanNanos || spanNanos <= 0L) return Estimate.UNKNOWN;
    long deltaBytes = newest.bytes() - oldest.bytes();
    // Time passed and no bytes arrived. Zero is the arithmetically correct rate and a useless one:
    // it divides into an infinite horizon, so the honest answer is that we do not know.
    if (deltaBytes <= 0L) return Estimate.UNKNOWN;
    double bytesPerSecond = deltaBytes * 1_000_000_000d / spanNanos;
    if (totalBytes <= 0L) return new Estimate(bytesPerSecond, -1L);
    long remainingBytes = Math.max(0L, totalBytes - newest.bytes());
    return new Estimate(bytesPerSecond, Math.round(remainingBytes / bytesPerSecond));
  }

  /** How many samples the window currently holds — for tests and diagnostics. */
  public synchronized int sampleCount() {
    return samples.size();
  }
}
