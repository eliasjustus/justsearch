/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.util.function.LongSupplier;

/**
 * Tempdoc 885 item 21e — RISK-002's missing instrument.
 *
 * <p>RISK-002 ("SQLite job queue write contention under high-throughput ingestion") has been at
 * status <em>Monitoring</em> since tempdoc 269 §A9 with the trigger ">2x throughput regression /
 * >30 min bulk imports" and <b>nothing to monitor it with</b>: the queue exposed a depth gauge, and
 * depth is a level, not a rate. A queue that is draining at 2 docs/s and a queue that is draining
 * at 200 docs/s look identical at the same depth. These meters supply the rate, and the lock-wait
 * numbers that say whether contention is the reason a rate is low.
 *
 * <p>Shape: trailing sixty one-second buckets, so {@code ratePerMinute()} is an exact count of the
 * last sixty seconds rather than a decayed estimate — which matters because the risk's trigger is a
 * ratio between two measured runs, and a decayed estimate makes two runs incomparable unless they
 * are the same length. Reads are cheap and take no queue lock, so the OTel flush thread never
 * contends with the very lock it is measuring.
 */
public final class QueueThroughputMeters {

  private static final int SLOTS = 60;

  private final LongSupplier clockMs;
  private final RollingCounter enqueued;
  private final RollingCounter dequeued;
  private final RollingStat lockWait;

  public QueueThroughputMeters() {
    this(System::currentTimeMillis);
  }

  /** @param clockMs injectable wall clock so the sixty-second window is testable in microseconds */
  public QueueThroughputMeters(LongSupplier clockMs) {
    this.clockMs = clockMs;
    this.enqueued = new RollingCounter(clockMs);
    this.dequeued = new RollingCounter(clockMs);
    this.lockWait = new RollingStat(clockMs);
  }

  public void recordEnqueued(long count) {
    enqueued.record(count);
  }

  public void recordDequeued(long count) {
    dequeued.record(count);
  }

  /** Records how long a caller waited to acquire the queue's single write lock. */
  public void recordLockWaitMs(long waitMs) {
    lockWait.record(waitMs);
  }

  public long enqueueRatePerMinute() {
    return enqueued.trailingSum();
  }

  public long dequeueRatePerMinute() {
    return dequeued.trailingSum();
  }

  public long enqueuedTotal() {
    return enqueued.total();
  }

  public long dequeuedTotal() {
    return dequeued.total();
  }

  /** Worst lock wait observed in the trailing minute, in ms. */
  public long lockWaitMaxMs() {
    return lockWait.trailingMax();
  }

  /** Mean lock wait over the trailing minute, in ms; {@code 0} when nothing was measured. */
  public long lockWaitAvgMs() {
    return lockWait.trailingMean();
  }

  /** Wall clock, exposed so callers can time a lock acquisition against the same source. */
  public long nowMs() {
    return clockMs.getAsLong();
  }

  /** Trailing-window sum over one-second buckets. */
  private static final class RollingCounter {
    private final LongSupplier clockMs;
    private final long[] counts = new long[SLOTS];
    private final long[] second = new long[SLOTS];
    private long total;

    RollingCounter(LongSupplier clockMs) {
      this.clockMs = clockMs;
      java.util.Arrays.fill(second, Long.MIN_VALUE);
    }

    synchronized void record(long n) {
      if (n <= 0) {
        return;
      }
      long sec = clockMs.getAsLong() / 1000L;
      int slot = (int) Math.floorMod(sec, SLOTS);
      if (second[slot] != sec) {
        second[slot] = sec;
        counts[slot] = 0L;
      }
      counts[slot] += n;
      total += n;
    }

    synchronized long trailingSum() {
      long sec = clockMs.getAsLong() / 1000L;
      long sum = 0L;
      for (int i = 0; i < SLOTS; i++) {
        // A slot whose stamp is outside the window belongs to an earlier revolution of the ring.
        if (second[i] > sec - SLOTS && second[i] <= sec) {
          sum += counts[i];
        }
      }
      return sum;
    }

    synchronized long total() {
      return total;
    }
  }

  /** Trailing-window max + mean over one-second buckets. */
  private static final class RollingStat {
    private final LongSupplier clockMs;
    private final long[] max = new long[SLOTS];
    private final long[] sum = new long[SLOTS];
    private final long[] count = new long[SLOTS];
    private final long[] second = new long[SLOTS];

    RollingStat(LongSupplier clockMs) {
      this.clockMs = clockMs;
      java.util.Arrays.fill(second, Long.MIN_VALUE);
    }

    synchronized void record(long value) {
      if (value < 0) {
        return;
      }
      long sec = clockMs.getAsLong() / 1000L;
      int slot = (int) Math.floorMod(sec, SLOTS);
      if (second[slot] != sec) {
        second[slot] = sec;
        max[slot] = 0L;
        sum[slot] = 0L;
        count[slot] = 0L;
      }
      max[slot] = Math.max(max[slot], value);
      sum[slot] += value;
      count[slot]++;
    }

    synchronized long trailingMax() {
      long sec = clockMs.getAsLong() / 1000L;
      long worst = 0L;
      for (int i = 0; i < SLOTS; i++) {
        if (second[i] > sec - SLOTS && second[i] <= sec) {
          worst = Math.max(worst, max[i]);
        }
      }
      return worst;
    }

    synchronized long trailingMean() {
      long sec = clockMs.getAsLong() / 1000L;
      long total = 0L;
      long n = 0L;
      for (int i = 0; i < SLOTS; i++) {
        if (second[i] > sec - SLOTS && second[i] <= sec) {
          total += sum[i];
          n += count[i];
        }
      }
      return n == 0L ? 0L : total / n;
    }
  }
}
