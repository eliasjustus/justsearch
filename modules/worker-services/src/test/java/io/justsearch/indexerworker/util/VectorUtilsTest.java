package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link VectorUtils}. */
class VectorUtilsTest {

  @Nested
  class ToFloatArrayTests {

    @Test
    void nullList_throwsNullPointerException() {
      assertThrows(NullPointerException.class, () -> VectorUtils.toFloatArray(null));
    }

    @Test
    void emptyList_returnsEmptyArray() {
      float[] result = VectorUtils.toFloatArray(List.of());
      assertNotNull(result);
      assertEquals(0, result.length);
    }

    @Test
    void singleElement_returnsArrayWithOneElement() {
      float[] result = VectorUtils.toFloatArray(List.of(1.5f));
      assertArrayEquals(new float[] {1.5f}, result, 0.0001f);
    }

    @Test
    void multipleElements_preservesOrderAndValues() {
      List<Float> input = Arrays.asList(1.0f, 2.5f, -3.0f, 0.0f);
      float[] result = VectorUtils.toFloatArray(input);
      assertArrayEquals(new float[] {1.0f, 2.5f, -3.0f, 0.0f}, result, 0.0001f);
    }

    @Test
    void largeValues_handlesCorrectly() {
      List<Float> input = List.of(Float.MAX_VALUE, Float.MIN_VALUE);
      float[] result = VectorUtils.toFloatArray(input);
      assertEquals(Float.MAX_VALUE, result[0], 0.0001f);
      assertEquals(Float.MIN_VALUE, result[1], 0.0001f);
    }
  }

  @Nested
  class CosineTests {

    @Test
    void nullFirstArray_returnsZero() {
      assertEquals(0.0, VectorUtils.cosine(null, new float[] {1.0f}));
    }

    @Test
    void nullSecondArray_returnsZero() {
      assertEquals(0.0, VectorUtils.cosine(new float[] {1.0f}, null));
    }

    @Test
    void emptyFirstArray_returnsZero() {
      assertEquals(0.0, VectorUtils.cosine(new float[] {}, new float[] {1.0f}));
    }

    @Test
    void emptySecondArray_returnsZero() {
      assertEquals(0.0, VectorUtils.cosine(new float[] {1.0f}, new float[] {}));
    }

    @Test
    void mismatchedLengths_returnsZero() {
      assertEquals(0.0, VectorUtils.cosine(new float[] {1.0f, 2.0f}, new float[] {1.0f}));
    }

    @Test
    void identicalVectors_returnsOne() {
      float[] v = {1.0f, 2.0f, 3.0f};
      assertEquals(1.0, VectorUtils.cosine(v, v), 0.0001);
    }

    @Test
    void oppositeVectors_returnsNegativeOne() {
      float[] a = {1.0f, 0.0f};
      float[] b = {-1.0f, 0.0f};
      assertEquals(-1.0, VectorUtils.cosine(a, b), 0.0001);
    }

    @Test
    void orthogonalVectors_returnsZero() {
      float[] a = {1.0f, 0.0f};
      float[] b = {0.0f, 1.0f};
      assertEquals(0.0, VectorUtils.cosine(a, b), 0.0001);
    }

    @Test
    void unitVectors45Degrees_returnsCosine45() {
      float[] a = {1.0f, 0.0f};
      float[] b = {(float) Math.sqrt(2) / 2, (float) Math.sqrt(2) / 2};
      // cos(45°) ≈ 0.7071
      assertEquals(Math.sqrt(2) / 2, VectorUtils.cosine(a, b), 0.0001);
    }

    @Test
    void zeroVector_returnsZero() {
      float[] a = {0.0f, 0.0f};
      float[] b = {1.0f, 1.0f};
      assertEquals(0.0, VectorUtils.cosine(a, b));
    }

    @Test
    void highDimensionalVectors_computesCorrectly() {
      // Create two normalized vectors
      float[] a = new float[128];
      float[] b = new float[128];
      for (int i = 0; i < 128; i++) {
        a[i] = i / 128.0f;
        b[i] = (127 - i) / 128.0f;
      }
      double result = VectorUtils.cosine(a, b);
      // Just verify it's in valid range and computes
      assertTrue(result >= -1.0 && result <= 1.0);
    }
  }
}
