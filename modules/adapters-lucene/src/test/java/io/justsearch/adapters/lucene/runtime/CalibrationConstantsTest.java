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
}
