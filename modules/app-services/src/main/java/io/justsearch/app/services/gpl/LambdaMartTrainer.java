/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.ml.lightgbm.PredictionType;
import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMDataset;
import io.github.metarank.lightgbm4j.LGBMException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trains a LambdaMART model from the GPL triple store.
 *
 * <p>Reads the NDJSON triple store written by {@link GplJobCoordinator}, groups triples by
 * {@code query_id}, performs an 80/20 train/eval split, trains with LightGBM's
 * {@code lambdarank} objective, and evaluates offline.
 *
 * <p>Legacy triples (missing {@code query_id} or {@code bm25} fields) are silently skipped.
 * Groups with no positive example (label=1) are also skipped since lambdarank requires at
 * least one relevant document per query.
 *
 * <p>Minimum dataset: 2 queries with mixed positives/negatives. Smaller datasets throw
 * {@link IllegalStateException}.
 */
public final class LambdaMartTrainer {

  private static final Logger log = LoggerFactory.getLogger(LambdaMartTrainer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** LightGBM training parameters — conservative for small datasets. */
  private static final String LGBM_PARAMS =
      "objective=lambdarank metric=ndcg num_leaves=8 min_data_in_leaf=10"
          + " learning_rate=0.1 verbosity=-1 num_threads=1 label_gain=0,1";

  /** Number of boosting iterations. */
  private static final int NUM_ITERATIONS = 100;

  /** Train/eval split fraction. */
  private static final double TRAIN_FRACTION = 0.8;

  /** k value for NDCG@k and MRR@k evaluation. */
  private static final int K = 10;

  /**
   * Result of a training run.
   *
   * @param booster the trained LGBMBooster (caller owns lifecycle; call {@code close()} when done)
   * @param ndcg10 mean NDCG@10 on the evaluation split
   * @param mrr10 mean MRR@10 on the evaluation split
   * @param trainGroups number of query groups used for training
   * @param evalGroups number of query groups used for evaluation
   */
  public record TrainingResult(
      LGBMBooster booster, double ndcg10, double mrr10, int trainGroups, int evalGroups) {}

  /**
   * One document's worth of data within a query group.
   * Uses a plain class to avoid the ArrayRecordComponent warning — record components should not
   * expose mutable arrays.
   */
  private static final class TripleRow {
    final float[] features;
    final float label;

    TripleRow(float[] features, float label) {
      this.features = features;
      this.label = label;
    }
  }

  /**
   * Reads the NDJSON triple store at {@code storePath}, trains a LambdaMART model, and returns
   * the trained booster with offline evaluation metrics.
   *
   * @param storePath path to the NDJSON triple store file
   * @return training result containing the booster and eval metrics
   * @throws IOException if reading the triple store fails
   * @throws LGBMException if LightGBM training fails
   * @throws IllegalStateException if the dataset is too small or malformed
   */
  public TrainingResult train(Path storePath) throws IOException, LGBMException {
    log.info("LambdaMART trainer: reading triples from {}", storePath);

    // Group triples by query_id, preserving insertion order for deterministic splits.
    Map<String, List<TripleRow>> groups = new LinkedHashMap<>();
    int skipped = 0;
    int loaded = 0;

    try (var reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode node = MAPPER.readTree(line);

        // Skip legacy triples without feature columns.
        // Accept both "sparse" (current) and "bm25" (legacy) keys for backward compatibility.
        if (!node.has("query_id") || (!node.has("sparse") && !node.has("bm25"))) {
          skipped++;
          continue;
        }

        String queryId = node.get("query_id").asText();
        float sparse =
            node.has("sparse")
                ? (float) node.get("sparse").asDouble()
                : (float) node.get("bm25").asDouble();
        float vector = node.has("vector") ? (float) node.get("vector").asDouble() : 0f;
        // Tempdoc 580 §17 P5 — V2 schema: the SPLADE leg, kept distinct from BM25 sparse.
        float splade = node.has("whole_splade") ? (float) node.get("whole_splade").asDouble() : 0f;
        boolean isNegative = node.has("is_negative") && node.get("is_negative").asBoolean();
        float label = isNegative ? 0.0f : 1.0f;

        // QPP fields (qpp_max_idf, qpp_avg_ictf, qpp_query_scope) are present in the triple store
        // but are intentionally excluded from this schema. See LambdaMartFeatureSchema.
        float[] rawFeatures = LambdaMartFeatureSchema.buildRow(sparse, vector, splade);
        groups.computeIfAbsent(queryId, k -> new ArrayList<>()).add(new TripleRow(rawFeatures, label));
        loaded++;
      }
    }

    log.info("LambdaMART trainer: loaded {} triples ({} skipped) across {} query groups",
        loaded, skipped, groups.size());

    // Remove groups with no positive (lambdarank requires ≥1 relevant doc per query).
    groups.entrySet().removeIf(e -> e.getValue().stream().noneMatch(r -> r.label > 0));

    if (groups.size() < 2) {
      throw new IllegalStateException(
          "LambdaMART training requires at least 2 query groups with positives; got "
              + groups.size() + " (loaded=" + loaded + " skipped=" + skipped + ")");
    }

    // 80/20 split by query_id boundary.
    List<String> queryIds = new ArrayList<>(groups.keySet());
    int splitIndex = Math.max(1, (int) Math.round(queryIds.size() * TRAIN_FRACTION));
    List<String> trainIds = queryIds.subList(0, splitIndex);
    List<String> evalIds = queryIds.subList(splitIndex, queryIds.size());

    log.info("LambdaMART trainer: train={} groups, eval={} groups", trainIds.size(), evalIds.size());

    // Build training dataset.
    LGBMDataset trainDs = buildDataset(groups, trainIds, null);
    LGBMBooster booster = null;
    try {
      booster = LGBMBooster.create(trainDs, LGBM_PARAMS);
      for (int i = 0; i < NUM_ITERATIONS; i++) {
        boolean finished = booster.updateOneIter();
        if (finished) {
          log.debug("LambdaMART: early stop at iteration {}", i + 1);
          break;
        }
      }
      log.info("LambdaMART training complete ({} iterations)", NUM_ITERATIONS);

      // Offline evaluation.
      double ndcg10 = 0.0;
      double mrr10 = 0.0;
      int evalGroupCount = 0;

      if (!evalIds.isEmpty()) {
        LGBMDataset evalDs = buildDataset(groups, evalIds, trainDs);
        try {
          double[] metrics = evaluateOffline(booster, groups, evalIds);
          ndcg10 = metrics[0];
          mrr10 = metrics[1];
          evalGroupCount = evalIds.size();
        } finally {
          try { evalDs.close(); } catch (Exception e) { log.debug("Failed to close eval dataset", e); }
        }
      }

      log.info("LambdaMART eval: NDCG@{}={} MRR@{}={} groups={}",
          K, ndcg10, K, mrr10, evalGroupCount);

      LGBMBooster result = booster;
      booster = null; // ownership transferred to caller via TrainingResult
      return new TrainingResult(result, ndcg10, mrr10, trainIds.size(), evalGroupCount);

    } finally {
      if (booster != null) {
        try { booster.close(); } catch (Exception e) { log.debug("Failed to close booster", e); }
      }
      try { trainDs.close(); } catch (Exception e) { log.debug("Failed to close train dataset", e); }
    }
  }

