package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that config values set by the Head reach the Worker subprocess (tempdoc 329 item 3).
 *
 * <p>Sets distinctive values for forwarded properties, spawns a Worker, and confirms the Worker
 * sees them via the {@code effective_config} map in {@link HealthCheckResponse}. Catches missing
 * forwarding entries at CI time instead of during live debugging.
 */
@DisplayName("Head→Worker Config Propagation Tests")
class ConfigPropagationTest {

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
  @DisplayName("Worker reports effective config via health check")
  void workerReportsEffectiveConfig() throws Exception {
    worker =
        WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
    worker.spawnWorker();

    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();

    int grpcPort = mmf.awaitPort(30_000, 100);
    grpcClient = new GrpcTestClient(grpcPort);

    HealthCheckResponse response = grpcClient.healthCheck();
    Map<String, String> config = response.getEffectiveConfigMap();

    assertFalse(config.isEmpty(), "Worker should report effective config values");
    // data.dir is always set by the test provisioner
    assertTrue(
        config.containsKey("justsearch.data.dir"),
        "Worker effective_config should include justsearch.data.dir");
  }

  @Test
  @DisplayName("Forwarded system property reaches Worker")
  void forwardedSyspropReachesWorker() throws Exception {
    // Inject a distinctive value for a property in the divergence check set.
    // The provisioner already sets data.dir to the temp dir — verify it arrives.
    String expectedDataDir = env.getTempDir().toAbsolutePath().toString();

    worker =
        WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
    worker.spawnWorker();

    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();

    int grpcPort = mmf.awaitPort(30_000, 100);
    grpcClient = new GrpcTestClient(grpcPort);

    HealthCheckResponse response = grpcClient.healthCheck();
    Map<String, String> config = response.getEffectiveConfigMap();

    String actualDataDir = config.get("justsearch.data.dir");
    assertNotNull(actualDataDir, "Worker should report justsearch.data.dir");
    assertEquals(
        expectedDataDir,
        actualDataDir,
        "Worker's data.dir should match the value forwarded from Head");
  }
}
