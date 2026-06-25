/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Utility functions shared across benchmark classes.
 *
 * <p>This class consolidates duplicated code from the various benchmark classes to ensure
 * consistent behavior, especially for percentile calculations.
 */
public final class BenchmarkUtils {
  private static final Logger log = LoggerFactory.getLogger(BenchmarkUtils.class);

  // Cached OSHI OperatingSystem for RSS capture - expensive to create (~10-50ms), thread-safe for reads
  private static final OperatingSystem OSHI_OS;

  static {
    OperatingSystem os = null;
    try {
      os = new SystemInfo().getOperatingSystem();
    } catch (Exception e) {
      log.debug("OSHI initialization failed (RSS capture will be unavailable): {}", e.getMessage());
    }
    OSHI_OS = os;
  }

  private BenchmarkUtils() {}

  /**
   * Calculate percentile using R-7 linear interpolation (Excel/NumPy default).
   *
   * <p><b>IMPORTANT:</b> This method accepts {@code p} in range [0, 1] to match existing benchmark
   * code. Internally converts to [0, 100] for Apache Commons Math.
   *
   * <p>Edge case behavior (matches existing EngineIndexBench):
   *
   * <ul>
   *   <li>Empty array: returns 0.0 (not an exception)
   *   <li>p &lt;= 0: returns first element (minimum)
   *   <li>p &gt;= 1: returns last element (maximum)
   * </ul>
   *
   * @param values the data values (will be sorted internally by Commons Math)
   * @param p percentile to compute in range [0, 1] (e.g., 0.95 for p95)
   * @return the percentile value
   */
  public static double percentile(double[] values, double p) {
    // Match existing behavior: empty/null array returns 0.0
    if (values == null || values.length == 0) {
      return 0.0;
    }

    // Match existing behavior: edge cases return min/max element
    // Use O(n) stream operations instead of O(n log n) sort
    if (p <= 0) {
      return Arrays.stream(values).min().orElse(0.0);
    }
    if (p >= 1) {
      return Arrays.stream(values).max().orElse(0.0);
    }

    // Apache Commons Math expects p in [0, 100], our API uses [0, 1]
    // Percentile is NOT thread-safe, so create new instance per call
    return new Percentile()
        .withEstimationType(Percentile.EstimationType.R_7)
        .evaluate(values, p * 100.0);
  }

  /**
   * Convenience overload for List&lt;Double&gt;.
   *
   * @param values list of values
   * @param p percentile in range [0, 1]
   * @return the percentile value
   */
  public static double percentile(List<Double> values, double p) {
    if (values == null || values.isEmpty()) {
      return 0.0;
    }
    return percentile(values.stream().mapToDouble(Double::doubleValue).toArray(), p);
  }

  /**
   * Convenience overload for List&lt;Long&gt; (e.g., nanosecond timings).
   *
   * <p>Note: Long values are converted to double; precision loss is negligible for values &lt;
   * 2^53 (about 10^15 nanoseconds = ~11 days).
   *
   * @param values list of long values
   * @param p percentile in range [0, 1]
   * @return the percentile value as double
   */
  public static double percentileLong(List<Long> values, double p) {
    if (values == null || values.isEmpty()) {
      return 0.0;
    }
    return percentile(values.stream().mapToDouble(Long::doubleValue).toArray(), p);
  }

  /**
   * Round to 2 decimal places.
   *
   * @param v value to round
   * @return rounded value
   */
  public static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  /**
   * Round to 3 decimal places.
   *
   * @param v value to round
   * @return rounded value
   */
  public static double round3(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }

  private static final int GIT_TIMEOUT_SECONDS = 5;

