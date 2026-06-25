package io.justsearch.indexerworker.bgem3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for BGE-M3 sparse post-processing logic (no model required). */
class BgeM3PostProcessTest {

  private static final String[] VOCAB = new String[10];

  static {
    VOCAB[0] = "<s>"; // CLS
    VOCAB[1] = "</s>"; // SEP
    VOCAB[2] = "<pad>";
    VOCAB[3] = "<unk>";
    VOCAB[4] = "▁the";
    VOCAB[5] = "▁court";
    VOCAB[6] = "▁held";
    VOCAB[7] = "▁breach";
    VOCAB[8] = "duty";
    VOCAB[9] = "▁fid";
  }

  @Test
  void basicSparseExtraction() {
    long[] inputIds = {0, 4, 5, 6, 1, 2}; // <s> the court held </s> <pad>
    long[] attentionMask = {1, 1, 1, 1, 1, 0};
    float[][] weights = {{0.1f}, {0.5f}, {0.8f}, {0.3f}, {0.2f}, {0.0f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    // Special tokens (0=<s>, 1=</s>) and padding (mask=0) should be excluded
    assertFalse(result.containsKey("<s>"));
    assertFalse(result.containsKey("</s>"));
    assertFalse(result.containsKey("<pad>"));

    assertEquals(0.5f, result.get("▁the"));
    assertEquals(0.8f, result.get("▁court"));
    assertEquals(0.3f, result.get("▁held"));
    assertEquals(3, result.size());
  }

  @Test
  void maxAggregationForRepeatedTokens() {
    // Same token appears twice with different weights — keep max
    long[] inputIds = {0, 4, 5, 4, 1}; // <s> the court the </s>
    long[] attentionMask = {1, 1, 1, 1, 1};
    float[][] weights = {{0.1f}, {0.3f}, {0.8f}, {0.7f}, {0.1f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    // "▁the" appears at positions 1 (0.3) and 3 (0.7) — max is 0.7
    assertEquals(0.7f, result.get("▁the"));
    assertEquals(0.8f, result.get("▁court"));
    assertEquals(2, result.size());
  }

  @Test
  void zeroWeightsExcluded() {
    long[] inputIds = {0, 4, 5, 6, 1};
    long[] attentionMask = {1, 1, 1, 1, 1};
    float[][] weights = {{0.0f}, {0.0f}, {0.5f}, {0.0f}, {0.0f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertEquals(1, result.size());
    assertEquals(0.5f, result.get("▁court"));
  }

  @Test
  void negativeWeightsExcluded() {
    long[] inputIds = {0, 4, 5, 1};
    long[] attentionMask = {1, 1, 1, 1};
    float[][] weights = {{0.0f}, {-0.1f}, {0.5f}, {0.0f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertEquals(1, result.size());
    assertFalse(result.containsKey("▁the"));
  }

  @Test
  void weightClampedAt64() {
    long[] inputIds = {0, 4, 1};
    long[] attentionMask = {1, 1, 1};
    float[][] weights = {{0.0f}, {100.0f}, {0.0f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertEquals(64.0f, result.get("▁the"));
  }

  @Test
  void allSpecialTokensProducesEmptyMap() {
    long[] inputIds = {0, 1, 2, 3};
    long[] attentionMask = {1, 1, 1, 1};
    float[][] weights = {{0.5f}, {0.5f}, {0.5f}, {0.5f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertTrue(result.isEmpty());
  }

  @Test
  void emptyInput() {
    long[] inputIds = {};
    long[] attentionMask = {};
    float[][] weights = {};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertTrue(result.isEmpty());
  }

  @Test
  void outOfVocabIdSkipped() {
    long[] inputIds = {0, 4, 99999, 1}; // 99999 is beyond vocab
    long[] attentionMask = {1, 1, 1, 1};
    float[][] weights = {{0.0f}, {0.5f}, {0.8f}, {0.0f}};

    Map<String, Float> result =
        BgeM3Encoder.postProcessSparse(inputIds, attentionMask, weights, VOCAB);

    assertEquals(1, result.size());
    assertEquals(0.5f, result.get("▁the"));
  }
}
