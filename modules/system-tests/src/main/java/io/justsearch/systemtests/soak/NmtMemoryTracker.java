/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.soak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks JVM Native Memory using Native Memory Tracking (NMT).
 *
 * <p>NMT provides accurate off-heap memory tracking that RSS cannot capture.
 * This is essential for detecting memory leaks in Project Panama/FFM allocations.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Worker must be started with: -XX:NativeMemoryTracking=summary
 * NmtMemoryTracker tracker = new NmtMemoryTracker(workerPid);
 * tracker.baseline();
 *
 * // Run stress tests...
 *
 * NmtDiff diff = tracker.diff();
 * assertTrue(diff.internalGrowth() < 1_000_000, "Internal memory grew > 1MB");
 * }</pre>
 *
 * <p><b>Memory Categories:</b>
 * <ul>
 *   <li><b>Java Heap</b> - Managed by GC</li>
 *   <li><b>Class</b> - Loaded class metadata</li>
 *   <li><b>Thread</b> - Thread stacks</li>
 *   <li><b>Code</b> - JIT compiled code</li>
 *   <li><b>GC</b> - GC overhead</li>
 *   <li><b>Internal</b> - JVM internal (watch for leaks!)</li>
 *   <li><b>Other</b> - Native allocations via Panama/FFM</li>
 * </ul>
 */
public final class NmtMemoryTracker {
  private static final Logger log = LoggerFactory.getLogger(NmtMemoryTracker.class);

  // Pattern to match NMT summary lines like:
  // -                    Internal (reserved=1234KB, committed=567KB)
  private static final Pattern CATEGORY_PATTERN = Pattern.compile(
      "^-\\s+(\\w+)\\s+\\(reserved=(\\d+)KB,\\s+committed=(\\d+)KB\\)");

  // Pattern to match total line:
  // Total: reserved=1234KB, committed=567KB
  private static final Pattern TOTAL_PATTERN = Pattern.compile(
      "Total:\\s+reserved=(\\d+)KB,\\s+committed=(\\d+)KB");

  private final long pid;
  private NmtSnapshot baseline;
  private final List<NmtSnapshot> snapshots = new ArrayList<>();

  /**
   * Creates a new NMT tracker for the given process.
   *
   * @param pid The JVM process ID
   */
  public NmtMemoryTracker(long pid) {
    this.pid = pid;
  }

  /**
   * Establishes the memory baseline.
   *
   * @throws IOException if jcmd execution fails
   * @throws NmtUnavailableException if NMT is not enabled for this JVM
   */
  public void baseline() throws IOException, NmtUnavailableException {
    log.info("Establishing NMT baseline for PID {}", pid);
    executeJcmd("baseline");
    this.baseline = captureSnapshot();
    log.info("Baseline established: {} committed", formatBytes(baseline.totalCommitted()));
  }

  /**
   * Captures the current memory state and computes diff from baseline.
   *
   * @return The memory diff since baseline
   * @throws IOException if jcmd execution fails
   */
  public NmtDiff diff() throws IOException, NmtUnavailableException {
    if (baseline == null) {
      throw new IllegalStateException("No baseline established. Call baseline() first.");
    }

    NmtSnapshot current = captureSnapshot();
    snapshots.add(current);

    return new NmtDiff(baseline, current);
  }

  /**
   * Captures a snapshot without computing diff.
   */
  public NmtSnapshot snapshot() throws IOException, NmtUnavailableException {
    NmtSnapshot snap = captureSnapshot();
    snapshots.add(snap);
    return snap;
  }

  /**
   * Returns all captured snapshots (useful for trend analysis).
   */
  public List<NmtSnapshot> snapshots() {
    return List.copyOf(snapshots);
  }

  /**
   * Returns the baseline snapshot.
   */
  public NmtSnapshot getBaseline() {
    return baseline;
  }

  /**
   * Analyzes memory trend across all snapshots.
   *
   * @param category Memory category to analyze
   * @return Trend analysis result
   */
  public TrendAnalysis analyzeTrend(String category) {
    if (snapshots.size() < 2) {
      return new TrendAnalysis(category, 0, 0, false, "insufficient data");
    }

    List<Long> values = new ArrayList<>();
    for (NmtSnapshot snap : snapshots) {
      Long committed = snap.categories().get(category);
      if (committed != null) {
        values.add(committed);
      }
    }

    if (values.size() < 2) {
      return new TrendAnalysis(category, 0, 0, false, "category not found");
    }

    // Simple linear regression to detect trend
    double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
    int n = values.size();
    for (int i = 0; i < n; i++) {
      sumX += i;
      sumY += values.get(i);
      sumXY += i * values.get(i);
      sumX2 += i * i;
    }

    double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
    long totalGrowth = values.get(n - 1) - values.get(0);
    boolean isIncreasing = slope > 100; // > 100 bytes/sample indicates leak

    return new TrendAnalysis(category, totalGrowth, slope, isIncreasing,
        isIncreasing ? "potential leak detected" : "stable");
  }

