/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/** Internal normalization boundary for Lucene DOT_PRODUCT vector fields and queries. */
final class VectorNormalization {
  private VectorNormalization() {}

  static float[] l2NormalizedCopy(float[] vector, String description) {
    if (vector == null || vector.length == 0) {
      throw new IllegalArgumentException(description + " must not be null or empty");
    }
    double squaredNorm = 0.0;
    for (float value : vector) {
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException(description + " values must be finite");
      }
      squaredNorm += (double) value * value;
    }
    if (!(squaredNorm > 0.0) || !Double.isFinite(squaredNorm)) {
      throw new IllegalArgumentException(description + " must have a finite, non-zero magnitude");
    }
    double inverseNorm = 1.0 / Math.sqrt(squaredNorm);
    float[] normalized = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      normalized[i] = (float) (vector[i] * inverseNorm);
    }
    return normalized;
  }
}
