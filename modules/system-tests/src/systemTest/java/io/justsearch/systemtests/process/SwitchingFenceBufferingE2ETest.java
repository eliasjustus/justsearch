package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("SWITCHING fence buffering E2E test")
class SwitchingFenceBufferingE2ETest {
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
  @DisplayName("Mutations during SWITCHING are durably buffered and replayed after cutover")
  void switchingFenceBuffersAndReplays() throws Exception {
    Path dataDir = env.getTempDir();

    Path watchedRoot = dataDir.resolve("switching-root");
    Files.createDirectories(watchedRoot);

    String blueMarker = "BM" + UUID.randomUUID().toString().replace("-", "");
    Path blueFile = watchedRoot.resolve("blue.txt");
    Files.writeString(blueFile, "hello " + blueMarker);

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

    // Index Blue doc
    mmf.keepAlive();
    assertTrue(grpcClient.submitFile(blueFile.toString()), "Blue file should be accepted");
    assertTrue(grpcClient.awaitIndexing(1, 30_000, 200), "Blue file should be indexed");
    assertTrue(grpcClient.searchText(blueMarker, 10).getResultsCount() > 0, "Blue doc should be searchable");

    String blueDocId = PathNormalizer.normalizePath(blueFile.toAbsolutePath().toString());
    assertFalse(blueDocId.isBlank(), "blue doc_id should be normalized");

    StatusResponse before = grpcClient.getDetailedStatus();
    String activeBefore = before.getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "active_generation_id should be present");

    // Start migration (restart)
    mmf.keepAlive();
    var startResp = grpcClient.startMigration("system_test_switching", true, 5_000);
    assertTrue(startResp.getAccepted(), "startMigration should be accepted");

    assertTrue(worker.waitForTermination(pid1, Duration.ofSeconds(20)), "Worker should restart after startMigration");

    // Respawn worker in migration mode
    mmf.zeroPort();
    mmf.clearShutdown();
    mmf.keepAlive();
    long pid2 = worker.spawnWorker();
    int port2 = mmf.awaitPort(30_000, 100);
    grpcClient.close();
    grpcClient = new GrpcTestClient(port2);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy after migration restart");

    // Force SWITCHING early to exercise buffering.
    mmf.keepAlive();
    var cutoverReqResp = grpcClient.requestCutover(true /* forceSwitching */, 5_000);
    assertTrue(cutoverReqResp.getAccepted(), "requestCutover should be accepted");

