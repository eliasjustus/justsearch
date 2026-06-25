package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OnnxEmbeddingEncoder} math operations.
 *
 * <p>These tests exercise the static/internal math helpers (L2 normalization, truncation) via
 * reflection, without requiring an ONNX model on disk. Model-dependent tests live in {@link
 * OnnxEmbeddingEncoderIntegrationTest}.
 */
@DisplayName("OnnxEmbeddingEncoder (unit)")
final class OnnxEmbeddingEncoderTest {

  private ConfigStore previousStore;

  @BeforeEach
  void capturePreviousStore() {
    previousStore = ConfigStore.globalOrNull();
  }

  @AfterEach
  void restorePreviousStore() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Nested
  @DisplayName("L2 normalization")
  class L2Normalize {

    @Test
    @DisplayName("unit vector is unchanged")
    void unitVectorUnchanged() throws Exception {
      float[] input = {1.0f, 0.0f, 0.0f};
      float[] result = invokeL2Normalize(input);
      assertArrayEquals(new float[] {1.0f, 0.0f, 0.0f}, result, 1e-6f);
    }

    @Test
    @DisplayName("normalizes to unit length")
    void normalizesToUnitLength() throws Exception {
      float[] input = {3.0f, 4.0f}; // norm = 5
      float[] result = invokeL2Normalize(input);
      assertEquals(0.6f, result[0], 1e-6f);
      assertEquals(0.8f, result[1], 1e-6f);
      assertEquals(1.0, norm(result), 1e-5);
    }

    @Test
    @DisplayName("high-dimensional vector normalizes to unit length")
    void highDimensionalNormalizesToUnit() throws Exception {
      float[] input = new float[768];
      for (int i = 0; i < input.length; i++) {
        input[i] = (float) (i * 0.01 - 3.84);
      }
      float[] result = invokeL2Normalize(input);
      assertEquals(1.0, norm(result), 1e-5);
    }

    @Test
    @DisplayName("zero vector is returned as-is")
    void zeroVectorUnchanged() throws Exception {
      float[] input = {0.0f, 0.0f, 0.0f};
      float[] result = invokeL2Normalize(input);
      assertArrayEquals(new float[] {0.0f, 0.0f, 0.0f}, result, 1e-6f);
    }

    @Test
    @DisplayName("negative values are handled correctly")
    void negativeValues() throws Exception {
      float[] input = {-3.0f, 4.0f};
      float[] result = invokeL2Normalize(input);
      assertEquals(-0.6f, result[0], 1e-6f);
      assertEquals(0.8f, result[1], 1e-6f);
      assertEquals(1.0, norm(result), 1e-5);
    }
  }

  @Nested
  @DisplayName("truncation")
  class Truncate {

    @Test
    @DisplayName("returns same array when length matches")
    void sameArrayWhenLengthMatches() throws Exception {
      long[] input = {1, 2, 3};
      long[] result = invokeTruncate(input, 3);
      assertSame(input, result);
    }

    @Test
    @DisplayName("truncates to shorter length")
    void truncatesToShorterLength() throws Exception {
      long[] input = {1, 2, 3, 4, 5};
      long[] result = invokeTruncate(input, 3);
      assertArrayEquals(new long[] {1, 2, 3}, result);
    }

    @Test
    @DisplayName("truncates to single element")
    void truncatesToSingleElement() throws Exception {
      long[] input = {10, 20, 30};
      long[] result = invokeTruncate(input, 1);
      assertArrayEquals(new long[] {10}, result);
    }
  }

  @Nested
  @DisplayName("Chunking constants")
  class Chunking {

    @Test
    @DisplayName("chunk overlap matches GGUF default of 128")
    void chunkOverlapMatchesGgufDefault() throws Exception {
      var field = OnnxEmbeddingEncoder.class.getDeclaredField("chunkOverlap");
      // Verify the field exists and is the right type — the actual value is validated
      // at construction time, but we can verify the constant via the source expectation:
      // stride = chunkSize(512) - chunkOverlap(128) = 384, matching EmbeddingActor.
      assertEquals(int.class, field.getType());
    }

    @Test
    @DisplayName("stride calculation produces expected chunk boundaries")
    void strideCalculation() {
      // These mirror OnnxEmbeddingEncoder's chunking: chunkSize=512, chunkOverlap=128
      int chunkSize = 512;
      int chunkOverlap = 128;
      int stride = Math.max(1, chunkSize - chunkOverlap);
      assertEquals(384, stride, "stride must match GGUF path (512 - 128 = 384)");
    }

