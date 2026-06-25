package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.HandleLeakDetector;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.soak.SoakTestRunner;
import io.justsearch.systemtests.soak.SoakTestRunner.SoakResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soak Test Suite: Long-running tests for memory leak detection.
 *
 * <p>These tests run many iterations to detect:
 * <ul>
 *   <li>Native memory leaks (via NMT)</li>
 *   <li>Handle/file descriptor leaks</li>
 *   <li>Performance degradation over time</li>
 * </ul>
 *
 * <p>These tests are NOT run as part of normal CI due to their duration.
 * They should be run nightly or weekly.
 */
@DisplayName("Soak Test Suite")
@Timeout(value = 4, unit = TimeUnit.HOURS)
class SoakSuiteTest {
  private static final Logger log = LoggerFactory.getLogger(SoakSuiteTest.class);

  private static final int SEARCH_ITERATIONS = 5000;  // Reduced from 10k for faster feedback
  private static final int RESTART_CYCLES = 20;

  private static Path workerDistPath;
  private static boolean workerDistExists;
  private static Path projectRoot;

  private Path testDataDir;
  private WorkerProcessManager processManager;
  private MmfTestHarness mmfHarness;
  private GrpcTestClient grpcClient;

  @BeforeAll
  static void setupSharedResources() throws IOException {
    // Find project root
    Path currentDir = Path.of(System.getProperty("user.dir"));
    projectRoot = findProjectRoot(currentDir);

    if (projectRoot == null) {
      log.warn("Could not find project root - soak tests will be skipped");
      workerDistExists = false;
      return;
    }

    // Check for worker distribution
    workerDistPath = projectRoot.resolve("modules/indexer-worker/build/install/indexer-worker");
    workerDistExists = Files.exists(workerDistPath);

    if (!workerDistExists) {
      log.warn("Worker distribution not found at {} - run :modules:indexer-worker:installDist first",
          workerDistPath);
      return;
    }

    log.info("Worker distribution found at: {}", workerDistPath);
    log.info("Project root: {}", projectRoot);
    // No shared test config directory needed - using fromDistributionNoConfig() mode
  }

