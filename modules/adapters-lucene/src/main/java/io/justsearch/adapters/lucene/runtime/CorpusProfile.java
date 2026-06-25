/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/**
 * Cached corpus-level statistics used for routing decisions (e.g., whether to enable chunk-aware
 * merge). Computed from {@code parent_token_count} DocValues across all parent documents in the
 * index.
 *
 * <p>Bucket boundaries are aligned with the SPLADE/CE threshold boundaries from the search routing
 * design (tempdoc 270/309): 256, 512, 1024, 2048, 4096 tokens.
 */
public record CorpusProfile(
    long parentDocCount,
    long chunkDocCount,
    long totalTokenCount,
    long docsWithTokenCount,
    int[] tokenCountBuckets) {

  /** Bucket boundaries (upper-exclusive): [0-256), [256-512), [512-1024), [1024-2048), [2048-4096), [4096+). */
  static final int[] BUCKET_BOUNDARIES = {256, 512, 1024, 2048, 4096};

  /** Midpoints for each bucket, used for approximate median computation. */
  private static final long[] BUCKET_MIDPOINTS = {128, 384, 768, 1536, 3072, 6144};

  public static final CorpusProfile EMPTY = new CorpusProfile(0, 0, 0, 0, new int[6]);

  /** Fraction of parent documents that have at least one chunk document. */
  public double chunkRate() {
    return parentDocCount == 0 ? 0.0 : (double) chunkDocCount / parentDocCount;
  }

  /**
   * Approximate median token count, computed from the bucket histogram. Returns the midpoint of the
   * bucket containing the median. Returns 0 if no documents have token count data.
   */
  public long medianTokenCount() {
    if (docsWithTokenCount == 0) return 0;
    long half = docsWithTokenCount / 2;
    long cumulative = 0;
    for (int i = 0; i < tokenCountBuckets.length; i++) {
      cumulative += tokenCountBuckets[i];
      if (cumulative > half) {
        return BUCKET_MIDPOINTS[i];
      }
    }
    return BUCKET_MIDPOINTS[BUCKET_MIDPOINTS.length - 1];
  }

  /**
   * Returns true if the corpus is predominantly short documents, meaning chunk-aware merge is
   * unlikely to help (chunks ≈ documents, branch fusion injects noise).
   */
  public boolean isShortCorpus() {
    return medianTokenCount() < 512 || chunkRate() < 0.05;
  }

  /**
   * Returns true if the corpus is predominantly long documents, meaning chunk-aware merge should
   * be fully enabled.
   */
  public boolean isLongCorpus() {
    return medianTokenCount() > 2048 && chunkRate() > 0.5;
  }

  /** Returns the bucket index for a given token count. */
  static int bucketFor(long tokenCount) {
    for (int i = 0; i < BUCKET_BOUNDARIES.length; i++) {
      if (tokenCount < BUCKET_BOUNDARIES[i]) return i;
    }
    return BUCKET_BOUNDARIES.length; // last bucket (4096+)
  }
}
