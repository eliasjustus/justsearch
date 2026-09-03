package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the tempdoc-702 dense-score calibration constants in {@link HybridSearchOps}.
 *
 * <p>The dense KNN field is indexed with Lucene's default EUCLIDEAN similarity ({@code
 * FieldMapper}'s 2-arg {@code KnnFloatVectorField} constructor), not COSINE, but vectors are
 * L2-normalized so {@code score_euc = 1/(3-2*cos)} and ranking is identical either way. These
 * constants were originally authored in cosine-score terms and mis-set for the actual EUCLIDEAN
 * score space; this test guards against a future "tidy the magic number" edit silently
 * reintroducing the miscalibration.
 */
class CalibrationConstantsTest {

  @Test
  void arbitrationDenseConfidentMinMatchesEuclideanConversionOfIntendedCosZero() {
    // Intended: cos >= 0 ("not anti-correlated"). score_euc = 1/(3-2*cos) at cos=0 is 1/3.
    assertEquals(
        1.0 / 3.0,
        HybridSearchOps.ARBITRATION_DENSE_CONFIDENT_MIN,
        1e-9,
        "ARBITRATION_DENSE_CONFIDENT_MIN must equal 1/(3-2*cos) at the intended cos=0 gate"
            + " ('not anti-correlated'), i.e. 1.0/3.0 (tempdoc 702). Do not replace with a"
            + " re-derived or eyeballed value.");
  }

  @Test
  void defaultVectorLowSignalThresholdMatchesEuclideanConversionOfIntendedCosinePoint40() {
    // Intended: cosine-score (1+cos)/2 == 0.40 <=> cos == -0.2.
    // score_euc = 1/(3-2*cos) at cos=-0.2 is 1/3.4 ~= 0.294.
    assertEquals(
        0.294,
        HybridSearchOps.DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD,
        1e-3,
        "DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD must equal 1/(3-2*cos) at the intended cosine-score"
            + " 0.40 gate (cos=-0.2), i.e. 1/3.4 ~= 0.294 (tempdoc 702). Do not replace with a"
            + " re-derived or eyeballed value.");
  }

  // ---- Fallback-default drift pins ----
  //
  // HybridSearchOps's DEFAULT_* constants are fallback values used only when
  // session.resolvedConfig is null (the test-mode RuntimeSession(IndexSchema) constructor path).
  // Each one claims (HybridSearchOps.java header comment) to mirror a ResolvedConfigBuilder
  // documented default. Pin each pair here so a future edit to either side is caught instead of
  // silently drifting (this test previously covered only 2 of the 8 constants; the other 6 were
  // unpinned, which is how DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL and
  // DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL drifted from the builder undetected).

  @Test
  void defaultCandidateLimitMaxMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveInt("index.hybrid.candidate_limit_max", 100)
    assertEquals(100, HybridSearchOps.DEFAULT_CANDIDATE_LIMIT_MAX);
  }

  @Test
  void defaultTextCandidateMultiplierMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveInt("index.hybrid.text_candidate_multiplier", 10)
    assertEquals(10, HybridSearchOps.DEFAULT_TEXT_CANDIDATE_MULTIPLIER);
  }

  @Test
  void defaultVectorCandidateMultiplierMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveInt("index.hybrid.vector_candidate_multiplier", 10)
    assertEquals(10, HybridSearchOps.DEFAULT_VECTOR_CANDIDATE_MULTIPLIER);
  }

  @Test
  void defaultVectorRrfWeightMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveDouble("index.hybrid.vector_rrf_weight", 0.75)
    assertEquals(0.75, HybridSearchOps.DEFAULT_VECTOR_RRF_WEIGHT, 1e-9);
  }

  @Test
  void defaultVectorOnlyCapLowSignalMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveInt("index.hybrid.vector_only_cap_low_signal", 3)
    assertEquals(3, HybridSearchOps.DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL);
  }

  @Test
  void defaultVectorRrfWeightLowSignalMatchesResolvedConfigBuilder() {
    // ResolvedConfigBuilder: resolveDouble("index.hybrid.vector_rrf_weight_low_signal", 0.25)
    assertEquals(0.25, HybridSearchOps.DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL, 1e-9);
  }
}