    // Wait for SWITCHING state to be visible
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      mmf.keepAlive();
      StatusResponse s = grpcClient.getDetailedStatus(5_000);
      if ("SWITCHING".equalsIgnoreCase(s.getMigration().getMigrationState())) {
        break;
      }
      Thread.sleep(250);
    }
    assertEquals(
        "SWITCHING",
        grpcClient.getDetailedStatus(5_000).getMigration().getMigrationState().toUpperCase(Locale.ROOT),
        "migration_state should be SWITCHING");

    // Buffered UPSERT: submit a new file while switching.
    String greenMarker = "GM" + UUID.randomUUID().toString().replace("-", "");
    Path greenFile = dataDir.resolve("switching-newfile.txt");
    Files.writeString(greenFile, "hello " + greenMarker);

    mmf.keepAlive();
    int accepted = grpcClient.submitBatch(List.of(greenFile.toString()));
    assertEquals(1, accepted, "submitBatch should be accepted during SWITCHING (buffered)");

    // Buffered DELETE: delete the Blue doc_id during SWITCHING (should delete from Green after cutover).
    mmf.keepAlive();
    var delResp = grpcClient.deleteById(blueDocId, 10_000);
    assertTrue(delResp.getSuccess(), "deleteById should be accepted during SWITCHING (buffered)");

    // Buffered SYNC_ROOT: request syncDirectory during SWITCHING (durably buffered).
    // Use a separate root so we don't re-enqueue/delete-test files.
    Path syncRoot = dataDir.resolve("switching-sync-root");
    Files.createDirectories(syncRoot);
    String syncMarker = "SM" + UUID.randomUUID().toString().replace("-", "");
    Path syncFile = syncRoot.resolve("sync.txt");
    Files.writeString(syncFile, "hello " + syncMarker);

    mmf.keepAlive();
    var syncResp = grpcClient.syncDirectory(syncRoot.toString(), true /* force */, 30_000);
    assertTrue(
        syncResp.getError().toUpperCase(Locale.ROOT).contains("DEFERRED") || syncResp.getDeferredToSwitchBuffer(),
        "syncDirectory should be deferred to switch buffer during SWITCHING");

    // Sanity: switch buffer depth should be > 0 while switching.
    mmf.keepAlive();
    StatusResponse switchingStatus = grpcClient.getDetailedStatus(5_000);
    assertTrue(switchingStatus.getMigration().getSwitchBufferDepth() > 0, "switch_buffer_depth should increase during SWITCHING");

    // Wait for cutover-triggered restart.
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

    // Validate generation pointer swap occurred.
    StatusResponse after = grpcClient.getDetailedStatus(5_000);
    assertEquals("IDLE", after.getMigration().getMigrationState(), "migration_state should be IDLE after cutover");
    assertNotEquals(activeBefore, after.getMigration().getActiveGenerationId(), "active generation should change after cutover");

    // Wait for buffered UPSERT to index (marker becomes searchable).
    long searchDeadline = System.currentTimeMillis() + 30_000;
    boolean greenFound = false;
    while (System.currentTimeMillis() < searchDeadline) {
      mmf.keepAlive();
      if (grpcClient.searchText(greenMarker, 10).getResultsCount() > 0) {
        greenFound = true;
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(greenFound, "Buffered UPSERT should be replayed and indexed after cutover");

    // Wait for buffered SYNC_ROOT to index (marker becomes searchable).
    long syncDeadline = System.currentTimeMillis() + 30_000;
    boolean syncFound = false;
    while (System.currentTimeMillis() < syncDeadline) {
      mmf.keepAlive();
      if (grpcClient.searchText(syncMarker, 10).getResultsCount() > 0) {
        syncFound = true;
        break;
      }
      Thread.sleep(250);
    }
    assertTrue(syncFound, "Buffered SYNC_ROOT should be replayed and indexed after cutover");

    // Wait for buffered DELETE to take effect (blueMarker should not be searchable on new active).
    long deleteDeadline = System.currentTimeMillis() + 30_000;
    boolean blueGone = false;
    while (System.currentTimeMillis() < deleteDeadline) {
      mmf.keepAlive();
      if (grpcClient.searchText(blueMarker, 10).getResultsCount() == 0) {
        blueGone = true;
        break;
      }
      Thread.sleep(250);
    }
    if (!blueGone) {
      mmf.keepAlive();
      SearchResponse r = grpcClient.searchText(blueMarker, 10);
      StatusResponse st = grpcClient.getDetailedStatus(5_000);
      String ids =
          r.getResultsList().stream().limit(5).map(x -> x.getId()).reduce("", (a, b) -> a + "\n- " + b);
      fail(
          "Buffered DELETE did not take effect after cutover.\n"
              + "expectedDeletedDocId="
              + blueDocId
              + "\n"
              + "switchBufferDepth="
              + st.getMigration().getSwitchBufferDepth()
              + "\n"
              + "activeGenerationId="
              + st.getMigration().getActiveGenerationId()
              + "\n"
              + "migrationState="
              + st.getMigration().getMigrationState()
              + "\n"
              + "sampleHitIds="
              + ids);
    }

    // Switch buffer should drain to 0 eventually (best-effort).
    long bufDeadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < bufDeadline) {
      mmf.keepAlive();
      if (grpcClient.getDetailedStatus(5_000).getMigration().getSwitchBufferDepth() == 0) {
        break;
      }
      Thread.sleep(200);
    }
    assertEquals(0L, grpcClient.getDetailedStatus(5_000).getMigration().getSwitchBufferDepth(), "switch_buffer_depth should drain");

    assertTrue(worker.stopGracefully(Duration.ofSeconds(10)), "Worker should stop gracefully");
    assertTrue(worker.waitForTermination(pid3, Duration.ofSeconds(10)), "Worker should terminate");
  }
}
