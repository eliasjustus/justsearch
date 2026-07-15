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
   *
   * <p>Tempdoc 717: the token-median test only fires when token data actually exists
   * ({@code docsWithTokenCount > 0}). {@link #medianTokenCount()} returns 0 when there is NO token
   * data — that means <em>unknown</em>, not <em>short</em>. Treating unknown as short mis-classified
   * a genuinely long corpus whose {@code parent_token_count} was left unpopulated by a SPLADE-load
   * race at index time, causing the {@code chunk_merge} leg to be skipped and dense quality to
   * halve. Fail OPEN for chunks on missing data: a corpus that produced chunks (high
   * {@link #chunkRate()}) is not short. Running chunk-merge on a genuinely short corpus is
   * hybrid-neutral (F-036); skipping it on a long one is the catastrophic case.
   */
  public boolean isShortCorpus() {
    // Trust the token-median only when token data covers a MAJORITY of parents. A small covered
    // subset can drag the reported median under 512 and mis-classify a large corpus as short
    // (tempdoc 717 review, Finding 1) — the histogram median is computed only over the covered docs.
    // Below majority coverage, fail OPEN for chunks (only the chunkRate gate can classify short).
    boolean tokenDataReliable = docsWithTokenCount * 2 >= parentDocCount;
    boolean shortByTokens = tokenDataReliable && medianTokenCount() < 512;
    return shortByTokens || chunkRate() < 0.05;
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
