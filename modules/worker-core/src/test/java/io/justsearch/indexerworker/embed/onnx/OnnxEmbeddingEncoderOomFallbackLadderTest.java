/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OnnxEmbeddingEncoder#runOomFallbackLadder}, the GPU-batch1-then-CPU
 * fallback ladder extracted as a testable seam (tempdoc 710 Move 3) so its retry-order /
 * exception-routing logic can be exercised without a real ORT session or a real GPU OOM (neither
 * is reproducible in a unit test — see {@code NativeSessionHandleTest} for the sibling canary
 * that pins the BFC-arena message match itself).
 *
 * <p>Mirrors the intent of {@code SpladeEncoderBoundedTokenizeTest} in spirit (an observable
 * contract test standing in for a memory property that can't be asserted in-JVM), but here the
 * property under test is retry ORDERING and EXCEPTION ROUTING, not tokenization equivalence.
 */
@DisplayName("710: OnnxEmbeddingEncoder OOM fallback ladder (batch-1-on-GPU, then CPU)")
final class OnnxEmbeddingEncoderOomFallbackLadderTest {

  private static OrtException bfcArenaFailure(String detail) {
    return new OrtException(
        "BFCArena::AllocateRawInternal Available memory of 1000 is smaller than requested bytes"
            + " of 2000 ("
            + detail
            + ")");
  }

  private static float[][] hiddenFor(int index) {
    return new float[][] {{index, index + 0.5f}};
  }

  @Test
  @DisplayName("all docs succeed on GPU at batch=1 -> CPU runner never invoked")
  void allGpuSinglesSucceed() throws OrtException {
    List<Integer> cpuCalls = new ArrayList<>();
    float[][][] result =
        OnnxEmbeddingEncoder.runOomFallbackLadder(
            3,
            OnnxEmbeddingEncoderOomFallbackLadderTest::hiddenFor,
            i -> {
              cpuCalls.add(i);
              throw new AssertionError("CPU runner should not be invoked when GPU singles succeed");
            });

    assertTrue(cpuCalls.isEmpty(), "CPU runner must not be invoked when every GPU single succeeds");
    for (int i = 0; i < 3; i++) {
      assertArrayEquals(hiddenFor(i), result[i], "result[" + i + "] mismatch");
    }
  }

  @Test
  @DisplayName("one doc arena-OOMs at batch=1 -> only that doc falls back to CPU, others stay GPU")
  void singleDocFallsBackToCpuOnArenaOom() throws OrtException {
    List<Integer> cpuCalls = new ArrayList<>();
    float[][] cpuResultForDoc1 = {{-1f, -2f}};

    float[][][] result =
        OnnxEmbeddingEncoder.runOomFallbackLadder(
            3,
            i -> {
              if (i == 1) {
                throw bfcArenaFailure("doc1-gpu-single-oom");
              }
              return hiddenFor(i);
            },
            i -> {
              cpuCalls.add(i);
              return cpuResultForDoc1;
            });

    assertEquals(List.of(1), cpuCalls, "CPU runner should be invoked exactly once, for doc 1");
    assertArrayEquals(hiddenFor(0), result[0], "doc 0 must keep its GPU-single result");
    assertSame(cpuResultForDoc1, result[1], "doc 1 must use the CPU fallback result");
    assertArrayEquals(hiddenFor(2), result[2], "doc 2 must keep its GPU-single result");
  }

  @Test
  @DisplayName("non-arena OrtException from a GPU single propagates without touching CPU runner")
  void nonArenaExceptionPropagatesWithoutCpuFallback() {
    OrtException nonArenaFailure = new OrtException("Session creation failed: unrelated error");
    List<Integer> cpuCalls = new ArrayList<>();

    OrtException thrown =
        assertThrows(
            OrtException.class,
            () ->
                OnnxEmbeddingEncoder.runOomFallbackLadder(
                    2,
                    i -> {
                      if (i == 0) {
                        throw nonArenaFailure;
                      }
                      return hiddenFor(i);
                    },
                    i -> {
                      cpuCalls.add(i);
                      return hiddenFor(i);
                    }));

    assertSame(nonArenaFailure, thrown, "non-arena exception must propagate unmodified");
    assertTrue(cpuCalls.isEmpty(), "CPU runner must not be invoked for a non-arena failure");
  }

  @Test
  @DisplayName("doc arena-OOMs on GPU AND on CPU -> exception propagates (no further fallback)")
  void cpuAlsoFailingPropagates() {
    OrtException cpuFailure = bfcArenaFailure("cpu-also-oom");

    OrtException thrown =
        assertThrows(
            OrtException.class,
            () ->
                OnnxEmbeddingEncoder.runOomFallbackLadder(
                    1,
                    i -> {
                      throw bfcArenaFailure("gpu-single-oom");
                    },
                    i -> {
                      throw cpuFailure;
                    }));

    assertSame(cpuFailure, thrown, "CPU-side failure must propagate unmodified (no retry beyond CPU)");
  }

  @Test
  @DisplayName("mixed batch: order is preserved across a mix of GPU-success and CPU-fallback docs")
  void preservesOrderAcrossMixedOutcomes() throws OrtException {
    // Docs 1 and 3 arena-OOM at batch=1 on GPU and fall back to CPU; 0, 2, 4 stay on GPU.
    float[][][] result =
        OnnxEmbeddingEncoder.runOomFallbackLadder(
            5,
            i -> {
              if (i == 1 || i == 3) {
                throw bfcArenaFailure("doc" + i + "-oom");
              }
              return hiddenFor(i);
            },
            i -> new float[][] {{100f + i, 200f + i}});

    assertArrayEquals(hiddenFor(0), result[0]);
    assertArrayEquals(new float[][] {{101f, 201f}}, result[1]);
    assertArrayEquals(hiddenFor(2), result[2]);
    assertArrayEquals(new float[][] {{103f, 203f}}, result[3]);
    assertArrayEquals(hiddenFor(4), result[4]);
    assertFalse(result[1] == result[3], "distinct doc fallbacks must not alias the same array");
  }
}
