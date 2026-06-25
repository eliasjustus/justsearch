/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a small Stage 3A GPL report summarizing whole-doc vs chunk-branch contribution patterns
 * across parent-length buckets.
 */
public final class GplStage3aAnalysisReport {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private static final List<String> BUCKET_ORDER =
      List.of("le_1024", "1025_2048", "2049_4095", "ge_4096", "unknown");

  private GplStage3aAnalysisReport() {}

  public record BucketSummary(
      String bucket,
      long tripleCount,
      long wholeOnlyCount,
      long chunkOnlyCount,
      long bothCount,
      long neitherCount,
      double wholeContributionRate,
      double chunkContributionRate) {}

  public record Report(
      long analyzedTriples,
      long wholeOnlyCount,
      long chunkOnlyCount,
      long bothCount,
      long neitherCount,
      List<BucketSummary> buckets,
      String generatedAt) {}

  private static final class BucketAccumulator {
    long tripleCount;
    long wholeOnlyCount;
    long chunkOnlyCount;
    long bothCount;
    long neitherCount;

    void add(boolean wholeContributed, boolean chunkContributed) {
      tripleCount++;
      if (wholeContributed && chunkContributed) {
        bothCount++;
      } else if (wholeContributed) {
        wholeOnlyCount++;
      } else if (chunkContributed) {
        chunkOnlyCount++;
      } else {
        neitherCount++;
      }
    }

    BucketSummary toSummary(String bucket) {
      double wholeRate =
          tripleCount > 0 ? (wholeOnlyCount + bothCount) / (double) tripleCount : 0.0;
      double chunkRate =
          tripleCount > 0 ? (chunkOnlyCount + bothCount) / (double) tripleCount : 0.0;
      return new BucketSummary(
          bucket,
          tripleCount,
          wholeOnlyCount,
          chunkOnlyCount,
          bothCount,
          neitherCount,
          wholeRate,
          chunkRate);
    }
  }

  /** Returns the default Stage 3A GPL analysis report path next to the triple store. */
  public static Path reportPathFor(Path tripleStoreFile) {
    return tripleStoreFile.resolveSibling("gpl-stage3a-analysis.json");
  }

  /** Summarizes the Stage 3A GPL triple store without writing the result. */
  public static Report analyze(Path tripleStoreFile) throws IOException {
    Map<String, BucketAccumulator> buckets = new LinkedHashMap<>();
    for (String bucket : BUCKET_ORDER) {
      buckets.put(bucket, new BucketAccumulator());
    }

    if (Files.exists(tripleStoreFile)) {
      try (var reader = Files.newBufferedReader(tripleStoreFile, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          JsonNode node = MAPPER.readTree(line);
          if (!node.has("query_id")) {
            continue;
          }
          boolean wholeContributed =
              nonZero(node, "whole_sparse")
                  || nonZero(node, "whole_vector")
                  || nonZero(node, "whole_splade")
                  || nonZero(node, "whole_cc");
          boolean chunkContributed =
              nonZero(node, "chunk_sparse")
                  || nonZero(node, "chunk_vector")
                  || nonZero(node, "chunk_splade")
                  || nonZero(node, "chunk_cc");

          long parentTokenCount = node.has("parent_token_count") ? node.get("parent_token_count").asLong() : -1L;
          buckets.computeIfAbsent(bucketFor(parentTokenCount), ignored -> new BucketAccumulator())
              .add(wholeContributed, chunkContributed);
        }
      }
    }

    List<BucketSummary> summaries = new ArrayList<>(buckets.size());
    long analyzedTriples = 0L;
    long wholeOnlyCount = 0L;
    long chunkOnlyCount = 0L;
    long bothCount = 0L;
    long neitherCount = 0L;
    for (var entry : buckets.entrySet()) {
      BucketSummary summary = entry.getValue().toSummary(entry.getKey());
      summaries.add(summary);
      analyzedTriples += summary.tripleCount();
      wholeOnlyCount += summary.wholeOnlyCount();
      chunkOnlyCount += summary.chunkOnlyCount();
      bothCount += summary.bothCount();
      neitherCount += summary.neitherCount();
    }

    return new Report(
        analyzedTriples,
        wholeOnlyCount,
        chunkOnlyCount,
        bothCount,
        neitherCount,
        summaries,
        Instant.now().toString());
  }

  /** Writes the Stage 3A GPL report next to the triple store and returns the report path. */
  public static Path write(Path tripleStoreFile) throws IOException {
    Path reportPath = reportPathFor(tripleStoreFile);
    Files.createDirectories(reportPath.getParent());
    MAPPER.writeValue(reportPath.toFile(), analyze(tripleStoreFile));
    return reportPath;
  }

  private static boolean nonZero(JsonNode node, String fieldName) {
    return node.has(fieldName) && Math.abs(node.get(fieldName).asDouble()) > 1e-9;
  }

  private static String bucketFor(long parentTokenCount) {
    if (parentTokenCount < 0) {
      return "unknown";
    }
    if (parentTokenCount <= 1024L) {
      return "le_1024";
    }
    if (parentTokenCount <= 2048L) {
      return "1025_2048";
    }
    if (parentTokenCount <= 4095L) {
      return "2049_4095";
    }
    return "ge_4096";
  }
}
