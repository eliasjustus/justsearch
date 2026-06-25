package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OcrConfidenceExtractorTest {
  @TempDir Path tempDir;

  @AfterEach
  void clearRuntimeProperties() {
    System.clearProperty("justsearch.tesseract.path");
    System.clearProperty("justsearch.tessdata.path");
    TikaOcrRuntime.resetLanguageCacheForTests();
  }

  @Test
  void extractPlainTextUsesResolvedTesseractRuntime() throws Exception {
    Path runtime = tempDir.resolve("runtime");
    Path executable = writeFakeTesseract(runtime);
    Path tessdata = runtime.resolve("tessdata");
    Files.createDirectories(tessdata);
    System.setProperty("justsearch.tesseract.path", executable.toString());
    System.setProperty("justsearch.tessdata.path", tessdata.toString());
    Path image = tempDir.resolve("image.png");
    Files.writeString(image, "fixture");

    String text =
        OcrConfidenceExtractor.extractPlainTextBounded(
                image,
                new OcrRoutingConfig(true, java.util.List.of("eng"), 5_000, 1, 4096, 40_000_000),
                null,
                Integer.MAX_VALUE)
            .text();

    assertEquals("FAKE OCR TEXT", text);
  }

  @Test
  void extractPlainTextBoundedReportsTruncation() throws Exception {
    Path runtime = tempDir.resolve("runtime");
    Path executable = writeFakeTesseract(runtime);
    Path tessdata = runtime.resolve("tessdata");
    Files.createDirectories(tessdata);
    System.setProperty("justsearch.tesseract.path", executable.toString());
    System.setProperty("justsearch.tessdata.path", tessdata.toString());
    Path image = tempDir.resolve("image.png");
    Files.writeString(image, "fixture");

    OcrConfidenceExtractor.TextResult text =
        OcrConfidenceExtractor.extractPlainTextBounded(
            image, new OcrRoutingConfig(true, java.util.List.of("eng"), 5_000, 1, 4096, 40_000_000), null, 4);

    assertEquals("FAKE", text.text());
    assertTrue(text.truncated());
  }

  @Test
  void extractPlainTextBoundedReportsFailedRuntimeReason() throws Exception {
    Path runtime = tempDir.resolve("failing-runtime");
    Path executable = writeFakeFailingTesseract(runtime);
    Path tessdata = runtime.resolve("tessdata");
    Files.createDirectories(tessdata);
    System.setProperty("justsearch.tesseract.path", executable.toString());
    System.setProperty("justsearch.tessdata.path", tessdata.toString());
    Path image = tempDir.resolve("image.png");
    Files.writeString(image, "fixture");

    OcrConfidenceExtractor.TextResult text =
        OcrConfidenceExtractor.extractPlainTextBounded(
            image, new OcrRoutingConfig(true, java.util.List.of("eng"), 5_000, 1, 4096, 40_000_000), null, 100);

    assertEquals("", text.text());
    assertEquals(OcrSkipReason.UNKNOWN, text.failureReason());
  }

  @Test
  void parseTsvComputesNormalizedMeanAndLowConfidenceWords() {
    OcrConfidenceExtractor.Summary summary =
        OcrConfidenceExtractor.parseTsv(
            """
            level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
            1\t1\t0\t0\t0\t0\t0\t0\t100\t100\t-1\t
            5\t1\t1\t1\t1\t1\t10\t10\t20\t10\t95\tAlpha
            5\t1\t1\t1\t1\t2\t40\t10\t20\t10\t45\tBeta
            5\t1\t1\t1\t1\t3\t70\t10\t20\t10\t-1\tGamma
            5\t1\t1\t1\t1\t4\t100\t10\t20\t10\t80\t
            malformed
            """);

    assertEquals(0.7d, summary.meanConfidence());
    assertEquals(1, summary.lowConfidenceWordCount());
    assertEquals(2, summary.wordCount());
  }

  @Test
  void parseTsvReturnsEmptyForNoUsableWords() {
    OcrConfidenceExtractor.Summary summary =
        OcrConfidenceExtractor.parseTsv(
            """
            level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
            5\t1\t1\t1\t1\t1\t10\t10\t20\t10\t-1\tAlpha
            5\t1\t1\t1\t1\t2\t40\t10\t20\t10\t90\t
            """);

    assertNull(summary.meanConfidence());
    assertEquals(0, summary.lowConfidenceWordCount());
    assertEquals(0, summary.wordCount());
  }

  @Test
  void aggregateWeightsMeanByWordCount() {
    OcrConfidenceExtractor.Summary summary =
        OcrConfidenceExtractor.aggregate(
            java.util.List.of(
                new OcrConfidenceExtractor.Summary(0.5d, 2, 4),
                new OcrConfidenceExtractor.Summary(0.9d, 0, 1)));

    assertEquals(0.58d, summary.meanConfidence());
    assertEquals(2, summary.lowConfidenceWordCount());
    assertEquals(5, summary.wordCount());
  }

  private static Path writeFakeTesseract(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path executable = directory.resolve(isWindows() ? "tesseract.cmd" : "tesseract");
    String script =
        isWindows()
            ? """
              @echo off
              echo FAKE OCR TEXT
              exit /b 0
              """
            : """
              #!/usr/bin/env sh
              echo "FAKE OCR TEXT"
              exit 0
              """;
    Files.writeString(executable, script);
    executable.toFile().setExecutable(true, false);
    return executable;
  }

  private static Path writeFakeFailingTesseract(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path executable = directory.resolve(isWindows() ? "tesseract-fail.cmd" : "tesseract-fail");
    String script =
        isWindows()
            ? """
              @echo off
              echo failed >&2
              exit /b 2
              """
            : """
              #!/usr/bin/env sh
              echo "failed" >&2
              exit 2
              """;
    Files.writeString(executable, script);
    executable.toFile().setExecutable(true, false);
    return executable;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
