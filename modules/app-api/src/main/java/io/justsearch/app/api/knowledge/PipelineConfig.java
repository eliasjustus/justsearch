/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

/**
 * Component activation configuration for the search pipeline (256: Phase A).
 *
 * <p>Mirrors {@code io.justsearch.ipc.PipelineConfig} but lives in {@code app-api} so that
 * controller and adapter code never imports proto types directly.
 *
 * <p>Named preset constants ({@link #TEXT}, {@link #VECTOR}, etc.) encode the conservative default
 * for each mode. {@code expandPreset()} in the adapter may override individual flags based on
 * runtime configuration (e.g., cross-encoder availability).
 */
public record PipelineConfig(
    boolean sparseEnabled,
    boolean denseEnabled,
    boolean spladeEnabled,
    String fusionAlgorithm,
    boolean lambdamartEnabled,
    boolean crossEncoderEnabled,
    int crossEncoderWindow,
    boolean expansionEnabled,
    boolean freshnessEnabled) {

  /**
   * Conservative preset for TEXT mode: BM25 retrieval, no dense or SPLADE. Cross-encoder is off in
   * this constant; {@code expandPreset()} enables it based on runtime {@code RerankerConfig}.
   * Expansion enabled (TEXT is sparse-only, benefits from morphological expansion). Freshness off in
   * preset (enabled by expandPreset for interactive search).
   */
  public static final PipelineConfig TEXT =
      new PipelineConfig(true, false, false, "none", true, false, 0, true, false);

  /** Preset for VECTOR mode: dense KNN only. No expansion (dense provides semantic recall). */
  public static final PipelineConfig VECTOR =
      new PipelineConfig(false, true, false, "none", false, false, 0, false, false);

  /** Preset for HYBRID mode: BM25 + dense with RRF fusion. No expansion (dense leg active). */
  public static final PipelineConfig HYBRID =
      new PipelineConfig(true, true, false, "rrf", true, false, 0, false, false);

  /** Preset for SPLADE mode: standalone SPLADE sparse retrieval. Expansion enabled. */
  public static final PipelineConfig SPLADE =
      new PipelineConfig(false, false, true, "none", false, false, 0, true, false);
}
