package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("indexBasePath lock E2E test")
class IndexBasePathLockE2ETest {
  @RegisterExtension static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private WorkerProcessManager workerA;
  private WorkerProcessManager workerB;
  private MmfTestHarness mmfA;

  @AfterEach
  void cleanup() throws Exception {
    if (mmfA != null) {
      mmfA.close();
      mmfA = null;
    }
    if (workerB != null) {
      workerB.close();
      workerB = null;
    }
    if (workerA != null) {
      workerA.close();
      workerA = null;
    }
  }

  @Test
  @DisplayName("Second worker fails fast when sharing the same indexBasePath")
  void secondWorkerFailsFastOnSharedIndexBasePath() throws Exception {
    Path root = env.getTempDir();
    Path sharedIndexBasePath = root.resolve("shared-index-base");
    Files.createDirectories(sharedIndexBasePath);

    Path dataA = root.resolve("data-a");
    Path dataB = root.resolve("data-b");
    Files.createDirectories(dataA);
    Files.createDirectories(dataB);

    String[] baseArgs = env.getWorkerJvmArgs();
    List<String> argsA = new ArrayList<>();
    List<String> argsB = new ArrayList<>();
    for (String a : baseArgs) {
      argsA.add(a);
      argsB.add(a);
    }
    argsA.add("-Djustsearch.index.base_path=" + sharedIndexBasePath.toAbsolutePath());
    argsB.add("-Djustsearch.index.base_path=" + sharedIndexBasePath.toAbsolutePath());

    workerA = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataA).withJvmArgs(argsA.toArray(new String[0]));
    long pidA = workerA.spawnWorker();

    mmfA = new MmfTestHarness(workerA.getSignalFilePath());
    mmfA.open();
    mmfA.keepAlive();
    int portA = mmfA.awaitPort(30_000, 100);
    try (GrpcTestClient clientA = new GrpcTestClient(portA)) {
      assertTrue(clientA.isHealthy(), "Worker A should be healthy");

      // Now start worker B pointing at the same index base path.
      workerB = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataB).withJvmArgs(argsB.toArray(new String[0]));
      long pidB = workerB.spawnWorker();

      // Worker B should terminate quickly (fails to acquire index root lock).
      assertTrue(workerB.waitForTermination(pidB, Duration.ofSeconds(10)), "Worker B should exit quickly");
      assertFalse(WorkerProcessManager.isProcessAlive(pidB), "Worker B should not be alive");

      // Worker A should remain alive.
      mmfA.keepAlive();
      assertTrue(WorkerProcessManager.isProcessAlive(pidA), "Worker A should remain alive");
      assertTrue(clientA.isHealthy(), "Worker A should remain healthy");
    }
  }
}
