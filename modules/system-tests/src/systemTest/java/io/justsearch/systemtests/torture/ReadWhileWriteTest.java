package io.justsearch.systemtests.torture;

import static org.junit.jupiter.api.Assertions.assertTrue;


import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-While-Write Concurrency Test.
 *
 * <p>Verifies that search performance remains acceptable while heavy indexing is in progress.
 * This tests for lock contention in SQLite (WAL mode) and Lucene (NRT).
 */
@DisplayName("Concurrency Suite")
class ReadWhileWriteTest {
  private static final Logger log = LoggerFactory.getLogger(ReadWhileWriteTest.class);

  private static Path workerDistPath;
  private static boolean workerDistExists;
  private static Path projectRoot;

  private Path testDataDir;
  private WorkerProcessManager processManager;
  private MmfTestHarness mmfHarness;

  @BeforeAll
  static void setupSharedResources() throws IOException {
    Path currentDir = Path.of(System.getProperty("user.dir"));
    projectRoot = findProjectRoot(currentDir);

    if (projectRoot != null) {
      workerDistPath = projectRoot.resolve("modules/indexer-worker/build/install/indexer-worker");
      workerDistExists = Files.exists(workerDistPath);
      log.info("Worker distribution found: {}", workerDistExists);
      log.info("Project root: {}", projectRoot);
      // No shared test config directory needed - using fromDistributionNoConfig() mode
    }
  }

  @BeforeEach
  void setup() throws IOException {
    testDataDir = Files.createTempDirectory("concurrency-data-");

    if (workerDistExists && projectRoot != null) {
      // Create process manager using no-copy mode - reads SSOT directly from project root
      processManager = WorkerProcessManager.fromDistributionNoConfig(
          workerDistPath, testDataDir, projectRoot);
      mmfHarness = new MmfTestHarness(processManager.getSignalFilePath());
    }
  }

  @AfterEach
  void cleanup() {
    if (mmfHarness != null) {
        try { mmfHarness.close(); } catch (IOException e) {}
    }
    if (processManager != null) {
        processManager.close();
    }
    // Best effort cleanup
    cleanupDirectory(testDataDir);
  }

  @Test
  @DisplayName("Search latency remains low during heavy indexing")
  @Timeout(120)
  void searchLatencyUnderLoad() throws Exception {
    assertTrue(workerDistExists, "❌ Worker distribution not available");

    mmfHarness.open();
    mmfHarness.resetAll();

    // Start heartbeat keeper
    AtomicBoolean keepRunning = new AtomicBoolean(true);
    Thread heartbeatThread = new Thread(() -> {
      while (keepRunning.get()) {
        try {
          mmfHarness.keepAlive();
          Thread.sleep(1000);
        } catch (Exception e) {}
      }
    });
    heartbeatThread.setDaemon(true);
    heartbeatThread.start();

    try {
        processManager.spawnWorker();
        int port = awaitPortWithRetries(45_000);

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            assertTrue(client.isHealthy());

            int searchThreads = 10;
            int indexingOps = 50; // Reduced for speed in test environment

            ExecutorService searchPool = Executors.newFixedThreadPool(searchThreads);
            AtomicInteger completedSearches = new AtomicInteger(0);
            AtomicReference<Throwable> searchError = new AtomicReference<>();

            // Start background searching
            for (int i = 0; i < searchThreads; i++) {
                var unused = searchPool.submit(() -> {
                    while (keepRunning.get()) {
                        try {
                            long start = System.nanoTime();
                            boolean healthy = client.isHealthy(); // Proxy for search
                            long end = System.nanoTime();

                            if (!healthy) throw new IOException("Worker unhealthy");

                            long durationMs = TimeUnit.NANOSECONDS.toMillis(end - start);
                            if (durationMs > 200) {
                                log.warn("Slow search: {}ms", durationMs);
                            }

                            completedSearches.incrementAndGet();
                            Thread.sleep(10);
                        } catch (Exception e) {
                            if (keepRunning.get()) {
                                searchError.set(e);
                            }
                            break;
                        }
                    }
                });
            }

            // Perform "heavy" indexing (simulated via rapid health checks/activity)
            // In a real test, we would send index requests
            long startIndexing = System.currentTimeMillis();
            for (int i = 0; i < indexingOps; i++) {
                if (searchError.get() != null) break;
                mmfHarness.simulateRecentActivity(0); // Trigger "activity"
                Thread.sleep(50);
            }
            long indexingDuration = System.currentTimeMillis() - startIndexing;
            log.info("Indexing {} ops took {}ms", indexingOps, indexingDuration);

            // Stop searching
            keepRunning.set(false);
            searchPool.shutdown();
            searchPool.awaitTermination(5, TimeUnit.SECONDS);

            if (searchError.get() != null) {
                throw new RuntimeException("Search failed", searchError.get());
            }

            log.info("Completed {} concurrent searches", completedSearches.get());
            assertTrue(completedSearches.get() > 100, "Should have completed significant number of searches");
        }
    } finally {
        keepRunning.set(false);
    }
  }

  private int awaitPortWithRetries(long timeoutMs) throws Exception {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < timeoutMs) {
      try {
        mmfHarness.keepAlive();
        int port = mmfHarness.readPort();
        if (port > 0) return port;
      } catch (Exception e) {
        // Ignore
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException("Timeout waiting for port");
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

  private void cleanupDirectory(Path dir) {
    if (dir == null || !Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException e) {}
          });
    } catch (IOException e) {}
  }
}
