/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.justsearch.indexerworker.services.SearchReasonCode;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SearchInputCaptureVectorNormalizationTest {

  @Test
  void queryBoundaryNormalizesDenseVectors() {
    VectorEncoding.Success success =
        assertInstanceOf(
            VectorEncoding.Success.class,
            SearchInputCapture.normalizedVectorEncoding(new float[] {3.0f, 4.0f}, "explicit"));

    assertEquals(List.of(0.6f, 0.8f), success.vector());
    assertEquals("explicit", success.source());
  }

  @Test
  void queryBoundaryRejectsVectorsThatCannotSatisfyDotProductContract() {
    for (float[] invalid :
        List.of(
            new float[] {0.0f, 0.0f},
            new float[] {Float.NaN, 1.0f},
            new float[] {Float.POSITIVE_INFINITY, 1.0f})) {
      VectorEncoding.Failed failed =
          assertInstanceOf(
              VectorEncoding.Failed.class,
              SearchInputCapture.normalizedVectorEncoding(invalid, "explicit"));
      assertEquals(SearchReasonCode.EMBEDDING_GENERATION_FAILED, failed.reason());
    }
  }
}
