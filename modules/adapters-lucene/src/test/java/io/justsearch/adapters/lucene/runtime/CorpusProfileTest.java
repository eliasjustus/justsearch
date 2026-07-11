package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CorpusProfileTest {

  @Nested
  class MedianTokenCount {

    @Test
    void emptyProfileReturnsZero() {
      assertEquals(0, CorpusProfile.EMPTY.medianTokenCount());
    }

    @Test
    void allDocsInFirstBucketReturnsFirstMidpoint() {
      // 100 docs all in [0-256) bucket
      var profile = new CorpusProfile(100, 0, 10_000, 100, new int[] {100, 0, 0, 0, 0, 0});
      assertEquals(128, profile.medianTokenCount());
    }

    @Test
    void allDocsInLastBucketReturnsLastMidpoint() {
      // 50 docs all in [4096+) bucket
      var profile = new CorpusProfile(50, 30, 300_000, 50, new int[] {0, 0, 0, 0, 0, 50});
      assertEquals(6144, profile.medianTokenCount());
    }

    @Test
    void medianInMiddleBucket() {
      // 10 in [0-256), 10 in [512-1024), 10 in [4096+)
      // median is doc 15 → in [512-1024) bucket → midpoint 768
      var profile = new CorpusProfile(30, 0, 0, 30, new int[] {10, 0, 10, 0, 0, 10});
      assertEquals(768, profile.medianTokenCount());
    }

    @Test
    void bimodalDistribution() {
      // 40 short docs [0-256), 60 long docs [2048-4096)
      // median is doc 50 → in [2048-4096) bucket → midpoint 3072
      var profile = new CorpusProfile(100, 50, 0, 100, new int[] {40, 0, 0, 0, 60, 0});
      assertEquals(3072, profile.medianTokenCount());
    }

    @Test
    void noDocsWithTokenCountReturnsZero() {
      var profile = new CorpusProfile(100, 0, 0, 0, new int[] {0, 0, 0, 0, 0, 0});
      assertEquals(0, profile.medianTokenCount());
    }
  }

  @Nested
  class ChunkRate {

    @Test
    void zeroDocs() {
      assertEquals(0.0, CorpusProfile.EMPTY.chunkRate());
    }

    @Test
    void noChunks() {
      var profile = new CorpusProfile(100, 0, 0, 0, new int[6]);
      assertEquals(0.0, profile.chunkRate());
    }

    @Test
    void halfChunked() {
      var profile = new CorpusProfile(100, 50, 0, 0, new int[6]);
      assertEquals(0.5, profile.chunkRate(), 0.001);
    }
  }

  @Nested
  class RegimeClassification {

    @Test
    void shortCorpusWhenMedianBelow512() {
      // All docs in [0-256) bucket
      var profile = new CorpusProfile(100, 10, 10_000, 100, new int[] {100, 0, 0, 0, 0, 0});
      assertTrue(profile.isShortCorpus());
      assertFalse(profile.isLongCorpus());
    }

    @Test
    void shortCorpusWhenChunkRateBelow5Percent() {
      // Median is in [1024-2048) but almost no chunks
      var profile = new CorpusProfile(100, 3, 0, 100, new int[] {0, 0, 0, 100, 0, 0});
      assertTrue(profile.isShortCorpus());
    }

    @Test
    void longCorpusWhenMedianAbove2048AndHighChunkRate() {
      // All docs in [4096+) bucket with high chunk rate
      var profile = new CorpusProfile(100, 80, 0, 100, new int[] {0, 0, 0, 0, 0, 100});
      assertFalse(profile.isShortCorpus());
      assertTrue(profile.isLongCorpus());
    }

    @Test
    void mixedCorpusWhenNeitherShortNorLong() {
      // Median in [1024-2048) with moderate chunk rate
      var profile = new CorpusProfile(100, 40, 0, 100, new int[] {20, 10, 10, 30, 20, 10});
      assertFalse(profile.isShortCorpus());
      assertFalse(profile.isLongCorpus());
    }

    @Test
    void emptyProfileIsShort() {
      assertTrue(CorpusProfile.EMPTY.isShortCorpus());
      assertFalse(CorpusProfile.EMPTY.isLongCorpus());
    }

    /**
     * Tempdoc 717 regression: a corpus with chunks but NO token data ({@code parent_token_count}
     * left unpopulated by a SPLADE-load race at index time) must NOT be classified short — {@code
     * medianTokenCount()==0} means <em>unknown</em>, not <em>short</em>. This is exactly the
     * degenerate legal-clerc state (198 parents, 4293 chunks, zero token data) that intermittently
     * skipped the {@code chunk_merge} leg and halved dense quality (0.34 vs 0.62).
     */
    @Test
    void chunksButNoTokenDataIsNotShort() {
      var profile = new CorpusProfile(198, 4293, 0, 0, new int[] {0, 0, 0, 0, 0, 0});
      assertEquals(0, profile.medianTokenCount(), "no token data → median unknown (0)");
      assertFalse(
          profile.isShortCorpus(),
          "a corpus that produced chunks must not be short on absent token data");
    }

    /**
     * Tempdoc 717: the fail-open on missing token data does NOT over-open — a corpus with no token
     * data AND genuinely few chunks (chunkRate &lt; 0.05) is still short via the chunkRate gate.
     */
    @Test
    void noTokenDataButFewChunksStillShort() {
      var profile = new CorpusProfile(100, 2, 0, 0, new int[6]); // chunkRate 0.02 < 0.05
      assertTrue(profile.isShortCorpus());
    }

    /**
     * Tempdoc 717 (review Finding 1): a large chunked corpus where only a MINORITY of parents have
     * token data must not be classified short off that unreliable median — the token-median test
     * requires majority coverage. Here 5 of 200 parents are covered (all short), but 190 chunks →
     * fail open.
     */
    @Test
    void minorityTokenCoverageDoesNotTrustMedian() {
      var profile = new CorpusProfile(200, 190, 640, 5, new int[] {5, 0, 0, 0, 0, 0});
      assertEquals(128, profile.medianTokenCount(), "median over the tiny covered subset is low");
      assertFalse(
          profile.isShortCorpus(),
          "a minority-coverage median must not classify a chunked corpus short");
    }
  }

  @Nested
  class BucketFor {

    @Test
    void zeroPutInFirstBucket() {
      assertEquals(0, CorpusProfile.bucketFor(0));
    }

    @Test
    void boundaryGoesInNextBucket() {
      assertEquals(1, CorpusProfile.bucketFor(256));
      assertEquals(2, CorpusProfile.bucketFor(512));
      assertEquals(3, CorpusProfile.bucketFor(1024));
      assertEquals(4, CorpusProfile.bucketFor(2048));
      assertEquals(5, CorpusProfile.bucketFor(4096));
    }

    @Test
    void justBelowBoundaryStaysInCurrentBucket() {
      assertEquals(0, CorpusProfile.bucketFor(255));
      assertEquals(1, CorpusProfile.bucketFor(511));
      assertEquals(2, CorpusProfile.bucketFor(1023));
    }

    @Test
    void largeValueGoesInLastBucket() {
      assertEquals(5, CorpusProfile.bucketFor(100_000));
    }
  }
}
