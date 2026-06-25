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

@DisplayName("Rollback E2E test")
class RollbackE2ETest {
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
  @DisplayName("Rollback returns active_generation to previous_generation and preserves search")
  void rollbackRevertsGenerationPointer() throws Exception {
    Path dataDir = env.getTempDir();

    Path watchedRoot = dataDir.resolve("rollback-root");
    Files.createDirectories(watchedRoot);

    String marker = "rollback-marker-" + System.currentTimeMillis();
    Path file = watchedRoot.resolve("doc.txt");
    Files.writeString(file, "hello " + marker);

    Path rootsFile = dataDir.resolve("watched_roots.json");
    JSON.writeValue(rootsFile.toFile(), Map.of("roots", List.of(Map.of("path", watchedRoot.toString()))));

    worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataDir).withJvmArgs(env.getWorkerJvmArgs());
    long pid1 = worker.spawnWorker();

    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();

    int port1 = mmf.awaitPort(30_000, 100);
    grpcClient = new GrpcTestClient(port1);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy");

    // Index initial doc
    mmf.keepAlive();
    assertTrue(grpcClient.submitFile(file.toString()), "File should be accepted for indexing");
    assertTrue(grpcClient.awaitIndexing(1, 30_000, 200), "Indexing should complete");
    assertTrue(grpcClient.searchText(marker, 10).getResultsCount() > 0, "Doc should be searchable");

    StatusResponse before = grpcClient.getDetailedStatus(5_000);
    String activeBefore = before.getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id should be present");

    // Start migration (restart)
    mmf.keepAlive();
    assertTrue(grpcClient.startMigration("system_test_rollback", true, 5_000).getAccepted());
    assertTrue(worker.waitForTermination(pid1, Duration.ofSeconds(20)), "Worker should restart after startMigration");

    // Respawn into migration mode
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid2 = worker.spawnWorker();
    int port2 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(port2);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after migration restart");

    // Let migration run to cutover (force SWITCHING early to speed test).
    mmf.keepAlive();
    assertTrue(grpcClient.requestCutover(true, 5_000).getAccepted());

    assertTrue(worker.waitForTermination(pid2, Duration.ofSeconds(60)), "Worker should restart after cutover");

    // Respawn after cutover
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid3 = worker.spawnWorker();
    int port3 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(port3);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after cutover restart");

    StatusResponse afterCutover = grpcClient.getDetailedStatus(5_000);
    assertEquals("IDLE", afterCutover.getMigration().getMigrationState(), "migration_state should be IDLE after cutover");
    assertNotEquals(activeBefore, afterCutover.getMigration().getActiveGenerationId(), "active generation should change after cutover");
    assertEquals(activeBefore, afterCutover.getMigration().getPreviousGenerationId(), "previous should be old active generation");

    // Rollback (restart)
    mmf.keepAlive();
    assertTrue(grpcClient.rollbackMigration(true, 5_000).getAccepted(), "rollback should be accepted");
    assertTrue(worker.waitForTermination(pid3, Duration.ofSeconds(20)), "Worker should restart after rollback");

    // Respawn after rollback
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid4 = worker.spawnWorker();
    int port4 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(port4);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after rollback restart");

    StatusResponse afterRollback = grpcClient.getDetailedStatus(5_000);
    assertEquals(activeBefore, afterRollback.getMigration().getActiveGenerationId(), "active generation should revert after rollback");
    assertEquals(afterCutover.getMigration().getActiveGenerationId(), afterRollback.getMigration().getPreviousGenerationId(), "previous should swap");

    // Search still works
    long deadline = System.currentTimeMillis() + 10_000;
    boolean found = false;
    while (System.currentTimeMillis() < deadline) {
      mmf.keepAlive();
      if (grpcClient.searchText(marker, 10).getResultsCount() > 0) {
        found = true;
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(found, "Search should still find the marker after rollback");

    assertTrue(worker.stopGracefully(Duration.ofSeconds(10)), "Worker should stop gracefully");
    assertTrue(worker.waitForTermination(pid4, Duration.ofSeconds(10)), "Worker should terminate");
  }
}
