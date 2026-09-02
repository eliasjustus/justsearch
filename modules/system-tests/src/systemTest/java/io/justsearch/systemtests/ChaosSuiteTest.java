package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.StatusRuntimeException;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chaos Suite: Stability tests that intentionally break the environment.
 *
 * <p>These tests verify system recovery and resilience:
 * <ul>
 *   <li><b>Watchdog Test</b>: Worker dies when Main dies (zombie prevention)</li>
 *   <li><b>Time Lord Test</b>: indexing duty cycle under real foreground search load</li>
 *   <li><b>Disconnector Test</b>: Recovery from worker crash during operation</li>
 *   <li><b>Signal Noise</b>: IPC robustness under MMF fuzzing</li>
 *   <li><b>Stale Port</b>: Ignore stale port values in MMF</li>
 * </ul>
 *
 * <p><b>Note:</b> These tests require the worker JAR to be built.
 * Set system property: {@code justsearch.worker.jar}
 */
@DisplayName("Chaos Suite")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ChaosSuiteTest {
  private static final Logger log = LoggerFactory.getLogger(ChaosSuiteTest.class);

  /** How long to wait for worker state changes. */
  private static final Duration STATE_CHANGE_TIMEOUT = Duration.ofSeconds(5);

  private static Path workerDistPath;
  private static boolean workerDistExists;
  private static Path projectRoot;

  private Path testDataDir;  // Per-test data directory
  private WorkerProcessManager processManager;
  private MmfTestHarness mmfHarness;
  private GrpcTestClient grpcClient;

  @BeforeAll
  static void setupSharedResources() throws IOException {
    // Find project root
    Path currentDir = Path.of(System.getProperty("user.dir"));
    projectRoot = findProjectRoot(currentDir);

    // Check for distribution directory
    String distPath = System.getProperty("justsearch.worker.dist");
    if (distPath != null && !distPath.isBlank()) {
      workerDistPath = Path.of(distPath);
    } else if (projectRoot != null) {
      workerDistPath = projectRoot.resolve("modules/indexer-worker/build/install/indexer-worker");
    } else {
      workerDistPath = Path.of("modules/indexer-worker/build/install/indexer-worker");
    }

    boolean isWindows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    String scriptName = isWindows ? "indexer-worker.bat" : "indexer-worker";
    Path scriptPath = workerDistPath.resolve("bin").resolve(scriptName);
    workerDistExists = Files.exists(scriptPath);

    if (!workerDistExists) {
      log.warn("Worker distribution not found at: {}. Some tests will be skipped.", scriptPath);
      log.warn("Build the worker with: ./gradlew :modules:indexer-worker:installDist");
      return;
    }

    log.info("Worker distribution found at: {}", workerDistPath);
    log.info("Project root: {}", projectRoot);
    // No shared test config directory needed - using fromDistributionNoConfig() mode
  }

  private static Path findProjectRoot(Path start) {
    Path current = start;
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
    if (!workerDistExists || projectRoot == null) {
      return;
    }

    // Each test gets its own data directory
    testDataDir = Path.of(System.getProperty("java.io.tmpdir"),
        "justsearch-chaos-test", "run-" + System.currentTimeMillis(), "data");
    Files.createDirectories(testDataDir);

    // Create process manager using no-copy mode - reads SSOT directly from project root
    processManager = WorkerProcessManager.fromDistributionNoConfig(
        workerDistPath, testDataDir, projectRoot);
    mmfHarness = new MmfTestHarness(processManager.getSignalFilePath());
  }

  @AfterEach
  void cleanup() throws IOException {
    if (grpcClient != null) {
      grpcClient.close();
      grpcClient = null;
    }
    if (mmfHarness != null) {
      mmfHarness.close();
      mmfHarness = null;
    }
    if (processManager != null) {
      processManager.close();
      processManager = null;
    }
  }

  // =========================================================================
  // Helper: Spawn worker with heartbeat keeper
  // =========================================================================

  /**
   * Spawns worker and waits for port, keeping heartbeat alive in background.
   * Worker startup can take 60+ seconds on first run due to JVM warmup.
   *
   * @param timeoutMs Maximum time to wait for port
   * @return The port the worker is listening on
   */
  private int spawnWorkerAndAwaitPort(long timeoutMs) throws Exception {
    long startTime = System.currentTimeMillis();
    long workerPid = processManager.spawnWorker();

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

    int port;
    try {
      port = mmfHarness.awaitPort(timeoutMs, 200);
    } finally {
      keepRunning.set(false);
      heartbeatThread.interrupt();
    }

    log.info("Worker started in {}ms on port {} (PID {})",
        System.currentTimeMillis() - startTime, port, workerPid);
    return port;
  }

  // =========================================================================
  // Watchdog Test: Zombie Prevention
  // =========================================================================

  @Nested
  @DisplayName("Watchdog Test (Zombie Prevention)")
  class WatchdogTests {

    @Test
    @DisplayName("Worker terminates when heartbeat goes stale (simulated main death)")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void workerDiesWhenHeartbeatStale() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution or shared config not available");

      // 1. Start worker
      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive(); // Initial heartbeat

      int port = spawnWorkerAndAwaitPort(120_000);
      long workerPid = processManager.getPid();
      assertTrue(processManager.isProcessAlive(workerPid), "Worker should be alive after spawn");
      assertTrue(port > 0, "Worker should publish a valid port");

      // 2. Verify worker is responding with state-based verification
      grpcClient = new GrpcTestClient(port);
      StatusResponse status = grpcClient.getDetailedStatus();
      assertTrue(status.getCore().getIsHealthy(), "Worker should be healthy");
      assertEquals(0, status.getCore().getQueueDepth(), "Queue should be empty initially");
      assertEquals("IDLE", status.getCore().getState(), "Worker should be IDLE with no jobs");
      assertEquals(workerPid, grpcClient.getWorkerPid(), "Health check PID should match");

      // 3. Keep heartbeat alive during startup grace period (15s in WorkerSignalBus)
      // The worker ignores heartbeat staleness for the first 15 seconds
      log.info("Waiting for startup grace period (keeping heartbeat fresh)...");
      for (int i = 0; i < 16; i++) {
        mmfHarness.keepAlive();
        Thread.sleep(1000);
        assertTrue(processManager.isProcessAlive(workerPid), "Worker should stay alive during grace period");
      }

      // 4. Simulate main process death: stop heartbeat updates
      log.info("Simulating main process death (stale heartbeat)...");
      mmfHarness.simulateDeadMain(10_000); // Heartbeat 10s stale

      // 5. Worker should detect stale heartbeat and terminate
      // Worker checks every ~1s in sentinel thread, so give it time
      boolean terminated = processManager.waitForTermination(workerPid, Duration.ofSeconds(10));
      assertTrue(terminated, "Worker should terminate within 10s after heartbeat goes stale");

      // 6. Verify worker is gone from OS process list
      assertFalse(processManager.isProcessAlive(workerPid), "Worker PID should no longer exist");
    }

    @Test
    @DisplayName("Worker stays alive while heartbeat is fresh")
    void workerStaysAliveWithFreshHeartbeat() throws Exception {
      if (!workerDistExists) {
        log.info("Skipping: Worker distribution not available");
        return;
      }

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive(); // Initial heartbeat

      long workerPid = processManager.spawnWorker();

      // Wait for worker to start
      Thread.sleep(2000);
      assertTrue(processManager.isProcessAlive(workerPid), "Worker should be alive after spawn");

      // Keep heartbeat fresh for 5 seconds
      for (int i = 0; i < 10; i++) {
        mmfHarness.keepAlive();
        Thread.sleep(500);
        assertTrue(processManager.isProcessAlive(workerPid),
            "Worker should stay alive with fresh heartbeat");
      }

      log.info("Worker stayed alive for 5s with fresh heartbeats");
    }
  }

  // =========================================================================
  // Time Lord Test: Throttling Validation
  // =========================================================================

  @Nested
  @DisplayName("Time Lord Test (Duty-cycle validation under foreground load)")
  class TimeLordTests {

    @Test
    @DisplayName("Indexing is throttled — not stopped — while foreground searches are in flight")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void indexingRunsAtAReducedDutyUnderForegroundSearchLoad() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution or shared config not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      int port = spawnWorkerAndAwaitPort(120_000);
      grpcClient = new GrpcTestClient(port);

      // Tempdoc 885 item 3. The predecessor of this test wrote a wall-clock byte into the MMF
      // (mmfHarness.simulateRecentActivity) and asserted the loop went PAUSED and stayed there.
      // That signal no longer exists: the Worker observes its own in-flight foreground RPCs and
      // runs a duty cycle. So the property under test changed with it — from "a Head-written
      // timestamp stops indexing" to "real foreground load slows indexing without starving it",
      // which is exactly what the 885 baseline arm (c) showed the pause could not do (699 of
      // 5184 documents in 22 minutes).
      List<String> corpus = writeThrottlingCorpus(400);
      int submitted = grpcClient.submitBatch(corpus);
      assertTrue(submitted > 0, "corpus should have been accepted for indexing");

      long docsBeforeLoad = grpcClient.getDetailedStatus().getCore().getDocCount();

      // Drive continuous foreground search load: every call increments the Worker's foreground
      // gauge for its duration, which is what the duty cycle reads.
      AtomicBoolean loadRunning = new AtomicBoolean(true);
      AtomicInteger queriesIssued = new AtomicInteger();
      AtomicBoolean observedPaced = new AtomicBoolean(false);
      ExecutorService loadPool = Executors.newFixedThreadPool(2);
      for (int i = 0; i < 2; i++) {
        loadPool.submit(
            () -> {
              while (loadRunning.get()) {
                try {
                  grpcClient.searchText("chaos throttling probe", 5);
                  queriesIssued.incrementAndGet();
                } catch (RuntimeException e) {
                  // A search failing under load is not what this test measures.
                }
              }
            });
      }

      try {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
          if ("PAUSED".equals(grpcClient.getWorkerState())) {
            observedPaced.set(true); // sampled inside a duty-cycle yield window
          }
          Thread.sleep(100);
        }
      } finally {
        loadRunning.set(false);
        loadPool.shutdownNow();
        loadPool.awaitTermination(10, TimeUnit.SECONDS);
      }

      long docsUnderLoad = grpcClient.getDetailedStatus().getCore().getDocCount();
      log.info(
          "Under load: {} queries issued, docCount {} -> {}, paced sample seen={}",
          queriesIssued.get(), docsBeforeLoad, docsUnderLoad, observedPaced.get());

      assertTrue(queriesIssued.get() > 0, "the foreground load must actually have run");
      assertTrue(
          docsUnderLoad > docsBeforeLoad,
          "indexing must keep making progress under continuous foreground load — a full stop is"
              + " the pre-885 breath-hold behaviour this item removed");
      assertTrue(
          observedPaced.get(),
          "the loop must be observed yielding (PAUSED) at least once under load — otherwise the"
              + " duty cycle never engaged and this test proves nothing about throttling");

      // With the load gone, the loop must leave the yield state and finish the corpus.
      String resumedState =
          awaitWorkerState(state -> !"PAUSED".equals(state), STATE_CHANGE_TIMEOUT);
      assertNotEquals("PAUSED", resumedState, "Worker should resume once foreground load drains");
    }

    /** Writes a small synthetic corpus for the throttling probe. */
    private List<String> writeThrottlingCorpus(int fileCount) throws IOException {
      Path corpusDir = testDataDir.resolve("throttle-corpus");
      Files.createDirectories(corpusDir);
      List<String> paths = new ArrayList<>(fileCount);
      for (int i = 0; i < fileCount; i++) {
        Path file = corpusDir.resolve("doc-" + i + ".txt");
        Files.writeString(
            file,
            "chaos throttling probe document " + i + "\n"
                + "the duty cycle must keep indexing this corpus while searches run\n");
        paths.add(file.toAbsolutePath().toString());
      }
      return paths;
    }

    private String awaitWorkerState(java.util.function.Predicate<String> condition, Duration timeout)
        throws InterruptedException {
      long deadline = System.currentTimeMillis() + timeout.toMillis();
      while (System.currentTimeMillis() < deadline) {
        String state = grpcClient.getWorkerState();
        if (condition.test(state)) {
          return state;
        }
        Thread.sleep(100);
      }
      return grpcClient.getWorkerState();
    }
  }

  // =========================================================================
  // Signal Noise Test: MMF Fuzzing
  // =========================================================================

  @Nested
  @DisplayName("Signal Noise Test (MMF Fuzzing)")
  class SignalNoiseTests {

    @Test
    @DisplayName("Worker survives concurrent MMF fuzzing")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void workerSurvivesMmfFuzzing() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution or shared config not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      int port = spawnWorkerAndAwaitPort(120_000);
      long workerPid = processManager.getPid();
      grpcClient = new GrpcTestClient(port);

      // Start fuzzer at 1000Hz writing to reserved bytes
      mmfHarness.startFuzzer(1000);

      // Perform operations while fuzzing
      boolean crashed = false;
      int successCount = 0;

      try {
        for (int i = 0; i < 50; i++) {
          mmfHarness.keepAlive(); // Maintain heartbeat
          try {
            if (grpcClient.isHealthy()) {
              successCount++;
            }
          } catch (Exception e) {
            log.warn("Health check failed during fuzzing: {}", e.getMessage());
          }
          Thread.sleep(100);

          if (!processManager.isProcessAlive(workerPid)) {
            crashed = true;
            break;
          }
        }
      } finally {
        mmfHarness.stopFuzzer();
      }

      assertFalse(crashed, "Worker should not crash from MMF fuzzing");
      assertTrue(successCount > 40, "Most health checks should succeed during fuzzing");
      assertTrue(processManager.isProcessAlive(workerPid), "Worker should still be alive");
    }
  }

  // =========================================================================
  // Stale Port Discovery Test
  // =========================================================================

  @Nested
  @DisplayName("Stale Port Discovery")
  class StalePortTests {

    // Use a fixed location to avoid JUnit temp cleanup issues on Windows
    private Path getSignalFile(String suffix) throws IOException {
      Path dir = Path.of(System.getProperty("java.io.tmpdir"), "justsearch-chaos-test");
      Files.createDirectories(dir);
      return dir.resolve("signal_" + suffix + "_" + System.currentTimeMillis() + ".lock");
    }

    @Test
    @DisplayName("MMF port can be read and written")
    void mmfPortReadWrite() throws IOException {
      Path signalFile = getSignalFile("port");
      MmfTestHarness harness = new MmfTestHarness(signalFile);
      try {
        harness.open();
        harness.resetAll();

        // Write a port
        harness.writePort(9999);
        assertEquals(9999, harness.readPort());

        // Zero the port
        harness.zeroPort();
        assertEquals(0, harness.readPort());

        // Write another port
        harness.writePort(12345);
        assertEquals(12345, harness.readPort());
      } finally {
        harness.close();
      }
    }

    @Test
    @DisplayName("MMF shutdown signal works correctly")
    void mmfShutdownSignal() throws IOException {
      Path signalFile = getSignalFile("shutdown");
      MmfTestHarness harness = new MmfTestHarness(signalFile);
      try {
        harness.open();
        harness.resetAll();

        assertFalse(harness.isShutdownSet());
        harness.setShutdown();
        assertTrue(harness.isShutdownSet());
        harness.clearShutdown();
        assertFalse(harness.isShutdownSet());
      } finally {
        harness.close();
      }
    }
  }

  // =========================================================================
  // Disconnector Test: Crash Recovery
  // =========================================================================

  @Nested
  @DisplayName("Disconnector Test (Crash Recovery)")
  class DisconnectorTests {

    @Test
    @DisplayName("Client detects worker death via gRPC failure")
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void clientDetectsWorkerDeath() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution or shared config not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      log.info("Spawning worker for crash recovery test...");
      long startTime = System.currentTimeMillis();
      long workerPid = processManager.spawnWorker();

      // Keep heartbeat alive in background while worker starts (5s staleness threshold)
      AtomicBoolean keepRunning = new AtomicBoolean(true);
      Thread heartbeatThread = new Thread(() -> {
        while (keepRunning.get()) {
          try {
            mmfHarness.keepAlive();
            Thread.sleep(1000); // Send heartbeat every second
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }, "heartbeat-keeper");
      heartbeatThread.setDaemon(true);
      heartbeatThread.start();

      // Worker startup can take 60-90s on first run due to JVM warmup, class loading, and SSOT parsing
      int port;
      try {
        port = mmfHarness.awaitPort(120_000, 200);
      } finally {
        keepRunning.set(false);
        heartbeatThread.interrupt();
      }
      log.info("Worker started in {}ms on port {}", System.currentTimeMillis() - startTime, port);
      grpcClient = new GrpcTestClient(port);

      // Verify connectivity with state-based verification
      StatusResponse status = grpcClient.getDetailedStatus();
      assertTrue(status.getCore().getIsHealthy(), "Worker should be healthy");
      assertTrue(status.getCore().getUptimeMs() > 0, "Worker should have positive uptime");
      log.info("Worker healthy on port {}, PID {}, state={}", port, workerPid, status.getCore().getState());

      // Kill the worker
      log.info("Force-killing worker PID {}", workerPid);
      processManager.forceKill(workerPid);
      processManager.waitForTermination(workerPid, Duration.ofSeconds(5));

      // Client should fail on next call
      Thread.sleep(500); // Brief delay for socket to close
      assertFalse(grpcClient.isHealthy(), "Health check should fail after worker death");
      log.info("Client correctly detected worker death");
    }

    @Test
    @DisplayName("Worker recovers when killed mid-traffic and restarted")
    @Timeout(value = 300, unit = TimeUnit.SECONDS)  // Increased: 2x 120s awaitPort + overhead
    void workerRecoveryAfterMidTrafficKill() throws Exception {
      assertTrue(workerDistExists && processManager != null,
          "Worker distribution not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      // Phase 1: Spawn and verify healthy
      AtomicBoolean keepRunning = new AtomicBoolean(true);
      Thread heartbeatThread = startHeartbeatThread(keepRunning);

      log.info("Spawning worker for recovery test...");
      long initialPid = processManager.spawnWorker();
      int port1;
      try {
        port1 = mmfHarness.awaitPort(120_000, 200);
      } finally {
        keepRunning.set(false);
        heartbeatThread.interrupt();
      }

      grpcClient = new GrpcTestClient(port1);
      assertTrue(grpcClient.getDetailedStatus().getCore().getIsHealthy(), "Initial worker should be healthy");
      log.info("Initial worker healthy on port {} (PID {})", port1, initialPid);

      // Phase 2: Generate traffic while healthy
      for (int i = 0; i < 10; i++) {
        mmfHarness.keepAlive();
        assertTrue(grpcClient.isHealthy(), "Worker should remain healthy during traffic");
        Thread.sleep(100);
      }

      // Phase 3: Kill worker mid-traffic
      log.info("Killing worker PID {} mid-traffic", initialPid);
      processManager.forceKill(initialPid);
      processManager.waitForTermination(initialPid, Duration.ofSeconds(5));
      Thread.sleep(500); // Socket closure delay

      // Phase 4: Verify client detects failure
      assertFalse(grpcClient.isHealthy(), "Should detect worker death");
      log.info("Client correctly detected worker death");

      // Phase 5: Restart worker
      grpcClient.close();
      mmfHarness.zeroPort();
      mmfHarness.keepAlive();

      // Wait for old heartbeat thread to fully terminate before reusing AtomicBoolean
      try {
        heartbeatThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      keepRunning.set(true);
      heartbeatThread = startHeartbeatThread(keepRunning);

      log.info("Restarting worker...");
      long restartedPid = processManager.spawnWorker();
      assertNotEquals(initialPid, restartedPid, "Restarted worker should have different PID");
      assertTrue(processManager.isProcessAlive(restartedPid), "Restarted worker process should be alive");

      int port2;
      try {
        port2 = mmfHarness.awaitPort(120_000, 200);
      } finally {
        keepRunning.set(false);
        heartbeatThread.interrupt();
      }
      assertTrue(port2 > 0, "Restarted worker should publish valid port");
      log.info("Restarted worker on port {} (PID {})", port2, restartedPid);

      // Phase 6: Verify recovery
      grpcClient = new GrpcTestClient(port2);
      assertTrue(grpcClient.getDetailedStatus().getCore().getIsHealthy(), "Restarted worker should be healthy");

      // Phase 7: Sustained traffic after recovery
      for (int i = 0; i < 10; i++) {
        mmfHarness.keepAlive();
        assertTrue(grpcClient.isHealthy(), "Restarted worker should sustain traffic");
        Thread.sleep(100);
      }

      log.info("Worker recovery test completed successfully");
    }

    private Thread startHeartbeatThread(AtomicBoolean keepRunning) {
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
      return heartbeatThread;
    }
  }

  // =========================================================================
  // Concurrent Request Test
  // =========================================================================

  @Nested
  @DisplayName("Protocol Stress")
  class ProtocolStressTests {

    @Test
    @DisplayName("Multiple concurrent health checks succeed")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void concurrentHealthChecks() throws Exception {
      org.junit.jupiter.api.Assertions.assertTrue(workerDistExists && processManager != null,
          "❌ Worker distribution or shared config not available");

      mmfHarness.open();
      mmfHarness.resetAll();
      mmfHarness.keepAlive();

      int port = spawnWorkerAndAwaitPort(120_000);

      // Initial state-based verification
      try (GrpcTestClient initialClient = new GrpcTestClient(port)) {
        StatusResponse initialStatus = initialClient.getDetailedStatus();
        assertTrue(initialStatus.getCore().getIsHealthy(), "Worker should be healthy before concurrent test");
        log.info("Pre-test state: queueDepth={}, state={}",
            initialStatus.getCore().getQueueDepth(), initialStatus.getCore().getState());
      }

      int threadCount = 10;
      int requestsPerThread = 20;
      CountDownLatch latch = new CountDownLatch(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger failCount = new AtomicInteger(0);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      List<Future<?>> futures = new ArrayList<>();
      try {
        for (int t = 0; t < threadCount; t++) {
          futures.add(executor.submit(() -> {
            try (GrpcTestClient client = new GrpcTestClient(port)) {
              for (int r = 0; r < requestsPerThread; r++) {
                mmfHarness.keepAlive(); // Keep worker alive
                if (client.isHealthy()) {
                  successCount.incrementAndGet();
                } else {
                  failCount.incrementAndGet();
                }
              }
            } finally {
              latch.countDown();
            }
          }));
        }

        latch.await(30, TimeUnit.SECONDS);

        // Propagate any thread exceptions that would otherwise be silently lost
        for (Future<?> f : futures) {
          try {
            f.get();
          } catch (ExecutionException e) {
            log.error("Thread failed during concurrent health check", e.getCause());
            throw e;
          }
        }
      } finally {
        executor.shutdownNow();
      }

      int total = threadCount * requestsPerThread;
      log.info("Concurrent test: {} success, {} fail out of {}",
          successCount.get(), failCount.get(), total);

      assertTrue(successCount.get() >= total * 0.95,
          "At least 95% of concurrent requests should succeed");
    }
  }

  // =========================================================================
  // Harness Unit Tests (no worker needed)
  // =========================================================================

  @Nested
  @DisplayName("Harness Unit Tests")
  class HarnessUnitTests {

    @Test
    @DisplayName("WorkerProcessManager reports process state correctly")
    void processManagerState() throws IOException {
      // Get current JVM's PID - should be alive
      long currentPid = ProcessHandle.current().pid();
      Path dummyDir = Path.of(System.getProperty("java.io.tmpdir"), "justsearch-test-pm");
      Files.createDirectories(dummyDir);
      WorkerProcessManager pm = new WorkerProcessManager(
          Path.of("dummy.jar"), dummyDir);

      assertTrue(pm.isProcessAlive(currentPid), "Current process should be alive");
      assertFalse(pm.isProcessAlive(999999999L), "Non-existent PID should not be alive");
    }

    @Test
    @DisplayName("MmfTestHarness fuzzer starts and stops cleanly")
    void fuzzerStartStop() throws IOException, InterruptedException {
      Path signalDir = Path.of(System.getProperty("java.io.tmpdir"), "justsearch-chaos-test");
      Files.createDirectories(signalDir);
      Path signalFile = signalDir.resolve("fuzzer_" + System.currentTimeMillis() + ".lock");

      MmfTestHarness harness = new MmfTestHarness(signalFile);
      try {
        harness.open();
        harness.resetAll();

        // Start fuzzer
        harness.startFuzzer(100);
        Thread.sleep(200); // Let it run a bit

        // Stop fuzzer
        harness.stopFuzzer();

        // Should not throw
      } finally {
        harness.close();
      }
    }
  }
}
