package io.justsearch.benchmarks.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BenchmarkUtils}.
 *
 * <p>Critical tests verify that the new Apache Commons Math-based percentile implementation matches
 * the legacy EngineIndexBench algorithm exactly.
 */
class BenchmarkUtilsTest {

  @Nested
  class PercentileTests {

    /**
     * CRITICAL: Verify the new percentile implementation matches the legacy EngineIndexBench
     * algorithm for a variety of inputs.
     */
    @Test
    void percentile_matchesLegacyAlgorithm() {
      double[] testData = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0};

      for (double p : new double[] {0.0, 0.1, 0.25, 0.5, 0.75, 0.90, 0.95, 0.99, 1.0}) {
        double expected = legacyPercentile(toList(testData), p);
        double actual = BenchmarkUtils.percentile(testData, p);
        assertEquals(
            expected, actual, 1e-10, "Mismatch at p=" + p + " for testData=[1..10]");
      }
    }

    /** CRITICAL: Verify with a larger, irregular dataset. */
    @Test
    void percentile_matchesLegacyAlgorithm_largerDataset() {
      double[] testData = {
        3.14, 2.71, 1.41, 1.73, 2.23, 6.28, 9.81, 0.57, 1.61, 2.30, 4.67, 8.31, 5.55, 7.77, 0.69
      };

      for (double p : new double[] {0.0, 0.25, 0.5, 0.75, 0.95, 1.0}) {
        double expected = legacyPercentile(toList(testData), p);
        double actual = BenchmarkUtils.percentile(testData, p);
        assertEquals(
            expected, actual, 1e-10, "Mismatch at p=" + p + " for irregular dataset");
      }
    }

