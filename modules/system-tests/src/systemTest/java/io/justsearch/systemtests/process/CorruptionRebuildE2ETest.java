package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 628 — the standing automated guard for the headline self-heal claim: corrupt the active
 * index, and the worker must detect it, back it up (never delete), and rebuild a *losslessly equivalent*
 * index from the source files still on disk — so the same document is searchable again.
 *
 * <p>This is the CI-runnable regression guard that replaces the one-time manual dev-stack validation
 * (which recovered all 34 docs after corruption). It is AI-free: a keyword document is searchable after
 * the rebuild without any embedding model (mirrors {@link MigrationControlE2ETest}). It relies on the
 * shipped recovery posture in the worker distribution's {@code application.yaml}
 * ({@code index.recovery.policy=BACKUP_REBUILD} + {@code index.auto_recovery=true}).
 */
@DisplayName("Corruption → rebuild-from-source E2E (628 self-heal guard)")
class CorruptionRebuildE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @RegisterExtension static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private WorkerProcessManager worker;
  private MmfTestHarness mmf;
  private GrpcTestClient grpcClient;

  @AfterEach
  void cleanup() throws Exception {
    if (grpcClient != null) {
      grpcClient.close();
      grpcClient = null;
    }
    if (worker != null) {
      worker.close();
      worker = null;
    }
    if (mmf != null) {
      mmf.close();
      mmf = null;
    }
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  @DisplayName("A corrupt index self-heals: backup + rebuild-from-source makes the doc searchable again")
  void corruptIndexRebuildsFromSourceAndIsSearchableAgain() throws Exception {
    Path dataDir = env.getTempDir();

    // A watched root the rebuild's enumerator will re-walk from source.
    Path docsDir = dataDir.resolve("rebuild-docs");
    Files.createDirectories(docsDir);
    String marker = "rebuild-marker-" + System.currentTimeMillis();
    Path file = docsDir.resolve("hello.txt");
    Files.writeString(file, "hello " + marker);
    JSON.writeValue(
        dataDir.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", docsDir.toString()))));

    // 1) Baseline: index the file and confirm it is searchable.
    // The worker boots standalone (no Head). After the tempdoc-628 structural fix, standalone routes
    // config through ResolvedConfigBuilder.contributeBaseSources(), so it reads index.auto_recovery
    // from the test-config's application.yaml directly — no config-snapshot workaround needed. This
    // proves the fix end-to-end: the corrupt index self-heals because standalone honors YAML recovery
    // config exactly as production does.
    worker =
        WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataDir)
            .withJvmArgs(env.getWorkerJvmArgs());
    long pid1 = worker.spawnWorker();
    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();
    int port1 = mmf.awaitPort(30_000, 100);
    grpcClient = new GrpcTestClient(port1);
    assertTrue(grpcClient.isHealthy(), "worker healthy before corruption");

    assertTrue(grpcClient.submitFile(file.toString()), "file accepted for indexing");
    assertTrue(grpcClient.awaitIndexing(1, 30_000, 200), "indexing completes");
    assertTrue(
        grpcClient.searchText(marker, 10).getResultsCount() > 0,
        "the marker is searchable before corruption");

    // 2) Stop the worker to release file locks, then corrupt the active generation's commit file.
    grpcClient.close();
    grpcClient = null;
    assertTrue(worker.stopGracefully(Duration.ofSeconds(10)), "worker stops to release locks");
    assertTrue(worker.waitForTermination(pid1, Duration.ofSeconds(15)), "worker terminates");

    Path indexBase = dataDir.resolve("index").resolve("default");
    assertFalse(
        anyBackupExists(indexBase), "no backup should exist before the self-heal");
    corruptActiveSegmentsFile(indexBase);

    // 3+4) Respawn and let the worker self-heal across however many rebuild restarts it needs.
    //      The corruption recovery kicks a blue/green rebuild-from-source that restarts the worker
    //      (like a migration — see MigrationControlE2ETest/RollbackE2ETest), so this tolerates the
    //      worker self-restarting through rebuild generations: respawn whenever it has exited, until
    //      the marker is searchable again (the headline self-heal claim) — or the budget runs out.
    assertTrue(
        recoverAndAwaitSearchable(marker, Duration.ofSeconds(150)),
        "after corruption the worker must rebuild from source and find the marker again");

    // The damaged index was preserved (backed up, never deleted).
    assertTrue(anyBackupExists(indexBase), "the corrupt index must be backed up (never deleted)");

    worker.stopGracefully(Duration.ofSeconds(10));
  }

  /**
   * Drives the worker through its corruption self-heal: (re)spawns it whenever it has exited (the
   * rebuild migration restarts the worker, possibly several times), re-establishes the gRPC client on
   * the new signal port, and returns true as soon as the marker is searchable again.
   */
  private boolean recoverAndAwaitSearchable(String marker, Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    boolean running = false; // worker is currently stopped (we stopped it to inject corruption)
    while (System.currentTimeMillis() < deadline) {
      mmf.keepAlive();
      if (!running || !worker.isAlive()) {
        // (Re)start the worker for the next recovery/rebuild generation.
        if (grpcClient != null) {
          grpcClient.close();
          grpcClient = null;
        }
        mmf.zeroPort();
        mmf.clearShutdown();
        mmf.keepAlive();
        worker.spawnWorker();
        running = true;
        try {
          int port = mmf.awaitPort(20_000, 100);
          grpcClient = new GrpcTestClient(port);
        } catch (IllegalStateException exitedBeforeServing) {
          // Worker exited immediately to start the rebuild migration; loop respawns the next gen.
          running = false;
          continue;
        }
      }
      try {
        if (grpcClient != null
            && grpcClient.isHealthy()
            && grpcClient.searchText(marker, 10).getResultsCount() > 0) {
          return true;
        }
      } catch (RuntimeException cuttingOver) {
        // Worker is mid-restart for cutover; the next loop iteration re-checks liveness and respawns.
      }
      Thread.sleep(500);
    }
    return false;
  }

  /** Overwrites the active generation's {@code segments_N} commit file so the next open detects CORRUPT_INDEX. */
  private static void corruptActiveSegmentsFile(Path indexBase) throws IOException {
    Path statePath = indexBase.resolve("state.json");
    JsonNode state = JSON.readTree(Files.readString(statePath, StandardCharsets.UTF_8));
    String active = state.path("active_generation").asText();
    Path genDir = indexBase.resolve("indices").resolve(active);
    Path segments;
    try (Stream<Path> s = Files.list(genDir)) {
      segments =
          s.filter(p -> p.getFileName().toString().startsWith("segments_"))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("no segments_N in " + genDir));
    }
    byte[] bytes = Files.readAllBytes(segments);
    bytes[bytes.length / 2] = (byte) (bytes[bytes.length / 2] ^ 0xFF);
    Files.write(segments, bytes);
  }

  private static boolean anyBackupExists(Path indexBase) throws IOException {
    Path indices = indexBase.resolve("indices");
    if (!Files.isDirectory(indices)) {
      return false;
    }
    try (Stream<Path> s = Files.list(indices)) {
      return s.anyMatch(p -> p.getFileName().toString().contains(".bak-"));
    }
  }
}
