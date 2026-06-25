/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

/**
 * Defines the LambdaMART feature schema. <strong>V2 (tempdoc 580 §17 P5):</strong> 3 features —
 * the three retrieval legs kept <em>distinct</em>: {@code [sparse(BM25), vector(dense), splade]}.
 *
 * <p>This fixes the §13.7 finding that V1 collapsed BM25 and SPLADE into one "sparse" slot, so the
 * learned reranker saw only two of the three signals fusion combines and was informationally poorer
 * than the fusion it post-processes. Un-collapsing the legs gives the model the full leg set; the
 * snapshot/triple already carry all three (SPARSE_RETRIEVAL / DENSE_RETRIEVAL / SPLADE_RETRIEVAL
 * stage scores). Further non-fusion features (parent token count, rank) are the next extension — the
 * schema is now openly extensible — but the load-bearing fix this release ships is REAL labels
 * (the disposition⋈snapshot label projection, §17.5), not the feature count.
 *
 * <p>Column order is a stable contract — do not reorder without retraining all models. A model
 * trained under the old 2-feature schema is rejected at load by the feature-count check (intended:
 * V2 requires a retrain on real-feedback triples).
 *
 * <p>QPP signals (MaxIDF, AvgICTF, QueryScope) remain intentionally excluded — they are constant
 * within a result set (no intra-query gradient) and belong at the query-gating layer, not here.
 */
public final class LambdaMartFeatureSchema {

  private LambdaMartFeatureSchema() {}

  /** Total number of features in this schema (V2: sparse + vector + splade). */
  public static final int NUM_FEATURES = 3;

  /** Column index for the BM25/lexical sparse retrieval score. */
  public static final int IDX_SPARSE = 0;

  /** Column index for the vector (dense) score. Always 0.0 in TEXT mode. */
  public static final int IDX_VECTOR = 1;

  /** Column index for the learned-sparse (SPLADE) retrieval score. */
  public static final int IDX_SPLADE = 2;

  /**
   * Ordered feature names corresponding to the column indices above.
   *
   * <p>These names are for documentation, debugging, and future use. When training embeds feature
   * names in the LightGBM dataset (V2), {@link io.github.metarank.lightgbm4j.LGBMBooster
   * #getFeatureNames()} will return these values and enable load-time name validation.
   *
   * <p>Current models do not embed feature names (trained via {@code createFromMat()} without
   * explicit name assignment), so {@code getFeatureNames()} returns {@code ["Column_0",
   * "Column_1"]}. This constant is forward-looking for V2 training.
   */
  public static final java.util.List<String> FEATURE_NAMES =
      java.util.List.of("sparse", "vector", "splade");

  /**
   * Min-max normalization with NaN guard.
   *
   * <p>When {@code max - min < 1e-9f} (constant feature), returns {@code 0.0f} instead of NaN.
   *
   * @param value the raw value to normalize
   * @param min the observed minimum across the result set
   * @param max the observed maximum across the result set
   * @return normalized value in [0, 1], or 0.0f for constant features
   */
  public static float normalize(float value, float min, float max) {
    return (max - min) < 1e-9f ? 0.0f : (value - min) / (max - min);
  }

  /**
   * Builds a feature row from pre-normalized values.
   *
   * @param sparseNorm normalized BM25/lexical sparse score
   * @param vectorNorm normalized vector (dense) score
   * @param spladeNorm normalized learned-sparse (SPLADE) score
   * @return float array of length {@link #NUM_FEATURES}
   */
  public static float[] buildRow(float sparseNorm, float vectorNorm, float spladeNorm) {
    float[] row = new float[NUM_FEATURES];
    row[IDX_SPARSE] = sparseNorm;
    row[IDX_VECTOR] = vectorNorm;
    row[IDX_SPLADE] = spladeNorm;
    return row;
  }
}
