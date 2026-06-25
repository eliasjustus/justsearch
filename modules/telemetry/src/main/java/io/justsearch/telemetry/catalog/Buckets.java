/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.List;

/**
 * Common histogram bucket lists, shared across catalogs.
 *
 * <p>Adding a new bucket constant here is preferred to declaring an inline {@code List.of(...)}
 * in a catalog — it gives the bucket set a name and makes cross-catalog reuse explicit.
 */
public final class Buckets {

  private Buckets() {}

  /**
   * Time histogram (ms): 100, 250, 500, 1k, 2k, 5k, 10k, 20k. Shape used by
   * {@code WorkerLuceneTelemetryAdapter}'s commit and swap durations.
   */
  public static final List<Long> TIME_HISTOGRAM =
      List.of(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L);

  /**
   * Write-barrier wait histogram (microseconds): 1, 10, 100, 1k, 10k, 100k. Granular at the low
   * end since uncontended acquires complete in single-digit microseconds.
   */
  public static final List<Long> WRITE_BARRIER_HISTOGRAM =
      List.of(1L, 10L, 100L, 1_000L, 10_000L, 100_000L);

  /**
   * SLO latency histogram (ms): 5, 10, 20, 50, 100, 200, 400, 800, 1.5k, 3k, 5k, 10k. Shape used
   * by {@code pipeline.stage_ms} and other latency metrics with sub-second SLOs.
   */
  public static final List<Long> SLO_LATENCY_MS =
      List.of(5L, 10L, 20L, 50L, 100L, 200L, 400L, 800L, 1_500L, 3_000L, 5_000L, 10_000L);

  /**
   * Byte-size histogram: 1KB, 4KB, 16KB, 64KB, 256KB, 1MB, 4MB. Shape used by
   * {@code agent.session.context_size_bytes_at_end} (tempdoc 415); sized for typical model
   * context window growth, with the tail capturing pathological sessions.
   */
  public static final List<Long> BYTE_SIZE_HISTOGRAM =
      List.of(1_024L, 4_096L, 16_384L, 65_536L, 262_144L, 1_048_576L, 4_194_304L);

  /**
   * Small-count histogram: 1, 2, 5, 10, 20, 50. Shape used by
   * {@code agent.session.iterations_at_end} and {@code agent.session.tool_calls_at_end}
   * (tempdoc 415); bounded above by {@code request.maxIterations()}.
   */
  public static final List<Long> SMALL_COUNT_HISTOGRAM = List.of(1L, 2L, 5L, 10L, 20L, 50L);
}
