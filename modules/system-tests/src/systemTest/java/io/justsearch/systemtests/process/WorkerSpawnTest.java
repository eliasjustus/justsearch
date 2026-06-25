package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests for worker process spawning, health checks, and graceful shutdown.
 *
 * <p>Uses {@link TestEnvironmentProvisioner} to handle:
 * <ul>
 *   <li>Worker JAR verification (fail-fast)</li>
 *   <li>Temp directory creation for test data</li>
 *   <li>System property configuration to point to real SSOT/config</li>
 *   <li>Cleanup after tests</li>
 * </ul>
 */
@DisplayName("Worker Process Spawn Tests")
class WorkerSpawnTest {

    @RegisterExtension
    static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

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
    @DisplayName("Worker process starts and becomes healthy")
    void workerStartsAndBecomesHealthy() throws Exception {
        // Create worker with JAR path and data directory
        // Use the provisioner's JVM args (repo root, SSOT path, config, data dir)
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());

        long pid = worker.spawnWorker();

        assertTrue(pid > 0, "Worker should have valid PID");
        assertTrue(WorkerProcessManager.isProcessAlive(pid), "Worker process should be alive");

        // Open MMF to read port and send heartbeats
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();  // Send initial heartbeat

        // Wait for worker to write its gRPC port to MMF
        int grpcPort = mmf.awaitPort(30_000, 100);
        assertTrue(grpcPort > 0, "Worker should expose gRPC port");

        // Verify health via gRPC
        grpcClient = new GrpcTestClient(grpcPort);
        assertTrue(grpcClient.isHealthy(), "Worker should be healthy via gRPC");
    }

    @Test
    @DisplayName("Worker process exposes gRPC port via MMF")
    void workerExposesGrpcPort() throws Exception {
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
        worker.spawnWorker();

        // Open MMF for port discovery
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();

        // Await port with timeout
        int grpcPort = mmf.awaitPort(30_000, 100);

        assertTrue(grpcPort > 0, "gRPC port should be positive");
        assertTrue(grpcPort < 65536, "gRPC port should be valid");

        // Verify we can connect
        grpcClient = new GrpcTestClient(grpcPort);
        assertNotNull(grpcClient.healthCheck(), "Should get health response");
    }

    @Test
    @DisplayName("Worker process responds to graceful shutdown")
    void workerRespondsToShutdown() throws Exception {
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
        long pid = worker.spawnWorker();

        // Wait for worker to be ready
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();
        mmf.awaitPort(30_000, 100);

        // Request graceful shutdown
        boolean stoppedGracefully = worker.stopGracefully(Duration.ofSeconds(10));
        assertTrue(stoppedGracefully, "Worker should stop gracefully");

        // Verify process terminated
        boolean terminated = worker.waitForTermination(pid, Duration.ofSeconds(5));
        assertTrue(terminated, "Worker should terminate within 5 seconds");
        assertFalse(WorkerProcessManager.isProcessAlive(pid), "Worker should not be alive after stop");
    }
}
