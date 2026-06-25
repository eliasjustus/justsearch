package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests for gRPC communication with the worker process.
 *
 * <p>Uses {@link TestEnvironmentProvisioner} to handle:
 * <ul>
 *   <li>Worker JAR verification (fail-fast)</li>
 *   <li>Temp directory creation for test data</li>
 *   <li>System property configuration to point to real SSOT/config</li>
 *   <li>Cleanup after tests</li>
 * </ul>
 *
 * <p>This test class starts a single worker in {@code @BeforeAll} and
 * reuses it for all test methods.
 */
@DisplayName("gRPC Communication Tests")
class GrpcCommunicationTest {

    @RegisterExtension
    static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

    private static WorkerProcessManager worker;
    private static MmfTestHarness mmf;
    private static GrpcTestClient grpcClient;
    private static long workerPid;

    @BeforeAll
    static void startWorker() throws Exception {
        // Start worker using provisioner's paths and JVM args
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
        workerPid = worker.spawnWorker();

        // Open MMF for port discovery and heartbeats
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();

        // Wait for gRPC port
        int grpcPort = mmf.awaitPort(30_000, 100);

        // Connect gRPC client
        grpcClient = new GrpcTestClient(grpcPort);

        // Verify healthy
        assertTrue(grpcClient.isHealthy(), "Worker should be healthy before tests");
    }

    @AfterAll
    static void stopWorker() throws Exception {
        if (grpcClient != null) {
            grpcClient.close();
            grpcClient = null;
        }
        if (mmf != null) {
            mmf.close();
            mmf = null;
        }
        if (worker != null) {
            worker.close();
            worker = null;
        }
    }

    @BeforeEach
    void keepAlive() {
        if (mmf != null) {
            mmf.keepAlive();
        }
    }

    @Test
    @DisplayName("Health check returns valid response")
    void healthCheckReturnsValidResponse() {
        HealthCheckResponse response = grpcClient.healthCheck();

        assertTrue(response.getServing(), "Worker should be serving");
        assertTrue(response.getPid() > 0, "Worker PID should be valid");
        assertEquals(workerPid, response.getPid(), "Reported PID should match spawned PID");
        assertNotNull(response.getWorkerState(), "Worker state should not be null");
    }

    @Test
    @DisplayName("Detailed status returns worker state")
    void detailedStatusReturnsWorkerState() {
        StatusResponse response = grpcClient.getDetailedStatus();

        assertTrue(response.getCore().getIsHealthy(), "Worker should report healthy in detailed status");
        assertTrue(response.getCore().getUptimeMs() >= 0, "Uptime should be non-negative");

        // Initial state might be IDLE or INDEXING depending on startup, but usually IDLE if queue empty
        assertNotNull(response.getCore().getState(), "State should not be null");

        // Queue should be empty initially
        assertEquals(0, response.getCore().getQueueDepth(), "Queue depth should be 0 initially");
    }

    @Test
    @DisplayName("Search returns valid response (even if empty)")
    void searchReturnsValidResponse() {
        // Perform text search
        SearchResponse response = grpcClient.searchText("test query", 10);

        assertNotNull(response, "Search response should not be null");
        // We expect 0 results since index is empty
        assertEquals(0, response.getResultsCount(), "Should have 0 results in empty index");
        assertEquals(0, response.getTotalHits(), "Total hits should be 0");
    }

    @Test
    @DisplayName("FetchDocuments returns valid response for unknown doc IDs")
    void fetchDocumentsReturnsValidResponse() {
        // Fetch documents that don't exist (index is empty)
        FetchDocumentsResponse response = grpcClient.fetchDocuments(List.of("nonexistent-doc-1", "nonexistent-doc-2"));

        assertNotNull(response, "FetchDocuments response should not be null");
        assertEquals(2, response.getDocumentsCount(), "Should return 2 document entries");

        // Verify each document entry has expected structure
        for (DocumentContent doc : response.getDocumentsList()) {
            assertNotNull(doc.getDocId(), "Document ID should not be null");
            assertFalse(doc.getFound(), "Document should not be found in empty index");
            assertTrue(doc.getContent().isEmpty(), "Content should be empty for unfound doc");
        }
    }

    @Test
    @DisplayName("RetrieveContext returns valid response (empty context for empty index)")
    void retrieveContextReturnsValidResponse() {
        // Retrieve context for documents that don't exist
        RetrieveContextResponse response = grpcClient.retrieveContext(
            "What is the meaning of life?",
            List.of("nonexistent-doc-1"),
            5
        );

        assertNotNull(response, "RetrieveContext response should not be null");
        // Context will be empty since index is empty
        assertNotNull(response.getContext(), "Context should not be null");
        // usedChunks and chunksFound should be valid
        assertFalse(response.getUsedChunks(), "Should not have used chunks in empty index");
        assertEquals(0, response.getChunksFound(), "Should have 0 chunks found in empty index");
    }
}
