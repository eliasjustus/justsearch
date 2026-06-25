package io.justsearch.systemtests.vdu;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.services.vdu.ImagePreparer;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import io.justsearch.systemtests.chaos.ExternalLlamaServerClient;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end system tests for VDU batch processing.
 *
 * <p>Tests the full VDU pipeline: Real Worker + Real LLM (llama-server).
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>llama-server running at localhost:8080 with a vision-capable model</li>
 *   <li>Worker distribution built (./gradlew :modules:indexer-worker:installDist)</li>
 * </ul>
 *
 * <p><b>Note:</b> Tests will FAIL if llama-server is not available.
 * This is intentional - silent skipping hides untested code paths.
 */
@DisplayName("VDU Batch Processor E2E Tests")
@Tag("systemTest")
@Tag("ai")
class VduBatchProcessorE2ETest {
  private static final Logger log = LoggerFactory.getLogger(VduBatchProcessorE2ETest.class);
  private static final int LLAMA_SERVER_PORT = 8080;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  // Expected text content from test images (used for output quality verification)
  private static final String EXPECTED_TEXT_KEYWORD = "TEST";  // Must appear in extracted text

  @RegisterExtension
  static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private static ExternalLlamaServerClient llamaClient;
  private static boolean llamaServerAvailable;

  private WorkerProcessManager worker;
  private MmfTestHarness mmf;
  private GrpcTestClient grpcClient;
  private Path testImageDir;
  private TestableVduBatchProcessor vduProcessor;

