/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.ml.lightgbm.PredictionType;
import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMException;
import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import io.justsearch.app.api.gpl.RerankerService;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a trained LambdaMART model to rerank search results using sparse and vector retrieval
 * scores (V1 feature schema — 2 features).
 *
 * <p>The model is loaded via {@link #setModel} after training completes. Inference is safe to call
 * from multiple threads concurrently because each call fetches a local reference to the current
 * booster via {@link AtomicReference} and performs an independent JNI prediction call.
 *
 * <p>When no model is loaded, {@link #rerank} returns {@code null}, which signals the caller
 * to fall back to the next reranking strategy (e.g. cross-encoder).
 *
 * <p>Feature normalization is per-result-set (min-max), so each search call is independently
 * normalized. This decouples the trained model from the absolute score scale of whichever sparse
 * backend (SPLADE or BM25) is active, which is important because the two backends operate on
 * materially different score magnitudes.
 */
public final class LambdaMartReranker implements RerankerService {

  private static final Logger log = LoggerFactory.getLogger(LambdaMartReranker.class);
  private static final ObjectMapper META_MAPPER = new ObjectMapper();

  private final AtomicReference<LGBMBooster> boosterRef = new AtomicReference<>(null);
  private final AtomicReference<LambdaMartTrainingStatus> trainingStatus =
      new AtomicReference<>(new LambdaMartTrainingStatus(LambdaMartTrainingStatus.Phase.PENDING, null, null, null, null, null, null));

  /** Returns the current training status snapshot. */
  @Override
  public LambdaMartTrainingStatus getTrainingStatus() {
    return trainingStatus.get();
  }

  /** Updates the training status (called from the training thread). */
  public void setTrainingStatus(LambdaMartTrainingStatus status) {
    trainingStatus.set(status);
  }

  /**
   * Atomically replaces the current model with {@code newBooster}.
   *
   * <p>The previous booster is closed after replacement. This is safe because:
   * <ol>
   *   <li>Inference threads fetch the booster reference before calling into LightGBM.</li>
   *   <li>The old booster reference is only closed after it has been replaced in the
   *       AtomicReference, so no new inference calls will start on it.</li>
   *   <li>Existing in-flight calls on the old booster complete before GC can collect it
   *       (the local variable in {@link #rerank} keeps it alive).</li>
   * </ol>
   *
   * <p>Note: calling {@code close()} on an LGBMBooster that still has in-flight JNI calls is
   * technically a race. Since model swaps happen at most once (post-training), and the window
   * between atomic replacement and close is tiny, this is accepted for V1.
   *
   * @param newBooster the newly trained booster; must not be {@code null}
   */
  public void setModel(LGBMBooster newBooster) {
    LGBMBooster old = boosterRef.getAndSet(newBooster);
    if (old != null) {
      try {
        old.close();
      } catch (Exception e) {
        log.warn("LambdaMART: failed to close previous booster (non-fatal)", e);
      }
    }
    log.info("LambdaMART reranker: new model loaded");
  }

  /**
   * Returns {@code true} if a model is currently loaded.
   */
  @Override
  public boolean isLoaded() {
    return boosterRef.get() != null;
  }

  /**
   * Closes the currently loaded booster if any. Called at application shutdown.
   */
  public void close() {
    LGBMBooster booster = boosterRef.getAndSet(null);
    if (booster != null) {
      try {
        booster.close();
      } catch (Exception e) {
        log.warn("LambdaMART: failed to close booster at shutdown", e);
      }
    }
  }

  /**
   * Persists the current model to disk using atomic rename. No-op if no model is loaded.
   * Best-effort: logs warning on failure (non-fatal).
   *
   * @param modelFile target file path (e.g. {@code dataDir.resolve("lambdamart-model.txt")})
   */
  public void saveModel(Path modelFile) {
    LGBMBooster booster = boosterRef.get();
    if (booster == null) return;
    try {
      String modelText =
          booster.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
      Files.createDirectories(modelFile.getParent());
      Path tmp = modelFile.resolveSibling(modelFile.getFileName() + ".tmp");
      Files.writeString(tmp, modelText, StandardCharsets.UTF_8);
      try {
        Files.move(
            tmp, modelFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, modelFile, StandardCopyOption.REPLACE_EXISTING);
      }
      // Write meta sidecar alongside the model (best-effort, non-fatal).
      LambdaMartTrainingStatus ts = trainingStatus.get();
      if (ts != null) {
        try {
          Path metaFile = modelFile.resolveSibling(modelFile.getFileName() + ".meta.json");
          Path metaTmp = metaFile.resolveSibling(metaFile.getFileName() + ".tmp");
          Files.writeString(metaTmp, buildMetaJson(ts), StandardCharsets.UTF_8);
          try {
            Files.move(
                metaTmp, metaFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
          } catch (AtomicMoveNotSupportedException e2) {
            Files.move(metaTmp, metaFile, StandardCopyOption.REPLACE_EXISTING);
          }
        } catch (Exception e2) {
          log.warn("LambdaMART: failed to write meta sidecar (non-fatal): {}", e2.getMessage());
        }
      }
      log.info("LambdaMART model saved to {}", modelFile);
    } catch (Exception e) {
      log.warn("Failed to save LambdaMART model to {}", modelFile, e);
    }
  }

  private static String buildMetaJson(LambdaMartTrainingStatus s) {
    return "{"
        + "\"schemaVersion\":1,"
        + "\"numFeatures\":"
        + LambdaMartFeatureSchema.NUM_FEATURES
        + ","
        + "\"ndcg10\":"
        + (s.ndcg10() == null ? "null" : s.ndcg10())
        + ","
        + "\"mrr10\":"
        + (s.mrr10() == null ? "null" : s.mrr10())
        + ","
        + "\"trainGroups\":"
        + (s.trainGroups() == null ? "null" : s.trainGroups())
        + ","
        + "\"evalGroups\":"
        + (s.evalGroups() == null ? "null" : s.evalGroups())
        + ","
        + "\"lastTrainedAt\":"
        + (s.lastTrainedAt() == null ? "null" : "\"" + s.lastTrainedAt() + "\"")
        + "}";
  }

  /**
   * Loads a model from disk. Returns {@code true} if successful, {@code false} if the file does
   * not exist or cannot be parsed.
   *
   * @param modelFile model file path
   * @return true if a model was loaded
   */
  public boolean loadModel(Path modelFile) {
    if (!Files.isRegularFile(modelFile)) return false;
    try {
      String modelText = Files.readString(modelFile, StandardCharsets.UTF_8);
      LGBMBooster booster = LGBMBooster.loadModelFromString(modelText);
      // Validate feature count against the current schema before accepting the model.
      // Protects against stale models from a previous feature schema version.
      int modelFeatures;
      try {
        modelFeatures = booster.getNumFeature();
      } catch (LGBMException e) {
        booster.close();
        log.warn("LambdaMART: cannot read feature count from {}: {}", modelFile, e.getMessage());
        log.debug("LambdaMART: cannot read feature count (stack trace)", e);
        return false;
      }
      if (modelFeatures != LambdaMartFeatureSchema.NUM_FEATURES) {
        booster.close();
        log.warn(
            "LambdaMART: model at {} has {} feature(s) but schema expects {}; "
                + "rejecting stale model — retrain required",
            modelFile, modelFeatures, LambdaMartFeatureSchema.NUM_FEATURES);
        return false;
      }
      setModel(booster);
      // Restore LambdaMartTrainingStatus from meta sidecar if present.
      Path metaFile = modelFile.resolveSibling(modelFile.getFileName() + ".meta.json");
      LambdaMartTrainingStatus restored = loadMetaJson(metaFile);
      if (restored == null) {
        restored = new LambdaMartTrainingStatus(LambdaMartTrainingStatus.Phase.LOADED_FROM_DISK, null, null, null, null, null, null);
      }
      setTrainingStatus(restored);
      log.info("LambdaMART model loaded from {}", modelFile);
      return true;
    } catch (Exception e) {
      log.warn("Failed to load LambdaMART model from {}", modelFile, e);
      return false;
    }
  }

  private LambdaMartTrainingStatus loadMetaJson(Path metaFile) {
    if (!Files.isRegularFile(metaFile)) return null;
    try {
      String json = Files.readString(metaFile, StandardCharsets.UTF_8);
      JsonNode node = META_MAPPER.readTree(json);
      Double ndcg10 =
          node.has("ndcg10") && !node.get("ndcg10").isNull()
              ? node.get("ndcg10").asDouble()
              : null;
      Double mrr10 =
          node.has("mrr10") && !node.get("mrr10").isNull()
              ? node.get("mrr10").asDouble()
              : null;
      Integer trainGroups =
          node.has("trainGroups") && !node.get("trainGroups").isNull()
              ? node.get("trainGroups").asInt()
              : null;
      Integer evalGroups =
          node.has("evalGroups") && !node.get("evalGroups").isNull()
              ? node.get("evalGroups").asInt()
              : null;
      Instant lastTrainedAt = null;
      if (node.has("lastTrainedAt") && !node.get("lastTrainedAt").isNull()) {
        lastTrainedAt = Instant.parse(node.get("lastTrainedAt").asText());
      }
      return new LambdaMartTrainingStatus(
          LambdaMartTrainingStatus.Phase.LOADED_FROM_DISK, ndcg10, mrr10, trainGroups, evalGroups, lastTrainedAt, null);
    } catch (Exception e) {
      log.warn("LambdaMART: failed to read meta sidecar at {}: {}", metaFile, e.getMessage());
      log.debug("LambdaMART: failed to read meta sidecar (stack trace)", e);
      return null;
    }
  }

  /**
   * Scores and returns a reordering of {@code n} results using the LambdaMART model.
   *
   * <p>Features are min-max normalized per this result set (per-result-set normalization).
   * This decouples the model from the absolute score scale, which is necessary for robustness
   * when the sparse backend switches between SPLADE and BM25 (materially different magnitudes).
   *
   * @param sparseScores BM25/lexical sparse scores for each result (length n)
   * @param vectors vector/dense scores for each result (length n; 0.0 for TEXT mode)
   * @param spladeScores learned-sparse (SPLADE) scores for each result (length n; tempdoc 580 §17 P5
   *     V2 — the third leg, kept distinct from BM25 sparse so the model sees all three fusion legs)
   * @param n number of results (must equal each array's length)
   * @return list of indices into the result arrays in descending relevance order, or {@code null}
   *     if no model is loaded or scoring fails
   */
  @Override
  public List<Integer> rerank(float[] sparseScores, float[] vectors, float[] spladeScores, int n) {
    LGBMBooster booster = boosterRef.get();
    if (booster == null) return null;
    if (n == 0) return List.of();

    // Compute per-result-set min/max for each leg.
    float sparseMin = sparseScores[0], sparseMax = sparseScores[0];
    float vecMin = vectors[0], vecMax = vectors[0];
    float spladeMin = spladeScores[0], spladeMax = spladeScores[0];
    for (int i = 1; i < n; i++) {
      if (sparseScores[i] < sparseMin) sparseMin = sparseScores[i];
      if (sparseScores[i] > sparseMax) sparseMax = sparseScores[i];
      if (vectors[i] < vecMin) vecMin = vectors[i];
      if (vectors[i] > vecMax) vecMax = vectors[i];
      if (spladeScores[i] < spladeMin) spladeMin = spladeScores[i];
      if (spladeScores[i] > spladeMax) spladeMax = spladeScores[i];
    }

    // Build flat row-major normalized feature matrix.
    float[] matrix = new float[n * LambdaMartFeatureSchema.NUM_FEATURES];
    for (int i = 0; i < n; i++) {
      float[] row = LambdaMartFeatureSchema.buildRow(
          LambdaMartFeatureSchema.normalize(sparseScores[i], sparseMin, sparseMax),
          LambdaMartFeatureSchema.normalize(vectors[i], vecMin, vecMax),
          LambdaMartFeatureSchema.normalize(spladeScores[i], spladeMin, spladeMax));
      System.arraycopy(row, 0, matrix, i * LambdaMartFeatureSchema.NUM_FEATURES, LambdaMartFeatureSchema.NUM_FEATURES);
    }

    double[] scores;
    try {
      scores = booster.predictForMat(
          matrix, n, LambdaMartFeatureSchema.NUM_FEATURES, true,
          PredictionType.C_API_PREDICT_RAW_SCORE);
    } catch (Exception e) {
      log.warn("LambdaMART: prediction failed (non-fatal); falling back to original order", e);
      return null;
    }

    // Build sorted index list (descending score).
    Integer[] indices = new Integer[n];
    for (int i = 0; i < n; i++) indices[i] = i;
    Arrays.sort(indices, (a, b) -> Double.compare(scores[b], scores[a]));

    List<Integer> result = new ArrayList<>(n);
    for (int idx : indices) result.add(idx);
    return result;
  }
}
