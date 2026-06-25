package io.justsearch.systemtests.relevance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RelevanceMetrics")
class RelevanceMetricsTest {

  private static final double DELTA = 1e-9;

  // ==================== recallAtK ====================

  @Nested
  @DisplayName("recallAtK")
  class RecallAtKTests {

    @Test
    @DisplayName("Perfect recall when all relevant docs in top K")
    void perfectRecall() {
      assertEquals(
          1.0,
          RelevanceMetrics.recallAtK(List.of("A", "B", "C"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Partial recall when some relevant docs in top K")
    void partialRecall() {
      assertEquals(
          0.5,
          RelevanceMetrics.recallAtK(List.of("A", "X", "Y"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Zero recall when no relevant docs in top K")
    void zeroRecall() {
      assertEquals(
          0.0,
          RelevanceMetrics.recallAtK(List.of("X", "Y", "Z"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Returns 1.0 when relevant set is empty (convention)")
    void emptyRelevantSet() {
      assertEquals(
          1.0,
          RelevanceMetrics.recallAtK(List.of("A", "B"), Set.of(), 3),
          DELTA);
    }

    @Test
    @DisplayName("Handles K larger than retrieved list")
    void kLargerThanRetrieved() {
      // 1 of 2 relevant found in a list of 2 with K=10
      assertEquals(
          0.5,
          RelevanceMetrics.recallAtK(List.of("A", "X"), Set.of("A", "B"), 10),
          DELTA);
    }

    @Test
    @DisplayName("Only considers top K results")
    void onlyTopK() {
      // B is at position 4 (0-indexed 3), but K=2 so it's not considered
      assertEquals(
          0.5,
          RelevanceMetrics.recallAtK(List.of("A", "X", "Y", "B"), Set.of("A", "B"), 2),
          DELTA);
    }
  }

  // ==================== precisionAtK ====================

  @Nested
  @DisplayName("precisionAtK")
  class PrecisionAtKTests {

    @Test
    @DisplayName("Perfect precision when all top K are relevant")
    void perfectPrecision() {
      assertEquals(
          1.0,
          RelevanceMetrics.precisionAtK(List.of("A", "B", "C"), Set.of("A", "B", "C"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Partial precision")
    void partialPrecision() {
      // 2 relevant out of K=3
      assertEquals(
          2.0 / 3.0,
          RelevanceMetrics.precisionAtK(List.of("A", "X", "B"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Zero precision when none relevant")
    void zeroPrecision() {
      assertEquals(
          0.0,
          RelevanceMetrics.precisionAtK(List.of("X", "Y", "Z"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Returns 0.0 when K <= 0")
    void kZero() {
      assertEquals(
          0.0, RelevanceMetrics.precisionAtK(List.of("A"), Set.of("A"), 0), DELTA);
    }

    @Test
    @DisplayName("Divides by K even when retrieved list is shorter")
    void kLargerThanRetrieved() {
      // 1 relevant doc retrieved, but K=5 so precision = 1/5
      assertEquals(
          0.2,
          RelevanceMetrics.precisionAtK(List.of("A"), Set.of("A", "B"), 5),
          DELTA);
    }
  }

  // ==================== ndcgAtK ====================

  @Nested
  @DisplayName("ndcgAtK")
  class NdcgAtKTests {

    @Test
    @DisplayName("Perfect NDCG when relevant docs ranked first")
    void perfectRanking() {
      // Both relevant docs at positions 1 and 2 — ideal order
      assertEquals(
          1.0,
          RelevanceMetrics.ndcgAtK(List.of("A", "B", "X"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Returns 1.0 when relevant set is empty (convention)")
    void emptyRelevantSet() {
      assertEquals(
          1.0,
          RelevanceMetrics.ndcgAtK(List.of("X", "Y"), Set.of(), 3),
          DELTA);
    }

    @Test
    @DisplayName("Imperfect ranking produces correct NDCG")
    void imperfectRanking() {
      // retrieved=[A, X, B], relevant={A, B}
      // DCG = 1/log2(2) + 0/log2(3) + 1/log2(4) = 1.0 + 0 + 0.5 = 1.5
      // IDCG = 1/log2(2) + 1/log2(3) = 1.0 + 0.63093 = 1.63093
      // NDCG = 1.5 / 1.63093 ≈ 0.91973
      double expected = 1.5 / (1.0 + 1.0 / (Math.log(3) / Math.log(2)));
      assertEquals(
          expected,
          RelevanceMetrics.ndcgAtK(List.of("A", "X", "B"), Set.of("A", "B"), 3),
          DELTA);
    }

    @Test
    @DisplayName("Single relevant doc at position 1 vs position 3")
    void discountEffect() {
      double atPos1 = RelevanceMetrics.ndcgAtK(List.of("A", "X", "Y"), Set.of("A"), 3);
      double atPos3 = RelevanceMetrics.ndcgAtK(List.of("X", "Y", "A"), Set.of("A"), 3);
      // Position 1 should score higher than position 3
      assertTrue(atPos1 > atPos3, "Doc at position 1 should score higher than position 3");
      // Position 1 is perfect: NDCG = 1.0
      assertEquals(1.0, atPos1, DELTA);
      // Position 3: DCG = 1/log2(4) = 0.5, IDCG = 1/log2(2) = 1.0, NDCG = 0.5
      assertEquals(0.5, atPos3, DELTA);
    }

    @Test
    @DisplayName("Zero NDCG when no relevant docs retrieved")
    void noRelevantRetrieved() {
      assertEquals(
          0.0,
          RelevanceMetrics.ndcgAtK(List.of("X", "Y", "Z"), Set.of("A", "B"), 3),
          DELTA);
    }
  }

  // ==================== reciprocalRank ====================

  @Nested
  @DisplayName("reciprocalRank")
  class ReciprocalRankTests {

    @Test
    @DisplayName("First doc relevant returns 1.0")
    void firstDocRelevant() {
      assertEquals(
          1.0,
          RelevanceMetrics.reciprocalRank(List.of("A", "X", "Y"), Set.of("A")),
          DELTA);
    }

    @Test
    @DisplayName("Third doc relevant returns 1/3")
    void thirdDocRelevant() {
      assertEquals(
          1.0 / 3.0,
          RelevanceMetrics.reciprocalRank(List.of("X", "Y", "A"), Set.of("A")),
          DELTA);
    }

    @Test
    @DisplayName("No relevant docs returns 0.0")
    void noRelevantDocs() {
      assertEquals(
          0.0,
          RelevanceMetrics.reciprocalRank(List.of("X", "Y", "Z"), Set.of("A")),
          DELTA);
    }

    @Test
    @DisplayName("Returns rank of first relevant when multiple relevant exist")
    void multipleRelevant() {
      // B is at position 2, A is at position 3 — returns 1/2 (first found)
      assertEquals(
          0.5,
          RelevanceMetrics.reciprocalRank(List.of("X", "B", "A"), Set.of("A", "B")),
          DELTA);
    }
  }

  // ==================== QueryResult ====================

  @Nested
  @DisplayName("QueryResult")
  class QueryResultTests {

    @Test
    @DisplayName("compute() calculates all metrics at K=3")
    void computeAllMetrics() {
      var result = RelevanceMetrics.QueryResult.compute(
          "q1", "test query", List.of("A", "X", "B"), Set.of("A", "B"));

      // Recall@3: 2/2 = 1.0
      assertEquals(1.0, result.recallAt3(), DELTA);
      // Precision@3: 2/3
      assertEquals(2.0 / 3.0, result.precisionAt3(), DELTA);
      // NDCG@3: same as imperfectRanking test above
      double expectedNdcg = 1.5 / (1.0 + 1.0 / (Math.log(3) / Math.log(2)));
      assertEquals(expectedNdcg, result.ndcgAt3(), DELTA);
      // MRR: A is at position 1 → 1.0
      assertEquals(1.0, result.reciprocalRank(), DELTA);
    }

    @Test
    @DisplayName("meetsThresholds passes when above thresholds")
    void meetsThresholdsPasses() {
      var result = RelevanceMetrics.QueryResult.compute(
          "q1", "test", List.of("A", "B", "C"), Set.of("A", "B"));
      assertTrue(result.meetsThresholds(0.8, 0.8));
    }

    @Test
    @DisplayName("meetsThresholds fails when recall below threshold")
    void meetsThresholdsFailsOnRecall() {
      var result = RelevanceMetrics.QueryResult.compute(
          "q1", "test", List.of("A", "X", "Y"), Set.of("A", "B", "C"));
      // Recall@3 = 1/3 ≈ 0.333, below 0.8
      assertFalse(result.meetsThresholds(0.8, 0.0));
    }

    @Test
    @DisplayName("meetsThresholds fails when NDCG below threshold")
    void meetsThresholdsFailsOnNdcg() {
      var result = RelevanceMetrics.QueryResult.compute(
          "q1", "test", List.of("X", "Y", "A"), Set.of("A"));
      // NDCG@3 = 0.5, below 0.8
      assertFalse(result.meetsThresholds(0.0, 0.8));
    }
  }

  // ==================== AggregatedMetrics ====================

  @Nested
  @DisplayName("AggregatedMetrics")
  class AggregatedMetricsTests {

    @Test
    @DisplayName("Empty results returns all zeros")
    void emptyResults() {
      var agg = RelevanceMetrics.AggregatedMetrics.aggregate(List.of(), 0.8, 0.8);
      assertEquals(0, agg.queryCount());
      assertEquals(0.0, agg.meanRecallAt3(), DELTA);
      assertEquals(0.0, agg.meanPrecisionAt3(), DELTA);
      assertEquals(0.0, agg.meanNdcgAt3(), DELTA);
      assertEquals(0.0, agg.meanReciprocalRank(), DELTA);
      assertEquals(0, agg.passCount());
      assertEquals(0, agg.failCount());
    }

    @Test
    @DisplayName("Aggregation computes means and pass/fail counts")
    void aggregation() {
      var r1 = RelevanceMetrics.QueryResult.compute(
          "q1", "query 1", List.of("A", "B", "X"), Set.of("A", "B")); // perfect recall
      var r2 = RelevanceMetrics.QueryResult.compute(
          "q2", "query 2", List.of("X", "Y", "Z"), Set.of("A")); // zero recall

      var agg = RelevanceMetrics.AggregatedMetrics.aggregate(List.of(r1, r2), 0.8, 0.8);

      assertEquals(2, agg.queryCount());
      // r1 recall=1.0, r2 recall=0.0 → mean=0.5
      assertEquals(0.5, agg.meanRecallAt3(), DELTA);
      // r1 passes (recall=1.0, ndcg=1.0), r2 fails (recall=0.0, ndcg=0.0)
      assertEquals(1, agg.passCount());
      assertEquals(1, agg.failCount());
    }
  }
}