  /**
   * Get the current git commit SHA.
   *
   * <p>Times out after {@value #GIT_TIMEOUT_SECONDS} seconds to prevent hangs if git is broken or
   * waiting for input.
   *
   * @return the full SHA, or null if git is not available, not in a repo, or times out
   */
  public static String getGitSha() {
    Process proc = null;
    try {
      proc = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
      String stdout = new String(proc.getInputStream().readAllBytes(), UTF_8).trim();
      boolean finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return null;
      }
      return proc.exitValue() == 0 ? stdout : null;
    } catch (Exception e) {
      return null;
    } finally {
      if (proc != null && proc.isAlive()) {
        proc.destroyForcibly();
      }
    }
  }

  /**
   * Get the short (7-character) git SHA.
   *
   * @param sha full SHA
   * @return short SHA, or the input if null or too short
   */
  public static String shortSha(String sha) {
    return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
  }

  /**
   * Recursively delete a directory and all its contents.
   *
   * @param path the path to delete
   * @throws IOException if deletion fails
   */
  public static void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException ignored) {
                  // Best effort deletion
                }
              });
    }
  }

  /**
   * Calculate the total size of all files in a directory.
   *
   * @param root the directory to measure
   * @return total size in bytes
   * @throws IOException if walking the directory fails
   */
  public static long directorySizeBytes(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return 0L;
    }
    try (var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              p -> {
                try {
                  return Files.size(p);
                } catch (IOException e) {
                  return 0L;
                }
              })
          .sum();
    }
  }

  /**
   * Generate a random vector with values in range [-1, 1].
   *
   * @param rnd random number generator
   * @param dim dimensionality of the vector
   * @return a new float array with random values
   */
  public static float[] randomVector(Random rnd, int dim) {
    float[] v = new float[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = rnd.nextFloat() * 2f - 1f;
    }
    return v;
  }

  /**
   * Snapshot of JVM heap memory state.
   *
   * <p><b>IMPORTANT:</b> This captures JVM heap only. Lucene's MMapDirectory uses off-heap memory
   * (OS page cache) which is NOT included in these metrics. Actual memory consumption may be
   * significantly higher than reported heap values.
   *
   * @param usedBytes current heap usage in bytes
   * @param maxBytes maximum heap size (-Xmx) in bytes
   * @param gcCount total number of GC invocations across all collectors
   * @param gcTimeMs total time spent in GC across all collectors in milliseconds
   */
  public record HeapSnapshot(long usedBytes, long maxBytes, long gcCount, long gcTimeMs) {

    /**
     * Capture current JVM heap state.
     *
     * @return a new HeapSnapshot with current values
     */
    public static HeapSnapshot capture() {
      MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
      MemoryUsage heap = mem.getHeapMemoryUsage();
      long gcCount =
          ManagementFactory.getGarbageCollectorMXBeans().stream()
              .mapToLong(GarbageCollectorMXBean::getCollectionCount)
              .filter(c -> c >= 0) // -1 means undefined
              .sum();
      long gcTime =
          ManagementFactory.getGarbageCollectorMXBeans().stream()
              .mapToLong(GarbageCollectorMXBean::getCollectionTime)
              .filter(t -> t >= 0) // -1 means undefined
              .sum();
      return new HeapSnapshot(heap.getUsed(), heap.getMax(), gcCount, gcTime);
    }

    /**
     * Convert to a Map suitable for JSON serialization.
     *
     * @return map with keys: used_bytes, max_bytes, gc_count, gc_time_ms
     */
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("used_bytes", usedBytes);
      map.put("max_bytes", maxBytes);
      map.put("gc_count", gcCount);
      map.put("gc_time_ms", gcTimeMs);
      return map;
    }
  }

  /**
   * Snapshot of process-level memory (RSS) from the OS perspective.
   *
   * <p>RSS (Resident Set Size) represents the actual physical memory used by the process,
   * including heap, native memory, and memory-mapped files (like Lucene's MMapDirectory). This
   * provides a more complete picture than JVM heap metrics alone.
   *
   * <p>On Windows, this returns the Working Set size (includes shared pages like DLLs). Note: this
   * may be larger than Task Manager's "Memory (Private Working Set)" column. On Linux/macOS, this
   * returns the RSS from /proc or equivalent.
   *
   * <p><b>Note:</b> Heap and RSS captures are not atomic. If GC runs between captures, they may
   * reflect different memory states.
   *
   * @param rssBytes resident set size in bytes, or -1 if unavailable
   * @param vszBytes virtual memory size in bytes, or -1 if unavailable
   */
  public record RssSnapshot(long rssBytes, long vszBytes) {

    /** RSS unavailable on this platform/JVM. */
    public static final RssSnapshot UNAVAILABLE = new RssSnapshot(-1, -1);

    /**
     * Capture current process RSS using OSHI.
     *
     * <p>Uses cached OSHI instances for performance (~1ms vs ~50ms if created each call).
     *
     * @return snapshot with RSS/VSZ, or UNAVAILABLE if OSHI fails
     */
    public static RssSnapshot capture() {
      if (OSHI_OS == null) {
        return UNAVAILABLE;
      }
      try {
        int pid = (int) ProcessHandle.current().pid();
        OSProcess proc = OSHI_OS.getProcess(pid);
        if (proc == null) {
          log.debug("OSHI returned null for PID {} (RSS capture unavailable)", pid);
          return UNAVAILABLE;
        }
        long rss = proc.getResidentSetSize();
        long vsz = proc.getVirtualSize();
        // Treat 0 as unavailable (some containers report 0 incorrectly)
        if (rss <= 0) {
          log.debug("OSHI returned RSS={} for PID {} (treating as unavailable)", rss, pid);
          return UNAVAILABLE;
        }
        return new RssSnapshot(rss, vsz);
      } catch (Exception e) {
        log.debug("RSS capture failed (will report as unavailable): {}", e.getMessage());
        return UNAVAILABLE;
      }
    }

    /**
     * Check if RSS was successfully captured.
     *
     * @return true if RSS is available (positive value)
     */
    public boolean isAvailable() {
      return rssBytes > 0;
    }

    /**
     * Convert to Map for JSON serialization. Returns empty map if unavailable.
     *
     * @return map with keys: rss_bytes, vsz_bytes (if available)
     */
    public Map<String, Object> toMap() {
      if (!isAvailable()) {
        return Map.of();
      }
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("rss_bytes", rssBytes);
      if (vszBytes >= 0) {
        map.put("vsz_bytes", vszBytes);
      }
      return map;
    }
  }
}
