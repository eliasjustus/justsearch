/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.soak;

import io.justsearch.systemtests.soak.NmtMemoryTracker.NmtDiff;
import io.justsearch.systemtests.soak.NmtMemoryTracker.NmtSnapshot;
import io.justsearch.systemtests.soak.NmtMemoryTracker.TrendAnalysis;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework for running soak tests to detect memory leaks.
 *
 * <p>Soak tests run many iterations of operations to detect:
 * <ul>
 *   <li>Native memory leaks (via NMT)</li>
 *   <li>Handle leaks</li>
 *   <li>Thread leaks</li>
 *   <li>Performance degradation over time</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * SoakTestRunner runner = SoakTestRunner.builder()
 *     .iterations(1000)
 *     .workerPid(workerPid)
 *     .memoryLeakThreshold(1_000_000)  // 1MB
 *     .operation(ctx -> {
 *         // Do one iteration (e.g., search, index)
 *         client.search("test query", 10);
 *     })
 *     .build();
 *
 * SoakResult result = runner.run();
 * assertTrue(result.passed(), result.summary());
 * }</pre>
 */
public final class SoakTestRunner {
  private static final Logger log = LoggerFactory.getLogger(SoakTestRunner.class);

  private final int iterations;
  private final long workerPid;
  private final long memoryLeakThresholdBytes;
  private final int snapshotInterval;
  private final Duration timeout;
  private final Consumer<IterationContext> operation;
  private final List<Consumer<SoakProgress>> progressListeners;

  private SoakTestRunner(Builder builder) {
    this.iterations = builder.iterations;
    this.workerPid = builder.workerPid;
    this.memoryLeakThresholdBytes = builder.memoryLeakThresholdBytes;
    this.snapshotInterval = builder.snapshotInterval;
    this.timeout = builder.timeout;
    this.operation = builder.operation;
    this.progressListeners = List.copyOf(builder.progressListeners);
  }

  /**
   * Creates a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Runs the soak test.
   *
   * @return Test result
   */
  public SoakResult run() {
    log.info("Starting soak test: {} iterations, PID {}", iterations, workerPid);
    Instant start = Instant.now();

    NmtMemoryTracker memoryTracker = null;
    boolean nmtAvailable = false;
    List<IterationResult> iterationResults = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    // Initialize memory tracking
    if (workerPid > 0) {
      try {
        memoryTracker = new NmtMemoryTracker(workerPid);
        memoryTracker.baseline();
        nmtAvailable = true;
        log.info("NMT tracking enabled");
      } catch (NmtMemoryTracker.NmtUnavailableException e) {
        log.warn("NMT not available", e);
        errors.add("NMT unavailable: " + e.getMessage());
      } catch (IOException e) {
        log.warn("Failed to initialize NMT", e);
        errors.add("NMT init failed: " + e.getMessage());
      }
    }

    // Run iterations
    int successCount = 0;
    int failCount = 0;
    boolean timedOut = false;

    for (int i = 0; i < iterations; i++) {
      // Check timeout
      if (Duration.between(start, Instant.now()).compareTo(timeout) > 0) {
        log.warn("Soak test timed out at iteration {}", i);
        timedOut = true;
        break;
      }

      // Run operation
      IterationContext ctx = new IterationContext(i, iterations);
      Instant iterStart = Instant.now();

      try {
        operation.accept(ctx);
        long durationMs = Duration.between(iterStart, Instant.now()).toMillis();
        iterationResults.add(new IterationResult(i, true, durationMs, null));
        successCount++;
      } catch (Exception e) {
        long durationMs = Duration.between(iterStart, Instant.now()).toMillis();
        iterationResults.add(new IterationResult(i, false, durationMs, e.getMessage()));
        failCount++;
        log.warn("Iteration {} failed", i, e);
      }

      // Take memory snapshot at intervals
      if (nmtAvailable && i > 0 && i % snapshotInterval == 0) {
        try {
          memoryTracker.snapshot();
        } catch (IOException | NmtMemoryTracker.NmtUnavailableException e) {
          log.debug("Memory snapshot failed at iteration {}", i);
        }
      }

      // Report progress
      if (i % 100 == 0 || i == iterations - 1) {
        SoakProgress progress = new SoakProgress(
            i + 1,
            iterations,
            successCount,
            failCount,
            Duration.between(start, Instant.now())
        );
        progressListeners.forEach(l -> l.accept(progress));
        log.info("Progress: {}/{} iterations, {} success, {} failed",
            i + 1, iterations, successCount, failCount);
      }
    }

    // Final analysis
    Duration totalDuration = Duration.between(start, Instant.now());
    NmtDiff memoryDiff = null;
    TrendAnalysis internalTrend = null;
    TrendAnalysis otherTrend = null;
    boolean memoryLeakDetected = false;

    if (nmtAvailable && memoryTracker != null) {
      try {
        memoryDiff = memoryTracker.diff();
        internalTrend = memoryTracker.analyzeTrend("Internal");
        otherTrend = memoryTracker.analyzeTrend("Other");

        memoryLeakDetected = memoryDiff.hasLeak(memoryLeakThresholdBytes);
        if (memoryLeakDetected) {
          errors.add(String.format("Memory leak detected: Internal=%+dKB, Other=%+dKB",
              memoryDiff.internalGrowth() / 1024,
              memoryDiff.otherGrowth() / 1024));
        }
      } catch (IOException | NmtMemoryTracker.NmtUnavailableException e) {
        errors.add("Final memory diff failed: " + e.getMessage());
      }
    }

    // Calculate statistics
    double avgDurationMs = iterationResults.stream()
        .mapToLong(IterationResult::durationMs)
        .average()
        .orElse(0);

    long maxDurationMs = iterationResults.stream()
        .mapToLong(IterationResult::durationMs)
        .max()
        .orElse(0);

    boolean passed = failCount == 0 && !memoryLeakDetected && !timedOut;

    return new SoakResult(
        passed,
        iterations,
        successCount,
        failCount,
        totalDuration,
        avgDurationMs,
        maxDurationMs,
        memoryDiff,
        internalTrend,
        otherTrend,
        memoryLeakDetected,
        timedOut,
        errors,
        iterationResults
    );
  }

