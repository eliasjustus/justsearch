package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * TSV parsing + aggregation is all that survives here after tempdoc 706: the process-spawning entry
 * points ({@code extractPlainTextBounded}, {@code extract}) collapsed into {@link PdfOcrEngine}'s
 * single owned invocation. Their spawn/timeout/truncation intent is exercised by {@code
 * PdfOcrEngineTest} against the process-factory seam instead.
 */
final class OcrConfidenceExtractorTest {

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
}