  /**
   * Builds a LGBMDataset from the given query groups. Row-major, min-max normalized per feature
   * column <em>per query group</em> — matching inference normalization in
   * {@link LambdaMartReranker}.
   */
  private static LGBMDataset buildDataset(
      Map<String, List<TripleRow>> allGroups,
      List<String> queryIds,
      LGBMDataset reference)
      throws LGBMException {

    int[] groupSizes = new int[queryIds.size()];
    List<List<float[]>> groupedFeatures = new ArrayList<>(queryIds.size());
    int numRows = 0;

    for (int g = 0; g < queryIds.size(); g++) {
      List<TripleRow> groupRows = allGroups.get(queryIds.get(g));
      groupSizes[g] = groupRows.size();
      numRows += groupRows.size();
      groupedFeatures.add(groupRows.stream().map(r -> r.features).toList());
    }

    float[] matrix = normalizeGroupedFeatures(groupedFeatures);
    float[] labels = new float[numRows];
    int labelIdx = 0;
    for (String queryId : queryIds) {
      for (TripleRow row : allGroups.get(queryId)) {
        labels[labelIdx++] = row.label;
      }
    }

    LGBMDataset ds = LGBMDataset.createFromMat(
        matrix, numRows, LambdaMartFeatureSchema.NUM_FEATURES, true, "", reference);
    ds.setField("label", labels);
    ds.setField("group", groupSizes);
    return ds;
  }

