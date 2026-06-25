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
