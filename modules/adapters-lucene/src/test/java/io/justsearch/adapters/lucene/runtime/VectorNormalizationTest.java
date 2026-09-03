/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class VectorNormalizationTest {

  @Test
  void returnsAUnitLengthCopy() {
    float[] source = {3.0f, 4.0f};

    assertArrayEquals(
        new float[] {0.6f, 0.8f},
        VectorNormalization.l2NormalizedCopy(source, "query vector"),
        0.0001f);
    assertArrayEquals(new float[] {3.0f, 4.0f}, source, 0.0f);
  }

  @Test
  void rejectsZeroAndNonFiniteVectors() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VectorNormalization.l2NormalizedCopy(new float[] {0.0f}, "query vector"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VectorNormalization.l2NormalizedCopy(new float[] {Float.NaN}, "query vector"));
  }
}
