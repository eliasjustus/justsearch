package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests VDU eligibility determination for PDF files.
 *
 * <p>Uses 60-second timeout to match production {@code TimeboxedContentExtractor} default,
 * which is necessary for Tika/pdfbox cold-start initialization on resource-constrained CI.
 *
 * <p><b>Disabled in CI:</b> Tika cold-start exceeds 60s on Windows CI runners.
 */
@DisplayName("VDU eligibility (PDF fixtures)")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class VduEligibilityPdfFixturesTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("pdf-text-layer.pdf -> VDU_STATUS_NOT_NEEDED (good Tika text)")
  @Timeout(60)
  void pdfTextLayerIsNotNeeded() throws Exception {
    Path pdf = copyResourceToTemp("/fixtures/pdf/pdf-text-layer.pdf", "pdf-text-layer.pdf");

    ContentExtractor extractor = new ContentExtractor();
    ExtractionResult extraction = extractor.extract(pdf);
    assertNotNull(extraction);

    IndexDocument doc = buildIndexDocument(pdf, extraction);
    Object vduStatus = doc.fields().get(SchemaFields.VDU_STATUS);
    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, vduStatus);
  }

  @Test
  @DisplayName("pdf-image-only.pdf -> VDU_STATUS_PENDING (garbage/empty Tika text)")
  @Timeout(60)
  void pdfImageOnlyIsPending() throws Exception {
    Path pdf = copyResourceToTemp("/fixtures/pdf/pdf-image-only.pdf", "pdf-image-only.pdf");

    ContentExtractor extractor = new ContentExtractor();
    ExtractionResult extraction = extractor.extract(pdf);
    assertNotNull(extraction);

    // We expect very little/empty extracted text for this fixture (no text layer).
    assertTrue(extraction.content() == null || extraction.content().length() < 100,
        "Expected extraction content to be < 100 chars but got: " +
            (extraction.content() == null ? "null" : extraction.content().length()));

    IndexDocument doc = buildIndexDocument(pdf, extraction);
    Object vduStatus = doc.fields().get(SchemaFields.VDU_STATUS);
    assertEquals(SchemaFields.VDU_STATUS_PENDING, vduStatus);
  }

  private Path copyResourceToTemp(String resourcePath, String fileName) throws IOException {
    try (InputStream is = VduEligibilityPdfFixturesTest.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Missing test resource: " + resourcePath);
      }
      Path out = tempDir.resolve(fileName);
      Files.copy(is, out);
      return out;
    }
  }

  private static IndexDocument buildIndexDocument(Path filePath, ExtractionResult extraction) throws Exception {
    return TestDocumentBuilder.buildDocument(filePath, extraction);
  }
}
