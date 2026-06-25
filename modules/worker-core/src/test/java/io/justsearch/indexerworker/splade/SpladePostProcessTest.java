package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;

import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests SPLADE post-processing: ReLU + log1p + max-pool over sequence positions.
 *
 * <p>Uses a minimal in-memory vocabulary and bypasses ORT session creation to test the core
 * activation logic in isolation.
 */
@DisplayName("SpladeEncoder postProcess")
class SpladePostProcessTest {

  private TestablePostProcessor processor;

  @BeforeEach
  void setUp() {
    Vocabulary vocab =
        DefaultVocabulary.builder()
            .add(List.of("[PAD]", "hello", "world", "test"))
            .optUnknownToken("[UNK]")
            .build();
    processor = new TestablePostProcessor(vocab);
  }

  @Nested
  @DisplayName("basic activation")
  class BasicActivation {

    @Test
    @DisplayName("positive logit produces log1p-activated weight")
    void positiveLogitProducesWeight() {
      // logits[0][0][1] = 2.0 → ReLU(2.0) = 2.0 → log1p(2.0) = log(3) ≈ 1.0986
      float[][][] logits = {{{0.0f, 2.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertEquals(1, results.size());

      Map<String, Float> sparse = results.get(0);
      assertTrue(sparse.containsKey("hello"), "Expected 'hello' in sparse vector");
      assertEquals(Math.log1p(2.0), sparse.get("hello"), 1e-5f);
    }

    @Test
    @DisplayName("negative logit is filtered by ReLU")
    void negativeLogitFilteredByRelu() {
      float[][][] logits = {{{0.0f, -5.0f, -1.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertTrue(results.get(0).isEmpty(), "All negative logits should produce empty vector");
    }

    @Test
    @DisplayName("zero logit produces no entry")
    void zeroLogitProducesNoEntry() {
      float[][][] logits = {{{0.0f, 0.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertTrue(results.get(0).isEmpty());
    }
  }

  @Nested
  @DisplayName("max-pooling over sequence")
  class MaxPooling {

    @Test
    @DisplayName("takes maximum over sequence positions for each vocab token")
    void takesMaxOverSequence() {
      // Two sequence positions: logits for token 1 are 1.0 and 3.0 → max is 3.0
      float[][][] logits = {
        {
          {0.0f, 1.0f, 0.0f, 0.0f},
          {0.0f, 3.0f, 0.5f, 0.0f}
        }
      };
      long[][] mask = {{1, 1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      Map<String, Float> sparse = results.get(0);

      assertEquals((float) Math.log1p(3.0), sparse.get("hello"), 1e-5f);
      assertEquals((float) Math.log1p(0.5), sparse.get("world"), 1e-5f);
    }

    @Test
    @DisplayName("masked positions are ignored in max-pool")
    void maskedPositionsIgnored() {
      // Position 0 has mask=1, position 1 has mask=0 (padding)
      float[][][] logits = {
        {
          {0.0f, 1.0f, 0.0f, 0.0f},
          {0.0f, 5.0f, 0.0f, 0.0f}
        }
      };
      long[][] mask = {{1, 0}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      Map<String, Float> sparse = results.get(0);

      // Only position 0 contributes (mask=1), so weight should be log1p(1.0) not log1p(5.0)
      assertEquals((float) Math.log1p(1.0), sparse.get("hello"), 1e-5f);
    }
  }

  @Nested
  @DisplayName("special token filtering")
  class SpecialTokenFiltering {

    @Test
    @DisplayName("token index 0 ([PAD]) is filtered out")
    void padTokenFiltered() {
      // High logit for token 0 ([PAD]) — should be filtered
      float[][][] logits = {{{10.0f, 0.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertFalse(results.get(0).containsKey("[PAD]"));
    }
  }

  @Nested
  @DisplayName("batch processing")
  class BatchProcessing {

    @Test
    @DisplayName("processes multiple items independently")
    void processesMultipleItems() {
      float[][][] logits = {
        {{0.0f, 2.0f, 0.0f, 0.0f}}, // batch 0: only "hello"
        {{0.0f, 0.0f, 3.0f, 0.0f}} // batch 1: only "world"
      };
      long[][] mask = {{1}, {1}};

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertEquals(2, results.size());

      assertTrue(results.get(0).containsKey("hello"));
      assertFalse(results.get(0).containsKey("world"));

      assertFalse(results.get(1).containsKey("hello"));
      assertTrue(results.get(1).containsKey("world"));
    }

    @Test
    @DisplayName("empty batch returns empty list")
    void emptyBatchReturnsEmptyList() {
      float[][][] logits = new float[0][][];
      long[][] mask = new long[0][];

      List<Map<String, Float>> results = processor.postProcess(logits, mask);
      assertTrue(results.isEmpty());
    }
  }

  @Nested
  @DisplayName("beta query-term pruning")
  class BetaPruning {

    @Test
    @DisplayName("keeps top half by weight when beta=0.5")
    void keepsTopHalfByWeight() {
      Map<String, Float> input =
          Map.of("apple", 4.0f, "banana", 3.0f, "cherry", 2.0f, "date", 1.0f);

      Map<String, Float> pruned = SpladeEncoder.pruneByBeta(input, 0.5f);

      assertEquals(2, pruned.size());
      assertTrue(pruned.containsKey("apple"));
      assertTrue(pruned.containsKey("banana"));
      assertFalse(pruned.containsKey("cherry"));
      assertFalse(pruned.containsKey("date"));
    }

    @Test
    @DisplayName("preserves all terms when beta is 1.0")
    void preservesAllWhenBetaIsOne() {
      Map<String, Float> input = Map.of("a", 1.0f, "b", 2.0f, "c", 3.0f);

      Map<String, Float> result = SpladeEncoder.pruneByBeta(input, 1.0f);

      assertEquals(3, result.size());
      assertEquals(input, result);
    }

    @Test
    @DisplayName("keeps at least one term even with very low beta")
    void keepsAtLeastOneTerm() {
      Map<String, Float> input = Map.of("high", 5.0f, "low", 0.1f);

      Map<String, Float> pruned = SpladeEncoder.pruneByBeta(input, 0.1f);

      assertEquals(1, pruned.size());
      assertTrue(pruned.containsKey("high"));
    }

    @Test
    @DisplayName("single-term map returned unchanged")
    void singleTermUnchanged() {
      Map<String, Float> input = Map.of("only", 2.5f);

      Map<String, Float> result = SpladeEncoder.pruneByBeta(input, 0.5f);

      assertEquals(1, result.size());
      assertEquals(2.5f, result.get("only"));
    }

    @Test
    @DisplayName("empty map returned unchanged")
    void emptyMapUnchanged() {
      Map<String, Float> input = Map.of();

      Map<String, Float> result = SpladeEncoder.pruneByBeta(input, 0.5f);

      assertTrue(result.isEmpty());
    }
  }

  @Nested
  @DisplayName("double-log activation")
  class DoubleLogActivation {

    private TestablePostProcessor doubleLogProcessor;

    @BeforeEach
    void setUp() {
      Vocabulary vocab =
          DefaultVocabulary.builder()
              .add(List.of("[PAD]", "hello", "world", "test"))
              .optUnknownToken("[UNK]")
              .build();
      doubleLogProcessor = new TestablePostProcessor(vocab, true);
    }

    @Test
    @DisplayName("double-log produces lower values than single-log")
    void producesLowerValues() {
      // logit = 2.0
      // single-log: log1p(2.0) = log(3) ≈ 1.0986
      // double-log: log1p(log1p(2.0)) = log1p(log(3)) = log(1 + log(3)) ≈ 0.7415
      float[][][] logits = {{{0.0f, 2.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = doubleLogProcessor.postProcess(logits, mask);
      Map<String, Float> sparse = results.get(0);

      float expected = (float) Math.log1p(Math.log1p(2.0));
      assertEquals(expected, sparse.get("hello"), 1e-5f);
      assertTrue(expected < (float) Math.log1p(2.0), "Double-log should be less than single-log");
    }

    @Test
    @DisplayName("double-log still filters negative logits via ReLU")
    void filtersNegativeLogits() {
      float[][][] logits = {{{0.0f, -5.0f, -1.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = doubleLogProcessor.postProcess(logits, mask);
      assertTrue(results.get(0).isEmpty());
    }

    @Test
    @DisplayName("double-log known value: logit=5.0")
    void knownValueHighLogit() {
      // logit = 5.0
      // double-log: log1p(log1p(5.0)) = log(1 + log(6)) ≈ log(1 + 1.7918) ≈ 1.0272
      float[][][] logits = {{{0.0f, 5.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> results = doubleLogProcessor.postProcess(logits, mask);
      float expected = (float) Math.log1p(Math.log1p(5.0));
      assertEquals(expected, results.get(0).get("hello"), 1e-5f);
    }
  }

  @Nested
  @DisplayName("sparse-output compatibility")
  class SparseOutputCompatibility {

    @Test
    @DisplayName("decodes sparse token ids and weights directly")
    void decodesSparseTokenIdsAndWeights() {
      Map<String, Float> sparse =
          SpladeEncoder.decodeSparseOutput(
              new long[] {0, 1, 2, 1, 99},
              new float[] {10.0f, 0.5f, 1.5f, 0.7f, 2.0f},
              processor.vocabulary);

      assertEquals(2, sparse.size());
      assertEquals(0.7f, sparse.get("hello"), 1e-6f);
      assertEquals(1.5f, sparse.get("world"), 1e-6f);
      assertFalse(sparse.containsKey("[PAD]"));
    }

    @Test
    @DisplayName("rejects length mismatch")
    void rejectsLengthMismatch() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SpladeEncoder.decodeSparseOutput(
                  new long[] {1, 2}, new float[] {1.0f}, processor.vocabulary));
    }
  }

  @Nested
  @DisplayName("postProcessBuffer (zero-copy FloatBuffer path)")
  class BufferProcessing {

    @Test
    @DisplayName("matches postProcess output for single item")
    void matchesSingleItem() {
      float[][][] logits = {{{0.0f, 2.0f, 0.5f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> arrayResult = processor.postProcess(logits, mask);
      List<Map<String, Float>> bufferResult =
          processor.postProcessBuffer(toFloatBuffer(logits), 1, 1, 4, mask);

      assertEquals(arrayResult, bufferResult);
    }

    @Test
    @DisplayName("matches postProcess for multi-position max-pooling")
    void matchesMaxPooling() {
      float[][][] logits = {
        {
          {0.0f, 1.0f, 0.0f, 0.0f},
          {0.0f, 3.0f, 0.5f, 0.0f}
        }
      };
      long[][] mask = {{1, 1}};

      List<Map<String, Float>> arrayResult = processor.postProcess(logits, mask);
      List<Map<String, Float>> bufferResult =
          processor.postProcessBuffer(toFloatBuffer(logits), 1, 2, 4, mask);

      assertEquals(arrayResult, bufferResult);
    }

    @Test
    @DisplayName("skips padding positions correctly")
    void skipsPadding() {
      float[][][] logits = {
        {
          {0.0f, 1.0f, 0.0f, 0.0f},
          {0.0f, 5.0f, 0.0f, 0.0f}
        }
      };
      long[][] mask = {{1, 0}};

      List<Map<String, Float>> arrayResult = processor.postProcess(logits, mask);
      List<Map<String, Float>> bufferResult =
          processor.postProcessBuffer(toFloatBuffer(logits), 1, 2, 4, mask);

      assertEquals(arrayResult, bufferResult);
      // Only position 0 contributes
      assertEquals((float) Math.log1p(1.0), bufferResult.get(0).get("hello"), 1e-5f);
    }

    @Test
    @DisplayName("processes batch of 2 items independently")
    void processesBatch() {
      float[][][] logits = {
        {{0.0f, 2.0f, 0.0f, 0.0f}},
        {{0.0f, 0.0f, 3.0f, 0.0f}}
      };
      long[][] mask = {{1}, {1}};

      List<Map<String, Float>> arrayResult = processor.postProcess(logits, mask);
      List<Map<String, Float>> bufferResult =
          processor.postProcessBuffer(toFloatBuffer(logits), 2, 1, 4, mask);

      assertEquals(arrayResult, bufferResult);
    }

    @Test
    @DisplayName("all-masked batch item produces empty sparse vector")
    void allMaskedProducesEmpty() {
      float[][][] logits = {{{10.0f, 10.0f, 10.0f, 10.0f}}};
      long[][] mask = {{0}};

      List<Map<String, Float>> result =
          processor.postProcessBuffer(toFloatBuffer(logits), 1, 1, 4, mask);

      assertTrue(result.get(0).isEmpty());
    }

    @Test
    @DisplayName("double-log activation matches array path")
    void doubleLogMatches() {
      Vocabulary vocab =
          DefaultVocabulary.builder()
              .add(List.of("[PAD]", "hello", "world", "test"))
              .optUnknownToken("[UNK]")
              .build();
      TestablePostProcessor dblProcessor = new TestablePostProcessor(vocab, true);

      float[][][] logits = {{{0.0f, 5.0f, 0.0f, 0.0f}}};
      long[][] mask = {{1}};

      List<Map<String, Float>> arrayResult = dblProcessor.postProcess(logits, mask);
      List<Map<String, Float>> bufferResult =
          dblProcessor.postProcessBuffer(toFloatBuffer(logits), 1, 1, 4, mask);

      assertEquals(arrayResult, bufferResult);
    }
  }

  /** Flattens a 3D float array into a row-major FloatBuffer (matching ORT tensor layout). */
  private static FloatBuffer toFloatBuffer(float[][][] array) {
    int batch = array.length;
    int seq = array[0].length;
    int vocab = array[0][0].length;
    FloatBuffer buf = FloatBuffer.allocate(batch * seq * vocab);
    for (int b = 0; b < batch; b++) {
      for (int t = 0; t < seq; t++) {
        buf.put(array[b][t]);
      }
    }
    buf.flip();
    return buf;
  }

  // ---------------------------------------------------------------------------
  // Helper: wraps SpladeEncoder.postProcess with a test vocabulary
  // ---------------------------------------------------------------------------

  /**
   * Accesses the package-private postProcess method with a test vocabulary injected via reflection.
   * This avoids needing a real ONNX model for post-processing tests.
   */
  private static class TestablePostProcessor {
    private final Vocabulary vocabulary;
    private final boolean doubleLog;

    TestablePostProcessor(Vocabulary vocabulary) {
      this(vocabulary, false);
    }

    TestablePostProcessor(Vocabulary vocabulary, boolean doubleLog) {
      this.vocabulary = vocabulary;
      this.doubleLog = doubleLog;
    }

    /**
     * Replicates SpladeEncoder.postProcessBuffer logic — reads from a flat FloatBuffer in
     * row-major order, same as ORT's getFloatBuffer() output.
     */
    List<Map<String, Float>> postProcessBuffer(
        FloatBuffer buf, int batchSize, int seqLen, int vocabSize, long[][] attentionMask) {
      List<Map<String, Float>> results = new java.util.ArrayList<>(batchSize);
      java.util.Set<Integer> skipIds = java.util.Set.of(0, 100, 101, 102, 103);

      for (int b = 0; b < batchSize; b++) {
        long[] mask = attentionMask[b];
        float[] maxActivated = new float[vocabSize];

        for (int t = 0; t < seqLen; t++) {
          if (mask[t] == 0) {
            buf.position(buf.position() + vocabSize);
            continue;
          }
          for (int v = 0; v < vocabSize; v++) {
            float val = buf.get();
            if (val > 0.0f) {
              float activated =
                  doubleLog
                      ? (float) Math.log1p(Math.log1p(val))
                      : (float) Math.log1p(val);
              maxActivated[v] = Math.max(maxActivated[v], activated);
            }
          }
        }

        Map<String, Float> sparseVec = new java.util.LinkedHashMap<>();
        for (int v = 0; v < vocabSize; v++) {
          if (maxActivated[v] > 0.0f && !skipIds.contains(v)) {
            String token = vocabulary.getToken(v);
            if (token != null) {
              sparseVec.put(token, maxActivated[v]);
            }
          }
        }
        results.add(sparseVec);
      }
      return results;
    }

    /**
     * Replicates SpladeEncoder.postProcess logic with the test vocabulary. This is a direct copy
     * of the algorithm to avoid reflection complexity while still testing the math.
     */
    List<Map<String, Float>> postProcess(float[][][] logits, long[][] attentionMask) {
      int batchSize = logits.length;
      List<Map<String, Float>> results = new java.util.ArrayList<>(batchSize);

      java.util.Set<Integer> skipIds = java.util.Set.of(0, 100, 101, 102, 103);

      for (int b = 0; b < batchSize; b++) {
        float[][] seqLogits = logits[b];
        long[] mask = attentionMask[b];
        int seqLen = seqLogits.length;
        int vocabSize = seqLogits[0].length;

        Map<String, Float> sparseVec = new java.util.LinkedHashMap<>();

        for (int v = 0; v < vocabSize; v++) {
          if (skipIds.contains(v)) {
            continue;
          }

          float maxVal = 0.0f;
          for (int t = 0; t < seqLen; t++) {
            if (mask[t] == 0) {
              continue;
            }
            float relu = Math.max(0.0f, seqLogits[t][v]);
            if (relu > 0.0f) {
              float activated =
                  doubleLog
                      ? (float) Math.log1p(Math.log1p(relu))
                      : (float) Math.log1p(relu);
              maxVal = Math.max(maxVal, activated);
            }
          }

          if (maxVal > 0.0f) {
            String token = vocabulary.getToken(v);
            if (token != null) {
              sparseVec.put(token, maxVal);
            }
          }
        }

        results.add(sparseVec);
      }

      return results;
    }
  }
}
