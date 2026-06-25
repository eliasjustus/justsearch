package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link LambdaMartFeatureSchema}. */
@DisplayName("LambdaMartFeatureSchema")
class LambdaMartFeatureSchemaTest {

  @Test
  @DisplayName("normalize: zero range returns 0.0f (NaN guard)")
  void normalize_zeroRange_returnsZero() {
    assertEquals(0.0f, LambdaMartFeatureSchema.normalize(5.0f, 5.0f, 5.0f), 1e-7f);
    assertEquals(0.0f, LambdaMartFeatureSchema.normalize(0.0f, 0.0f, 0.0f), 1e-7f);
  }

  @Test
  @DisplayName("normalize: midpoint value returns 0.5")
  void normalize_midpoint_returns0_5() {
    assertEquals(0.5f, LambdaMartFeatureSchema.normalize(5.0f, 0.0f, 10.0f), 1e-6f);
  }

  @Test
  @DisplayName("normalize: min value returns 0.0")
  void normalize_minValue_returnsZero() {
    assertEquals(0.0f, LambdaMartFeatureSchema.normalize(0.0f, 0.0f, 10.0f), 1e-7f);
  }

  @Test
  @DisplayName("normalize: max value returns 1.0")
  void normalize_maxValue_returnsOne() {
    assertEquals(1.0f, LambdaMartFeatureSchema.normalize(10.0f, 0.0f, 10.0f), 1e-6f);
  }

  @Test
  @DisplayName("buildRow: returns array of correct length and order (V2: sparse + vector + splade)")
  void buildRow_returnsCorrectIndices() {
    float[] row = LambdaMartFeatureSchema.buildRow(0.1f, 0.2f, 0.3f);

    assertEquals(LambdaMartFeatureSchema.NUM_FEATURES, row.length);
    assertEquals(0.1f, row[LambdaMartFeatureSchema.IDX_SPARSE], 1e-7f);
    assertEquals(0.2f, row[LambdaMartFeatureSchema.IDX_VECTOR], 1e-7f);
    assertEquals(0.3f, row[LambdaMartFeatureSchema.IDX_SPLADE], 1e-7f);
  }

  @Test
  @DisplayName("NUM_FEATURES is 3 (V2 schema: sparse + vector + splade)")
  void numFeaturesIs3() {
    assertEquals(3, LambdaMartFeatureSchema.NUM_FEATURES);
  }

  @Test
  @DisplayName("index constants are contiguous 0-2")
  void indexConstantsAreContiguous() {
    int[] indices = {
      LambdaMartFeatureSchema.IDX_SPARSE,
      LambdaMartFeatureSchema.IDX_VECTOR,
      LambdaMartFeatureSchema.IDX_SPLADE,
    };
    assertArrayEquals(new int[] {0, 1, 2}, indices);
  }

  @Test
  @DisplayName("FEATURE_NAMES size matches NUM_FEATURES and names match index constants")
  void featureNames_matchIndicesAndLength() {
    assertEquals(LambdaMartFeatureSchema.NUM_FEATURES, LambdaMartFeatureSchema.FEATURE_NAMES.size());
    assertEquals("sparse", LambdaMartFeatureSchema.FEATURE_NAMES.get(LambdaMartFeatureSchema.IDX_SPARSE));
    assertEquals("vector", LambdaMartFeatureSchema.FEATURE_NAMES.get(LambdaMartFeatureSchema.IDX_VECTOR));
    assertEquals("splade", LambdaMartFeatureSchema.FEATURE_NAMES.get(LambdaMartFeatureSchema.IDX_SPLADE));
  }
}
