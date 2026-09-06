/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Sliding token windows backed by the original token arrays, with no retained window copies.
 *
 * <p>Callers must leave the source arrays unchanged while using this view. Each {@link #get}
 * returns independent copies; a sublist remains a lazy view. The final window preserves the
 * encoder's next-start tiny-tail replacement rule, including extension up to {@code maxSeqLen}.
 */
final class TokenWindows extends AbstractList<long[][]> implements RandomAccess {
  private final long[] ids;
  private final long[] mask;
  private final long[] typeIds;
  private final int chunkSize;
  private final int maxSeqLen;
  private final long stride;
  private final int size;

  TokenWindows(
      long[] ids, long[] mask, long[] typeIds, int chunkSize, int chunkOverlap, int maxSeqLen) {
    this.ids = Objects.requireNonNull(ids, "ids");
    this.mask = Objects.requireNonNull(mask, "mask");
    this.typeIds = Objects.requireNonNull(typeIds, "typeIds");
    if (ids.length != mask.length || ids.length != typeIds.length) {
      throw new IllegalArgumentException("Token array lengths must match");
    }
    this.size = count(ids.length, chunkSize, chunkOverlap, maxSeqLen);
    this.chunkSize = chunkSize;
    this.maxSeqLen = maxSeqLen;
    this.stride = Math.max(1L, (long) chunkSize - chunkOverlap);
  }

  /** Counts windows without allocating token arrays or per-window metadata. */
  static int count(int tokenCount, int chunkSize, int chunkOverlap, int maxSeqLen) {
    if (tokenCount < 0 || chunkSize <= 0 || maxSeqLen <= 0) {
      throw new IllegalArgumentException("Token count must be nonnegative and window sizes positive");
    }
    if (tokenCount == 0) {
      return 0;
    }
    long stride = Math.max(1L, (long) chunkSize - chunkOverlap);
    // A next start continues exactly when at least max(1, chunkSize / 4) tokens remain.
    return 1 + (int) (Math.max(0L, (long) tokenCount - Math.max(1, chunkSize / 4)) / stride);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public long[][] get(int index) {
    Objects.checkIndex(index, size);
    long start = index * stride;
    long nextStart = start + stride;
    boolean extend = nextStart < ids.length && ids.length - nextStart < chunkSize / 4;
    int end = (int) Math.min(ids.length, start + (extend ? maxSeqLen : chunkSize));
    int offset = (int) start;
    return new long[][] {
      Arrays.copyOfRange(ids, offset, end),
      Arrays.copyOfRange(mask, offset, end),
      Arrays.copyOfRange(typeIds, offset, end)
    };
  }
}