    @Test
    void percentile_emptyArrayReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentile(new double[] {}, 0.5));
    }

    @Test
    void percentile_nullArrayReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentile((double[]) null, 0.5));
    }

    @Test
    void percentile_singleElementReturnsThatElement() {
      assertEquals(42.0, BenchmarkUtils.percentile(new double[] {42.0}, 0.5));
      assertEquals(42.0, BenchmarkUtils.percentile(new double[] {42.0}, 0.0));
      assertEquals(42.0, BenchmarkUtils.percentile(new double[] {42.0}, 1.0));
    }

    @Test
    void percentile_pZeroReturnsMin() {
      assertEquals(1.0, BenchmarkUtils.percentile(new double[] {3.0, 1.0, 2.0}, 0.0));
    }

    @Test
    void percentile_pOneReturnsMax() {
      assertEquals(3.0, BenchmarkUtils.percentile(new double[] {3.0, 1.0, 2.0}, 1.0));
    }

    @Test
    void percentile_negativeP_treatedAsZero() {
      assertEquals(1.0, BenchmarkUtils.percentile(new double[] {3.0, 1.0, 2.0}, -0.1));
    }

    @Test
    void percentile_pGreaterThanOne_treatedAsOne() {
      assertEquals(3.0, BenchmarkUtils.percentile(new double[] {3.0, 1.0, 2.0}, 1.5));
    }

    @Test
    void percentile_twoElements() {
      double[] data = {10.0, 20.0};
      assertEquals(10.0, BenchmarkUtils.percentile(data, 0.0), 1e-10);
      assertEquals(15.0, BenchmarkUtils.percentile(data, 0.5), 1e-10);
      assertEquals(20.0, BenchmarkUtils.percentile(data, 1.0), 1e-10);
    }

    @Test
    void percentileList_emptyListReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentile(List.of(), 0.5));
    }

    @Test
    void percentileList_nullListReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentile((List<Double>) null, 0.5));
    }

    @Test
    void percentileList_worksCorrectly() {
      List<Double> data = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
      assertEquals(3.0, BenchmarkUtils.percentile(data, 0.5), 1e-10);
    }

    @Test
    void percentileLong_emptyListReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentileLong(List.of(), 0.5));
    }

    @Test
    void percentileLong_nullListReturnsZero() {
      assertEquals(0.0, BenchmarkUtils.percentileLong(null, 0.5));
    }

    @Test
    void percentileLong_worksCorrectly() {
      List<Long> data = List.of(1000L, 2000L, 3000L, 4000L, 5000L);
      assertEquals(3000.0, BenchmarkUtils.percentileLong(data, 0.5), 1e-10);
    }

    /**
     * Legacy percentile algorithm copied from EngineIndexBench for verification. This is the R-7
     * linear interpolation method.
     */
    private static double legacyPercentile(List<Double> valuesMs, double p) {
      if (valuesMs == null || valuesMs.isEmpty()) return 0.0;
      List<Double> sorted = new ArrayList<>(valuesMs);
      sorted.sort(Double::compareTo);
      if (p <= 0) return sorted.getFirst();
      if (p >= 1) return sorted.getLast();
      double rank = p * (sorted.size() - 1);
      int lo = (int) Math.floor(rank);
      int hi = (int) Math.ceil(rank);
      if (lo == hi) return sorted.get(lo);
      double w = rank - lo;
      return sorted.get(lo) * (1.0 - w) + sorted.get(hi) * w;
    }

    private static List<Double> toList(double[] arr) {
      return Arrays.stream(arr).boxed().toList();
    }
  }

  @Nested
  class RoundingTests {
    @Test
    void round2_roundsToTwoDecimalPlaces() {
      assertEquals(3.14, BenchmarkUtils.round2(3.14159), 1e-10);
      assertEquals(3.15, BenchmarkUtils.round2(3.145), 1e-10);
      assertEquals(3.14, BenchmarkUtils.round2(3.144), 1e-10);
    }

    @Test
    void round2_handlesNegativeNumbers() {
      assertEquals(-3.14, BenchmarkUtils.round2(-3.14159), 1e-10);
    }

    @Test
    void round2_handlesZero() {
      assertEquals(0.0, BenchmarkUtils.round2(0.0), 1e-10);
    }

    @Test
    void round3_roundsToThreeDecimalPlaces() {
      assertEquals(3.142, BenchmarkUtils.round3(3.14159), 1e-10);
      assertEquals(3.142, BenchmarkUtils.round3(3.1415), 1e-10);
      assertEquals(3.141, BenchmarkUtils.round3(3.1414), 1e-10);
    }

    @Test
    void round3_handlesNegativeNumbers() {
      assertEquals(-3.142, BenchmarkUtils.round3(-3.14159), 1e-10);
    }

    @Test
    void round3_handlesZero() {
      assertEquals(0.0, BenchmarkUtils.round3(0.0), 1e-10);
    }
  }

  @Nested
  class GitShaTests {
    @Test
    void getGitSha_returnsNonNullInGitRepo() {
      // This test assumes we're running inside the JustSearch repo
      String sha = BenchmarkUtils.getGitSha();
      // May be null if git is not available, but if non-null should be valid SHA
      if (sha != null) {
        assertTrue(sha.length() >= 40, "SHA should be full length: " + sha);
        assertTrue(sha.matches("[a-f0-9]+"), "SHA should be hex: " + sha);
      }
    }

    @Test
    void shortSha_truncatesToSevenChars() {
      String fullSha = "abc1234567890def";
      assertEquals("abc1234", BenchmarkUtils.shortSha(fullSha));
    }

    @Test
    void shortSha_returnsNullForNull() {
      assertNull(BenchmarkUtils.shortSha(null));
    }

    @Test
    void shortSha_returnsInputIfTooShort() {
      assertEquals("abc", BenchmarkUtils.shortSha("abc"));
      assertEquals("abcdef", BenchmarkUtils.shortSha("abcdef"));
    }

    @Test
    void shortSha_handlesExactlySevenChars() {
      assertEquals("abcdefg", BenchmarkUtils.shortSha("abcdefg"));
    }
  }

  @Nested
  class FileSystemTests {
    @TempDir Path tempDir;

    @Test
    void deleteRecursively_deletesNestedDirectories() throws IOException {
      // Create nested structure
      Path nested = tempDir.resolve("a/b/c");
      Files.createDirectories(nested);
      Files.writeString(nested.resolve("file.txt"), "test");
      Files.writeString(tempDir.resolve("a/b/other.txt"), "test2");

      Path target = tempDir.resolve("a");
      assertTrue(Files.exists(target));

      BenchmarkUtils.deleteRecursively(target);

      assertFalse(Files.exists(target));
    }

    @Test
    void deleteRecursively_handlesNull() throws IOException {
      // Should not throw
      BenchmarkUtils.deleteRecursively(null);
    }

    @Test
    void deleteRecursively_handlesNonExistent() throws IOException {
      Path nonExistent = tempDir.resolve("does-not-exist");
      // Should not throw
      BenchmarkUtils.deleteRecursively(nonExistent);
    }

    @Test
    void deleteRecursively_deletesEmptyDirectory() throws IOException {
      Path emptyDir = tempDir.resolve("empty");
      Files.createDirectory(emptyDir);

      BenchmarkUtils.deleteRecursively(emptyDir);

      assertFalse(Files.exists(emptyDir));
    }

    @Test
    void directorySizeBytes_calculatesCorrectly() throws IOException {
      Path dir = tempDir.resolve("sized");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("a.txt"), "hello"); // 5 bytes
      Files.writeString(dir.resolve("b.txt"), "world!"); // 6 bytes

      long size = BenchmarkUtils.directorySizeBytes(dir);
      assertEquals(11L, size);
    }

    @Test
    void directorySizeBytes_includesSubdirectories() throws IOException {
      Path dir = tempDir.resolve("nested");
      Files.createDirectories(dir.resolve("sub"));
      Files.writeString(dir.resolve("a.txt"), "abc"); // 3 bytes
      Files.writeString(dir.resolve("sub/b.txt"), "defgh"); // 5 bytes

      long size = BenchmarkUtils.directorySizeBytes(dir);
      assertEquals(8L, size);
    }

    @Test
    void directorySizeBytes_returnsZeroForNonDirectory() throws IOException {
      Path file = tempDir.resolve("file.txt");
      Files.writeString(file, "content");

      assertEquals(0L, BenchmarkUtils.directorySizeBytes(file));
    }

    @Test
    void directorySizeBytes_returnsZeroForEmptyDirectory() throws IOException {
      Path empty = tempDir.resolve("empty");
      Files.createDirectory(empty);

      assertEquals(0L, BenchmarkUtils.directorySizeBytes(empty));
    }
  }

  @Nested
  class RandomVectorTests {
    @Test
    void randomVector_createsCorrectDimension() {
      Random rnd = new Random(42);
      float[] v = BenchmarkUtils.randomVector(rnd, 768);
      assertEquals(768, v.length);
    }

    @Test
    void randomVector_valuesInRange() {
      Random rnd = new Random(42);
      float[] v = BenchmarkUtils.randomVector(rnd, 1000);

      for (int i = 0; i < v.length; i++) {
        assertTrue(v[i] >= -1f && v[i] <= 1f, "Value at index " + i + " out of range: " + v[i]);
      }
    }

    @Test
    void randomVector_isDeterministicWithSameSeed() {
      float[] v1 = BenchmarkUtils.randomVector(new Random(42), 100);
      float[] v2 = BenchmarkUtils.randomVector(new Random(42), 100);

      assertArrayEquals(v1, v2);
    }

    @Test
    void randomVector_differentSeedsProduceDifferentVectors() {
      float[] v1 = BenchmarkUtils.randomVector(new Random(42), 100);
      float[] v2 = BenchmarkUtils.randomVector(new Random(43), 100);

      assertFalse(Arrays.equals(v1, v2));
    }
  }

  @Nested
  class HeapSnapshotTests {

    @Test
    void capture_returnsNonNegativeValues() {
      BenchmarkUtils.HeapSnapshot snapshot = BenchmarkUtils.HeapSnapshot.capture();

      assertTrue(snapshot.usedBytes() >= 0, "usedBytes should be non-negative");
      assertTrue(snapshot.maxBytes() > 0, "maxBytes should be positive");
      assertTrue(snapshot.gcCount() >= 0, "gcCount should be non-negative");
      assertTrue(snapshot.gcTimeMs() >= 0, "gcTimeMs should be non-negative");
    }

    @Test
    void capture_usedBytesLessThanOrEqualToMaxBytes() {
      BenchmarkUtils.HeapSnapshot snapshot = BenchmarkUtils.HeapSnapshot.capture();

      assertTrue(
          snapshot.usedBytes() <= snapshot.maxBytes(),
          "usedBytes (" + snapshot.usedBytes() + ") should be <= maxBytes (" + snapshot.maxBytes() + ")");
    }

    @Test
    void toMap_containsExpectedKeys() {
      BenchmarkUtils.HeapSnapshot snapshot = BenchmarkUtils.HeapSnapshot.capture();
      Map<String, Object> map = snapshot.toMap();

      assertTrue(map.containsKey("used_bytes"), "Map should contain 'used_bytes'");
      assertTrue(map.containsKey("max_bytes"), "Map should contain 'max_bytes'");
      assertTrue(map.containsKey("gc_count"), "Map should contain 'gc_count'");
      assertTrue(map.containsKey("gc_time_ms"), "Map should contain 'gc_time_ms'");
      assertEquals(4, map.size(), "Map should have exactly 4 entries");
    }

    @Test
    void toMap_valuesMatchRecordFields() {
      BenchmarkUtils.HeapSnapshot snapshot = BenchmarkUtils.HeapSnapshot.capture();
      Map<String, Object> map = snapshot.toMap();

      assertEquals(snapshot.usedBytes(), map.get("used_bytes"));
      assertEquals(snapshot.maxBytes(), map.get("max_bytes"));
      assertEquals(snapshot.gcCount(), map.get("gc_count"));
      assertEquals(snapshot.gcTimeMs(), map.get("gc_time_ms"));
    }

    @Test
    void record_isImmutable() {
      BenchmarkUtils.HeapSnapshot snapshot =
          new BenchmarkUtils.HeapSnapshot(1000L, 2000L, 5L, 100L);

      assertEquals(1000L, snapshot.usedBytes());
      assertEquals(2000L, snapshot.maxBytes());
      assertEquals(5L, snapshot.gcCount());
      assertEquals(100L, snapshot.gcTimeMs());
    }
  }

  @Nested
  class RssSnapshotTests {

    @Test
    void capture_returnsNonNegativeOrUnavailable() {
      BenchmarkUtils.RssSnapshot snapshot = BenchmarkUtils.RssSnapshot.capture();
      // Either unavailable (-1) or valid positive value
      assertTrue(
          snapshot.rssBytes() == -1 || snapshot.rssBytes() > 0,
          "rssBytes should be -1 or positive, got: " + snapshot.rssBytes());
    }

    @Test
    void capture_rssIsReasonableWhenAvailable() {
      BenchmarkUtils.RssSnapshot snapshot = BenchmarkUtils.RssSnapshot.capture();
      if (snapshot.isAvailable()) {
        // RSS should be at least 10MB for any JVM process
        assertTrue(
            snapshot.rssBytes() >= 10L * 1024 * 1024,
            "RSS seems too small: " + snapshot.rssBytes());
        // And less than 100GB (sanity check)
        assertTrue(
            snapshot.rssBytes() < 100L * 1024 * 1024 * 1024,
            "RSS seems too large: " + snapshot.rssBytes());
      }
    }

    @Test
    void toMap_returnsEmptyMapWhenUnavailable() {
      Map<String, Object> map = BenchmarkUtils.RssSnapshot.UNAVAILABLE.toMap();
      assertTrue(map.isEmpty());
    }

    @Test
    void toMap_containsExpectedKeysWhenAvailable() {
      BenchmarkUtils.RssSnapshot snapshot = BenchmarkUtils.RssSnapshot.capture();
      if (snapshot.isAvailable()) {
        Map<String, Object> map = snapshot.toMap();
        assertTrue(map.containsKey("rss_bytes"), "Map should contain 'rss_bytes'");
        assertEquals(snapshot.rssBytes(), map.get("rss_bytes"));
      }
    }

    @Test
    void isAvailable_returnsTrueForPositiveRss() {
      assertTrue(new BenchmarkUtils.RssSnapshot(1000L, 2000L).isAvailable());
    }

    @Test
    void isAvailable_returnsFalseForNegativeRss() {
      assertFalse(new BenchmarkUtils.RssSnapshot(-1L, -1L).isAvailable());
      assertFalse(BenchmarkUtils.RssSnapshot.UNAVAILABLE.isAvailable());
    }

    @Test
    void unavailableConstant_hasNegativeValues() {
      assertEquals(-1L, BenchmarkUtils.RssSnapshot.UNAVAILABLE.rssBytes());
      assertEquals(-1L, BenchmarkUtils.RssSnapshot.UNAVAILABLE.vszBytes());
    }

    @Test
    void toMap_includesVszWhenAvailable() {
      BenchmarkUtils.RssSnapshot snapshot = new BenchmarkUtils.RssSnapshot(1000L, 2000L);
      Map<String, Object> map = snapshot.toMap();
      assertTrue(map.containsKey("vsz_bytes"), "Map should contain 'vsz_bytes'");
      assertEquals(2000L, map.get("vsz_bytes"));
    }

    @Test
    void toMap_excludesVszWhenNegative() {
      BenchmarkUtils.RssSnapshot snapshot = new BenchmarkUtils.RssSnapshot(1000L, -1L);
      Map<String, Object> map = snapshot.toMap();
      assertTrue(map.containsKey("rss_bytes"));
      assertFalse(map.containsKey("vsz_bytes"), "Map should not contain 'vsz_bytes' when negative");
    }

    @Test
    void isAvailable_returnsFalseForZeroRss() {
      // Zero RSS is treated as unavailable (containers may incorrectly report 0)
      assertFalse(new BenchmarkUtils.RssSnapshot(0L, 2000L).isAvailable());
    }

    @Test
    void capture_rssIsGreaterThanOrEqualToHeapWhenAvailable() {
      BenchmarkUtils.RssSnapshot rss = BenchmarkUtils.RssSnapshot.capture();
      BenchmarkUtils.HeapSnapshot heap = BenchmarkUtils.HeapSnapshot.capture();
      if (rss.isAvailable()) {
        // RSS should always be >= heap used bytes because RSS includes heap + native + mmap
        assertTrue(
            rss.rssBytes() >= heap.usedBytes(),
            "RSS ("
                + rss.rssBytes()
                + ") should be >= heap used ("
                + heap.usedBytes()
                + ")");
      }
    }
  }
}
