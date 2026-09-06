/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone, dependency-free child JVM for the bounded-heap regression. */
public final class TokenWindowsHeapProbe {
  private TokenWindowsHeapProbe() {}

  public static void main(String[] args) {
    int tokens = 3_000_000;
    long[] ids = new long[tokens];
    long[] mask = new long[tokens];
    long[] types = new long[tokens];
    Arrays.fill(ids, 1);
    Arrays.fill(mask, 2);
    Arrays.fill(types, 3);
    if ("eager".equals(args[0])) {
      try {
        List<long[][]> eager = eagerOracle(ids, mask, types, 512, 128, 512);
        throw new IllegalStateException("Eager windows unexpectedly fit: " + eager.size());
      } catch (OutOfMemoryError expected) {
        System.out.println("EAGER_OOM");
      }
      return;
    }
    List<long[][]> windows = new TokenWindows(ids, mask, types, 512, 128, 512);
    long sum = 0;
    long copiedTokens = 0;
    for (int offset = 0; offset < windows.size(); offset += 8) {
      List<long[][]> batch = new ArrayList<>(8);
      for (long[][] window : windows.subList(offset, Math.min(offset + 8, windows.size()))) {
        batch.add(window);
      }
      for (long[][] window : batch) {
        copiedTokens += window[0].length;
        for (int index = 0; index < window[0].length; index++) {
          sum += window[0][index] + window[1][index] + window[2][index];
        }
      }
    }
    if (sum != copiedTokens * 6 || copiedTokens < tokens) {
      throw new IllegalStateException("Window traversal lost or corrupted tokens");
    }
    System.out.println("LAZY_OK windows=" + windows.size() + " copiedTokens=" + copiedTokens);
  }

  /** The former encoder loop, kept independent of TokenWindows as a geometry and memory oracle. */
  static List<long[][]> eagerOracle(
      long[] ids, long[] mask, long[] types, int chunkSize, int chunkOverlap, int maxSeqLen) {
    List<long[][]> chunks = new ArrayList<>();
    int stride = Math.max(1, chunkSize - chunkOverlap);
    int start = 0;
    while (start < ids.length) {
      int end = Math.min(start + chunkSize, ids.length);
      chunks.add(copy(ids, mask, types, start, end));
      start += stride;
      if (start < ids.length && ids.length - start < chunkSize / 4) {
        int lastStart = start - stride;
        int lastEnd = Math.min(ids.length, lastStart + maxSeqLen);
        chunks.set(chunks.size() - 1, copy(ids, mask, types, lastStart, lastEnd));
        break;
      }
    }
    return chunks;
  }

  private static long[][] copy(long[] ids, long[] mask, long[] types, int start, int end) {
    return new long[][] {
      Arrays.copyOfRange(ids, start, end),
      Arrays.copyOfRange(mask, start, end),
      Arrays.copyOfRange(types, start, end)
    };
  }
}
