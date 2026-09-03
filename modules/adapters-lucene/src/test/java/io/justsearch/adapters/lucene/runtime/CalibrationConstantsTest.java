package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the tempdoc-702 dense-score calibration constants in {@link HybridSearchOps}.
 *
 * <p>The dense KNN fields now use explicit DOT_PRODUCT similarity and unit-normalized vectors, so
 * Lucene's score is {@code (1 + dot) / 2}. These constants retain the intended cosine-score
 * boundaries from tempdoc 702 directly in that score space; this test guards against a future
 * "tidy the magic number" edit silently reintroducing the pre-wave-2 EUCLIDEAN conversion.
 */
class CalibrationConstantsTest {

  @Test
  void arbitrationDenseConfidentMinMatchesDotProductScoreOfIntendedCosZero() {
    // Unit vectors: dot=cos, and Lucene's DOT_PRODUCT score is (1+dot)/2. cos=0 therefore scores .5.
    assertEquals(
        0.5,
        HybridSearchOps.ARBITRATION_DENSE_CONFIDENT_MIN,
        1e-9,
        "ARBITRATION_DENSE_CONFIDENT_MIN must equal (1+dot)/2 at the intended cos=dot=0 gate"
            + " ('not anti-correlated'), i.e. 0.5 (tempdocs 702/915).");
  }

  @Test
  void defaultVectorLowSignalThresholdMatchesDotProductScorePoint40() {
    // Intended: cosine-score (1+cos)/2 == 0.40. DOT_PRODUCT uses that score for unit vectors.
    assertEquals(
        0.40,
        HybridSearchOps.DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD,
        1e-9,
        "DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD must retain the intended 0.40 cosine-score gate"
            + " directly in DOT_PRODUCT score space (tempdocs 702/915).");
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
