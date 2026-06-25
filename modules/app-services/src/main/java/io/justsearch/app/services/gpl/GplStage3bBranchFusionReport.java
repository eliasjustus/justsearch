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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Stage 3B GPL report for parent-level whole/chunk branch fusion calibration.
 *
 * <p>The report summarizes whole-branch versus chunk-branch coverage by parent-length bucket and
 * runs a small offline grid search over the Stage 3B branch-CC parameters using grouped query MRR.
 */
public final class GplStage3bBranchFusionReport {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private static final List<String> BUCKET_ORDER =
      List.of("le_1024", "1025_2048", "2049_4095", "ge_4096", "unknown");

  private static final double DEFAULT_CHUNK_WEIGHT = 0.50;
  private static final double DEFAULT_WHOLE_WEIGHT = 0.50;
  private static final double DEFAULT_CHUNK_MIN_MULTIPLIER = 0.25;
  private static final double HELD_OUT_PLATEAU_EPSILON = 0.01;
  private static final long FULL_WEIGHT_MAX_TOKENS = 1024L;
  private static final long MAX_WEIGHT_MIN_TOKENS = 4096L;

  private GplStage3bBranchFusionReport() {}

  public record BucketSummary(
      String bucket,
      long tripleCount,
      long wholePresentCount,
      long chunkPresentCount,
      double wholePresentRate,
      double chunkPresentRate,
      double averageEffectiveChunkWeight) {}

  public record SweepSummary(
      double branchCcWeightWhole,
      double branchCcWeightChunk,
      double branchChunkMinWeightMultiplier,
      long queryCount,
      long trainQueryCount,
      long heldOutQueryCount,
      double trainMrr,
      double heldOutMrr,
      double overallMrr,
      boolean stable) {}

  public record SelectedConfig(
      double branchCcWeightWhole,
      double branchCcWeightChunk,
      double branchChunkMinWeightMultiplier,
      boolean stable,
      boolean defaultsChangeRecommended,
      String selectionReason) {}

  public record Report(
      long analyzedTriples,
      long analyzedQueries,
      List<BucketSummary> buckets,
      List<SweepSummary> sweep,
      SelectedConfig selectedConfig,
      String generatedAt) {}

  private record TripleExample(
      String queryId,
      String docId,
      boolean isNegative,
      double branchWhole,
      double branchChunk,
      boolean branchPresentWhole,
      boolean branchPresentChunk,
      long parentTokenCount,
      Double storedEffectiveChunkWeight) {}

  private static final class BucketAccumulator {
    long tripleCount;
    long wholePresentCount;
    long chunkPresentCount;
    double effectiveChunkWeightSum;
    long effectiveChunkWeightCount;

    void add(boolean wholePresent, boolean chunkPresent, Double effectiveChunkWeight) {
      tripleCount++;
      if (wholePresent) {
        wholePresentCount++;
      }
      if (chunkPresent) {
        chunkPresentCount++;
      }
      if (effectiveChunkWeight != null) {
        effectiveChunkWeightSum += effectiveChunkWeight;
        effectiveChunkWeightCount++;
      }
    }

    BucketSummary toSummary(String bucket) {
      double wholeRate = tripleCount > 0 ? wholePresentCount / (double) tripleCount : 0.0;
      double chunkRate = tripleCount > 0 ? chunkPresentCount / (double) tripleCount : 0.0;
      double avgEffectiveChunkWeight =
          effectiveChunkWeightCount > 0
              ? effectiveChunkWeightSum / effectiveChunkWeightCount
              : 0.0;
      return new BucketSummary(
          bucket,
          tripleCount,
          wholePresentCount,
          chunkPresentCount,
          wholeRate,
          chunkRate,
          avgEffectiveChunkWeight);
    }
  }

  /** Returns the default Stage 3B GPL report path next to the triple store. */
  public static Path reportPathFor(Path tripleStoreFile) {
    return tripleStoreFile.resolveSibling("gpl-stage3b-branch-fusion.json");
  }

