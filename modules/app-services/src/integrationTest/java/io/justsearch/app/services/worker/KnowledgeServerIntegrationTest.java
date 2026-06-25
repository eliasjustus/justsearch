package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.BatchResponse;

import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-End integration test for the Knowledge Server.
 *
 * <p>This test verifies that Phases 1-3 work together:
 * <ol>
 *   <li>Phase 1: Protocol (gRPC definitions)</li>
 *   <li>Phase 2: Knowledge Server (worker process)</li>
 *   <li>Phase 3: Client (main process integration)</li>
 * </ol>
 *
 * <p><b>Note:</b> Tests that spawn worker processes require the indexer-worker distribution.
 * Run: {@code ./gradlew :modules:indexer-worker:installDist}
 *
 * <p>Signal bus tests run in CI; process-spawning tests are local-only.
 */
@Timeout(60)
class KnowledgeServerIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeServerIntegrationTest.class);

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path testFile;
    private KnowledgeServerConfig config;
    private MainSignalBus signalBus;
    private boolean workerJarExists;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

        // Create a test file to index
        testFile = tempDir.resolve("test-document.txt");
        Files.writeString(testFile, "This is a test document for Knowledge Server integration testing.");

        // Check if worker lib directory exists
        try {
            config = createTestConfig();
            workerJarExists = Files.isDirectory(config.workerLibDir());
            if (!workerJarExists) {
                log.warn("Worker lib directory not found at {}. Run: ./gradlew :modules:indexer-worker:installDist",
                        config.workerLibDir());
            }
        } catch (Exception e) {
            log.warn("Failed to load config: {}", e.getMessage());
            workerJarExists = false;
        }
    }

    @AfterEach
    void tearDown() {
        if (signalBus != null) {
            signalBus.close();
        }
        // Give the OS time to release file locks (Windows workaround)
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Test
    void signalBus_canOpenAndWrite() throws IOException {
        Path signalPath = dataDir.resolve("worker_signal.lock");
        signalBus = new MainSignalBus(signalPath);
        signalBus.open();

        // Verify file was created
        assertTrue(Files.exists(signalPath), "Signal file should exist");

        // Test write operations
        assertDoesNotThrow(() -> signalBus.writeHeartbeat());
        assertDoesNotThrow(() -> signalBus.writeActivity());
        assertDoesNotThrow(() -> signalBus.zeroPort());

        // Port should be 0 initially
        assertEquals(0, signalBus.readPort());
    }

    @Test
    void signalBus_portDiscoveryTimeout() throws IOException {
        Path signalPath = dataDir.resolve("worker_signal.lock");
        signalBus = new MainSignalBus(signalPath);
        signalBus.open();
        signalBus.zeroPort();

        // Should timeout since no one writes a port
        assertThrows(IllegalStateException.class,
                () -> signalBus.awaitPort(500, 50),
                "Should timeout when port remains 0");
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void config_loadsSuccessfully() {
        assertTrue(workerJarExists, "❌ Worker JAR required for this test");

        assertNotNull(config);
        assertNotNull(config.dataDir());
        assertNotNull(config.workerLibDir());
        assertTrue(config.deadlineMs() > 0);
        log.info("Config loaded: production={}, workerLibDir={}",
                config.isProduction(), config.workerLibDir());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void workerSpawner_canBeCreated() throws IOException {
        assertTrue(workerJarExists, "❌ Worker JAR required for this test");

        Path signalPath = dataDir.resolve("worker_signal.lock");
        signalBus = new MainSignalBus(signalPath);

        // Should be able to create spawner without throwing
        WorkerSpawner spawner = new WorkerSpawner(config, signalBus);
        assertNotNull(spawner);
        assertFalse(spawner.isRunning());
    }

    /**
     * Full E2E test - spawns worker, connects, submits batch, and searches.
     *
     * <p>This test is marked as a longer timeout since it spawns a real JVM.
     * <p><b>Disabled in CI:</b> Requires worker process spawning.
     */
    @Test
    @Timeout(120)
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void fullE2E_spawnConnectIngestAndSearch() throws IOException, InterruptedException {
        assertTrue(workerJarExists, "❌ Worker JAR required for this test");

        Path signalPath = dataDir.resolve("worker_signal.lock");
        KnowledgeServerConfig testConfig = new KnowledgeServerConfig(
                false, // dev mode
                dataDir,
                config.libDir(),
                config.workingDirectory(),
                config.workerLibDir(),
                signalPath,
                5000L, // deadline
                30000L, // port timeout
                3,
                config.workerHeapSize(),
                5000L, // workerShutdownTimeoutMs
                5000L, // pidValidationTimeoutMs
                1000L, // stabilityWindowMs
                5000, // batchSize
                30_000L); // healthCheckRetryBudgetMs (alpha.23 R13-A)

        signalBus = new MainSignalBus(signalPath);
        WorkerSpawner spawner = new WorkerSpawner(testConfig, signalBus);
        RemoteKnowledgeClient client = null;

        try {
            // 1. Start worker
            log.info("Starting worker process...");
            int port = spawner.start();
            assertTrue(port > 0, "Worker should bind to a valid port");
            log.info("Worker started on port {}", port);

            // 2. Connect client
            client = new RemoteKnowledgeClient(signalBus, testConfig.deadlineMs(), testConfig.maxRetries());
            client.connect(port);

            // 3. Health check
            assertTrue(client.isHealthy(), "Worker should be healthy");
            log.info("Health check passed");

            // 4. Submit batch
            BatchResponse batchResponse = client.submitBatch(List.of(testFile));
            assertEquals(1, batchResponse.getAcceptedCount(), "Should accept 1 file");
            log.info("Batch submitted: accepted={}", batchResponse.getAcceptedCount());

            // 5. Check status (queue should have 1 item)
            Thread.sleep(500); // Give it time to enqueue
            StatusResponse status = client.getStatus();
            log.info("Status: queueDepth={}, docCount={}, healthy={}",
                    status.getCore().getQueueDepth(), status.getCore().getDocCount(), status.getCore().getIsHealthy());
            assertTrue(status.getCore().getIsHealthy(), "Worker should be healthy");

            // 6. Wait for indexing completion based on document count convergence.
            // queueDepth can transiently hit 0 before docCount is fully reflected.
            int maxWaitSeconds = 30;
            for (int i = 0; i < maxWaitSeconds && status.getCore().getDocCount() < 1; i++) {
                Thread.sleep(1000);
                status = client.getStatus();
                log.info("Waiting for indexing... queueDepth={}, docCount={}",
                        status.getCore().getQueueDepth(), status.getCore().getDocCount());
            }

            // 7. Verify document was indexed
            assertTrue(status.getCore().getDocCount() >= 1,
                    "Document should be indexed (queueDepth=" + status.getCore().getQueueDepth()
                            + ", docCount=" + status.getCore().getDocCount() + ")");
            log.info("Document indexed, docCount={}", status.getCore().getDocCount());

            // 8. Text search - should find the test document
            SearchResponse searchResponse = client.search("test document integration", 10, PipelineConfigs.TEXT);
            log.info("Text search results: hits={}, took={}ms",
                    searchResponse.getResultsCount(), searchResponse.getTookMs());
            assertTrue(searchResponse.getResultsCount() > 0,
                    "Text search should find at least one result");

            // Verify the found document contains expected content
            if (searchResponse.getResultsCount() > 0) {
                var firstResult = searchResponse.getResults(0);
                log.info("First result: id={}, score={}", firstResult.getId(), firstResult.getScore());
                assertTrue(firstResult.getScore() > 0, "Score should be positive");
            }

            // 9. Hybrid search (will fall back to text if no embedding model)
            SearchResponse hybridResponse = client.search("Knowledge Server testing", 10, PipelineConfigs.HYBRID);
            log.info("Hybrid search results: hits={}, took={}ms",
                    hybridResponse.getResultsCount(), hybridResponse.getTookMs());
            // Hybrid may or may not find results depending on embedding availability
            // Just verify it doesn't crash
            assertNotNull(hybridResponse, "Hybrid search should return a response");

            log.info("E2E test completed successfully!");

        } finally {
            if (client != null) {
                client.close();
            }
            spawner.close();
        }
    }

    /**
     * Test indexing multiple files with varied content.
     *
     * <p><b>Disabled in CI:</b> Requires worker process spawning.
     */
    @Test
    @Timeout(120)
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void fullE2E_indexMultipleFilesAndSearchVaried() throws IOException, InterruptedException {
        assertTrue(workerJarExists, "❌ Worker JAR required for this test");

        // Create multiple test files with different content
        Path file1 = tempDir.resolve("java-programming.txt");
        Files.writeString(file1, "Java is a popular programming language for enterprise applications.");

        Path file2 = tempDir.resolve("python-tutorial.txt");
        Files.writeString(file2, "Python is widely used for data science and machine learning projects.");

        Path file3 = tempDir.resolve("rust-systems.txt");
        Files.writeString(file3, "Rust provides memory safety without garbage collection for systems programming.");

        Path signalPath = dataDir.resolve("worker_signal.lock");
        KnowledgeServerConfig testConfig = new KnowledgeServerConfig(
                false, // dev mode
                dataDir,
                config.libDir(),
                config.workingDirectory(),
                config.workerLibDir(),
                signalPath,
                5000L, // deadline
                30000L, // port timeout
                3,
                config.workerHeapSize(),
                5000L, // workerShutdownTimeoutMs
                5000L, // pidValidationTimeoutMs
                1000L, // stabilityWindowMs
                5000, // batchSize
                30_000L); // healthCheckRetryBudgetMs (alpha.23 R13-A)

        signalBus = new MainSignalBus(signalPath);
        WorkerSpawner spawner = new WorkerSpawner(testConfig, signalBus);
        RemoteKnowledgeClient client = null;

        try {
            // Start worker and connect
            int port = spawner.start();
            client = new RemoteKnowledgeClient(signalBus, testConfig.deadlineMs(), testConfig.maxRetries());
            client.connect(port);

            // Submit all files
            BatchResponse batchResponse = client.submitBatch(List.of(file1, file2, file3));
            assertEquals(3, batchResponse.getAcceptedCount(), "Should accept all 3 files");
            log.info("Submitted {} files for indexing", batchResponse.getAcceptedCount());

            // Wait for indexing completion based on document count convergence.
            // queueDepth can transiently hit 0 before docCount is fully reflected.
            StatusResponse status = client.getStatus();
            int maxWaitSeconds = 60;
            for (int i = 0; i < maxWaitSeconds && status.getCore().getDocCount() < 3; i++) {
                Thread.sleep(1000);
                status = client.getStatus();
                log.info("Indexing progress: queueDepth={}, docCount={}",
                        status.getCore().getQueueDepth(), status.getCore().getDocCount());
            }

            // Verify all documents indexed
            assertTrue(status.getCore().getDocCount() >= 3,
                    "All 3 documents should be indexed (queueDepth=" + status.getCore().getQueueDepth()
                            + ", docCount=" + status.getCore().getDocCount() + ")");

            // Search for Java content
            SearchResponse javaResults = client.search("Java programming enterprise", 10, PipelineConfigs.TEXT);
            log.info("Java search: {} results", javaResults.getResultsCount());
            assertTrue(javaResults.getResultsCount() > 0, "Should find Java document");

            // Search for Python content
            SearchResponse pythonResults = client.search("Python machine learning", 10, PipelineConfigs.TEXT);
            log.info("Python search: {} results", pythonResults.getResultsCount());
            assertTrue(pythonResults.getResultsCount() > 0, "Should find Python document");

            // Search for Rust content
            SearchResponse rustResults = client.search("Rust memory safety", 10, PipelineConfigs.TEXT);
            log.info("Rust search: {} results", rustResults.getResultsCount());
            assertTrue(rustResults.getResultsCount() > 0, "Should find Rust document");

            // Search for term that doesn't exist
            SearchResponse noResults = client.search("quantum computing blockchain", 10, PipelineConfigs.TEXT);
            assertEquals(0, noResults.getResultsCount(), "Should not find non-existent content");

            log.info("Multi-file E2E test completed successfully!");

        } finally {
            if (client != null) {
                client.close();
            }
            spawner.close();
        }
    }

    private KnowledgeServerConfig createTestConfig() {
        KnowledgeServerConfig defaults = KnowledgeServerConfig.load();
        return new KnowledgeServerConfig(
                false, // dev mode
                dataDir,
                defaults.libDir(),
                defaults.workingDirectory(),
                defaults.workerLibDir(),
                dataDir.resolve("worker_signal.lock"),
                5000L,
                30000L,
                3,
                defaults.workerHeapSize(),
                5000L, // workerShutdownTimeoutMs
                5000L, // pidValidationTimeoutMs
                1000L, // stabilityWindowMs
                5000, // batchSize
                30_000L); // healthCheckRetryBudgetMs (alpha.23 R13-A)
    }
}
