/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic-counter gate for {@link SampleContract} sites (tempdoc 402 §3.3).
 *
 * <p>Each call to {@link #shouldSample} increments the counter for the
 * given {@link SampleKey} and returns {@code true} on every
 * {@link SampleKey#sampleRate()}-th invocation. Deterministic; two threads
 * hitting the same key race only on the atomic increment (no lost or
 * duplicated fires).
 *
 * <p>Per-call cost: one {@link ConcurrentHashMap#computeIfAbsent} (lock-free
 * fast path after the first insert) plus one {@link AtomicLong#incrementAndGet}
 * plus one modulus. Tempdoc 402 §3.3 claims ~2-5 ns uncontended; this is
 * un-measured. Microbenchmark before placing a sample site in a hot inner
 * loop (per-token, per-doc, per-index-entry).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * private static final SampleKey LEASE_HELD = new SampleKey("encoder.lease.held", 1000);
 *
 * if (ContractSampler.shouldSample(LEASE_HELD) && !lease.isHeld()) {
 *   ContractEmitter.emit("@SampleContract", "400 LR2-b", "Lease not held");
 * }
 * }</pre>
 */
public final class ContractSampler {

  private static final ConcurrentMap<SampleKey, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

  private ContractSampler() {}

  /**
   * Increment the key's counter and return {@code true} iff the new count
   * is a multiple of {@link SampleKey#sampleRate()}. Never throws.
   */
  public static boolean shouldSample(SampleKey key) {
    long n = COUNTERS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    return n % key.sampleRate() == 0;
  }

  /**
   * Clear every counter. Package-private — test-only. Production code
   * never resets counters (doing so would perturb the sample phase for
   * live traffic).
   */
  static void reset() {
    COUNTERS.clear();
  }
}
