package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;


import io.justsearch.ipc.BatchResponse;

import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
 * Integration test verifying Rich Document (PDF) ingestion.
 * Fills the gap in testing real-world document formats.
 *
 * <p><b>Disabled in CI:</b> This test spawns actual worker processes which
 * require environment-specific setup that isn't available on CI runners.
 */
@Timeout(120)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class RichDocumentIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(RichDocumentIntegrationTest.class);

    @TempDir
    Path tempDir;

    private Path dataDir;
    private KnowledgeServerConfig config;
    private MainSignalBus signalBus;
    private boolean workerJarExists;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

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
    }

    @Test
    void ingestAndSearchPdf() throws IOException, InterruptedException {
        assertTrue(workerJarExists, "❌ Worker JAR required for this test");

        // 1. Create a valid minimal PDF
        Path pdfFile = tempDir.resolve("test-document.pdf");
        createMinimalPdf(pdfFile);

        // 2. Spawn Worker
        Path signalPath = dataDir.resolve("worker_signal.lock");
        signalBus = new MainSignalBus(signalPath);
        WorkerSpawner spawner = new WorkerSpawner(config, signalBus);
        RemoteKnowledgeClient client = null;

        try {
            log.info("Starting worker...");
            int port = spawner.start();
            client = new RemoteKnowledgeClient(signalBus, config.deadlineMs(), config.maxRetries());
            client.connect(port);
            assertTrue(client.isHealthy(), "Worker should be healthy");

            // 3. Submit PDF
            log.info("Submitting PDF...");
            BatchResponse batchResponse = client.submitBatch(List.of(pdfFile));
            assertEquals(1, batchResponse.getAcceptedCount(), "Should accept PDF file");

            // 4. Wait for indexing completion (queue drain + doc-count convergence)
            waitForIndexing(client, 1);

            // 5. Search with bounded polling instead of fixed sleep.
            log.info("Searching for content...");
            SearchResponse response = waitForSearchHits(client, "JustSearch", 10, 20);
            log.info("Search results: hits={}", response.getResultsCount());

            assertTrue(response.getResultsCount() > 0, "Should find PDF document");
            assertTrue(response.getResults(0).getScore() > 0, "Score should be positive");

        } finally {
            if (client != null) client.close();
            spawner.close();
            // Give OS time to release file locks on Windows before @TempDir cleanup
            Thread.sleep(2000);
        }
    }

    private void createMinimalPdf(Path path) throws IOException {
        // Valid minimal PDF 1.4 with content "JustSearch PDF Test"
        // Base64 encoded to avoid encoding issues across OS
        String b64 =
            "JVBERi0xLjQKMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovUGFnZXMgMiAwIFIKPj4KZW5kb2Jq" +
            "CjIgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFszIDAgUl0KL0NvdW50IDEKPj4KZW5kb2Jq" +
            "CjMgMCBvYmoKPDwKL1R5cGUgL1BhZ2UKL1BhcmVudCAyIDAgUgovTWVkaWFCb3ggWzAgMCA2MTIg" +
            "NzkyXQovUmVzb3VyY2VzIDw8Ci9Gb250IDw8Ci9GMSA0IDAgUgo+Pgo+PgovQ29udGVudHMgNSAw" +
            "IFIKPj4KZW5kb2JqCjQgMCBvYmoKPDwKL1R5cGUgL0ZvbnQKL1N1YnR5cGUgL1R5cGUxCi9CYXNl" +
            "Rm9udCAvSGVsdmV0aWNhCj4+CmVuZG9Jago1IDAgb2JqCjw8Ci9MZW5ndGggNDQKPj4Kc3RyZWFt" +
            "CkJUCi9GMSAyNCBUZgoxMDAgNzAwIFRkCihKdXN0U2VhcmNoIFBERiBUZXN0KSBUagogRVQKZW5k" +
            "c3RyZWFtCmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTAgMDAw" +
            "MDAgbiAKMDAwMDAwMDA2MCAwMDAwMCBuIAowMDAwMDAwMTE3IDAwMDAwIG4gCjAwMDAwMDAyMjMg" +
            "MDAwMDAgbiAKMDAwMDAwMDMxMSAwMDAwMCBuIAp0cmFpbGVyCjw8Ci9TaXplIDYKL1Jvb3QgMSAw" +
            "IFIKPj4Kc3RhcnR4cmVmCjQwNQolJUVPRgo=";

        byte[] bytes = Base64.getDecoder().decode(b64);
        Files.write(path, bytes);
    }

    private void waitForIndexing(RemoteKnowledgeClient client, long minDocCount)
            throws IOException, InterruptedException {
        StatusResponse status = client.getStatus();
        int maxWaitSeconds = 30;
        int stablePolls = 0;
        for (int i = 0; i < maxWaitSeconds && stablePolls < 2; i++) {
            Thread.sleep(1000);
            status = client.getStatus();
            log.info("Indexing progress: queueDepth={}, docCount={}", status.getCore().getQueueDepth(), status.getCore().getDocCount());
            if (status.getCore().getQueueDepth() == 0 && status.getCore().getDocCount() >= minDocCount) {
                stablePolls++;
            } else {
                stablePolls = 0;
            }
        }
        assertTrue(status.getCore().getDocCount() >= minDocCount,
                "Indexing did not converge in time (queueDepth=" + status.getCore().getQueueDepth()
                        + ", docCount=" + status.getCore().getDocCount() + ")");
    }

    private SearchResponse waitForSearchHits(
            RemoteKnowledgeClient client,
            String query,
            int limit,
            int maxAttempts) throws IOException, InterruptedException {
        SearchResponse latest = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            latest = client.search(query, limit, PipelineConfigs.TEXT);
            if (latest.getResultsCount() > 0) {
                return latest;
            }
            log.info("Search retry {}/{} yielded no results yet", attempt, maxAttempts);
            Thread.sleep(500);
        }
        return latest;
    }

    private KnowledgeServerConfig createTestConfig() {
        // Use defaults but override data/signal paths
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
