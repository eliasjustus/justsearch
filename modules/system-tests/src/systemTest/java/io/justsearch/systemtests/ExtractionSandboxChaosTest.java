package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.ChaosExtractionSandboxChild;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chaos tier for the extraction sandbox pool (tempdoc 885 item 14 acceptance, "Chaos (live,
 * systemTest source set run explicitly)").
 *
 * <p>A real Worker distribution runs the production {@code PersistentExtractionSandbox}; only the
 * child's <b>parser</b> is substituted, via the production
 * {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} operator override pointing at
 * {@link ChaosExtractionSandboxChild}. That substitution is necessary, not convenient: no real
 * input wedges a parser (the tempdoc-410 adversarial corpus fails fast), so the wedge has to be
 * synthesised — and the stub runs the real {@code PolicyDrivenTikaExtractor} for every file that
 * is not marked, so "the next file extracts normally" is a real extraction rather than a canned
 * answer.
 *
 * <p>{@code JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process} forces every file out of process; under
 * the shipped {@code auto} default a {@code .txt} file is parsed in-process and would never reach
 * the pool.
 */
@DisplayName("Chaos Suite: extraction sandbox pool")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class ExtractionSandboxChaosTest {
  private static final Logger log = LoggerFactory.getLogger(ExtractionSandboxChaosTest.class);

  /** Production extraction deadline (TimeboxedContentExtractor.DEFAULT_TIMEOUT). */
  private static final long EXTRACTION_DEADLINE_MS = 60_000L;

  private static Path workerDistPath;
  private static boolean workerDistExists;
  private static Path projectRoot;

  private Path testDataDir;
  private Path corpusDir;
  private WorkerProcessManager processManager;
  private MmfTestHarness mmfHarness;
  private GrpcTestClient grpcClient;

  @BeforeAll
  static void locateDistribution() {
    projectRoot = findProjectRoot(Path.of(System.getProperty("user.dir")));
    String distPath = System.getProperty("justsearch.worker.dist.dir");
    if (distPath == null || distPath.isBlank()) {
      distPath = System.getProperty("justsearch.worker.dist");
    }
    if (distPath != null && !distPath.isBlank()) {
      workerDistPath = Path.of(distPath);
    } else if (projectRoot != null) {
      workerDistPath = projectRoot.resolve("modules/indexer-worker/build/install/indexer-worker");
    } else {
      workerDistPath = Path.of("modules/indexer-worker/build/install/indexer-worker");
    }
    String scriptName = isWindows() ? "indexer-worker.bat" : "indexer-worker";
    workerDistExists = Files.exists(workerDistPath.resolve("bin").resolve(scriptName));
    log.info("Worker distribution {} (exists={})", workerDistPath, workerDistExists);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
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
    assertTrue(workerDistExists && projectRoot != null,
        "Worker distribution required: ./gradlew :modules:indexer-worker:installDist");
    testDataDir = Path.of(System.getProperty("java.io.tmpdir"), "justsearch-sandbox-chaos",
        "run-" + System.currentTimeMillis(), "data");
    Files.createDirectories(testDataDir);
    corpusDir = testDataDir.resolveSibling("corpus");
    Files.createDirectories(corpusDir);

    processManager = WorkerProcessManager.fromDistributionNoConfig(
        workerDistPath, testDataDir, projectRoot);
    processManager
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_MODE", "process")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_POOL", "1")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND", chaosChildCommand())
        // Flush metrics fast enough that a per-scenario assertion does not wait a whole default
        // interval for extraction.sandbox_restart_total to reach the NDJSON file.
        .withEnv("JUSTSEARCH_TELEMETRY_FLUSH_MS", "1000");
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

  /**
   * The operator-override argv for the chaos child. The classpath goes into a JVM
   * {@code @argfile} because the Worker whitespace-splits
   * {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND}, so it cannot carry a long (or spaced) value
   * inline.
   */
  private String chaosChildCommand() throws IOException {
    String javaExe = Path.of(System.getProperty("java.home"), "bin",
        isWindows() ? "java.exe" : "java").toString();
    assertFalse(javaExe.contains(" "),
        "JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND is whitespace-split, so this harness needs a "
            + "space-free JDK path; got: " + javaExe);
    Path argFile = testDataDir.resolve("chaos-sandbox-child-args.txt");
    assertFalse(argFile.toAbsolutePath().toString().contains(" "),
        "argfile path must be space-free: " + argFile);
    // 128m: large enough for Tika on the small text fixtures, small enough that the chaos-oom
    // file exhausts the child heap in seconds.
    String args = "-Xmx128m\n"
        + "-Dfile.encoding=UTF-8\n"
        + "-cp\n"
        + "\"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
        + ChaosExtractionSandboxChild.class.getName() + "\n";
    Files.writeString(argFile, args, StandardCharsets.UTF_8);
    return javaExe + " @" + argFile.toAbsolutePath();
  }

  private int spawnWorkerAndAwaitPort(long timeoutMs) throws Exception {
    mmfHarness.open();
    mmfHarness.resetAll();
    mmfHarness.keepAlive();
    processManager.spawnWorker();

    Thread heartbeat = startHeartbeat();
    try {
      return mmfHarness.awaitPort(timeoutMs, 200);
    } finally {
      heartbeat.interrupt();
    }
  }

  /** Heartbeat keeper — the Worker self-terminates when the Head heartbeat goes stale. */
  private Thread startHeartbeat() {
    AtomicBoolean keepRunning = new AtomicBoolean(true);
    Thread heartbeat = new Thread(() -> {
      while (keepRunning.get() && !Thread.currentThread().isInterrupted()) {
        try {
          mmfHarness.keepAlive();
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (RuntimeException e) {
          return;
        }
      }
    }, "sandbox-chaos-heartbeat");
    heartbeat.setDaemon(true);
    heartbeat.start();
    return heartbeat;
  }

  private Path corpusFile(String name, String content) throws IOException {
    Path file = corpusDir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private long submitAndAwaitFailure(Path file, long failedCountBefore, long timeoutMs)
      throws Exception {
    assertTrue(grpcClient.submitFile(file.toAbsolutePath().toString()),
        "worker must accept " + file.getFileName());
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      StatusResponse status = grpcClient.getDetailedStatus();
      long failed = status.getFailure().getFailedCount();
      if (failed > failedCountBefore) {
        log.info("{} failed: count={} lastPath={} lastMessage={}", file.getFileName(), failed,
            status.getFailure().getLastFailedPath(),
            status.getFailure().getLastFailedErrorMessage());
        return failed;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("Failure count never advanced past " + failedCountBefore + " for "
        + file.getFileName());
  }

  /**
   * Deletes a chaos file once its failure has been recorded.
   *
   * <p>{@code PARSER_TIMEOUT} and {@code SANDBOX_FAILED} are {@code RETRY_WITH_BACKOFF} outcomes,
   * so the job comes back. With a pool of one, a retrying hang re-wedges the only child for
   * another whole deadline and the next file queues behind it — which would make this test measure
   * the retry ladder rather than the containment property it is about.
   */
  private void retireChaosFile(Path file) throws IOException {
    Files.deleteIfExists(file);
  }

  private void submitAndAwaitIndexed(Path file, long expectedDocCount) throws Exception {
    assertTrue(grpcClient.submitFile(file.toAbsolutePath().toString()),
        "worker must accept " + file.getFileName());
    assertTrue(grpcClient.awaitIndexing(expectedDocCount, 120_000, 500),
        "the file after a sandbox failure must extract normally: " + file.getFileName());
  }

  /** All {@code extraction.sandbox_restart_total} reason tags flushed so far. */
  private List<String> restartReasons() throws IOException {
    Path metrics = testDataDir.resolve("telemetry").resolve("metrics-worker.ndjson");
    List<String> reasons = new ArrayList<>();
    if (!Files.exists(metrics)) {
      return reasons;
    }
    for (String line : Files.readAllLines(metrics, StandardCharsets.UTF_8)) {
      if (!line.contains("\"name\":\"extraction.sandbox_restart_total\"")) {
        continue;
      }
      int at = line.indexOf("\"reason\":\"");
      if (at >= 0) {
        int start = at + "\"reason\":\"".length();
        int end = line.indexOf('"', start);
        if (end > start) {
          reasons.add(line.substring(start, end));
        }
      }
    }
    return reasons;
  }

  private void awaitRestartReason(String reason, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    List<String> seen = List.of();
    while (System.currentTimeMillis() < deadline) {
      seen = restartReasons();
      if (seen.contains(reason)) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("extraction.sandbox_restart_total{reason=" + reason
        + "} never reached the metrics file; observed reasons=" + seen);
  }

  private String workerLog() throws IOException {
    Path logFile = testDataDir.resolve("logs").resolve("worker.log");
    assertTrue(Files.exists(logFile), "worker log must exist at " + logFile);
    return Files.readString(logFile, StandardCharsets.UTF_8);
  }

  /** Worker log lines containing {@code needle} — the branch a failure took is per-line. */
  private List<String> workerLogLines(String needle) throws IOException {
    Path logFile = testDataDir.resolve("logs").resolve("worker.log");
    assertTrue(Files.exists(logFile), "worker log must exist at " + logFile);
    return Files.readAllLines(logFile, StandardCharsets.UTF_8).stream()
        .filter(line -> line.contains(needle))
        .toList();
  }

  // =========================================================================

  @Test
  @DisplayName("Hanging, crashing and OOM children are each contained; the next file indexes and "
      + "the Worker never restarts")
  @Timeout(value = 15, unit = TimeUnit.MINUTES)
  void sandboxFailuresAreContainedAndTheWorkerSurvives() throws Exception {
    int port = spawnWorkerAndAwaitPort(180_000);
    Thread heartbeat = startHeartbeat();
    try {
      grpcClient = new GrpcTestClient(port);
      long workerPid = grpcClient.getWorkerPid();
      assertTrue(workerPid > 0, "worker must report its PID");
      assertTrue(WorkerProcessManager.isProcessAlive(workerPid), "worker alive after spawn");

      long baselineFailures = grpcClient.getDetailedStatus().getFailure().getFailedCount();
      long failed = baselineFailures;
      long indexed = 0;

      // --- 1. Hanging child: killed at the deadline, next file extracts normally.
      Path hanging = corpusFile("chaos-hang-1.txt", "this parse never returns");
      failed = submitAndAwaitFailure(hanging, failed, EXTRACTION_DEADLINE_MS + 180_000);
      StatusResponse afterHang = grpcClient.getDetailedStatus();
      // The Worker normalises paths to lower case on Windows, so compare case-insensitively.
      assertTrue(hanging.toAbsolutePath().toString()
              .equalsIgnoreCase(afterHang.getFailure().getLastFailedPath()),
          "the hanging file must be the one recorded as failed; got: "
              + afterHang.getFailure().getLastFailedPath());
      assertTrue(afterHang.getFailure().getLastFailedErrorMessage().toLowerCase(Locale.ROOT)
              .contains("timed out"),
          "failure reason must name the timeout; got: "
              + afterHang.getFailure().getLastFailedErrorMessage());
      awaitRestartReason("timeout", 30_000);
      assertEquals(workerPid, grpcClient.getWorkerPid(), "worker must not have restarted");

      retireChaosFile(hanging);
      submitAndAwaitIndexed(corpusFile("after-timeout.txt", "healthy content one"), ++indexed);

      // --- 2. Crashing child: exit code in the reason, next file extracts normally.
      Path crashing = corpusFile("chaos-crash-1.txt", "this parse exits 3");
      failed = submitAndAwaitFailure(crashing, failed, 180_000);
      awaitRestartReason("crash", 30_000);
      assertTrue(workerLog().contains("exited with code 3"),
          "the sandbox failure reason must carry the child exit code");
      assertEquals(workerPid, grpcClient.getWorkerPid(), "worker must not have restarted");

      retireChaosFile(crashing);
      submitAndAwaitIndexed(corpusFile("after-crash.txt", "healthy content two"), ++indexed);

      // --- 3. OOM child: permanent parse failure, next file extracts normally.
      Path oom = corpusFile("chaos-oom-1.txt", "this parse exhausts the child heap");
      failed = submitAndAwaitFailure(oom, failed, 240_000);
      awaitRestartReason("oom", 30_000);
      // Which catch clause in JobBatchExtractor ran IS the retry policy: a plain
      // ExtractionException is PARSER_FAILED + IngestionRetryPolicy.NONE ("Content extraction
      // failed for"), while a SandboxExtractionException is SANDBOX_FAILED + RETRY_WITH_BACKOFF
      // ("Extraction sandbox failed for"). Asserting the branch is the permanence claim.
      List<String> oomLines = workerLogLines("exhausted its heap");
      assertFalse(oomLines.isEmpty(),
          "child OOM must be reported as heap exhaustion, not a generic sandbox failure");
      assertTrue(oomLines.stream().anyMatch(l -> l.contains("Content extraction failed for")),
          "child OOM must take the PERMANENT parse-failure branch (IngestionRetryPolicy.NONE); "
              + "got: " + oomLines);
      assertTrue(oomLines.stream().noneMatch(l -> l.contains("Extraction sandbox failed for")),
          "child OOM must NOT be classified as a retryable sandbox failure; got: " + oomLines);
      assertEquals(workerPid, grpcClient.getWorkerPid(), "worker must not have restarted");

      retireChaosFile(oom);
      submitAndAwaitIndexed(corpusFile("after-oom.txt", "healthy content three"), ++indexed);

      // --- The headline: one Worker process survived all three failure classes.
      assertTrue(failed >= baselineFailures + 3,
          "each of the three chaos files must have been recorded as failed; got " + failed);
      assertEquals(3, indexed, "the three files after each failure must all be indexed");
      assertTrue(WorkerProcessManager.isProcessAlive(workerPid),
          "the Worker process must still be alive after three sandbox failures");
      assertEquals(workerPid, grpcClient.getWorkerPid(),
          "the Worker PID must be unchanged across the whole sequence");
      log.info("Sandbox chaos sequence complete on worker PID {} (restart reasons: {})",
          workerPid, restartReasons());
    } finally {
      heartbeat.interrupt();
    }
  }

  @Test
  @DisplayName("Killing the Worker mid-parse leaves no orphaned extraction child")
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void workerShutdownLeavesNoOrphanChild() throws Exception {
    int port = spawnWorkerAndAwaitPort(180_000);
    Thread heartbeat = startHeartbeat();
    long childPid;
    long workerPid;
    try {
      grpcClient = new GrpcTestClient(port);
      workerPid = grpcClient.getWorkerPid();

      // Wedge a child mid-parse. This is the state where the pipe-EOF exit cannot help: the child
      // is not reading stdin, so only the parent-PID gate can reap it.
      Path hanging = corpusFile("chaos-hang-orphan.txt", "this parse never returns");
      assertTrue(grpcClient.submitFile(hanging.toAbsolutePath().toString()),
          "worker must accept the hanging file");

      childPid = awaitExtractionChild(workerPid, 180_000);
      log.info("Extraction child {} is wedged under worker {}", childPid, workerPid);
    } finally {
      heartbeat.interrupt();
    }

    // Kill the Worker outright, so the assertion cannot pass on the graceful shutdown-hook path.
    assertTrue(processManager.forceKill(workerPid), "worker must be killable");
    assertTrue(processManager.waitForTermination(workerPid, Duration.ofSeconds(30)),
        "worker must terminate");

    long deadline = System.currentTimeMillis() + 30_000;
    boolean gone = false;
    while (System.currentTimeMillis() < deadline) {
      if (!WorkerProcessManager.isProcessAlive(childPid)) {
        gone = true;
        break;
      }
      Thread.sleep(250);
    }
    if (!gone) {
      ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
    }
    assertTrue(gone, "extraction child " + childPid
        + " must halt itself once the Worker is gone (parent-PID gate)");
  }

  /** Waits until the Worker has spawned its extraction child, and returns that child's PID. */
  private long awaitExtractionChild(long workerPid, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<ProcessHandle> children = ProcessHandle.of(workerPid)
          .map(handle -> handle.descendants().toList())
          .orElse(List.of());
      if (!children.isEmpty()) {
        return children.get(0).pid();
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Worker " + workerPid + " never spawned an extraction child");
  }
}
