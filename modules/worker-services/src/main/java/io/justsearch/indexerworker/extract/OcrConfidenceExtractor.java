/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Optional Tesseract TSV confidence evidence for OCR routing decisions. */
final class OcrConfidenceExtractor {
  static final double LOW_CONFIDENCE_THRESHOLD = 0.60d;

  private OcrConfidenceExtractor() {}

  static TextResult extractPlainTextBounded(
      Path input, OcrRoutingConfig config, Logger log, int maxChars) {
    if (input == null || config == null || !config.enabled()) {
      return TextResult.empty();
    }
    TikaOcrRuntime.RuntimePaths runtime = TikaOcrRuntime.resolve();
    if (runtime == null || !runtime.available()) {
      return TextResult.empty();
    }
    Path output = null;
    Path error = null;
    try {
      output = Files.createTempFile("justsearch-ocr-text-", ".txt");
      error = Files.createTempFile("justsearch-ocr-text-", ".err");
      ProcessBuilder builder =
          new ProcessBuilder(
                  runtime.executable().toString(),
                  input.toString(),
                  "stdout",
                  "--psm",
                  "6",
                  "-l",
                  config.tikaLanguage())
              .redirectOutput(output.toFile())
              .redirectError(error.toFile());
      configureRuntimeEnvironment(builder, runtime);
      Process process = builder.start();
      boolean exited =
          process.waitFor(Math.max(1, config.tikaTimeoutSeconds()), TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return TextResult.failed(OcrSkipReason.TIMEOUT);
      }
      if (process.exitValue() != 0) {
        if (log != null) {
          log.debug(
              "Direct Tesseract OCR returned exit {} for {}: {}",
              process.exitValue(),
              input.getFileName(),
              safeRead(error));
        }
        return TextResult.failed(OcrSkipReason.UNKNOWN);
      }
      return readBounded(output, maxChars);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TextResult.failed(OcrSkipReason.TIMEOUT);
    } catch (IOException | RuntimeException e) {
      if (log != null) {
        log.debug("Direct Tesseract OCR unavailable for {}: {}", input.getFileName(), e.getMessage());
      }
      return TextResult.failed(OcrSkipReason.UNKNOWN);
    } finally {
      deleteQuietly(output);
      deleteQuietly(error);
    }
  }

  static Summary extract(Path input, OcrRoutingConfig config, Logger log) {
    if (input == null || config == null || !config.enabled()) {
      return Summary.empty();
    }
    TikaOcrRuntime.RuntimePaths runtime = TikaOcrRuntime.resolve();
    if (runtime == null || !runtime.available()) {
      return Summary.empty();
    }
    Path output = null;
    try {
      output = Files.createTempFile("justsearch-ocr-confidence-", ".tsv");
      ProcessBuilder builder =
          new ProcessBuilder(
                  runtime.executable().toString(),
                  input.toString(),
                  "stdout",
                  "--psm",
                  "6",
                  "-l",
                  config.tikaLanguage(),
                  "tsv")
              .redirectErrorStream(true)
              .redirectOutput(output.toFile());
      configureRuntimeEnvironment(builder, runtime);
      Process process = builder.start();
      boolean exited =
          process.waitFor(Math.max(1, config.tikaTimeoutSeconds()), TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return Summary.empty();
      }
      if (process.exitValue() != 0) {
        return Summary.empty();
      }
      return parseTsv(Files.readString(output, StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Summary.empty();
    } catch (IOException | RuntimeException e) {
      if (log != null) {
        log.debug("OCR confidence extraction unavailable for {}: {}", input.getFileName(), e.getMessage());
      }
      return Summary.empty();
    } finally {
      if (output != null) {
        deleteQuietly(output);
      }
    }
  }

  private static void configureRuntimeEnvironment(
      ProcessBuilder builder, TikaOcrRuntime.RuntimePaths runtime) {
    if (runtime.tessdataDirectory() != null) {
      builder.environment().put("TESSDATA_PREFIX", runtime.tessdataDirectory().toString());
    }
    if (runtime.executableDirectory() != null) {
      builder
          .environment()
          .merge(
              "PATH",
              runtime.executableDirectory().toString(),
              (oldValue, newValue) -> newValue + java.io.File.pathSeparator + oldValue);
    }
  }

  private static String safeRead(Path path) {
    if (path == null) {
      return "";
    }
    try {
      return Files.readString(path, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "";
    }
  }

  private static TextResult readBounded(Path path, int maxChars) throws IOException {
    int cap = Math.max(0, maxChars);
    if (cap == 0) {
      return new TextResult("", Files.size(path) > 0, null);
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      StringBuilder out = new StringBuilder(Math.min(cap, 8192));
      char[] buffer = new char[cap >= 8191 ? 8192 : cap + 1];
      boolean truncated = false;
      while (out.length() <= cap) {
        int read = reader.read(buffer);
        if (read < 0) {
          break;
        }
        int remaining = cap - out.length();
        if (read > remaining) {
          out.append(buffer, 0, Math.max(0, remaining));
          truncated = true;
          break;
        }
        out.append(buffer, 0, read);
      }
      return new TextResult(out.toString().strip(), truncated, null);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup of bounded OCR evidence/temp output.
    }
  }

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

  record TextResult(String text, boolean truncated, OcrSkipReason failureReason) {
    static TextResult empty() {
      return new TextResult("", false, null);
    }

    static TextResult failed(OcrSkipReason reason) {
      return new TextResult("", false, reason == null ? OcrSkipReason.UNKNOWN : reason);
    }
  }
}