  /**
   * Normalizes a grouped feature matrix per group. Each inner list is one query group; each
   * {@code float[]} is one document's raw feature vector. Returns a flat row-major matrix with
   * per-group min-max normalization applied — matching the inference path in
   * {@link LambdaMartReranker}.
   *
   * <p>Package-private for testing.
   */
  static float[] normalizeGroupedFeatures(List<List<float[]>> groups) {
    int numFeatures = LambdaMartFeatureSchema.NUM_FEATURES;
    int totalRows = groups.stream().mapToInt(List::size).sum();
    float[] matrix = new float[totalRows * numFeatures];
    int rowOffset = 0;

    for (List<float[]> group : groups) {
      float[] colMin = new float[numFeatures];
      float[] colMax = new float[numFeatures];
      Arrays.fill(colMin, Float.MAX_VALUE);
      Arrays.fill(colMax, Float.MIN_VALUE);
      for (float[] features : group) {
        for (int c = 0; c < numFeatures; c++) {
          if (features[c] < colMin[c]) colMin[c] = features[c];
          if (features[c] > colMax[c]) colMax[c] = features[c];
        }
      }
      for (float[] features : group) {
        for (int c = 0; c < numFeatures; c++) {
          matrix[rowOffset * numFeatures + c] =
              LambdaMartFeatureSchema.normalize(features[c], colMin[c], colMax[c]);
        }
        rowOffset++;
      }
    }

    return matrix;
  }

  /**
   * Evaluates the booster offline using the raw (un-normalized) feature values from the eval
   * split. Normalization is re-applied per result set at inference time.
   *
   * @return double[] { meanNdcg10, meanMrr10 }
   */
  private static double[] evaluateOffline(
      LGBMBooster booster,
      Map<String, List<TripleRow>> allGroups,
      List<String> evalIds)
      throws LGBMException {

    double totalNdcg = 0.0;
    double totalMrr = 0.0;
    int count = 0;

    for (String queryId : evalIds) {
      List<TripleRow> groupRows = allGroups.get(queryId);
      if (groupRows == null || groupRows.isEmpty()) continue;

      int n = groupRows.size();

      // Normalize this group's features using the shared helper (per-group min-max,
      // matching the training normalization applied in buildDataset()).
      List<float[]> groupFeatures = new ArrayList<>(n);
      float[] labels = new float[n];
      for (int r = 0; r < n; r++) {
        groupFeatures.add(groupRows.get(r).features);
        labels[r] = groupRows.get(r).label;
      }
      float[] matrix = normalizeGroupedFeatures(List.of(groupFeatures));

      // Predict and sort by descending score.
      double[] rawScores = booster.predictForMat(
          matrix, n, LambdaMartFeatureSchema.NUM_FEATURES, true,
          PredictionType.C_API_PREDICT_RAW_SCORE);

      Integer[] indices = new Integer[n];
      for (int i = 0; i < n; i++) indices[i] = i;
      Arrays.sort(indices, (a, b) -> Double.compare(rawScores[b], rawScores[a]));

      // Compute NDCG@K.
      int numRelevant = 0;
      for (float l : labels) if (l > 0) numRelevant++;

      double dcg = 0.0;
      for (int i = 0; i < Math.min(K, n); i++) {
        dcg += labels[indices[i]] / (Math.log(i + 2) / Math.log(2));
      }
      double idcg = 0.0;
      for (int i = 0; i < Math.min(K, numRelevant); i++) {
        idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
      }
      double ndcg = idcg > 0 ? dcg / idcg : 0.0;

      // Compute MRR@K.
      double mrr = 0.0;
      for (int i = 0; i < Math.min(K, n); i++) {
        if (labels[indices[i]] > 0) {
          mrr = 1.0 / (i + 1);
          break;
        }
      }

      totalNdcg += ndcg;
      totalMrr += mrr;
      count++;
    }

    if (count == 0) return new double[] {0.0, 0.0};
    return new double[] {totalNdcg / count, totalMrr / count};
  }
}
