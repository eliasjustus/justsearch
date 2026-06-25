package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TimeboxedContentExtractor}.
 */
@DisplayName("TimeboxedContentExtractor")
class TimeboxedContentExtractorTest {

  @TempDir
  Path tempDir;

  private ContentExtractor delegate;
  private TimeboxedContentExtractor extractor;

  @BeforeEach
  void setUp() {
    delegate = new ContentExtractor();
  }

  @AfterEach
  void tearDown() {
    if (extractor != null) {
      extractor.close();
    }
  }

  @Test
  @DisplayName("successful extraction returns result")
  @Timeout(10)
  void successfulExtraction() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Hello World");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Hello World", result.content().trim());
    assertTrue(result.mimeType().startsWith("text/"));
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("IOException is propagated for missing file")
  @Timeout(5)
  void ioExceptionPropagated() {
    Path nonExistent = tempDir.resolve("missing.txt");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(5), null);

    assertThrows(IOException.class, () -> {
      extractor.extract(nonExistent);
    });
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("extractSafe returns empty result on IOException")
  @Timeout(5)
  void extractSafeOnIoException() {
    Path nonExistent = tempDir.resolve("missing.txt");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extractSafe(nonExistent);

    assertEquals("", result.content());
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("minimum timeout is enforced")
  @Timeout(10)
  void minimumTimeoutEnforced() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    // Try to set a timeout below minimum (100ms < 5s minimum)
    // The extractor should use the minimum timeout instead
    extractor = new TimeboxedContentExtractor(delegate, Duration.ofMillis(100), null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
    // Fast operations should still work even with minimum timeout
  }

  @Test
  @DisplayName("null timeout uses minimum")
  @Timeout(10)
  void nullTimeoutUsesMinimum() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    extractor = new TimeboxedContentExtractor(delegate, null, null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
  }

  @Test
  @DisplayName("detectMimeType delegates correctly")
  @Timeout(5)
  void detectMimeTypeDelegates() throws Exception {
    Path textFile = tempDir.resolve("document.txt");
    Files.writeString(textFile, "plain text content");

    extractor = new TimeboxedContentExtractor(delegate);
    String mime = extractor.detectMimeType(textFile);

    assertTrue(mime.startsWith("text/"));
  }

  @Test
  @DisplayName("close shuts down executor cleanly")
  @Timeout(5)
  void closeShutdownsExecutor() {
    extractor = new TimeboxedContentExtractor(delegate);
    extractor.close();
    // Should not throw
  }

  @Test
  @DisplayName("default constructor uses default timeout")
  @Timeout(10)
  void defaultConstructor() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    extractor = new TimeboxedContentExtractor(delegate);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
  }

  @Test
  @DisplayName("markdown file extraction works")
  @Timeout(10)
  void markdownExtraction() throws Exception {
    Path mdFile = tempDir.resolve("readme.md");
    Files.writeString(mdFile, "# Title\n\nSome **bold** text.");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(10), null);
    ExtractionResult result = extractor.extract(mdFile);

    assertTrue(result.content().contains("Title"));
    assertTrue(result.content().contains("bold"));
  }

  @Test
  @DisplayName("empty file returns empty content")
  @Timeout(10)
  void emptyFileReturnsEmptyContent() throws Exception {
    Path emptyFile = tempDir.resolve("empty.txt");
    Files.writeString(emptyFile, "");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extract(emptyFile);

    assertEquals("", result.content());
  }

  @Test
  @DisplayName("html extraction strips tags")
  @Timeout(10)
  void htmlExtraction() throws Exception {
    Path htmlFile = tempDir.resolve("page.html");
    Files.writeString(htmlFile, "<html><body><p>Hello World</p></body></html>");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(10), null);
    ExtractionResult result = extractor.extract(htmlFile);

    assertTrue(result.content().contains("Hello World"));
    // HTML tags should be stripped
    assertFalse(result.content().contains("<p>"));
  }

  @Test
  @DisplayName("timeout count starts at zero")
  @Timeout(5)
  void timeoutCountStartsAtZero() {
    extractor = new TimeboxedContentExtractor(delegate);
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("extraction result type checking works")
  @Timeout(10)
  void extractionResultTypes() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Test content");

    extractor = new TimeboxedContentExtractor(delegate);
    ExtractionResult result = extractor.extract(textFile);

    assertTrue(result.isTextBased());
    assertFalse(result.isPdf());
    assertFalse(result.isOffice());
  }

  @Test
  @DisplayName("extractSafe returns result on success")
  @Timeout(10)
  void extractSafeOnSuccess() throws Exception {
    Path textFile = tempDir.resolve("safe-test.txt");
    Files.writeString(textFile, "Safe content");

    extractor = new TimeboxedContentExtractor(delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extractSafe(textFile);

    assertEquals("Safe content", result.content().trim());
  }

  @Test
  @DisplayName("custom provider implementation works through happy path")
  @Timeout(10)
  void customProviderHappyPath() throws Exception {
    ContentExtractorProvider stubProvider =
        new ContentExtractorProvider() {
          @Override
          public ExtractionResult extract(Path file) {
            return new ExtractionResult("stub content", "stub title", "text/plain");
          }

          @Override
          public String detectMimeType(Path file) {
            return "text/plain";
          }
        };

    extractor = new TimeboxedContentExtractor(stubProvider, Duration.ofSeconds(5), null);
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "ignored");

    ExtractionResult result = extractor.extract(file);
    assertEquals("stub content", result.content());
    assertEquals("stub title", result.title());
    assertEquals("text/plain", result.mimeType());
    assertEquals("text/plain", extractor.detectMimeType(file));
  }

  @Test
  @DisplayName("explicit sandbox can be injected")
  @Timeout(5)
  void explicitSandboxCanBeInjected() throws Exception {
    Path file = tempDir.resolve("sandbox.txt");
    Files.writeString(file, "ignored");

    ExtractionSandbox sandbox =
        path ->
            ExtractionArtifact.full(
                new ExtractionResult("sandbox content", "sandbox title", "text/plain"),
                "InjectedSandbox");

    extractor = new TimeboxedContentExtractor(sandbox, Duration.ofSeconds(5), null);

    ExtractionArtifact artifact = extractor.extractArtifact(file);
    assertEquals("sandbox content", artifact.result().content());
    assertEquals("InjectedSandbox", artifact.parserId());
    assertEquals("application/octet-stream", extractor.detectMimeType(file));
  }

  @Test
  @DisplayName("artifact validation rejects failed sandbox responses")
  void artifactValidationRejectsFailedStatus() {
    ExtractionArtifact artifact =
        new ExtractionArtifact(
            ExtractionStatus.FAILED,
            new ExtractionResult("bad", null, "text/plain"),
            "policy",
            "parser",
            false,
            java.util.List.of());

    assertThrows(
        ContentExtractor.ExtractionException.class,
        () -> artifact.validateContentBoundsOnly(1024));
  }

  @Test
  @DisplayName("extract times out deterministically with injected delegate")
  @Timeout(5)
  void extractTimesOutDeterministically() throws Exception {
    Path file = tempDir.resolve("slow.bin");
    Files.writeString(file, "x");

    // Provider that blocks until interrupted (so timeout path is deterministic).
    ContentExtractorProvider slowDelegate =
        new ContentExtractorProvider() {
          @Override
          public ExtractionResult extract(Path f) throws IOException, ContentExtractor.ExtractionException {
            CountDownLatch never = new CountDownLatch(1);
            try {
              // Wait "forever" unless interrupted by cancellation.
              never.await(60, TimeUnit.SECONDS);
              return new ExtractionResult("unexpected", null, "application/octet-stream");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              // If we were interrupted, just simulate a fast termination.
              return new ExtractionResult("", null, "application/octet-stream");
            }
          }

          @Override
          public String detectMimeType(Path f) {
            return "application/octet-stream";
          }
        };

    extractor = new TimeboxedContentExtractor(slowDelegate, Duration.ofMillis(50), null, false);

    assertThrows(ExtractionTimeoutException.class, () -> extractor.extract(file));
    assertEquals(1, extractor.getTimeoutCount());
  }
}
