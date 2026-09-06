/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnnxEmbeddingWindowBatchTest {
  @Test
  void globalBatchesCrossDocumentBoundariesAndPoolInOriginalOrder() throws Exception {
    List<Integer> sizes = new ArrayList<>();
    List<Long> order = new ArrayList<>();
    OnnxEmbeddingEncoder.WindowBatch batch = new OnnxEmbeddingEncoder.WindowBatch(false, 8, windows -> {
      sizes.add(windows.size());
      List<float[]> result = new ArrayList<>();
      for (long[][] window : windows) {
        order.add(window[0][0]);
        result.add(new float[] {window[0][0], 1f});
      }
      return result;
    });
    batch.addDocument(windows(1, 3));
    batch.addDocument(windows(4, 7));
    batch.addDocument(windows(11, 2));
    var result = batch.finish();

    assertEquals(List.of(8, 4), sizes);
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), order);
    assertEquals(List.of(3, 7, 2), result.stream().map(OnnxEmbeddingEncoder.EmbedResult::chunkCount).toList());
    assertArrayEquals(normalized(2f, 1f), result.get(0).vector(), 1e-6f);
    assertArrayEquals(normalized(7f, 1f), result.get(1).vector(), 1e-6f);
    assertArrayEquals(normalized(11.5f, 1f), result.get(2).vector(), 1e-6f);
    assertTrue(result.stream().allMatch(item -> item.chunkVectors().isEmpty()));
  }

  @Test
  void fullChunkConsumerRetainsVectorsWhilePooledConsumerDoesNot() throws Exception {
    var full = new OnnxEmbeddingEncoder.WindowBatch(true, 8, OnnxEmbeddingWindowBatchTest::infer);
    var pooled = new OnnxEmbeddingEncoder.WindowBatch(false, 8, OnnxEmbeddingWindowBatchTest::infer);
    for (var input : List.of(windows(1, 1), windows(2, 9))) {
      full.addDocument(input);
      pooled.addDocument(input);
    }
    var fullResult = full.finish();
    var pooledResult = pooled.finish();
    for (int i = 0; i < fullResult.size(); i++) {
      assertArrayEquals(fullResult.get(i).vector(), pooledResult.get(i).vector());
      assertEquals(fullResult.get(i).chunkCount(), pooledResult.get(i).chunkCount());
      assertTrue(pooledResult.get(i).chunkVectors().isEmpty());
    }
    assertTrue(fullResult.get(0).chunkVectors().isEmpty());
    assertEquals(9, fullResult.get(1).chunkVectors().size());
    assertArrayEquals(new float[] {10f, 1f}, fullResult.get(1).chunkVectors().get(8));
  }

  @Test
  void singletonGroupingAndEmptyInputsKeepTheirContracts() throws Exception {
    List<Integer> sizes = new ArrayList<>();
    var batch = new OnnxEmbeddingEncoder.WindowBatch(false, 1, windows -> {
      sizes.add(windows.size());
      return infer(windows);
    });
    batch.addDocument(windows(2, 3));
    assertEquals(1, batch.finish().size());
    assertEquals(List.of(1, 1, 1), sizes);
    assertTrue(new OnnxEmbeddingEncoder.WindowBatch(false, 8,
        windows -> { throw new AssertionError("Empty batch must not invoke inference"); }).finish().isEmpty());
  }

  @Test
  void malformedInferenceCannotSilentlyDropWindowResults() {
    var batch = new OnnxEmbeddingEncoder.WindowBatch(false, 1, windows -> List.of());
    assertThrows(IllegalStateException.class, () -> batch.addDocument(windows(1, 1)));
  }

  private static List<float[]> infer(List<long[][]> windows) {
    List<float[]> result = new ArrayList<>();
    for (long[][] window : windows) result.add(new float[] {window[0][0], 1f});
    return result;
  }

  private static List<long[][]> windows(int first, int count) {
    List<long[][]> result = new ArrayList<>();
    for (int i = first; i < first + count; i++) {
      result.add(new long[][] {new long[] {i}, new long[] {1}, new long[] {0}});
    }
    return result;
  }

  private static float[] normalized(float first, float second) {
    double norm = Math.sqrt((double) first * first + (double) second * second);
    return new float[] {(float) (first / norm), (float) (second / norm)};
  }
}
