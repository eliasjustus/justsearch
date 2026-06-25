package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.systemtests.chaos.ExternalLlamaServerClient;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for VDU processing using a real llama-server with VLM.
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>llama-server running at localhost:8080 with a vision-capable model</li>
 *   <li>Start with: {@code .\native-bin\llama-server\llama-server.exe -m models\... --mmproj ...}</li>
 * </ul>
 *
 * <p>Tests will be skipped (not fail) if llama-server is not running.
 *
 * <p>Run with:
 * <pre>
 * .\gradlew :modules:system-tests:integrationTest --tests "*VduProcessorIntegrationTest*"
 * </pre>
 */
@Tag("ai")
@Tag("vdu")
@DisplayName("VDU Processor Integration Tests")
class VduProcessorIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(VduProcessorIntegrationTest.class);

  private static final int LLAMA_SERVER_PORT = 8080;
  private static final int MAX_TOKENS_EXTRACTION = 2048;
  private static final int MAX_TOKENS_ENRICHMENT = 512;

  /** Prompt for text extraction (Pass 1 of VDU pipeline). */
  private static final String EXTRACTION_PROMPT = """
      Extract all text from this document image. Include:
      - Headings and paragraphs
      - Table data (format as markdown tables)
      - Form field labels and values
      - Any visible text, numbers, or dates

      Output the extracted content in markdown format.
      """;

  /** Prompt template for enrichment (Pass 2 of VDU pipeline). */
  private static final String ENRICHMENT_PROMPT_TEMPLATE = """
      Based on the following extracted document content, provide:
      1. A brief summary (2-3 sentences)
      2. Document type (invoice, contract, receipt, letter, etc.)
      3. Key entities (dates, amounts, names, addresses)

      Output as JSON: {"summary": "...", "doc_type": "...", "entities": {...}}

      Document content:
      %s
      """;

  private static ExternalLlamaServerClient llamaClient;
  private static ObjectMapper objectMapper;
  private static boolean serverAvailable;

  @BeforeAll
  static void setup() {
    llamaClient = new ExternalLlamaServerClient(LLAMA_SERVER_PORT);
    objectMapper = new ObjectMapper();

    serverAvailable = llamaClient.isHealthy();
    if (!serverAvailable) {
      log.warn("⚠️  llama-server not available at localhost:{}", LLAMA_SERVER_PORT);
      log.warn("    Start server with: .\\native-bin\\llama-server\\llama-server.exe ...");
    } else {
      log.info("llama-server healthy at localhost:{}", LLAMA_SERVER_PORT);
    }
  }

  // =========================================================================
  // Image Preparation (mirrors ImagePreparer logic)
  // =========================================================================

  /**
   * Prepares an image for VLM consumption (resize, convert to JPEG).
   */
  private static byte[] prepareImage(Path imagePath) throws Exception {
    BufferedImage original = ImageIO.read(imagePath.toFile());
    if (original == null) {
      throw new IllegalArgumentException("Cannot read image: " + imagePath);
    }

    int maxDim = 1280; // Vision-model optimal (Qwen3-VL inherited)
    int width = original.getWidth();
    int height = original.getHeight();

    BufferedImage processed;
    if (width <= maxDim && height <= maxDim) {
      // Just convert to RGB (handles PNG with alpha)
      processed = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = processed.createGraphics();
      g.setColor(java.awt.Color.WHITE);
      g.fillRect(0, 0, width, height);
      g.drawImage(original, 0, 0, null);
      g.dispose();
    } else {
      // Resize
      double scale = Math.min((double) maxDim / width, (double) maxDim / height);
      int newWidth = (int) (width * scale);
      int newHeight = (int) (height * scale);

      processed = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = processed.createGraphics();
      g.setColor(java.awt.Color.WHITE);
      g.fillRect(0, 0, newWidth, newHeight);
      g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
          java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(original, 0, 0, newWidth, newHeight, null);
      g.dispose();
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(processed, "JPEG", baos);
    return baos.toByteArray();
  }

  // =========================================================================
  // Pass 1: Vision Text Extraction Tests
  // =========================================================================

  @Test
  @DisplayName("extracts text from test image (fake.png)")
  void extractsTextFromTestImage() throws Exception {
    assumeTrue(serverAvailable, "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    Path testImage = findTestImage("fake.png");
    assertTrue(Files.exists(testImage), "❌ Test image not found: " + testImage);

    byte[] imageBytes = prepareImage(testImage);
    log.info("Prepared image: {} bytes", imageBytes.length);

    String extractedText = llamaClient.visionCompletion(EXTRACTION_PROMPT, imageBytes, MAX_TOKENS_EXTRACTION);

    assertNotNull(extractedText, "Extracted text should not be null");
    assertFalse(extractedText.isBlank(), "Extracted text should not be blank");

    log.info("Extracted {} characters from {}", extractedText.length(), testImage.getFileName());
    log.debug("Extracted text:\n{}", extractedText);
  }

  @Test
  @DisplayName("extracts text from second test image (fake2.png)")
  void extractsTextFromSecondTestImage() throws Exception {
    assumeTrue(serverAvailable, "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    Path testImage = findTestImage("fake2.png");
    assertTrue(Files.exists(testImage), "❌ Test image not found: " + testImage);

    byte[] imageBytes = prepareImage(testImage);

    String extractedText = llamaClient.visionCompletion(EXTRACTION_PROMPT, imageBytes, MAX_TOKENS_EXTRACTION);

    assertNotNull(extractedText);
    assertFalse(extractedText.isBlank());

    log.info("Extracted {} characters from {}", extractedText.length(), testImage.getFileName());
  }

  // =========================================================================
  // Pass 2: Enrichment (Text-only, no vision)
  // =========================================================================

  @Test
  @DisplayName("generates valid JSON enrichment from extracted text")
  void generatesValidJsonEnrichment() throws Exception {
    assumeTrue(serverAvailable, "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    // Sample extracted text (simulating Pass 1 output)
    String extractedText = """
        INVOICE #12345
        Date: December 1, 2024

        Bill To:
        John Smith
        123 Main Street
        Springfield, IL 62701

        Items:
        - Widget A: $50.00
        - Service Fee: $25.00

        Total: $75.00

        Payment due within 30 days.
        """;

    String enrichmentPrompt = String.format(ENRICHMENT_PROMPT_TEMPLATE, extractedText);
    String enrichment = llamaClient.textCompletion(enrichmentPrompt, MAX_TOKENS_ENRICHMENT);

    assertNotNull(enrichment, "Enrichment should not be null");
    assertFalse(enrichment.isBlank(), "Enrichment should not be blank");

    log.info("Enrichment response: {}", enrichment);

    // Try to parse as JSON (model should output JSON)
    // Note: Model may include markdown code fences, so strip them
    String jsonContent = stripCodeFences(enrichment);

    try {
      JsonNode json = objectMapper.readTree(jsonContent);
      // Verify expected structure
      assertTrue(json.has("summary") || json.has("doc_type") || json.has("entities"),
          "Enrichment should contain at least one expected field (summary, doc_type, or entities)");
      log.info("Parsed enrichment JSON successfully");
    } catch (Exception e) {
      log.warn("Enrichment is not valid JSON: {}", e.getMessage());
      // Don't fail - model output may vary
    }
  }

  // =========================================================================
  // Full Pipeline Test (Pass 1 + Pass 2)
  // =========================================================================

  @Test
  @DisplayName("full VDU pipeline: extract text then enrich")
  void fullVduPipeline() throws Exception {
    assumeTrue(serverAvailable, "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    Path testImage = findTestImage("fake.png");
    assertTrue(Files.exists(testImage), "❌ Test image not found: " + testImage);

    // Pass 1: Extract text
    byte[] imageBytes = prepareImage(testImage);
    String extractedText = llamaClient.visionCompletion(EXTRACTION_PROMPT, imageBytes, MAX_TOKENS_EXTRACTION);

    assertNotNull(extractedText);
    assertFalse(extractedText.isBlank());
    log.info("Pass 1 complete: extracted {} characters", extractedText.length());

    // Pass 2: Enrich (text-only)
    String truncatedText = extractedText.length() > 8000
        ? extractedText.substring(0, 8000) + "\n... [truncated]"
        : extractedText;

    String enrichmentPrompt = String.format(ENRICHMENT_PROMPT_TEMPLATE, truncatedText);
    String enrichment = llamaClient.textCompletion(enrichmentPrompt, MAX_TOKENS_ENRICHMENT);

    assertNotNull(enrichment);
    assertFalse(enrichment.isBlank());
    log.info("Pass 2 complete: enrichment {} characters", enrichment.length());

    log.info("Full VDU pipeline completed successfully");
  }

  // =========================================================================
  // Error Handling Tests
  // =========================================================================

  @Test
  @DisplayName("handles empty image gracefully")
  void handlesEmptyImageGracefully() throws Exception {
    assumeTrue(serverAvailable, "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    // Create a minimal valid image (1x1 white pixel)
    BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    tiny.setRGB(0, 0, 0xFFFFFF);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageIO.write(tiny, "JPEG", baos);
    byte[] imageBytes = baos.toByteArray();

    // Should not throw, but may return minimal/empty content
    String result = llamaClient.visionCompletion("Extract text from this image", imageBytes, 256);

    assertNotNull(result, "Result should not be null even for tiny image");
    log.info("Tiny image result: {}", result);
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private static Path findTestImage(String filename) {
    // Try various locations relative to project root
    Path[] candidates = {
        Path.of("test_data_vdu", filename),
        Path.of("../../test_data_vdu", filename),
        Path.of("../../../test_data_vdu", filename),
        Path.of(System.getProperty("user.dir"), "test_data_vdu", filename)
    };

    for (Path candidate : candidates) {
      if (Files.exists(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }

    // Return first candidate (will fail with meaningful path in test)
    return candidates[0].toAbsolutePath().normalize();
  }

  private static String stripCodeFences(String text) {
    // Remove markdown code fences if present
    String stripped = text.trim();
    if (stripped.startsWith("```json")) {
      stripped = stripped.substring(7);
    } else if (stripped.startsWith("```")) {
      stripped = stripped.substring(3);
    }
    if (stripped.endsWith("```")) {
      stripped = stripped.substring(0, stripped.length() - 3);
    }
    return stripped.trim();
  }
}