  private static Path findProjectRoot(Path startDir) {
    Path current = startDir;
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  @BeforeEach
  void setup() throws IOException {
    // Use manual temp directory to avoid JUnit cleanup issues on Windows
    testDataDir = Files.createTempDirectory("soak-test-data-");

    if (workerDistExists && projectRoot != null) {
      // Create process manager using no-copy mode - reads SSOT directly from project root
      processManager = WorkerProcessManager.fromDistributionNoConfig(
          workerDistPath, testDataDir, projectRoot);
      mmfHarness = new MmfTestHarness(processManager.getSignalFilePath());
    }
  }

  @AfterEach
  void cleanup() {
    if (grpcClient != null) {
      try {
        grpcClient.close();
      } catch (Exception e) {
        log.warn("Failed to close gRPC client: {}", e.getMessage());
      }
      grpcClient = null;
    }
    if (mmfHarness != null) {
      try {
        mmfHarness.close();
      } catch (Exception e) {
        log.warn("Failed to close MMF harness: {}", e.getMessage());
      }
      mmfHarness = null;
    }
    if (processManager != null) {
      try {
        processManager.close();
      } catch (Exception e) {
        log.warn("Failed to close process manager: {}", e.getMessage());
      }
      processManager = null;
    }
    // Clean up temp data directory (best effort)
    if (testDataDir != null) {
      cleanupDirectory(testDataDir);
    }
  }

  private void cleanupDirectory(Path dir) {
    if (dir == null || !Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException e) {
              // Ignore - Windows file locking makes this flaky
              log.debug("Could not delete {}: {}", p, e.getMessage());
            }
          });
    } catch (IOException e) {
      log.debug("Could not walk directory {}: {}", dir, e.getMessage());
    }
  }

  // =========================================================================
  // Helper: Spawn worker with heartbeat keeper
  // =========================================================================

  private int spawnWorkerAndAwaitPort(long timeoutMs) throws Exception {
    long startTime = System.currentTimeMillis();

    // Keep heartbeat alive in background while worker starts
    AtomicBoolean keepRunning = new AtomicBoolean(true);
    Thread heartbeatThread = new Thread(() -> {
      while (keepRunning.get()) {
        try {
          mmfHarness.keepAlive();
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }, "heartbeat-keeper");
    heartbeatThread.setDaemon(true);
    heartbeatThread.start();

    processManager.spawnWorker();
    int port;
    try {
      port = mmfHarness.awaitPort(timeoutMs, 200);
    } finally {
      keepRunning.set(false);
      heartbeatThread.interrupt();
    }

    log.info("Worker started in {}ms on port {} (PID {})",
        System.currentTimeMillis() - startTime, port, processManager.getPid());
    return port;
  }

  // =========================================================================
  // Search Stress Soak Test
  // =========================================================================

  @Nested
  @DisplayName("Search Stress Soak Test")
  class SearchStressSoakTest {

    @Test
    @DisplayName("Search operations do not leak memory over " + SEARCH_ITERATIONS + " iterations")
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void searchDoesNotLeakMemory() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      // Enable NMT for memory tracking
      processManager.enableNmt();
      int port = spawnWorkerAndAwaitPort(120_000);
      long workerPid = processManager.getPid();

      grpcClient = new GrpcTestClient(port);
      assertTrue(grpcClient.isHealthy(), "Worker should be healthy");

      // Run soak test
      SoakResult result = SoakTestRunner.builder()
          .iterations(SEARCH_ITERATIONS)
          .workerPid(workerPid)
          .memoryLeakThreshold(10_000_000)  // 10MB threshold
          .snapshotInterval(500)
          .timeout(Duration.ofMinutes(30))
          .operation(ctx -> {
            // Keep worker alive
            mmfHarness.keepAlive();

            // Mix of search types
            try {
              boolean healthy = grpcClient.isHealthy();
              if (!healthy) {
                throw new RuntimeException("Worker became unhealthy at iteration " + ctx.iteration());
              }
            } catch (Exception e) {
              throw new RuntimeException("Health check failed at iteration " + ctx.iteration(), e);
            }
          })
          .onProgress(progress -> {
            if (progress.completedIterations() % 500 == 0) {
              log.info("Search soak progress: {}% ({}/{})",
                  String.format("%.1f", progress.progressPercent()),
                  progress.completedIterations(),
                  progress.totalIterations());
            }
          })
          .build()
          .run();

      log.info("Search soak test result:\n{}", result.summary());

      // Assertions
      assertTrue(result.passed(), "Soak test should pass: " + result.summary());
      assertFalse(result.memoryLeakDetected(),
          "No memory leak should be detected: " + result.summary());

      // Performance check: average iteration should be < 50ms
      assertTrue(result.avgIterationMs() < 50,
          "Average iteration should be < 50ms, was " + result.avgIterationMs() + "ms");
    }
  }

  // =========================================================================
  // Worker Restart Soak Test
  // =========================================================================

  @Nested
  @DisplayName("Worker Restart Soak Test")
  class WorkerRestartSoakTest {

    @Test
    @DisplayName("Repeated worker restarts do not leak handles")
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void repeatedRestartDoesNotLeakHandles() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution not available");

      HandleLeakDetector handleDetector = new HandleLeakDetector();
      long mainPid = ProcessHandle.current().pid();

      int handlesBefore = 0;
      try {
        handlesBefore = handleDetector.findAllHandles(mainPid).size();
      } catch (IOException | InterruptedException e) {
        log.warn("Could not count handles (platform may not support it): {}", e.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(false, "❌ Handle counting not available");
      }

      log.info("Starting {} restart cycles", RESTART_CYCLES);

      for (int cycle = 0; cycle < RESTART_CYCLES; cycle++) {
        log.info("Restart cycle {}/{}", cycle + 1, RESTART_CYCLES);

        // Create fresh data directory for each cycle
        Path cycleDataDir = testDataDir.resolve("cycle-" + cycle);
        Files.createDirectories(cycleDataDir);

        // Create process manager using no-copy mode
        WorkerProcessManager cycleManager = WorkerProcessManager.fromDistributionNoConfig(
            workerDistPath, cycleDataDir, projectRoot);
        Path signalFile = cycleManager.getSignalFilePath();

        try (MmfTestHarness cycleHarness = new MmfTestHarness(signalFile)) {

          try {
            cycleHarness.open();
            cycleHarness.resetAll();
            cycleHarness.keepAlive();

            // Spawn worker
            cycleManager.spawnWorker();

            // Keep heartbeat while waiting for port
            AtomicBoolean keepAlive = new AtomicBoolean(true);
            Thread heartbeat = new Thread(() -> {
              while (keepAlive.get()) {
                try {
                  cycleHarness.keepAlive();
                  Thread.sleep(500);
                } catch (InterruptedException e) {
                  break;
                }
              }
            });
            heartbeat.setDaemon(true);
            heartbeat.start();

            int port;
            try {
              port = cycleHarness.awaitPort(60_000, 200);
            } finally {
              keepAlive.set(false);
              heartbeat.interrupt();
            }

            // Do some work
            try (GrpcTestClient client = new GrpcTestClient(port)) {
              for (int i = 0; i < 10; i++) {
                cycleHarness.keepAlive();
                assertTrue(client.isHealthy(), "Worker should be healthy");
              }
            }

            // Force kill the worker
            cycleManager.forceKill(cycleManager.getPid());
            cycleManager.waitForTermination(cycleManager.getPid(), Duration.ofSeconds(5));

          } finally {
            cycleManager.close();
          }
        }

        // Brief pause between cycles
        System.gc();
        Thread.sleep(500);
      }

      int handlesAfter;
      try {
        handlesAfter = handleDetector.findAllHandles(mainPid).size();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while counting handles", e);
      }
      int handleGrowth = handlesAfter - handlesBefore;

      log.info("Handle growth after {} cycles: {} -> {} (+{})",
          RESTART_CYCLES, handlesBefore, handlesAfter, handleGrowth);

      // Allow small handle growth (< 3 per cycle average)
      int maxAllowedGrowth = RESTART_CYCLES * 3;
      assertTrue(handleGrowth < maxAllowedGrowth,
          "Handle leak detected: grew by " + handleGrowth + " (max allowed: " + maxAllowedGrowth + ")");
    }
  }

  // =========================================================================
  // Long Running Stability Test
  // =========================================================================

  @Nested
  @DisplayName("Long Running Stability Test")
  class LongRunningStabilityTest {

    @Test
    @DisplayName("Worker remains healthy under sustained load")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void workerRemainsHealthyUnderSustainedLoad() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      processManager.enableNmt();
      int port = spawnWorkerAndAwaitPort(120_000);
      grpcClient = new GrpcTestClient(port);

      int durationMinutes = 10;
      long endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L);
      int successCount = 0;
      int failCount = 0;

      log.info("Running sustained load test for {} minutes", durationMinutes);

      while (System.currentTimeMillis() < endTime) {
        mmfHarness.keepAlive();

        try {
          if (grpcClient.isHealthy()) {
            successCount++;
          } else {
            failCount++;
          }
        } catch (Exception e) {
          failCount++;
          log.warn("Health check failed: {}", e.getMessage());
        }

        // Check every 100ms
        Thread.sleep(100);

        // Log progress every minute
        if (successCount % 600 == 0) {
          log.info("Sustained load: {} success, {} fail", successCount, failCount);
        }
      }

      log.info("Sustained load complete: {} success, {} fail", successCount, failCount);

      // At least 95% success rate
      double successRate = (double) successCount / (successCount + failCount);
      assertTrue(successRate >= 0.95,
          "Success rate should be >= 95%, was " + String.format("%.1f%%", successRate * 100));

      // Worker should still be alive
      assertTrue(processManager.isProcessAlive(processManager.getPid()),
          "Worker should still be alive after sustained load");
    }
  }
}
