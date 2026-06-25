/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import java.util.Map;

/**
 * Immutable snapshot of encoder profiling data with raw cumulative totals.
 *
 * <p>Consumers derive averages: {@code phaseTotalUs.get(key) / calls}. Deltas between two
 * snapshots: {@code (totalB - totalA) / (callsB - callsA)}.
 */
public record EncoderProfileSnapshot(
    long calls,
    Map<String, Long> phaseTotalUs,
    long ortMinUs,
    long ortMaxUs,
    long ortP50Us,
    long ortP95Us,
    long ortP99Us) {

  /**
   * Formats per-call phase averages as a grep-friendly string.
   *
   * <p>Example: {@code "tokenize=5223us, tensor=38us, ort=81244us, total=86505us"}
   */
  public String formatAvgPhases(long callCount) {
    if (callCount <= 0) {
      return "no calls";
    }
    var sb = new StringBuilder();
    long total = 0;
    for (var e : phaseTotalUs.entrySet()) {
      long avg = e.getValue() / callCount;
      total += avg;
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(e.getKey()).append('=').append(avg).append("us");
    }
    sb.append(", total=").append(total).append("us");
    return sb.toString();
  }

  /**
   * Formats ORT latency distribution as a compact string.
   *
   * <p>Example: {@code "min=5000us, p50=69730us, p95=138936us, p99=228065us, max=751964us"}
   */
  public String formatOrtDist() {
    return "min=" + ortMinUs + "us, p50=" + ortP50Us + "us, p95=" + ortP95Us + "us, p99="
        + ortP99Us + "us, max=" + ortMaxUs + "us";
  }
}
