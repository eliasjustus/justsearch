package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import io.github.metarank.lightgbm4j.LGBMBooster;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link LambdaMartTrainer}. */
@DisplayName("LambdaMartTrainer")
class LambdaMartTrainerTest {

  private static boolean lightgbmAvailable;

  static {
    try {
      LGBMBooster.loadNative();
      lightgbmAvailable = true;
    } catch (Exception e) {
      lightgbmAvailable = false;
    }
  }

  @TempDir Path tempDir;
  private LambdaMartTrainer trainer;

  @BeforeEach
  void setUp() {
    trainer = new LambdaMartTrainer();
  }

  @Test
  @DisplayName("train throws IllegalStateException for empty file")
  void train_emptyFile_throwsIllegalStateException() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    Path store = tempDir.resolve("triples.ndjson");
    Files.writeString(store, "\n", StandardCharsets.UTF_8);

    assertThrows(IllegalStateException.class, () -> trainer.train(store));
  }

  @Test
  @DisplayName("train skips legacy lines without bm25 field")
  void train_skipsMissingBm25Lines() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    Path store = tempDir.resolve("triples.ndjson");
    // Legacy line (no bm25 field), should be skipped
    Files.writeString(store,
        """
        {"doc_id":"doc-1","synthetic_query":"test","score":0.85,"timestamp_ms":12345}
        """,
        StandardCharsets.UTF_8);

    // Should throw because no valid groups after skipping legacy lines
    assertThrows(IllegalStateException.class, () -> trainer.train(store));
  }

  @Test
  @DisplayName("train with sufficient data produces a valid booster")
  void train_sufficientData_producesValidBooster() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    Path store = buildSufficientTripleStore();

    LambdaMartTrainer.TrainingResult result = trainer.train(store);

    assertNotNull(result);
    assertNotNull(result.booster());
    assertTrue(result.trainGroups() >= 2, "Expected at least 2 training groups");
    assertTrue(result.ndcg10() >= 0.0, "NDCG@10 must be non-negative");
    assertTrue(result.mrr10() >= 0.0, "MRR@10 must be non-negative");
    result.booster().close();
  }

  @Test
  @DisplayName("train accepts Stage 3A enriched triples while still using V1 sparse/vector features")
  void train_acceptsStage3aEnrichedTriples() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    Path store = tempDir.resolve("triples-stage3a.ndjson");
    StringBuilder sb = new StringBuilder();
    for (int q = 0; q < 5; q++) {
      sb.append(buildStage3aLine("q" + q, "doc-pos-" + q, false, 5.0f + q, 0.8f, q + 1, 900L));
      sb.append(buildStage3aLine("q" + q, "doc-neg-" + q, true, 1.5f + q, 0.1f, q + 2, 2500L));
    }
    Files.writeString(store, sb.toString(), StandardCharsets.UTF_8);

    LambdaMartTrainer.TrainingResult result = trainer.train(store);

    assertNotNull(result.booster());
    result.booster().close();
  }

  @Test
  @DisplayName("train splits groups at 80% boundary")
  void train_splitRespectsBoundary() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    // Build 10 queries — expect ~8 train, ~2 eval
    Path store = buildNQueryStore(10);

    LambdaMartTrainer.TrainingResult result = trainer.train(store);

    assertNotNull(result);
    assertEquals(8, result.trainGroups(), "Expected 8 training groups (80% of 10)");
    assertEquals(2, result.evalGroups(), "Expected 2 eval groups (20% of 10)");
    result.booster().close();
  }

  @Test
  @DisplayName("train throws when all groups lack positives")
  void train_allGroupsNegativeOnly_throwsIllegalStateException() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    Path store = tempDir.resolve("triples.ndjson");
    // 3 queries, all negatives only
    StringBuilder sb = new StringBuilder();
    for (int q = 0; q < 3; q++) {
      for (int d = 0; d < 3; d++) {
        sb.append(buildLine("q" + q, "doc-neg-" + q + "-" + d, true, (float) (d + 1), d + 1));
      }
    }
    Files.writeString(store, sb.toString(), StandardCharsets.UTF_8);

    assertThrows(IllegalStateException.class, () -> trainer.train(store));
  }

  @Test
  @DisplayName("normalizeGroupedFeatures normalizes each group independently (not split-level)")
  void normalizeGroupedFeatures_perGroup_notSplitLevel() {
    // Two groups with different sparse score ranges.
    // Under split-level normalization, group A's values [10, 20, 30] against the combined
    // range [10, 300] would map to roughly [0.000, 0.034, 0.069] — clearly not [0.0, 0.5, 1.0].
    // Per-group normalization must map each group's min→0.0, mid→0.5, max→1.0 independently.
    int n = LambdaMartFeatureSchema.NUM_FEATURES;
    List<float[]> groupA = List.of(featureRow(10.0f), featureRow(20.0f), featureRow(30.0f));
    List<float[]> groupB = List.of(featureRow(100.0f), featureRow(200.0f), featureRow(300.0f));

    float[] matrix = LambdaMartTrainer.normalizeGroupedFeatures(List.of(groupA, groupB));

    assertEquals(6 * n, matrix.length);
    // Group A (rows 0-2): sparse (col 0) must be [0.0, 0.5, 1.0]
    assertEquals(0.0f, matrix[0 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupA row0");
    assertEquals(0.5f, matrix[1 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupA row1");
    assertEquals(1.0f, matrix[2 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupA row2");
    // Group B (rows 3-5): sparse (col 0) must also be [0.0, 0.5, 1.0] (independent range)
    assertEquals(0.0f, matrix[3 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupB row0");
    assertEquals(0.5f, matrix[4 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupB row1");
    assertEquals(1.0f, matrix[5 * n + LambdaMartFeatureSchema.IDX_SPARSE], 1e-6f, "groupB row2");
  }

  // ---- helpers ----

  /**
   * Builds a triple store with 5 queries, each having 1 positive + 3 negatives.
   */
  private Path buildSufficientTripleStore() throws Exception {
    return buildNQueryStore(5);
  }

  private Path buildNQueryStore(int numQueries) throws Exception {
    Path store = tempDir.resolve("triples.ndjson");
    StringBuilder sb = new StringBuilder();
    for (int q = 0; q < numQueries; q++) {
      // Positive
      sb.append(buildLine("q" + q, "doc-pos-" + q, false, 5.0f + q, q));
      // 3 negatives
      for (int n = 0; n < 3; n++) {
        sb.append(buildLine("q" + q, "doc-neg-" + q + "-" + n, true, 1.0f + n, n + 2));
      }
    }
    Files.writeString(store, sb.toString(), StandardCharsets.UTF_8);
    return store;
  }

  private static String buildLine(
      String queryId, String docId, boolean isNegative, float sparse, int rank) {
    return """
        {"query_id":"%s","doc_id":"%s","synthetic_query":"test query","score":0.8,"is_negative":%b,"sparse":%f,"vector":0.0,"qpp_max_idf":8.0,"qpp_avg_ictf":6.0,"qpp_query_scope":0.25,"rank_position":%d,"timestamp_ms":12345}
        """.formatted(queryId, docId, isNegative, sparse, rank);
  }

  private static String buildStage3aLine(
      String queryId,
      String docId,
      boolean isNegative,
      float sparse,
      float vector,
      int rank,
      long parentTokenCount) {
    return """
        {"query_id":"%s","doc_id":"%s","synthetic_query":"test query","score":0.8,"is_negative":%b,"sparse":%f,"vector":%f,"whole_sparse":%f,"whole_vector":%f,"whole_splade":0.3,"whole_cc":0.9,"chunk_sparse":0.2,"chunk_vector":0.1,"chunk_splade":0.05,"chunk_cc":0.4,"parent_token_count":%d,"qpp_max_idf":8.0,"qpp_avg_ictf":6.0,"qpp_query_scope":0.25,"rank_position":%d,"timestamp_ms":12345}
        """
        .formatted(
            queryId,
            docId,
            isNegative,
            sparse,
            vector,
            sparse,
            vector,
            parentTokenCount,
            rank);
  }

  /** Builds a feature vector with the given sparse score and all other features at 0.0f. */
  private static float[] featureRow(float sparse) {
    float[] f = new float[LambdaMartFeatureSchema.NUM_FEATURES];
    f[LambdaMartFeatureSchema.IDX_SPARSE] = sparse;
    return f;
  }
}