  /** Summarizes the Stage 3B GPL triple store without writing the result. */
  public static Report analyze(Path tripleStoreFile) throws IOException {
    Map<String, BucketAccumulator> buckets = new LinkedHashMap<>();
    for (String bucket : BUCKET_ORDER) {
      buckets.put(bucket, new BucketAccumulator());
    }

    Map<String, List<TripleExample>> examplesByQuery = new LinkedHashMap<>();
    long analyzedTriples = 0L;

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

          String queryId = node.get("query_id").asText();
          String docId = node.path("doc_id").asText("");
          boolean isNegative = node.path("is_negative").asBoolean(false);
          double branchWhole = doubleValue(node, "branch_whole", doubleValue(node, "whole_cc", 0.0));
          double branchChunk = doubleValue(node, "branch_chunk", doubleValue(node, "chunk_cc", 0.0));
          boolean branchPresentWhole =
              booleanValue(node, "branch_present_whole", nonZero(node, "branch_whole"));
          boolean branchPresentChunk =
              booleanValue(node, "branch_present_chunk", nonZero(node, "branch_chunk"));
          long parentTokenCount = node.has("parent_token_count") ? node.get("parent_token_count").asLong() : -1L;
          Double storedEffectiveChunkWeight =
              node.has("branch_effective_weight_chunk")
                  ? node.get("branch_effective_weight_chunk").asDouble()
                  : null;

          buckets
              .computeIfAbsent(bucketFor(parentTokenCount), ignored -> new BucketAccumulator())
              .add(branchPresentWhole, branchPresentChunk, storedEffectiveChunkWeight);

          examplesByQuery
              .computeIfAbsent(queryId, ignored -> new ArrayList<>())
              .add(
                  new TripleExample(
                      queryId,
                      docId,
                      isNegative,
                      branchWhole,
                      branchChunk,
                      branchPresentWhole,
                      branchPresentChunk,
                      parentTokenCount,
                      storedEffectiveChunkWeight));
          analyzedTriples++;
        }
      }
    }

    List<BucketSummary> summaries = new ArrayList<>(buckets.size());
    for (String bucket : BUCKET_ORDER) {
      summaries.add(buckets.get(bucket).toSummary(bucket));
    }

    List<SweepSummary> sweep = runSweep(examplesByQuery);
    SelectedConfig selected = selectConfig(sweep);
    return new Report(
        analyzedTriples,
        examplesByQuery.size(),
        summaries,
        sweep,
        selected,
        Instant.now().toString());
  }

  /** Writes the Stage 3B GPL report next to the triple store and returns the report path. */
  public static Path write(Path tripleStoreFile) throws IOException {
    Path reportPath = reportPathFor(tripleStoreFile);
    Files.createDirectories(reportPath.getParent());
    MAPPER.writeValue(reportPath.toFile(), analyze(tripleStoreFile));
    return reportPath;
  }

  private static List<SweepSummary> runSweep(Map<String, List<TripleExample>> examplesByQuery) {
    List<SweepSummary> sweep = new ArrayList<>();
    for (double chunkMinMultiplier : List.of(0.25, 0.50)) {
      for (int chunkWeightPercent = 25; chunkWeightPercent <= 75; chunkWeightPercent += 5) {
        double chunkWeight = chunkWeightPercent / 100.0;
        double wholeWeight = Math.max(0.0, 1.0 - chunkWeight);

        double trainSum = 0.0;
        double heldOutSum = 0.0;
        double overallSum = 0.0;
        long trainCount = 0L;
        long heldOutCount = 0L;
        long overallCount = 0L;

        for (var entry : examplesByQuery.entrySet()) {
          double reciprocalRank =
              reciprocalRankForQuery(entry.getValue(), wholeWeight, chunkWeight, chunkMinMultiplier);
          boolean heldOut = isHeldOutQuery(entry.getKey());
          overallSum += reciprocalRank;
          overallCount++;
          if (heldOut) {
            heldOutSum += reciprocalRank;
            heldOutCount++;
          } else {
            trainSum += reciprocalRank;
            trainCount++;
          }
        }

        double trainMrr = trainCount > 0 ? trainSum / trainCount : 0.0;
        double heldOutMrr = heldOutCount > 0 ? heldOutSum / heldOutCount : trainMrr;
        double overallMrr = overallCount > 0 ? overallSum / overallCount : 0.0;
        boolean stable = heldOutCount == 0 || Math.abs(trainMrr - heldOutMrr) <= 0.10;
        sweep.add(
            new SweepSummary(
                wholeWeight,
                chunkWeight,
                chunkMinMultiplier,
                overallCount,
                trainCount,
                heldOutCount,
                trainMrr,
                heldOutMrr,
                overallMrr,
                stable));
      }
    }
    sweep.sort(
        Comparator.comparingDouble(SweepSummary::branchChunkMinWeightMultiplier)
            .thenComparingDouble(SweepSummary::branchCcWeightChunk));
    return sweep;
  }

  private static double reciprocalRankForQuery(
      List<TripleExample> examples,
      double wholeWeight,
      double chunkWeight,
      double chunkMinMultiplier) {
    if (examples == null || examples.isEmpty()) {
      return 0.0;
    }

    ScoreStats wholeStats = scoreStats(examples, true);
    ScoreStats chunkStats = scoreStats(examples, false);

    List<ScoredExample> ranked =
        examples.stream()
            .map(
                example -> {
                  boolean wholePresent = example.branchPresentWhole();
                  boolean chunkPresent = example.branchPresentChunk();
                  double normalizedWhole =
                      wholePresent
                          ? normalizeScore(example.branchWhole(), wholeStats.min, wholeStats.range)
                          : 0.0;
                  double normalizedChunk =
                      chunkPresent
                          ? normalizeScore(example.branchChunk(), chunkStats.min, chunkStats.range)
                          : 0.0;

                  double wholeModifier = 1.0;
                  double chunkModifier =
                      example.parentTokenCount() >= 0
                          ? chunkBranchMultiplier(example.parentTokenCount(), chunkMinMultiplier)
                          : 1.0;

                  double rawWholeWeight = wholeWeight * wholeModifier;
                  double rawChunkWeight = chunkWeight * chunkModifier;
                  double denominator =
                      (wholePresent ? rawWholeWeight : 0.0) + (chunkPresent ? rawChunkWeight : 0.0);
                  double effectiveWholeWeight = denominator > 0.0 ? rawWholeWeight / denominator : 0.0;
                  double effectiveChunkWeight = denominator > 0.0 ? rawChunkWeight / denominator : 0.0;
                  double fusedScore =
                      effectiveWholeWeight * normalizedWhole
                          + effectiveChunkWeight * normalizedChunk;
                  return new ScoredExample(example, fusedScore);
                })
            .sorted(
                Comparator.comparingDouble(ScoredExample::score)
                    .reversed()
                    .thenComparingDouble(scored -> scored.example().branchWhole())
                    .reversed()
                    .thenComparingDouble(scored -> scored.example().branchChunk())
                    .reversed()
                    .thenComparing(scored -> scored.example().docId()))
            .toList();

    for (int i = 0; i < ranked.size(); i++) {
      if (!ranked.get(i).example().isNegative()) {
        return 1.0 / (i + 1);
      }
    }
    return 0.0;
  }

  private static SelectedConfig selectConfig(List<SweepSummary> sweep) {
    if (sweep.isEmpty()) {
      return new SelectedConfig(
          DEFAULT_WHOLE_WEIGHT,
          DEFAULT_CHUNK_WEIGHT,
          DEFAULT_CHUNK_MIN_MULTIPLIER,
          false,
          false,
          "no sweep data available");
    }

    double bestHeldOut =
        sweep.stream().mapToDouble(SweepSummary::heldOutMrr).max().orElse(0.0);
    List<SweepSummary> plateau =
        sweep.stream()
            .filter(SweepSummary::stable)
            .filter(summary -> summary.heldOutMrr() >= bestHeldOut - HELD_OUT_PLATEAU_EPSILON)
            .toList();
    boolean stableSelection = !plateau.isEmpty();
    if (plateau.isEmpty()) {
      plateau =
          sweep.stream()
              .filter(summary -> summary.heldOutMrr() >= bestHeldOut - HELD_OUT_PLATEAU_EPSILON)
              .toList();
    }

    double meanChunkWeight =
        plateau.stream().mapToDouble(SweepSummary::branchCcWeightChunk).average().orElse(DEFAULT_CHUNK_WEIGHT);
    double meanMinMultiplier =
        plateau.stream()
            .mapToDouble(SweepSummary::branchChunkMinWeightMultiplier)
            .average()
            .orElse(DEFAULT_CHUNK_MIN_MULTIPLIER);

    SweepSummary selected =
        plateau.stream()
            .min(
                Comparator.comparingDouble(
                        (SweepSummary summary) ->
                            Math.abs(summary.branchCcWeightChunk() - meanChunkWeight)
                                + Math.abs(
                                    summary.branchChunkMinWeightMultiplier() - meanMinMultiplier))
                    .thenComparing(
                        Comparator.comparingDouble(
                                (SweepSummary summary) ->
                                    Math.abs(summary.branchCcWeightChunk() - DEFAULT_CHUNK_WEIGHT))
                            .thenComparingDouble(
                                summary ->
                                    Math.abs(
                                        summary.branchChunkMinWeightMultiplier()
                                            - DEFAULT_CHUNK_MIN_MULTIPLIER))
                            .thenComparing(Comparator.comparingDouble(SweepSummary::heldOutMrr).reversed())))
            .orElse(sweep.get(0));

    boolean defaultsChangeRecommended =
        stableSelection
            && (Math.abs(selected.branchCcWeightChunk() - DEFAULT_CHUNK_WEIGHT) > 1e-9
                || Math.abs(
                        selected.branchChunkMinWeightMultiplier() - DEFAULT_CHUNK_MIN_MULTIPLIER)
                    > 1e-9);
    String reason =
        stableSelection
            ? "selected stable held-out plateau center"
            : "no stable plateau found; selected nearest held-out plateau center";
    return new SelectedConfig(
        selected.branchCcWeightWhole(),
        selected.branchCcWeightChunk(),
        selected.branchChunkMinWeightMultiplier(),
        stableSelection,
        defaultsChangeRecommended,
        reason);
  }

  private record ScoreStats(double min, double range) {}

  private record ScoredExample(TripleExample example, double score) {}

  private static ScoreStats scoreStats(List<TripleExample> examples, boolean wholeBranch) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    boolean any = false;
    for (TripleExample example : examples) {
      boolean present = wholeBranch ? example.branchPresentWhole() : example.branchPresentChunk();
      if (!present) {
        continue;
      }
      double score = wholeBranch ? example.branchWhole() : example.branchChunk();
      min = Math.min(min, score);
      max = Math.max(max, score);
      any = true;
    }
    if (!any) {
      return new ScoreStats(0.0, 1.0);
    }
    double range = max - min;
    return new ScoreStats(min, range > 0.0 ? range : 1.0);
  }

  private static double normalizeScore(double score, double min, double range) {
    return range > 0.0 ? (score - min) / range : 1.0;
  }

  private static double chunkBranchMultiplier(long parentTokenCount, double minMultiplier) {
    if (parentTokenCount <= FULL_WEIGHT_MAX_TOKENS) {
      return minMultiplier;
    }
    if (parentTokenCount >= MAX_WEIGHT_MIN_TOKENS) {
      return 1.0;
    }
    double span = MAX_WEIGHT_MIN_TOKENS - FULL_WEIGHT_MAX_TOKENS;
    double progress = (parentTokenCount - FULL_WEIGHT_MAX_TOKENS) / span;
    return minMultiplier + (1.0 - minMultiplier) * progress;
  }

  private static boolean nonZero(JsonNode node, String fieldName) {
    return node.has(fieldName) && Math.abs(node.get(fieldName).asDouble()) > 1e-9;
  }

  private static boolean booleanValue(JsonNode node, String fieldName, boolean defaultValue) {
    return node.has(fieldName) ? node.get(fieldName).asBoolean(defaultValue) : defaultValue;
  }

  private static double doubleValue(JsonNode node, String fieldName, double defaultValue) {
    return node.has(fieldName) ? node.get(fieldName).asDouble(defaultValue) : defaultValue;
  }

  private static boolean isHeldOutQuery(String queryId) {
    return Math.floorMod(queryId.hashCode(), 2) == 0;
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