  private NmtSnapshot captureSnapshot() throws IOException, NmtUnavailableException {
    String output = executeJcmd("summary");
    return parseNmtOutput(output);
  }

  private String executeJcmd(String command) throws IOException, NmtUnavailableException {
    String jcmdPath = findJcmd();
    ProcessBuilder pb = new ProcessBuilder(
        jcmdPath, String.valueOf(pid), "VM.native_memory", command);
    pb.redirectErrorStream(true);

    Process process = pb.start();
    StringBuilder output = new StringBuilder();

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }
    }

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        String result = output.toString();
        if (result.contains("Native memory tracking is not enabled")) {
          throw new NmtUnavailableException(
              "NMT not enabled. Start JVM with -XX:NativeMemoryTracking=summary");
        }
        throw new IOException("jcmd failed with exit code " + exitCode + ": " + result);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("jcmd interrupted", e);
    }

    return output.toString();
  }

  private NmtSnapshot parseNmtOutput(String output) throws IOException {
    Map<String, Long> categories = new HashMap<>();
    long totalReserved = 0;
    long totalCommitted = 0;

    for (String line : output.split("\n")) {
      Matcher categoryMatcher = CATEGORY_PATTERN.matcher(line.trim());
      if (categoryMatcher.matches()) {
        String category = categoryMatcher.group(1);
        long committed = Long.parseLong(categoryMatcher.group(3)) * 1024; // KB to bytes
        categories.put(category, committed);
        continue;
      }

      Matcher totalMatcher = TOTAL_PATTERN.matcher(line.trim());
      if (totalMatcher.matches()) {
        totalReserved = Long.parseLong(totalMatcher.group(1)) * 1024;
        totalCommitted = Long.parseLong(totalMatcher.group(2)) * 1024;
      }
    }

    if (totalCommitted == 0 && categories.isEmpty()) {
      throw new IOException("Failed to parse NMT output: " + output);
    }

    return new NmtSnapshot(
        System.currentTimeMillis(),
        totalReserved,
        totalCommitted,
        Map.copyOf(categories)
    );
  }

  private String findJcmd() {
    String javaHome = System.getProperty("java.home");
    String separator = System.getProperty("file.separator");
    String ext = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? ".exe" : "";
    return javaHome + separator + "bin" + separator + "jcmd" + ext;
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  // === Record types ===

  /**
   * Snapshot of NMT memory state at a point in time.
   */
  public record NmtSnapshot(
      long timestamp,
      long totalReserved,
      long totalCommitted,
      Map<String, Long> categories
  ) {
    public long getCategory(String name) {
      return categories.getOrDefault(name, 0L);
    }
  }

  /**
   * Difference between baseline and current NMT state.
   */
  public record NmtDiff(
      NmtSnapshot baseline,
      NmtSnapshot current
  ) {
    public long totalGrowth() {
      return current.totalCommitted() - baseline.totalCommitted();
    }

    public long internalGrowth() {
      return current.getCategory("Internal") - baseline.getCategory("Internal");
    }

    public long otherGrowth() {
      return current.getCategory("Other") - baseline.getCategory("Other");
    }

    public long heapGrowth() {
      return current.getCategory("Java Heap") - baseline.getCategory("Java Heap");
    }

    public Map<String, Long> categoryGrowth() {
      Map<String, Long> growth = new HashMap<>();
      for (String category : current.categories().keySet()) {
        long diff = current.getCategory(category) - baseline.getCategory(category);
        if (diff != 0) {
          growth.put(category, diff);
        }
      }
      return growth;
    }

    /**
     * Returns true if any category grew more than the threshold.
     */
    public boolean hasLeak(long thresholdBytes) {
      return internalGrowth() > thresholdBytes || otherGrowth() > thresholdBytes;
    }
  }

  /**
   * Trend analysis for a memory category.
   */
  public record TrendAnalysis(
      String category,
      long totalGrowth,
      double slopePerSample,
      boolean isIncreasing,
      String message
  ) {}

  /**
   * Exception thrown when NMT is not available.
   */
  public static class NmtUnavailableException extends Exception {
    public NmtUnavailableException(String message) {
      super(message);
    }
  }
}
