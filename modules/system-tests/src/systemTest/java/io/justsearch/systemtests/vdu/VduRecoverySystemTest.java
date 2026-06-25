package io.justsearch.systemtests.vdu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System tests for VDU recovery mechanism.
 *
 * <p>Verifies that documents stuck in PROCESSING state (due to crash) are
 * correctly reset to PENDING upon recovery.
 *
 * <p><b>Test Strategy:</b>
 * <ol>
 *   <li>Spawn a real Worker process</li>
 *   <li>Index a test image file (sets vdu_status=PENDING for images)</li>
 *   <li>Inject PROCESSING state via updateVduResult RPC</li>
 *   <li>Call recoverVduProcessing RPC</li>
 *   <li>Verify document is back to PENDING</li>
 * </ol>
 */
@DisplayName("VDU Recovery System Tests")
@Tag("systemTest")
class VduRecoverySystemTest {
  private static final Logger log = LoggerFactory.getLogger(VduRecoverySystemTest.class);

  @RegisterExtension
  static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private WorkerProcessManager worker;
  private MmfTestHarness mmf;
  private GrpcTestClient grpcClient;
  private Path testImageDir;

  @BeforeEach
  void setup() throws Exception {
    // Clean the data directory to ensure test isolation
    // Each test should start with a fresh index
    Path dataDir = env.getTempDir();
    cleanDataDirectory(dataDir);

    // Create test image directory
    testImageDir = dataDir.resolve("test-images");
    Files.createDirectories(testImageDir);

    // Spawn worker
    worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), dataDir);
    worker.withJvmArgs(env.getWorkerJvmArgs());
    long pid = worker.spawnWorker();
    log.info("Worker spawned with PID: {}", pid);

    // Open MMF for port discovery
    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();

    // Wait for worker to be ready
    int grpcPort = mmf.awaitPort(30_000, 100);
    log.info("Worker gRPC port: {}", grpcPort);

    grpcClient = new GrpcTestClient(grpcPort);
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy");
  }

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
  @DisplayName("recovers documents stuck in PROCESSING state")
  void recoversStuckProcessingDocuments() throws Exception {
    // 1. Create a test image file
    Path testImage = createTestImage("stuck-doc.png");
    String filePath = testImage.toAbsolutePath().toString();
    // Worker normalizes paths (lowercase on Windows) for doc_id
    String docId = normalizeDocId(testImage);
    log.info("Created test image: {} (docId: {})", filePath, docId);

    // 2. Submit for indexing (use actual file path)
    int accepted = grpcClient.submitBatch(List.of(filePath));
    assertEquals(1, accepted, "Should accept 1 file");

    // 3. Wait for indexing to complete
    boolean indexed = grpcClient.awaitIndexing(1, 30_000, 200);
    assertTrue(indexed, "Document should be indexed within 30s");

    // 4. Verify document is pending VDU (images start as PENDING)
    // Poll to handle searcher refresh latency
    assertTrue(awaitPending(docId, 5_000), "Document should be pending VDU after indexing");

    // 5. Inject PROCESSING state (simulates crash during VDU)
    int retryCount = grpcClient.markVduProcessing(docId, 3);
    assertTrue(retryCount >= 0, "Should be able to mark VDU PROCESSING");

    // 6. Verify document is NO LONGER pending (PROCESSING is not PENDING)
    // Poll to handle searcher refresh latency
    assertTrue(awaitNotPending(docId, 5_000),
        "PROCESSING document should not appear in pending list");

    // 7. Trigger recovery
    int recovered = grpcClient.recoverVduProcessing();
    assertEquals(1, recovered, "Should recover 1 document");

    // 8. Verify document is back to PENDING
    // Poll to handle searcher refresh latency
    assertTrue(awaitPending(docId, 5_000),
        "Document should be PENDING again after recovery");

    log.info("Recovery test passed: document {} recovered from PROCESSING to PENDING", docId);
  }

  @Test
  @DisplayName("recovery returns 0 when no documents stuck")
  void recoveryReturnsZeroWhenNoneStuck() throws Exception {
    // No documents indexed, nothing to recover
    int recovered = grpcClient.recoverVduProcessing();
    assertEquals(0, recovered, "Should recover 0 documents when none are stuck");
  }

  @Test
  @DisplayName("recovers multiple stuck documents")
  void recoversMultipleStuckDocuments() throws Exception {
    // 1. Create multiple test images
    Path img1 = createTestImage("stuck-1.png");
    Path img2 = createTestImage("stuck-2.png");
    Path img3 = createTestImage("stuck-3.png");

    // Worker normalizes paths (lowercase on Windows)
    String docId1 = normalizeDocId(img1);
    String docId2 = normalizeDocId(img2);
    String docId3 = normalizeDocId(img3);

    // 2. Submit all for indexing
    int accepted = grpcClient.submitBatch(List.of(
        img1.toString(), img2.toString(), img3.toString()));
    assertEquals(3, accepted);

    // 3. Wait for indexing
    assertTrue(grpcClient.awaitIndexing(3, 30_000, 200));

    // 4. Verify all are pending initially (poll to handle searcher refresh)
    assertTrue(awaitPending(docId1, 5_000), "Doc 1 should be pending VDU after indexing");
    assertTrue(awaitPending(docId2, 5_000), "Doc 2 should be pending VDU after indexing");
    assertTrue(awaitPending(docId3, 5_000), "Doc 3 should be pending VDU after indexing");

    // 5. Inject PROCESSING state for all
    assertTrue(grpcClient.markVduProcessing(docId1, 3) >= 0);
    assertTrue(grpcClient.markVduProcessing(docId2, 3) >= 0);
    assertTrue(grpcClient.markVduProcessing(docId3, 3) >= 0);

    // 6. Verify none are pending (poll to handle searcher refresh)
    assertTrue(awaitNotPending(docId1, 5_000), "Doc 1 should not be pending after PROCESSING");
    assertTrue(awaitNotPending(docId2, 5_000), "Doc 2 should not be pending after PROCESSING");
    assertTrue(awaitNotPending(docId3, 5_000), "Doc 3 should not be pending after PROCESSING");

    // 7. Recover all
    int recovered = grpcClient.recoverVduProcessing();
    assertEquals(3, recovered, "Should recover all 3 documents");

    // 8. Verify all are back to PENDING (poll to handle searcher refresh)
    assertTrue(awaitPending(docId1, 5_000), "Doc 1 should be PENDING after recovery");
    assertTrue(awaitPending(docId2, 5_000), "Doc 2 should be PENDING after recovery");
    assertTrue(awaitPending(docId3, 5_000), "Doc 3 should be PENDING after recovery");
  }

  // =========================================================================
  // Helper Methods
  // =========================================================================

  /**
   * Creates a minimal valid PNG image file for testing.
   */
  private Path createTestImage(String filename) throws Exception {
    Path imagePath = testImageDir.resolve(filename);

    // Create a minimal valid 100x100 PNG
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
        100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = img.createGraphics();
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, 100, 100);
    g.setColor(java.awt.Color.BLACK);
    g.drawString("TEST", 30, 50);
    g.dispose();

    javax.imageio.ImageIO.write(img, "PNG", imagePath.toFile());
    return imagePath;
  }

  /**
   * Normalizes a path to match Worker's document ID format.
   * Worker lowercases paths on Windows for case-insensitive comparison.
   */
  private String normalizeDocId(Path path) {
    String absolutePath = path.toAbsolutePath().toString();
    // Match Worker's PathNormalizer behavior
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      return absolutePath.toLowerCase(java.util.Locale.ROOT);
    }
    return absolutePath;
  }

  /**
   * Cleans the data directory to ensure test isolation.
   * Removes index files but preserves the directory structure.
   */
  private void cleanDataDirectory(Path dataDir) throws Exception {
    // Clean index directory if it exists
    Path indexDir = dataDir.resolve("index");
    if (Files.exists(indexDir)) {
      try (var stream = Files.walk(indexDir)) {
        stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (java.io.IOException e) {
                log.debug("Could not delete {}: {}", p, e.getMessage());
              }
            });
      }
    }

    // Clean queue database
    Path queueDb = dataDir.resolve("job_queue.db");
    Files.deleteIfExists(queueDb);
  }

  /**
   * Waits until a document is no longer in the pending VDU list.
   * Handles searcher refresh latency.
   */
  private boolean awaitNotPending(String docId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<String> pending = grpcClient.queryPendingVduDocIds(100);
      if (!pending.contains(docId)) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  /**
   * Waits until a document appears in the pending VDU list.
   * Handles searcher refresh latency.
   */
  private boolean awaitPending(String docId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<String> pending = grpcClient.queryPendingVduDocIds(100);
      if (pending.contains(docId)) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }
}