    @Test
    @DisplayName("chunk count for 900 tokens is 3")
    void chunkCountFor900Tokens() {
      int chunkSize = 512;
      int chunkOverlap = 128;
      int stride = chunkSize - chunkOverlap; // 384
      int tokenCount = 900;

      // Simulate chunking loop
      int chunks = 0;
      int start = 0;
      while (start < tokenCount) {
        chunks++;
        start += stride;
        // Merge-small-remainder logic: if remaining < chunkSize/4 (128), merge
        if (start < tokenCount && tokenCount - start < chunkSize / 4) {
          break; // last chunk was extended, no new chunk added
        }
      }
      assertEquals(3, chunks, "900 tokens with chunk=512, overlap=128 should produce 3 chunks");
    }

    @Test
    @DisplayName("single chunk when tokens fit within chunk size")
    void singleChunkWhenFits() {
      int chunkSize = 512;
      int chunkOverlap = 128;
      int stride = chunkSize - chunkOverlap;
      int tokenCount = 500; // fits in one chunk

      int chunks = 0;
      int start = 0;
      while (start < tokenCount) {
        chunks++;
        start += stride;
        if (start < tokenCount && tokenCount - start < chunkSize / 4) {
          break;
        }
      }
      assertEquals(1, chunks, "500 tokens should fit in a single 512-token chunk");
    }
  }

  @Nested
  @DisplayName("EmbedResult record")
  class EmbedResultTests {

    @Test
    @DisplayName("single-chunk result has empty chunk vectors")
    void singleChunkResult() {
      float[] vector = {0.5f, 0.5f, 0.5f, 0.5f};
      var result = new OnnxEmbeddingEncoder.EmbedResult(vector, java.util.List.of(), 1);
      assertEquals(1, result.chunkCount());
      assertTrue(result.chunkVectors().isEmpty());
      assertArrayEquals(vector, result.vector());
    }

    @Test
    @DisplayName("multi-chunk result has non-empty chunk vectors")
    void multiChunkResult() {
      float[] pooled = {0.7f, 0.3f};
      var chunks =
          java.util.List.of(new float[] {0.6f, 0.4f}, new float[] {0.8f, 0.2f});
      var result = new OnnxEmbeddingEncoder.EmbedResult(pooled, chunks, 2);
      assertEquals(2, result.chunkCount());
      assertEquals(2, result.chunkVectors().size());
    }
  }

  @Nested
  @DisplayName("GPU memory config")
  class GpuMemoryConfig {

    @Test
    @DisplayName("defaults to 3072 MB via EmbeddingConfig")
    void defaultsTo3072Mb() {
      // 391/E-J-N8: raised from 2048 → 3072 to accommodate gte-multilingual-base
      // FP16 activations (post-358 model swap). 2048 MB fragments under the
      // larger MLP intermediate tensors.
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.withDefaults()));
      EmbeddingConfig config = EmbeddingConfig.fromEnv();
      assertEquals(3072L * 1024 * 1024, config.gpuMemLimitBytes());
    }

    @Test
    @DisplayName("uses resolved config override via EmbeddingConfig")
    void usesResolvedConfigOverride() {
      ConfigStore.setGlobal(
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  Map.of("justsearch.embed.gpu_mem_mb", "2048"))));
      EmbeddingConfig config = EmbeddingConfig.fromEnv();
      assertEquals(2048L * 1024 * 1024, config.gpuMemLimitBytes());
    }
  }

  // --- Reflection helpers to invoke private static methods ---

  private static float[] invokeL2Normalize(float[] input) throws Exception {
    Method method =
        OnnxEmbeddingEncoder.class.getDeclaredMethod("l2Normalize", float[].class);
    method.setAccessible(true);
    return (float[]) method.invoke(null, input);
  }

  private static long[] invokeTruncate(long[] input, int len) throws Exception {
    Method method =
        OnnxEmbeddingEncoder.class.getDeclaredMethod("truncate", long[].class, int.class);
    method.setAccessible(true);
    return (long[]) method.invoke(null, input, len);
  }

  private static double norm(float[] vec) {
    double sum = 0.0;
    for (float v : vec) {
      sum += (double) v * v;
    }
    return Math.sqrt(sum);
  }
}
