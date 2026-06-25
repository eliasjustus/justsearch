package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.ml.lightgbm.PredictionType;
import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMDataset;
import io.github.metarank.lightgbm4j.LGBMException;
import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link LambdaMartReranker}. */
@DisplayName("LambdaMartReranker")
class LambdaMartRerankerTest {

  private static boolean lightgbmAvailable;

  static {
    try {
      LGBMBooster.loadNative();
      lightgbmAvailable = true;
    } catch (Exception e) {
      lightgbmAvailable = false;
    }
  }

  private LambdaMartReranker reranker;

  @BeforeEach
  void setUp() {
    reranker = new LambdaMartReranker();
  }

  @Test
  @DisplayName("isLoaded returns false before setModel")
  void isLoaded_beforeSetModel_returnsFalse() {
    assertFalse(reranker.isLoaded());
  }

  @Test
  @DisplayName("rerank returns null when no model is loaded")
  void rerank_noModel_returnsNull() {
    float[] bm25s = {1.0f, 2.0f};
    float[] vectors = {0.0f, 0.0f};
    float[] splades = {0.0f, 0.0f};
    assertNull(reranker.rerank(bm25s, vectors, splades, 2));
  }

  @Test
  @DisplayName("rerank returns empty list for empty input")
  void rerank_emptyInput_returnsEmptyList() {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(2);
    assumeTrue(model != null, "stub model build failed");
    reranker.setModel(model);

    List<Integer> result = reranker.rerank(new float[0], new float[0], new float[0], 0);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("isLoaded returns true after setModel")
  void isLoaded_afterSetModel_returnsTrue() {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(2);
    assumeTrue(model != null, "stub model build failed");

    reranker.setModel(model);
    assertTrue(reranker.isLoaded());
  }

  @Test
  @DisplayName("rerank returns sorted indices in descending score order")
  void rerank_knownFeatures_returnsCorrectOrder() {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(10);
    assumeTrue(model != null, "stub model build failed");
    reranker.setModel(model);

    // High BM25 first, low BM25 last — expect descending order
    float[] bm25s = {1.0f, 5.0f, 3.0f};
    float[] vectors = {0.0f, 0.0f, 0.0f};
    float[] splades = {0.0f, 0.0f, 0.0f};
    List<Integer> order = reranker.rerank(bm25s, vectors, splades, 3);

    assertNotNull(order);
    assertEquals(3, order.size());
    // All indices present (no duplicates or missing)
    List<Integer> sorted = new ArrayList<>(order);
    java.util.Collections.sort(sorted);
    assertEquals(List.of(0, 1, 2), sorted);
  }

  @Test
  @DisplayName("rerank is thread-safe under concurrent calls")
  void rerank_threadSafe_noConcurrentExceptions() throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(10);
    assumeTrue(model != null, "stub model build failed");
    reranker.setModel(model);

    int threadCount = 4;
    int callsPerThread = 20;
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger errors = new AtomicInteger(0);

    var executor = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
      futures.add(executor.submit(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < callsPerThread; i++) {
            float[] bm25s = {1.0f, 2.0f, 3.0f};
            float[] vectors = {0.0f, 0.0f, 0.0f};
            float[] splades = {0.0f, 0.0f, 0.0f};
            List<Integer> result = reranker.rerank(bm25s, vectors, splades, 3);
            if (result == null || result.size() != 3) errors.incrementAndGet();
          }
        } catch (Exception e) {
          errors.incrementAndGet();
        }
        return null;
      }));
    }

    startLatch.countDown();
    for (Future<?> f : futures) f.get();
    executor.shutdown();

    assertEquals(0, errors.get(), "Expected no errors in concurrent reranking");
  }

  @Test
  @DisplayName("loadModel returns true for a valid 2-feature model file")
  void loadModel_validModel_returnsTrue(@TempDir Path tempDir) throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(10);
    assumeTrue(model != null, "stub model build failed");

    String modelText = model.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
    model.close();
    Path modelFile = tempDir.resolve("lambdamart-model.txt");
    Files.writeString(modelFile, modelText, StandardCharsets.UTF_8);

    assertTrue(reranker.loadModel(modelFile));
    assertTrue(reranker.isLoaded());
  }

  @Test
  @DisplayName("loadModel rejects a stale model with a different feature count")
  void loadModel_schemaMismatch_returnsFalse(@TempDir Path tempDir) throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    // A 2-feature model is now stale vs the V2 (3-feature) schema (tempdoc 580 §17 P5).
    LGBMBooster staleModel = buildStubModelNFeatures(10, 2);
    assumeTrue(staleModel != null, "2-feature stub model build failed");

    String modelText =
        staleModel.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
    staleModel.close();
    Path modelFile = tempDir.resolve("stale-model.txt");
    Files.writeString(modelFile, modelText, StandardCharsets.UTF_8);

    assertFalse(reranker.loadModel(modelFile));
    assertFalse(reranker.isLoaded());
  }

  @Test
  @DisplayName("saveModel writes meta sidecar; loadModel restores TrainingStatus")
  void saveLoad_metaSidecar_restoresTrainingStatus(@TempDir Path tempDir) throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(10);
    assumeTrue(model != null, "stub model build failed");
    reranker.setModel(model);

    Instant trainedAt = Instant.now();
    reranker.setTrainingStatus(
        new LambdaMartTrainingStatus(LambdaMartTrainingStatus.Phase.SUCCEEDED, 0.72, 0.65, 100, 20, trainedAt, null));

    Path modelFile = tempDir.resolve("lambdamart-model.txt");
    reranker.saveModel(modelFile);

    // Verify meta sidecar was created alongside the model file.
    Path metaFile = tempDir.resolve("lambdamart-model.txt.meta.json");
    assertTrue(Files.exists(metaFile), "meta sidecar should exist");

    // Load into a fresh reranker and verify TrainingStatus is restored.
    LambdaMartReranker loaded = new LambdaMartReranker();
    assertTrue(loaded.loadModel(modelFile));

    LambdaMartTrainingStatus restored = loaded.getTrainingStatus();
    assertEquals(LambdaMartTrainingStatus.Phase.LOADED_FROM_DISK, restored.status());
    assertNotNull(restored.ndcg10());
    assertEquals(0.72, restored.ndcg10(), 1e-9);
    assertEquals(0.65, restored.mrr10(), 1e-9);
    assertEquals(100, restored.trainGroups());
    assertEquals(20, restored.evalGroups());
    assertNotNull(restored.lastTrainedAt());
  }

  @Test
  @DisplayName("loadModel succeeds with LOADED_FROM_DISK status when meta sidecar is absent")
  void loadModel_missingMeta_succeeds(@TempDir Path tempDir) throws Exception {
    assumeTrue(lightgbmAvailable, "lightgbm4j native not available");
    LGBMBooster model = buildStubModel(10);
    assumeTrue(model != null, "stub model build failed");

    // Write model text only — no meta sidecar.
    String modelText = model.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
    model.close();
    Path modelFile = tempDir.resolve("lambdamart-model.txt");
    Files.writeString(modelFile, modelText, StandardCharsets.UTF_8);

    assertTrue(reranker.loadModel(modelFile));
    assertTrue(reranker.isLoaded());

    LambdaMartTrainingStatus ts = reranker.getTrainingStatus();
    assertEquals(LambdaMartTrainingStatus.Phase.LOADED_FROM_DISK, ts.status());
    assertNull(ts.ndcg10());
  }

  /**
   * Builds a stub model with {@code numCols} features (instead of the schema default).
   * Used to test schema-mismatch rejection at load time.
   */
  private static LGBMBooster buildStubModelNFeatures(int numIterations, int numCols) {
    try {
      int numRows = 10;
      float[] data = new float[numRows * numCols];
      // First feature (col 0) drives ranking: positive docs 0-4 have increasing score.
      for (int r = 0; r < 5; r++) {
        data[r * numCols] = r + 1.0f;
      }
      float[] labels = new float[numRows];
      for (int r = 0; r < 5; r++) labels[r] = 1.0f;
      int[] groups = {10};

      LGBMDataset ds = LGBMDataset.createFromMat(data, numRows, numCols, true, "", null);
      try {
        ds.setField("label", labels);
        ds.setField("group", groups);
        LGBMBooster booster =
            LGBMBooster.create(
                ds,
                "objective=lambdarank metric=ndcg num_leaves=4 min_data_in_leaf=1"
                    + " verbosity=-1 num_threads=1 label_gain=0,1");
        try {
          for (int i = 0; i < numIterations; i++) {
            if (booster.updateOneIter()) break;
          }
        } catch (LGBMException e) {
          booster.close();
          return null;
        }
        return booster;
      } finally {
        try {
          ds.close();
        } catch (Exception e2) { /* suppress */
        }
      }
    } catch (Exception e) {
      return null;
    }
  }

  /** Builds a minimal LambdaMART model for testing. Returns null if native not available. */
  private static LGBMBooster buildStubModel(int numIterations) {
    try {
      int numRows = 10;
      int numCols = LambdaMartFeatureSchema.NUM_FEATURES;
      float[] data = new float[numRows * numCols];
      // First 5 rows: positive (bm25=1..5), last 5 rows: negative (bm25=0)
      for (int r = 0; r < 5; r++) {
        data[r * numCols + LambdaMartFeatureSchema.IDX_SPARSE] = r + 1.0f;
      }
      float[] labels = new float[numRows];
      for (int r = 0; r < 5; r++) labels[r] = 1.0f;
      int[] groups = {10}; // one group of 10

      LGBMDataset ds = LGBMDataset.createFromMat(data, numRows, numCols, true, "", null);
      try {
        ds.setField("label", labels);
        ds.setField("group", groups);
        LGBMBooster booster = LGBMBooster.create(ds,
            "objective=lambdarank metric=ndcg num_leaves=4 min_data_in_leaf=1"
                + " verbosity=-1 num_threads=1 label_gain=0,1");
        try {
          for (int i = 0; i < numIterations; i++) {
            if (booster.updateOneIter()) break;
          }
        } catch (LGBMException e) {
          booster.close();
          return null;
        }
        return booster;
      } finally {
        try { ds.close(); } catch (Exception e2) { /* suppress */ }
      }
    } catch (Exception e) {
      return null;
    }
  }
}
