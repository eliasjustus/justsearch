/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and aggregates Tesseract TSV confidence evidence for OCR routing decisions. The TSV is
 * produced by {@link PdfOcrEngine}'s single owned tesseract invocation (one spawn emits {@code txt}
 * and {@code tsv} together); this type no longer spawns any process itself.
 */
final class OcrConfidenceExtractor {
  static final double LOW_CONFIDENCE_THRESHOLD = 0.60d;

  private OcrConfidenceExtractor() {}

  static Summary parseTsv(String tsv) {
    if (tsv == null || tsv.isBlank()) {
      return Summary.empty();
    }
    int confidenceSum = 0;
    int lowConfidenceWords = 0;
    int wordCount = 0;
    String[] lines = tsv.split("\\R");
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      if (line == null || line.isBlank()) {
        continue;
      }
      String[] columns = line.split("\t", -1);
      if (columns.length < 12 || !"5".equals(columns[0].trim())) {
        continue;
      }
      String word = columns[11] == null ? "" : columns[11].trim();
      if (word.isBlank()) {
        continue;
      }
      Integer confidence = parseConfidence(columns[10]);
      if (confidence == null || confidence < 0) {
        continue;
      }
      int bounded = Math.max(0, Math.min(100, confidence));
      wordCount++;
      confidenceSum += bounded;
      if ((bounded / 100.0d) < LOW_CONFIDENCE_THRESHOLD) {
        lowConfidenceWords++;
      }
    }
    if (wordCount == 0) {
      return Summary.empty();
    }
    return new Summary(round((confidenceSum / (double) wordCount) / 100.0d), lowConfidenceWords, wordCount);
  }

  static Summary aggregate(List<Summary> summaries) {
    if (summaries == null || summaries.isEmpty()) {
      return Summary.empty();
    }
    List<Summary> usable = new ArrayList<>();
    int totalWords = 0;
    int lowConfidenceWords = 0;
    double weightedConfidence = 0.0d;
    for (Summary summary : summaries) {
      if (summary == null || summary.wordCount() <= 0 || summary.meanConfidence() == null) {
        continue;
      }
      usable.add(summary);
      totalWords += summary.wordCount();
      lowConfidenceWords += summary.lowConfidenceWordCount();
      weightedConfidence += summary.meanConfidence() * summary.wordCount();
    }
    if (usable.isEmpty() || totalWords <= 0) {
      return Summary.empty();
    }
    return new Summary(round(weightedConfidence / totalWords), lowConfidenceWords, totalWords);
  }

  private static Integer parseConfidence(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return (int) Math.round(Double.parseDouble(raw.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double round(double value) {
    if (!Double.isFinite(value)) {
      return 0.0d;
    }
    return Math.round(value * 1000.0d) / 1000.0d;
  }

  record Summary(Double meanConfidence, int lowConfidenceWordCount, int wordCount) {
    static Summary empty() {
      return new Summary(null, 0, 0);
    }

    boolean present() {
      return meanConfidence != null && wordCount > 0;
    }
  }
}
