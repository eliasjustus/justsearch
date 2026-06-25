package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("CrossEncoderReranker — batch-size bucketing (D4)")
class CrossEncoderRerankerTest {

  @ParameterizedTest(name = "batchSize={0} → bucket={1}")
  @CsvSource({
    "1, 4",
    "4, 4",
    "5, 8",
    "8, 8",
    "10, 16",
    "16, 16",
    "20, 24",
    "24, 24",
    "25, 32",
    "32, 32",
    "33, 48",
    "48, 48",
    "50, 64",
    "64, 64"
  })
  void bucketBatchSizeRoundsUpToNextBucket(int batchSize, int expected) {
    assertEquals(expected, CrossEncoderReranker.bucketBatchSize(batchSize));
  }

  @Test
  @DisplayName("batch sizes beyond largest bucket use exact size")
  void bucketBatchSizeBeyondLargestBucket() {
    assertEquals(100, CrossEncoderReranker.bucketBatchSize(100));
    assertEquals(65, CrossEncoderReranker.bucketBatchSize(65));
  }

  // -- padAttentionMask tests (NaN prevention for global-attention models) --

  @Test
  @DisplayName("padding rows get attentionMask[0]=1 to prevent NaN from global attention softmax")
  void padAttentionMaskSetsPaddingAnchor() {
    // 2 actual rows, pad to 4 (bucket boundary), seqLength=3
    long[][] original = {
      {1, 1, 0}, // actual row 0
      {1, 1, 1}, // actual row 1
    };
    long[][] result = CrossEncoderReranker.padAttentionMask(original, 4, 3);

    assertEquals(4, result.length, "should be padded to target rows");
    // Actual rows preserved
    assertArrayEquals(new long[] {1, 1, 0}, result[0], "actual row 0 unchanged");
    assertArrayEquals(new long[] {1, 1, 1}, result[1], "actual row 1 unchanged");
    // Padding rows: position 0 must be 1, rest zero
    assertEquals(1, result[2][0], "padding row 2 position 0 must be 1");
    assertEquals(0, result[2][1], "padding row 2 position 1 should be 0");
    assertEquals(0, result[2][2], "padding row 2 position 2 should be 0");
    assertEquals(1, result[3][0], "padding row 3 position 0 must be 1");
    assertEquals(0, result[3][1], "padding row 3 position 1 should be 0");
  }

  @Test
  @DisplayName("no padding needed returns original array unchanged")
  void padAttentionMaskNoPadding() {
    long[][] original = {
      {1, 1, 0},
      {1, 1, 1},
      {1, 0, 0},
      {1, 1, 0},
    };
    long[][] result = CrossEncoderReranker.padAttentionMask(original, 4, 3);

    assertSame(original, result, "should return original when no padding needed");
  }

  @Test
  @DisplayName("single document padded to bucket=4 gets 3 anchor rows")
  void padAttentionMaskSingleDoc() {
    long[][] original = {{1, 1, 1, 0}};
    long[][] result = CrossEncoderReranker.padAttentionMask(original, 4, 4);

    assertEquals(4, result.length);
    assertArrayEquals(new long[] {1, 1, 1, 0}, result[0], "actual row unchanged");
    for (int row = 1; row < 4; row++) {
      assertEquals(1, result[row][0], "padding row " + row + " must have anchor at position 0");
      for (int col = 1; col < 4; col++) {
        assertEquals(0, result[row][col], "padding row " + row + " col " + col + " should be 0");
      }
    }
  }
}
