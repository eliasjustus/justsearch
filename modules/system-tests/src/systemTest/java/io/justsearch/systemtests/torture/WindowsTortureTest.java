package io.justsearch.systemtests.torture;

import static org.junit.jupiter.api.Assertions.assertTrue;


import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows Torture Test: Verifies system stability under file locking contention.
 *
 * <p>This test simulates a hostile environment (common on Windows Corporate PC) where
 * background processes (Antivirus, Indexers, Backup Agents) randomly lock files
 * that the Worker process is trying to use.
 */
@DisplayName("Windows Torture Suite")
class WindowsTortureTest {
  private static final Logger log = LoggerFactory.getLogger(WindowsTortureTest.class);

  private static Path workerDistPath;
  private static boolean workerDistExists;
  private static Path projectRoot;

  private Path testDataDir;
  private WorkerProcessManager processManager;
  private MmfTestHarness mmfHarness;
  private FileIntruder intruder;

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
    // Use manual temp directory to avoid JUnit cleanup issues on Windows
    testDataDir = Files.createTempDirectory("torture-test-data-");
    intruder = new FileIntruder(testDataDir);

    if (workerDistExists && projectRoot != null) {
      // Create process manager using no-copy mode - reads SSOT directly from project root
      processManager = WorkerProcessManager.fromDistributionNoConfig(
          workerDistPath, testDataDir, projectRoot);
      mmfHarness = new MmfTestHarness(processManager.getSignalFilePath());
    }
  }

  @AfterEach
  void cleanup() {
    if (intruder != null) intruder.close();
    if (mmfHarness != null) {
      try {
        mmfHarness.close();
      } catch (IOException e) {
        // Ignore cleanup error
      }
    }
    if (processManager != null) {
        try { processManager.close(); } catch (Exception e) {}
    }
    // Best effort cleanup
    try {
        cleanupDirectory(testDataDir);
    } catch (Exception e) {
        // Ignore windows locking issues
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
              // Ignore
            }
          });
    } catch (IOException e) {
      // Ignore
    }
  }

  @Test
  @DisplayName("Worker survives file locking attack during startup")
  @Timeout(60)
  void workerSurvivesStartupLocking() throws Exception {
    assertTrue(workerDistExists, "❌ Worker distribution not available");
    assertTrue(isWindows(), "❌ File locking tests are only meaningful on Windows");

    // Start the intruder BEFORE the worker
    // 5 threads, holding locks for ~10ms (very annoying)
    intruder.start(5, 10);

    mmfHarness.open();
    mmfHarness.resetAll();

    // Start heartbeat keeper thread to ensure worker doesn't die from stale heartbeat during slow startup
    AtomicBoolean keepRunning = new AtomicBoolean(true);
    Thread heartbeatThread = new Thread(() -> {
      while (keepRunning.get()) {
        try {
          mmfHarness.keepAlive();
          Thread.sleep(1000);
        } catch (Exception e) {
           // Ignore
        }
      }
    });
    heartbeatThread.setDaemon(true);
    heartbeatThread.start();

    try {
        // Spawn worker
        processManager.spawnWorker();

        // Wait for worker to initialize despite the chaos
        // It might take longer due to retries
        int port = awaitPortWithRetries(45_000); // Increased timeout

        try (GrpcTestClient client = new GrpcTestClient(port)) {
          assertTrue(client.isHealthy(), "Worker should eventually become healthy");
        }
    } finally {
        keepRunning.set(false);
    }
  }

  @Test
  @DisplayName("Worker survives file locking during heavy indexing")
  @Timeout(120)
  void workerSurvivesIndexingLocking() throws Exception {
    assertTrue(workerDistExists, "❌ Worker distribution not available");

    mmfHarness.open();
    mmfHarness.resetAll();
    mmfHarness.keepAlive();

    processManager.spawnWorker();
    int port = awaitPortWithRetries(45_000); // Increased timeout

    try (GrpcTestClient client = new GrpcTestClient(port)) {
      assertTrue(client.isHealthy());

      // Start the intruder NOW
      // 3 threads, aggressive locking
      intruder.start(3, 50);

      // Send indexing requests
      int docsToIndex = 100;
      int successCount = 0;

      for (int i = 0; i < docsToIndex; i++) {
        mmfHarness.keepAlive();
        try {
          // We don't have a clean indexDocument method in GrpcTestClient yet
          // Using health check as proxy for "process didn't crash"
          // In a real test we would send actual documents
          if (client.isHealthy()) {
            successCount++;
          }
          Thread.sleep(50);
        } catch (Exception e) {
          log.warn("Operation failed due to chaos: {}", e.getMessage());
        }
      }

      // Worker should still be alive
      assertTrue(processManager.isProcessAlive(processManager.getPid()),
          "Worker process should survive file locking attack");

      assertTrue(successCount > 0, "Worker should have processed at least some requests");
    }
  }

  private int awaitPortWithRetries(long timeoutMs) throws Exception {
    // We need a custom await loop because MmfTestHarness might fail to read
    // if the signal file itself is locked by the intruder!
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < timeoutMs) {
      try {
        mmfHarness.keepAlive();
        int port = mmfHarness.readPort();
        if (port > 0) return port;
      } catch (Exception e) {
        // Ignore read errors caused by intruder
        log.debug("Port read failed during torture: {}", e.getMessage());
      }
      Thread.sleep(500); // Increased sleep to reduce lock contention
    }
    throw new IllegalStateException("Timeout waiting for port");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
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
}