  @BeforeAll
  static void checkLlamaServer() {
    llamaClient = new ExternalLlamaServerClient(LLAMA_SERVER_PORT);
    llamaServerAvailable = llamaClient.isHealthy();
    if (!llamaServerAvailable) {
      log.error("❌ llama-server not available at localhost:{}", LLAMA_SERVER_PORT);
      log.error("   Start server with:");
      log.error("   .\\native-bin\\llama-server\\llama-server.exe -m models\\... --mmproj ...");
    } else {
      log.info("llama-server healthy at localhost:{}", LLAMA_SERVER_PORT);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    assertTrue(llamaServerAvailable,
        "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT +
        ". Start it before running VDU tests.");

    // Clean the data directory to ensure test isolation
    Path dataDir = env.getTempDir();
    cleanDataDirectory(dataDir);

    // Create test image directory
    testImageDir = dataDir.resolve("test-images");
    Files.createDirectories(testImageDir);

    // Spawn worker
    worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir());
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

    // Create testable VDU processor
    vduProcessor = new TestableVduBatchProcessor(llamaClient, grpcClient);
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
  @DisplayName("processes image document with real LLM and verifies output quality")
  void processesImageWithRealLlm() throws Exception {
    // 1. Create and index test image
    Path testImage = createTestImage("e2e-test.png");
    String filePath = testImage.toAbsolutePath().toString();
    // Worker normalizes paths (lowercase on Windows) for doc_id
    String docId = normalizeDocId(testImage);
    log.info("Created test image: {} (docId: {})", filePath, docId);

    int accepted = grpcClient.submitBatch(List.of(filePath));
    assertEquals(1, accepted, "Should accept 1 file");

    // 2. Wait for indexing
    assertTrue(grpcClient.awaitIndexing(1, 30_000, 200), "Should index within 30s");

    // 3. Verify document is pending VDU (poll to handle searcher refresh)
    assertTrue(awaitPending(docId, 5_000), "Document should be pending VDU");

    // 4. Process with real LLM and capture results
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(1, result.processedCount(), "Should process 1 document");

    // =========================================================================
    // STRONG ASSERTIONS: Verify AI output quality (not just that it ran)
    // =========================================================================

    // 5a. Verify extracted text is not blank
    assertFalse(result.lastExtractedText().isBlank(),
        "❌ Extracted text should NOT be blank - LLM returned empty response");

    // 5b. Verify extracted text contains expected keywords from the test image
    String extractedLower = result.lastExtractedText().toLowerCase(Locale.ROOT);
    assertTrue(extractedLower.contains(EXPECTED_TEXT_KEYWORD.toLowerCase(Locale.ROOT)),
        "❌ Extracted text should contain '" + EXPECTED_TEXT_KEYWORD + "' from test image. " +
        "Actual extracted: " + truncate(result.lastExtractedText(), 200));

    // 5c. Verify enrichment is valid JSON (not garbage)
    assertDoesNotThrow(() -> {
      JsonNode json = objectMapper.readTree(result.lastEnrichment());
      assertNotNull(json, "Enrichment should parse as valid JSON");
    }, "❌ Enrichment should be valid JSON, got: " + truncate(result.lastEnrichment(), 200));

    // 6. Verify document is no longer pending (poll to handle searcher refresh)
    assertTrue(awaitNotPending(docId, 5_000),
        "Document should no longer be pending after VDU processing");

    // 7. Verify extracted content is searchable (the real use case!)
    assertTrue(awaitSearchable(EXPECTED_TEXT_KEYWORD, 10_000),
        "❌ Document should be searchable by extracted text keyword: " + EXPECTED_TEXT_KEYWORD);

    log.info("✅ E2E test PASSED with output quality verification:");
    log.info("   Extracted text length: {} chars", result.lastExtractedText().length());
    log.info("   Contains expected keyword: '{}'", EXPECTED_TEXT_KEYWORD);
    log.info("   Enrichment is valid JSON: true");
  }

  @Test
  @DisplayName("processes scanned PDF (image-only) with real LLM and verifies searchability")
  void processesScannedPdfWithRealLlm() throws Exception {
    // 1. Copy scanned PDF fixture into test data directory
    Path pdf = copyResourceToTestDir("/fixtures/pdf/scanned-alpha.pdf", "scanned-alpha.pdf");
    String filePath = pdf.toAbsolutePath().toString();
    String docId = normalizeDocId(pdf);
    log.info("Copied scanned PDF fixture: {} (docId: {})", filePath, docId);

    int accepted = grpcClient.submitBatch(List.of(filePath));
    assertEquals(1, accepted, "Should accept 1 file");

    // 2. Wait for indexing
    assertTrue(grpcClient.awaitIndexing(1, 60_000, 200), "Should index within 60s");

    // 3. Verify pending VDU (image-only PDF => no text layer)
    assertTrue(awaitPending(docId, 10_000), "Scanned PDF should be pending VDU");

    // 4. Process with real LLM
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(1, result.processedCount(), "Should process 1 document");

    // 5. Verify OCR output contains expected keyword
    assertFalse(result.lastExtractedText().isBlank(), "Extracted text should not be blank");
    assertTrue(
        result.lastExtractedText().toLowerCase(Locale.ROOT).contains("alpha"),
        "Extracted text should contain 'ALPHA'. Actual: " + truncate(result.lastExtractedText(), 200));

    // 6. Verify document is no longer pending
    assertTrue(awaitNotPending(docId, 10_000), "Document should no longer be pending after VDU processing");

    // 7. Verify extracted content is searchable
    assertTrue(awaitSearchable("ALPHA", 15_000), "Scanned PDF should be searchable by OCR text");
  }

  @Test
  @DisplayName("processes multiple images in batch with quality verification")
  void processesMultipleImagesInBatch() throws Exception {
    // 1. Create multiple test images with DIFFERENT content
    Path img1 = createTestImageWithText("batch-1.png", "ALPHA DOCUMENT");
    Path img2 = createTestImageWithText("batch-2.png", "BETA DOCUMENT");

    // Worker normalizes paths (lowercase on Windows)
    String docId1 = normalizeDocId(img1);
    String docId2 = normalizeDocId(img2);

    // 2. Submit all for indexing (use actual paths)
    int accepted = grpcClient.submitBatch(List.of(
        img1.toAbsolutePath().toString(),
        img2.toAbsolutePath().toString()));
    assertEquals(2, accepted);

    // 3. Wait for indexing
    assertTrue(grpcClient.awaitIndexing(2, 60_000, 200));

    // 4. Verify all pending (poll to handle searcher refresh)
    assertTrue(awaitPending(docId1, 5_000), "Doc 1 should be pending");
    assertTrue(awaitPending(docId2, 5_000), "Doc 2 should be pending");

    // 5. Process all and capture results
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(2, result.processedCount(), "Should process both documents");

    // =========================================================================
    // STRONG ASSERTIONS: Verify batch processing quality
    // =========================================================================

    // 6a. Verify all extracted texts are non-blank
    assertEquals(2, result.allExtractedTexts().size(), "Should have extracted text for both docs");
    for (String text : result.allExtractedTexts()) {
      assertFalse(text.isBlank(),
          "❌ Each extracted text should NOT be blank");
    }

    // 6b. Verify all enrichments are valid JSON
    assertEquals(2, result.allEnrichments().size(), "Should have enrichment for both docs");
    for (String enrichment : result.allEnrichments()) {
      assertDoesNotThrow(() -> objectMapper.readTree(enrichment),
          "❌ Each enrichment should be valid JSON, got: " + truncate(enrichment, 100));
    }

    // 7. Verify none pending (poll to handle searcher refresh)
    assertTrue(awaitNotPending(docId1, 5_000), "Doc 1 should not be pending after processing");
    assertTrue(awaitNotPending(docId2, 5_000), "Doc 2 should not be pending after processing");

    // 8. Verify BOTH documents are searchable with their unique content
    assertTrue(awaitSearchable("ALPHA", 10_000),
        "❌ Doc 1 should be searchable by 'ALPHA'");
    assertTrue(awaitSearchable("BETA", 10_000),
        "❌ Doc 2 should be searchable by 'BETA'");

    log.info("✅ Batch test PASSED: {} documents processed with verified quality",
        result.processedCount());
  }

  @Test
  @DisplayName("invalid image reaches FAILED status with proper error handling")
  void invalidImageReachesFailed() throws Exception {
    // 1. Create an invalid "image" (text file with .png extension)
    Path invalidImage = testImageDir.resolve("invalid.png");
    Files.writeString(invalidImage, "This is not a valid PNG image - just random text");
    String filePath = invalidImage.toAbsolutePath().toString();
    // Worker normalizes paths (lowercase on Windows)
    String docId = normalizeDocId(invalidImage);

    // 2. Submit for indexing
    grpcClient.submitBatch(List.of(filePath));
    assertTrue(grpcClient.awaitIndexing(1, 30_000, 200));

    // 3. Verify pending (poll to handle searcher refresh)
    assertTrue(awaitPending(docId, 5_000), "Invalid image should be pending VDU initially");

    // 4. Process (should fail gracefully, not crash)
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(0, result.processedCount(),
        "Invalid image should NOT count as successfully processed");

    // =========================================================================
    // STRONG ASSERTIONS: Verify failure is properly recorded
    // =========================================================================

    // 5a. Verify we attempted to process but it failed
    assertEquals(1, result.failedCount(),
        "Should have exactly 1 failed document");

    // 5b. Verify failure reason is captured (not silent)
    assertFalse(result.lastFailureReason().isBlank(),
        "❌ Failure reason should NOT be blank - errors must be logged");
    log.info("Failure reason captured: {}", result.lastFailureReason());

    // 6. Verify no longer pending (marked as FAILED) - poll to handle searcher refresh
    assertTrue(awaitNotPending(docId, 5_000),
        "Failed document should not be in pending list");

    log.info("✅ Invalid image test PASSED: failure properly recorded");
  }

  // =========================================================================
  // Helper Methods
  // =========================================================================

  private Path createTestImage(String filename) throws Exception {
    return createTestImageWithText(filename, "TEST DOCUMENT\nVDU E2E Test");
  }

  /**
   * Creates a test image with custom text content.
   * The text should be extractable by VDU for verification.
   */
  private Path createTestImageWithText(String filename, String text) throws Exception {
    Path imagePath = testImageDir.resolve(filename);

    // Create a test image with the specified text content
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
        300, 150, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = img.createGraphics();

    // White background for better OCR
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, 300, 150);

    // Black text, clear font
    g.setColor(java.awt.Color.BLACK);
    g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 18));

    // Draw each line of text
    String[] lines = text.split("\n");
    int y = 40;
    for (String line : lines) {
      g.drawString(line, 20, y);
      y += 30;
    }
    g.dispose();

    javax.imageio.ImageIO.write(img, "PNG", imagePath.toFile());
    log.debug("Created test image: {} with text: {}", imagePath, text.replace("\n", " | "));
    return imagePath;
  }

  /**
   * Normalizes a path to match Worker's document ID format.
   * Worker lowercases paths on Windows for case-insensitive comparison.
   */
  private String normalizeDocId(Path path) {
    String absolutePath = path.toAbsolutePath().toString();
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      return absolutePath.toLowerCase(java.util.Locale.ROOT);
    }
    return absolutePath;
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
   * Waits until a search query returns at least one result.
   * This verifies that extracted content is actually searchable.
   */
  private boolean awaitSearchable(String query, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        SearchResponse response = grpcClient.searchText(query, 10);
        if (response.getTotalHits() > 0) {
          log.debug("Search '{}' returned {} hits", query, response.getTotalHits());
          return true;
        }
      } catch (Exception e) {
        log.debug("Search failed: {}", e.getMessage());
      }
      Thread.sleep(200);
    }
    log.warn("Search '{}' timed out with no results", query);
    return false;
  }

  /**
   * Truncates a string for logging (avoids massive log output).
   */
  private static String truncate(String s, int maxLen) {
    if (s == null) return "<null>";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "... [truncated, total " + s.length() + " chars]";
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
    // (Worker currently uses jobs.db; keep legacy name deletion for back-compat.)
    Files.deleteIfExists(dataDir.resolve("jobs.db"));
    Files.deleteIfExists(dataDir.resolve("job_queue.db"));
  }

  private Path copyResourceToTestDir(String resourcePath, String filename) throws Exception {
    if (resourcePath == null || resourcePath.isBlank()) {
      throw new IllegalArgumentException("resourcePath is required");
    }
    String outName = (filename == null || filename.isBlank()) ? "fixture.bin" : filename;
    Path out = testImageDir.resolve(outName);

    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      assertNotNull(in, "Missing test resource: " + resourcePath);
      Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
    }
    return out;
  }

  // =========================================================================
  // Test Support Classes
  // =========================================================================

  /**
   * Test-friendly VDU batch processor that uses GrpcTestClient and ExternalLlamaServerClient.
   *
   * <p>Mirrors the real VduBatchProcessor logic but works with test infrastructure.
   * <p>ENHANCED: Returns detailed results for test verification of AI output quality.
   */
  static class TestableVduBatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(TestableVduBatchProcessor.class);

    private static final String EXTRACTION_PROMPT = """
        Extract all text from this document image. Include:
        - Headings and paragraphs
        - Any visible text, numbers, or dates
        Output the extracted content.
        """;

    private static final String ENRICHMENT_PROMPT_TEMPLATE = """
        Based on the following extracted document content, provide:
        1. A brief summary (1-2 sentences)
        2. Document type
        Output as JSON: {"summary": "...", "doc_type": "..."}

        Document content:
        %s
        """;

    private static final int MAX_RETRIES = 3;

    private final ExternalLlamaServerClient llamaClient;
    private final GrpcTestClient grpcClient;
    private final ImagePreparer imagePreparer;

    TestableVduBatchProcessor(ExternalLlamaServerClient llamaClient, GrpcTestClient grpcClient) {
      this.llamaClient = llamaClient;
      this.grpcClient = grpcClient;
      this.imagePreparer = new ImagePreparer();
    }

    /**
     * Processes all pending VDU documents (simple version for backward compat).
     *
     * @return Number of successfully processed documents
     */
    int processPendingFiles() {
      return processPendingFilesWithResults().processedCount();
    }

    /**
     * Processes all pending VDU documents and returns detailed results.
     *
     * <p>This method captures extraction results for test verification,
     * ensuring we don't just check "it ran" but "it produced meaningful output".
     *
     * @return ProcessingResult containing counts and captured outputs
     */
    ProcessingResult processPendingFilesWithResults() {
      List<String> pending = grpcClient.queryPendingVduDocIds(100);
      log.info("Processing {} pending VDU files", pending.size());

      int processed = 0;
      int failed = 0;
      java.util.List<String> extractedTexts = new java.util.ArrayList<>();
      java.util.List<String> enrichments = new java.util.ArrayList<>();
      String lastExtractedText = "";
      String lastEnrichment = "";
      String lastFailureReason = "";

      for (String docId : pending) {
        try {
          // Mark as PROCESSING (with retry protection)
          int retryCount = grpcClient.markVduProcessing(docId, MAX_RETRIES);
          if (retryCount < 0) {
            log.warn("Skipping {} - max retries exceeded", docId);
            lastFailureReason = "Max retries exceeded";
            failed++;
            continue;
          }

          // Check file exists
          Path filePath = Path.of(docId);
          if (!Files.exists(filePath)) {
            lastFailureReason = "File no longer exists: " + docId;
            markFailed(docId, lastFailureReason);
            failed++;
            continue;
          }

          // Process with real LLM
          log.info("Processing {} (retry {})", docId, retryCount);
          VduResult result = processFile(filePath);

          // Capture results for verification
          lastExtractedText = result.extractedText();
          lastEnrichment = result.enrichment();
          extractedTexts.add(lastExtractedText);
          enrichments.add(lastEnrichment);

          // Update result
          boolean updated = grpcClient.updateVduResult(
              docId,
              result.extractedText(),
              VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT,
              result.enrichment(),
              result.pageCount());

          if (updated) {
            processed++;
            log.info("Successfully processed {} - extracted {} chars",
                docId, result.extractedText().length());
          } else {
            log.warn("Failed to update result for {}", docId);
            lastFailureReason = "Failed to update result in index";
            failed++;
          }

        } catch (Exception e) {
          log.error("VDU processing failed for {}: {}", docId, e.getMessage());
          lastFailureReason = e.getMessage();
          markFailed(docId, e.getMessage());
          failed++;
        }
      }

      return new ProcessingResult(
          processed,
          failed,
          lastExtractedText,
          lastEnrichment,
          lastFailureReason,
          extractedTexts,
          enrichments);
    }

    private VduResult processFile(Path filePath) throws Exception {
      // Pass 1: Extract text via vision
      byte[] imageBytes = imagePreparer.prepare(filePath);
      String extractedText = llamaClient.visionCompletion(EXTRACTION_PROMPT, imageBytes, 2048);

      // VALIDATION: Fail fast if extraction returned nothing
      if (extractedText == null || extractedText.isBlank()) {
        throw new RuntimeException("LLM returned blank/null extracted text for: " + filePath);
      }

      // Pass 2: Enrich (text only)
      String truncatedText = extractedText.length() > 4000
          ? extractedText.substring(0, 4000) + "..."
          : extractedText;
      String enrichmentPrompt = String.format(ENRICHMENT_PROMPT_TEMPLATE, truncatedText);
      String enrichment = llamaClient.textCompletion(enrichmentPrompt, 256);

      return new VduResult(extractedText, enrichment, 1);
    }

    private void markFailed(String docId, String reason) {
      String safeReason = reason.replace("\"", "'");
      grpcClient.updateVduResult(
          docId,
          null,
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
          "{\"error\": \"" + safeReason + "\"}",
          0);
    }

    record VduResult(String extractedText, String enrichment, int pageCount) {}

    /**
     * Result of batch processing with captured outputs for test verification.
     */
    record ProcessingResult(
        int processedCount,
        int failedCount,
        String lastExtractedText,
        String lastEnrichment,
        String lastFailureReason,
        java.util.List<String> allExtractedTexts,
        java.util.List<String> allEnrichments) {}
  }
}
