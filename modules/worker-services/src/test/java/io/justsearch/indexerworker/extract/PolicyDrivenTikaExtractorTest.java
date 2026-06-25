package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class PolicyDrivenTikaExtractorTest {
  @TempDir Path tempDir;

  @Test
  @Timeout(10)
  void outputLimitProducesValidatedPartialArtifact() throws Exception {
    Path file = tempDir.resolve("long.txt");
    Files.writeString(file, "abcdefghijklmnopqrstuvwxyz");
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "tiny-policy", 5, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(policy).extractArtifact(file);

    assertEquals("tiny-policy", artifact.policyId());
    assertTrue(artifact.truncated());
    assertTrue(artifact.result().content().length() <= 5);
    assertEquals(artifact, artifact.validateContentBoundsOnly(5));
  }

  @Test
  void policyRejectsDisabledXmlHardening() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TikaExtractionPolicy(
                "unsafe", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, false, Set.of(), Set.of()));
  }

  @Test
  void validateRejectsArtifactWithMismatchedPolicyId() throws Exception {
    Path file = tempDir.resolve("plain.txt");
    Files.writeString(file, "hello");
    TikaExtractionPolicy policyA =
        new TikaExtractionPolicy(
            "policy-a", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());
    TikaExtractionPolicy policyB =
        new TikaExtractionPolicy(
            "policy-b", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(policyA).extractArtifact(file);
    assertEquals("policy-a", artifact.policyId());

    ExtractionException thrown =
        assertThrows(ExtractionException.class, () -> artifact.validate(policyB, null));
    assertTrue(thrown.getMessage().contains("policy id"));
  }

  @Test
  @Timeout(10)
  void policyExtractorPreservesStructuredAnnotatedText() throws Exception {
    Path html = tempDir.resolve("structured.html");
    Files.writeString(html, "<html><body><h1>Heading</h1><p>Paragraph</p></body></html>");

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor().extractArtifact(html);

    assertTrue(artifact.result().content().contains("Heading"));
    assertTrue(
        artifact.parserId().contains("structured"),
        "Policy extraction must keep the structured parser path as production default");
  }

  @Test
  @Timeout(10)
  void excludedMimeTypeFailsBeforeParse() throws Exception {
    Path file = tempDir.resolve("blocked.txt");
    Files.writeString(file, "plain text");
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "blocked-text",
            1024,
            1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of("text/plain"));

    assertThrows(ExtractionException.class, () -> new PolicyDrivenTikaExtractor(policy).extract(file));
  }

  /**
   * Production-path regression: the prior structural defect was that the post-extraction length
   * check (`result.content().length() &gt; policy.maxExtractedChars()`) is structurally unable
   * to fire when input chunk boundaries align with the cap. Validation on 2026-04-26 measured a
   * 12 MiB plain-text file under the 10 MiB default cap producing
   * {@code contentLen=10485760 truncated=false status=SUCCESS_FULL}. The fix routes the SAX
   * handler's {@code limitReached} flag through {@code extractWithStatus} so production extraction
   * surfaces SUCCESS_PARTIAL on oversized real-Tika input.
   */
  @Test
  @Timeout(120)
  void defaultPolicyOversizedPlainTextProducesPartialArtifact() throws Exception {
    Path file = tempDir.resolve("oversize.txt");
    long target = 12L * 1024 * 1024; // 12 MiB, 2 MiB over the 10 MiB default cap
    try (java.io.BufferedWriter writer = Files.newBufferedWriter(file)) {
      char[] chunk = new char[8192];
      java.util.Arrays.fill(chunk, 'x');
      long written = 0;
      while (written < target) {
        int len = (int) Math.min(chunk.length, target - written);
        writer.write(chunk, 0, len);
        written += len;
      }
    }

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor().extractArtifact(file);

    assertTrue(
        artifact.truncated(),
        "12 MiB input over 10 MiB default cap must surface as truncated; the SAX-level"
            + " limitReached signal replaces the structurally-unreachable length-based check.");
    assertEquals(
        ExtractionStatus.SUCCESS_PARTIAL,
        artifact.status(),
        "Truncated artifact must classify as SUCCESS_PARTIAL so the ledger and document agree.");
    assertTrue(
        artifact.result().content().length() <= TikaExtractionPolicy.DEFAULT_MAX_EXTRACTED_CHARS,
        "Defensive trim must keep content at or under the policy cap.");
  }

  @Test
  @Timeout(10)
  void xmlEntityPayloadDoesNotLeakExternalFileContent() throws Exception {
    Path secret = tempDir.resolve("secret.txt");
    Files.writeString(secret, "SECRET-XXE-CONTENT");
    Path xml = tempDir.resolve("xxe.xml");
    String uri = secret.toUri().toString();
    Files.writeString(
        xml,
        """
        <?xml version="1.0"?>
        <!DOCTYPE root [ <!ENTITY xxe SYSTEM "%s"> ]>
        <root>&xxe;</root>
        """
            .formatted(uri));

    try {
      ExtractionArtifact artifact = new PolicyDrivenTikaExtractor().extractArtifact(xml);
      assertFalse(artifact.result().content().contains("SECRET-XXE-CONTENT"));
    } catch (ExtractionException e) {
      assertFalse(e.getMessage().contains("SECRET-XXE-CONTENT"));
    }
  }

  @Test
  @Timeout(30)
  void eligibleLowQualityImageWithDisabledOcrRecordsWorkerSkipMetric() throws Exception {
    Path image = tempDir.resolve("ocr-disabled.png");
    writeTextImage(image, "DISABLED OCR");
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);

    ExtractionArtifact artifact =
        new PolicyDrivenTikaExtractor(
                TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), catalog)
            .extractArtifact(image);

    assertFalse(OcrRoutingConfig.PARSER_ID.equals(artifact.parserId()));
    assertFalse(
        artifact.result().content().toLowerCase(java.util.Locale.ROOT).contains("disabled"),
        "disabled OCR must not leak ambient Tika image OCR text");
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"structured\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"disabled\""));
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED)));
  }

  @Test
  @Timeout(30)
  void oversizedImageWithEnabledOcrRecordsWorkerSizeSkipMetric() throws Exception {
    Path image = tempDir.resolve("ocr-too-large.png");
    writeTextImage(image, "OVERSIZED OCR");
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);
    OcrRoutingConfig guarded = new OcrRoutingConfig(true, List.of("eng"), 10_000, 1, 10, 40_000_000);

    ExtractionArtifact artifact =
        new PolicyDrivenTikaExtractor(TikaExtractionPolicy.defaults(), guarded, catalog)
            .extractArtifact(image);

    assertFalse(OcrRoutingConfig.PARSER_ID.equals(artifact.parserId()));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"size\""));
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE)));
  }

  @Test
  @Timeout(30)
  void realTesseractRuntimeProducesOcrArtifactForImageText() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 10_000, 1, 4096, 40_000_000);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path image = tempDir.resolve("ocr-alpha.png");
    writeTextImage(image, "ALPHA DOCUMENT");
    PolicyDrivenTikaExtractor admissionProbe =
        new PolicyDrivenTikaExtractor(TikaExtractionPolicy.defaults(), ocrConfig);
    assertTrue(
        admissionProbe.shouldAttemptOcrForTesting(
            image, "image/png", "", StructuredDocumentSummary.empty()),
        "empty raster image should be OCR-admitted");
    String directText =
        OcrConfidenceExtractor.extractPlainTextBounded(image, ocrConfig, null, Integer.MAX_VALUE).text();
    TikaOcrRuntime.RuntimePaths runtimePaths = TikaOcrRuntime.resolve();
    assertTrue(
        directText.toLowerCase(java.util.Locale.ROOT).contains("alpha"),
        "direct packaged Tesseract should read fixture text, got: "
            + directText
            + " runtime="
            + runtimePaths);
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(null, ocrConfig, catalog)) {
      artifact = extractor.extractArtifact(image);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson()
            + " skippedDisabled="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED))
            + " skippedSize="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE))
            + " skippedTextual="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL))
            + " failedExtraction="
            + registry.counterValue(
                OcrMetricCatalog.FAILED_TOTAL,
                OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, "ExtractionException")));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrLanguage\":\"eng\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_full\""));
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrMeanConfidence\":"),
        artifact.visualExtractionEvidenceJson());
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrWordCount\":"),
        artifact.visualExtractionEvidenceJson());
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("alpha") && normalized.contains("document"),
        "expected OCR text to contain ALPHA DOCUMENT, got: " + artifact.result().content());
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SUCCEEDED_TOTAL, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE)));
    assertEquals(
        1L,
        registry.histogramCount(
            OcrMetricCatalog.TIME_MS, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE)));
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeAddsSelectiveOcrEvidenceForMixedPdf() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("mixed-image.pdf");
    writeMixedTextAndImagePdf(pdf);
    PolicyDrivenTikaExtractor admissionProbe =
        new PolicyDrivenTikaExtractor(TikaExtractionPolicy.defaults(), ocrConfig);
    assertTrue(
        admissionProbe.shouldAttemptOcrForTesting(
            pdf,
            "application/pdf",
            "This page contains enough readable digital text for the PDF text layer. ".repeat(4),
            new StructuredDocumentSummary(3, 297, 1, 2, 0, 1, 0, 0, 1)),
        "mixed PDF with missing readable pages should be OCR-admitted");

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_selective\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"mixedPdf\":true"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":0"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrMeanConfidence\":"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrWordCount\":"));
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("mixed") && normalized.contains("token"),
        "expected selective OCR text to contain MIXED TOKEN, got: " + artifact.result().content());
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeClearsMissingReadableTextForImageOnlyPdfOcr() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("image-only.pdf");
    writeImageOnlyPdf(pdf, "PLAIN SCAN TOKEN");

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_full\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":0"));
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("plain") && normalized.contains("scan") && normalized.contains("token"),
        "expected OCR text to contain PLAIN SCAN TOKEN, got: " + artifact.result().content());
  }

  @Test
  @Timeout(30)
  void mixedPdfWithDisabledOcrRecordsMissingPageEvidence() throws Exception {
    Path pdf = tempDir.resolve("mixed.pdf");
    writeMixedTextAndBlankPdf(pdf);

    ExtractionArtifact artifact =
        new PolicyDrivenTikaExtractor(TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled())
            .extractArtifact(pdf);

    assertEquals("tika-policy-structured", artifact.parserId());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"mixedPdf\":true"));
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":")
            && !artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":0"),
        artifact.visualExtractionEvidenceJson());
  }

  private static void writeTextImage(Path image, String text) throws Exception {
    ImageIO.write(createTextImage(text), "png", image.toFile());
  }

  private static BufferedImage createTextImage(String text) {
    BufferedImage buffered = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = buffered.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(ocrFixtureFont());
      graphics.drawString(text, 90, 220);
    } finally {
      graphics.dispose();
    }
    return buffered;
  }

  private static Font ocrFixtureFont() {
    File arial = Path.of("C:", "Windows", "Fonts", "arial.ttf").toFile();
    if (arial.isFile()) {
      try {
        return Font.createFont(Font.TRUETYPE_FONT, arial).deriveFont(Font.PLAIN, 72f);
      } catch (Exception ignored) {
        // Fall back to the logical font if Arial is unavailable or cannot be loaded.
      }
    }
    return new Font(Font.SANS_SERIF, Font.PLAIN, 72);
  }

  private static void writeMixedTextAndBlankPdf(Path pdf) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage textPage = new PDPage();
      document.addPage(textPage);
      try (PDPageContentStream content = new PDPageContentStream(document, textPage)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 720);
        content.showText("This page contains enough readable digital text for the PDF text layer. ".repeat(4));
        content.endText();
      }
      document.addPage(new PDPage());
      document.save(pdf.toFile());
    }
  }

  private static void writeMixedTextAndImagePdf(Path pdf) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage textPage = new PDPage();
      document.addPage(textPage);
      try (PDPageContentStream content = new PDPageContentStream(document, textPage)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 720);
        content.showText("This page contains enough readable digital text for the PDF text layer. ".repeat(4));
        content.endText();
      }

      PDPage imagePage = new PDPage();
      document.addPage(imagePage);
      PDImageXObject image = LosslessFactory.createFromImage(document, createTextImage("MIXED TOKEN"));
      try (PDPageContentStream content = new PDPageContentStream(document, imagePage)) {
        content.drawImage(image, 72, 500, 430, 125);
      }
      document.save(pdf.toFile());
    }
  }

  private static void writeImageOnlyPdf(Path pdf, String text) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage imagePage = new PDPage();
      document.addPage(imagePage);
      PDImageXObject image = LosslessFactory.createFromImage(document, createTextImage(text));
      try (PDPageContentStream content = new PDPageContentStream(document, imagePage)) {
        content.drawImage(image, 72, 500, 430, 125);
      }
      document.save(pdf.toFile());
    }
  }

}