  // === Builder ===

  public static final class Builder {
    private int iterations = 1000;
    private long workerPid = -1;
    private long memoryLeakThresholdBytes = 1_000_000; // 1MB
    private int snapshotInterval = 100;
    private Duration timeout = Duration.ofHours(1);
    private Consumer<IterationContext> operation = ctx -> {};
    private final List<Consumer<SoakProgress>> progressListeners = new ArrayList<>();

    /**
     * Sets the number of iterations.
     */
    public Builder iterations(int iterations) {
      this.iterations = iterations;
      return this;
    }

    /**
     * Sets the worker process PID for memory tracking.
     */
    public Builder workerPid(long workerPid) {
      this.workerPid = workerPid;
      return this;
    }

    /**
     * Sets the memory leak detection threshold in bytes.
     */
    public Builder memoryLeakThreshold(long bytes) {
      this.memoryLeakThresholdBytes = bytes;
      return this;
    }

    /**
     * Sets how often to take memory snapshots (every N iterations).
     */
    public Builder snapshotInterval(int interval) {
      this.snapshotInterval = interval;
      return this;
    }

    /**
     * Sets the maximum test duration.
     */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Sets the operation to run each iteration.
     */
    public Builder operation(Consumer<IterationContext> operation) {
      this.operation = operation;
      return this;
    }

    /**
     * Adds a progress listener.
     */
    public Builder onProgress(Consumer<SoakProgress> listener) {
      this.progressListeners.add(listener);
      return this;
    }

    /**
     * Builds the SoakTestRunner.
     */
    public SoakTestRunner build() {
      return new SoakTestRunner(this);
    }
  }

  // === Record types ===

  /**
   * Context passed to each iteration.
   */
  public record IterationContext(
      int iteration,
      int totalIterations
  ) {
    public double progress() {
      return (double) iteration / totalIterations;
    }
  }

  /**
   * Result of a single iteration.
   */
  public record IterationResult(
      int iteration,
      boolean success,
      long durationMs,
      String error
  ) {}

  /**
   * Progress update during soak test.
   */
  public record SoakProgress(
      int completedIterations,
      int totalIterations,
      int successCount,
      int failCount,
      Duration elapsed
  ) {
    public double progressPercent() {
      return (double) completedIterations / totalIterations * 100;
    }
  }

  /**
   * Final soak test result.
   */
  public record SoakResult(
      boolean passed,
      int totalIterations,
      int successCount,
      int failCount,
      Duration totalDuration,
      double avgIterationMs,
      long maxIterationMs,
      NmtDiff memoryDiff,
      TrendAnalysis internalTrend,
      TrendAnalysis otherTrend,
      boolean memoryLeakDetected,
      boolean timedOut,
      List<String> errors,
      List<IterationResult> iterationResults
  ) {
    /**
     * Returns a human-readable summary.
     */
    public String summary() {
      StringBuilder sb = new StringBuilder();
      sb.append(passed ? "PASSED" : "FAILED").append("\n");
      sb.append(String.format("Iterations: %d/%d succeeded (%.1f%%)\n",
          successCount, totalIterations, 100.0 * successCount / totalIterations));
      sb.append(String.format("Duration: %s (avg %.1fms/iter, max %dms)\n",
          formatDuration(totalDuration), avgIterationMs, maxIterationMs));

      if (memoryDiff != null) {
        sb.append(String.format("Memory: %+dKB committed",
            memoryDiff.totalGrowth() / 1024));
        if (memoryLeakDetected) {
          sb.append(" [LEAK DETECTED]");
        }
        sb.append("\n");
      }

      if (timedOut) {
        sb.append("WARNING: Test timed out\n");
      }

      if (!errors.isEmpty()) {
        sb.append("Errors:\n");
        for (String error : errors) {
          sb.append("  - ").append(error).append("\n");
        }
      }

      return sb.toString();
    }

    private String formatDuration(Duration d) {
      long seconds = d.toSeconds();
      if (seconds < 60) return seconds + "s";
      if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
      return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
    }
  }
}
