/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;

/**
 * Thread-safe accumulator for per-call encoder profiling data.
 *
 * <p>Replaces the copy-pasted profiling fields previously in OnnxEmbeddingEncoder, SpladeEncoder,
 * and BertNerInference. Supports incremental per-phase recording across method boundaries via
 * {@link #addPhaseNs} and {@link #recordOrtCall}.
 *
 * <p>Snapshots return raw cumulative totals (not averages) to enable delta computation between
 * successive snapshots for time-series analysis.
 */
public final class EncoderProfileAccumulator {
  private final ConcurrentHashMap<String, AtomicLong> phaseNs;
  private final AtomicLong callCount = new AtomicLong();
  private final AtomicLong minOrtNs = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong maxOrtNs = new AtomicLong(0);
  private final Histogram ortHistogram;

  /**
   * Creates an accumulator with pre-registered phase keys.
   *
   * @param phases phase names (e.g. "tokenize", "tensor", "ort", "extract") — pre-registered to
   *     avoid ConcurrentHashMap miss on the hot path
   */
  public EncoderProfileAccumulator(String... phases) {
    phaseNs = new ConcurrentHashMap<>(phases.length * 2);
    for (String p : phases) {
      phaseNs.put(p, new AtomicLong());
    }
    ortHistogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
  }

  /**
   * Accumulates time for a single sub-phase. Called from wherever the phase timing is computed —
   * may be a different method from other phases. Lock-free for pre-registered phases.
   */
  public void addPhaseNs(String phase, long ns) {
    AtomicLong counter = phaseNs.get(phase);
    if (counter != null) {
      counter.addAndGet(ns);
    } else {
      phaseNs.computeIfAbsent(phase, k -> new AtomicLong()).addAndGet(ns);
    }
  }

  /**
   * Records completion of one ORT inference call. Also accumulates the "ort" phase total via {@link
   * #addPhaseNs}. Increments the call counter and updates the ORT latency distribution
   * (min/max/histogram). Must be called exactly once per inference call — callers should NOT also
   * call {@code addPhaseNs("ort", ...)} separately.
   */
  public void recordOrtCall(long ortCallNs) {
    addPhaseNs("ort", ortCallNs);
    callCount.incrementAndGet();
    updateAtomicMin(minOrtNs, ortCallNs);
    updateAtomicMax(maxOrtNs, ortCallNs);
    synchronized (ortHistogram) {
      ortHistogram.recordValue(Math.min(ortCallNs, ortHistogram.getHighestTrackableValue()));
    }
  }

  /** Current call count — used by encoders for periodic log gating. */
  public long callCount() {
    return callCount.get();
  }

  /**
   * Returns an immutable snapshot with raw cumulative totals, always fresh. Returns {@code null} if
   * no calls have been recorded.
   *
   * <p>Note: the snapshot is not atomic across fields. Between reading callCount and iterating
   * phaseNs, concurrent calls may increment counters, so phase totals may reflect slightly more
   * calls than the reported count. This is acceptable for profiling data.
   */
  public EncoderProfileSnapshot snapshot() {
    long calls = callCount.get();
    if (calls == 0) {
      return null;
    }
    var totalUs = new LinkedHashMap<String, Long>();
    for (var e : phaseNs.entrySet()) {
      totalUs.put(e.getKey(), e.getValue().get() / 1000);
    }
    synchronized (ortHistogram) {
      return new EncoderProfileSnapshot(
          calls,
          Map.copyOf(totalUs),
          minOrtNs.get() / 1000,
          maxOrtNs.get() / 1000,
          ortHistogram.getValueAtPercentile(50) / 1000,
          ortHistogram.getValueAtPercentile(95) / 1000,
          ortHistogram.getValueAtPercentile(99) / 1000);
    }
  }

  /** Resets all counters and histogram. Called by {@link OperationalMetrics#resetAll()}. */
  public void reset() {
    callCount.set(0);
    phaseNs.values().forEach(a -> a.set(0));
    minOrtNs.set(Long.MAX_VALUE);
    maxOrtNs.set(0);
    synchronized (ortHistogram) {
      ortHistogram.reset();
    }
  }

  private static void updateAtomicMin(AtomicLong min, long value) {
    long current;
    do {
      current = min.get();
      if (value >= current) return;
    } while (!min.compareAndSet(current, value));
  }

  private static void updateAtomicMax(AtomicLong max, long value) {
    long current;
    do {
      current = max.get();
      if (value <= current) return;
    } while (!max.compareAndSet(current, value));
  }
}
