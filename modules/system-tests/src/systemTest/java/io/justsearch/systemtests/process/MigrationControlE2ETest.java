package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("Migration Control E2E Test")
class MigrationControlE2ETest {
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
    // Close worker FIRST to release file locks
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
  @DisplayName("Manual StartMigration triggers Blue/Green cutover and preserves search")
  void manualStartMigrationCutsOverAndPreservesSearch() throws Exception {
    Path dataDir = env.getTempDir();

    // Root we will index and also use as the migration enumerator root.
    Path docsDir = dataDir.resolve("migration-docs");
    Files.createDirectories(docsDir);

    String marker = "migration-marker-" + System.currentTimeMillis();
    Path file = docsDir.resolve("hello.txt");
    Files.writeString(file, "hello " + marker);

    // Persisted watched roots file used by migration enumerator (preferred over config roots).
    Path rootsFile = dataDir.resolve("watched_roots.json");
    JSON.writeValue(
        rootsFile.toFile(),
        Map.of("roots", List.of(Map.of("path", docsDir.toString()))));

    worker =
        WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataDir).withJvmArgs(env.getWorkerJvmArgs());
    long pid1 = worker.spawnWorker();

    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();

    int grpcPort1 = mmf.awaitPort(30_000, 100);
    grpcClient = new GrpcTestClient(grpcPort1);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy before migration");

    // Index initial doc in active generation
    mmf.keepAlive();
    assertTrue(grpcClient.submitFile(file.toString()), "File should be accepted for indexing");
    assertTrue(
        grpcClient.awaitIndexing(1, 30_000, 200),
        "Indexing should complete within 30 seconds");

    // Verify search works before migration
    mmf.keepAlive();
    assertTrue(
        grpcClient.searchText(marker, 10).getResultsCount() > 0,
        "Search should find the marker before migration");

    StatusResponse before = grpcClient.getDetailedStatus();
    String activeBefore = before.getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id should be set before migration");

    // Request migration start (worker will restart)
    mmf.keepAlive();
    var startResp = grpcClient.startMigration("system_test", true, 5_000);
    assertTrue(startResp.getAccepted(), "startMigration should be accepted");

    // Wait for planned restart
    boolean terminated = worker.waitForTermination(pid1, Duration.ofSeconds(20));
    assertTrue(terminated, "Worker should terminate after startMigration restart");

    // Restart worker (migration should resume and eventually cut over)
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid2 = worker.spawnWorker();
    int grpcPort2 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(grpcPort2);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after migration restart");

    // Wait for cutover-triggered restart
    boolean terminated2 = worker.waitForTermination(pid2, Duration.ofSeconds(60));
    assertTrue(terminated2, "Worker should terminate after cutover");

    // Start worker again on new active generation
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid3 = worker.spawnWorker();
    int grpcPort3 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(grpcPort3);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after cutover restart");

    // Validate cutover: active generation should change; migration should be IDLE
    StatusResponse after = grpcClient.getDetailedStatus();
    assertEquals("IDLE", after.getMigration().getMigrationState(), "migration_state should be IDLE after cutover");
    assertFalse(after.getMigration().getActiveGenerationId().isBlank(), "active_generation_id should be present");
    assertNotEquals(activeBefore, after.getMigration().getActiveGenerationId(), "active generation should change after cutover");
    assertEquals(activeBefore, after.getMigration().getPreviousGenerationId(), "previous should be old active generation");

    // Verify search still works (poll for refresh)
    mmf.keepAlive();
    long deadline = System.currentTimeMillis() + 10_000;
    boolean found = false;
    while (System.currentTimeMillis() < deadline) {
      if (grpcClient.searchText(marker, 10).getResultsCount() > 0) {
        found = true;
        break;
      }
      Thread.sleep(250);
      mmf.keepAlive();
    }
    assertTrue(found, "Search should find the marker after cutover");

    // Clean shutdown
    assertTrue(worker.stopGracefully(Duration.ofSeconds(10)), "Worker should stop gracefully");
    assertTrue(worker.waitForTermination(pid3, Duration.ofSeconds(10)), "Worker should terminate");
  }
}
