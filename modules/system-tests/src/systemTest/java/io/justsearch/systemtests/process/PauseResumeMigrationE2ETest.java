package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import io.grpc.StatusRuntimeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("Pause/Resume migration orchestration E2E test")
class PauseResumeMigrationE2ETest {
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
  @DisplayName("pauseMigration stops enumerator/cutover progress; resumeMigration allows progress to continue")
  void pauseResumeMigrationOrchestration() throws Exception {
    Path dataDir = env.getTempDir();

    // Make enumerator non-trivial so we can pause before it finishes.
    Path watchedRoot = dataDir.resolve("pause-root");
    Files.createDirectories(watchedRoot);
    // Keep just above the SWITCHING threshold (1000) so that after resume we can observe a
    // deterministic MIGRATING -> SWITCHING transition without needing a full cutover.
    int fileCount = 1_200;
    for (int i = 0; i < fileCount; i++) {
      Path p = watchedRoot.resolve("f-" + i + ".txt");
      Files.writeString(p, "x");
    }

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

    // Start migration (restart)
    mmf.keepAlive();
    assertTrue(grpcClient.startMigration("pause_resume_test", true, 5_000).getAccepted());
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

    // Pause orchestration quickly.
    mmf.keepAlive();
    var pauseResp = grpcClient.pauseMigration("system_test", 5_000);
    assertTrue(pauseResp.getAccepted(), "pauseMigration should be accepted");

    // Wait until status reflects paused.
    long pausedDeadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < pausedDeadline) {
      mmf.keepAlive();
      if (grpcClient.getDetailedStatus(5_000).getMigration().getPaused()) {
        break;
      }
      Thread.sleep(200);
    }
    StatusResponse paused = grpcClient.getDetailedStatus(5_000);
    assertTrue(paused.getMigration().getPaused(), "migration_paused should be true after pause");
    assertFalse(
        paused.getMigration().getMigrationState().trim().equalsIgnoreCase("SWITCHING"),
        "Pause should prevent migration from entering SWITCHING");

    // After a short grace period for the enumerator to observe pause, progress should stop.
    Thread.sleep(1_500);
    mmf.keepAlive();
    long seen0 = grpcClient.getDetailedStatus(5_000).getMigration().getEnumerator().getFilesSeen();
    Thread.sleep(2_000);
    mmf.keepAlive();
    long seen1 = grpcClient.getDetailedStatus(5_000).getMigration().getEnumerator().getFilesSeen();
    assertEquals(seen0, seen1, "migration enumerator progress should stop while paused");

    // Resume orchestration; progress should continue.
    mmf.keepAlive();
    var resumeResp = grpcClient.resumeMigration(5_000);
    assertTrue(resumeResp.getAccepted(), "resumeMigration should be accepted");

    // Resume should unblock cutover monitor; once the queue drains below the threshold it should
    // enter SWITCHING (or restart due to cutover).
    long resumeDeadline = System.currentTimeMillis() + 60_000;
    boolean progressed = false;
    while (System.currentTimeMillis() < resumeDeadline) {
      mmf.keepAlive();
      try {
        StatusResponse s = grpcClient.getDetailedStatus(5_000);
        String ms = s.getMigration().getMigrationState().trim();
        if (ms.equalsIgnoreCase("SWITCHING")) {
          progressed = true;
          break;
        }
      } catch (StatusRuntimeException e) {
        // If the worker restarted due to cutover, RPCs will fail; treat this as progress.
        if (!WorkerProcessManager.isProcessAlive(pid2)) {
          progressed = true;
          break;
        }
      }
      Thread.sleep(250);
    }
    assertTrue(progressed, "resume should allow migration to progress (SWITCHING or cutover restart)");

    // Clean shutdown (don't wait for full cutover; other tests cover it).
    if (WorkerProcessManager.isProcessAlive(pid2)) {
      assertTrue(worker.stopGracefully(Duration.ofSeconds(10)), "Worker should stop gracefully");
      assertTrue(worker.waitForTermination(pid2, Duration.ofSeconds(10)), "Worker should terminate");
    }
  }
}
