package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.relevance.RrfFusionHarness;
import io.justsearch.systemtests.relevance.RrfFusionHarness.FusedResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the RRF Fusion Harness.
 *
 * <p>Verifies the RRF algorithm math separate from Lucene:
 * <ul>
 *   <li>RRF score formula: 1/(k+rank)</li>
 *   <li>Documents appearing in both rankings get boosted</li>
 *   <li>Ranking is correct when mixing BM25 and Vector results</li>
 * </ul>
 */
@DisplayName("RRF Fusion Harness")
class RrfFusionHarnessTest {

  private static final double TOLERANCE = 0.00001;

  private RrfFusionHarness harness;

  @BeforeEach
  void setUp() {
    harness = new RrfFusionHarness();
  }

  @Nested
  @DisplayName("RRF Score Formula")
  class RrfScoreFormula {

    @Test
    @DisplayName("Rank 1 contribution is 1/(60+1) = 0.01639")
    void rank1Contribution() {
      double expected = 1.0 / 61.0;
      assertEquals(expected, RrfFusionHarness.expectedRrfContribution(1), TOLERANCE);
    }

    @Test
    @DisplayName("Rank 10 contribution is 1/(60+10) = 0.01429")
    void rank10Contribution() {
      double expected = 1.0 / 70.0;
      assertEquals(expected, RrfFusionHarness.expectedRrfContribution(10), TOLERANCE);
    }

    @Test
    @DisplayName("Single BM25 result has correct RRF score")
    void singleBm25Result() {
      harness.addBm25Result("doc-a", 15.2f);

      List<FusedResult> results = harness.fuse(10);

      assertEquals(1, results.size());
      assertEquals("doc-a", results.get(0).docId());
      assertEquals(RrfFusionHarness.expectedRrfContribution(1), results.get(0).rrfScore(), TOLERANCE);
      assertEquals(15.2f, results.get(0).bm25Score());
      assertEquals(0.0f, results.get(0).vectorScore());
    }
  }

  @Nested
  @DisplayName("Fusion Behavior")
  class FusionBehavior {

    @Test
    @DisplayName("Document appearing in both rankings is boosted to top")
    void documentInBothRankingsIsBoosted() {
      // doc-a: Rank 2 in BM25, Rank 1 in Vector
      // doc-b: Rank 1 in BM25 only
      // doc-c: Rank 2 in Vector only

      harness.addBm25Result("doc-b", 20.0f);  // BM25 Rank 1
      harness.addBm25Result("doc-a", 15.0f);  // BM25 Rank 2

      harness.addVectorResult("doc-a", 0.95f);  // Vector Rank 1
      harness.addVectorResult("doc-c", 0.85f);  // Vector Rank 2

      List<FusedResult> results = harness.fuse(10);

      // doc-a should be #1 because it appears in both rankings
      assertEquals("doc-a", results.get(0).docId());
      assertTrue(results.get(0).appearedInBoth());

      // doc-a's RRF score = 1/(60+2) + 1/(60+1) = 1/62 + 1/61
      double expectedScore = (1.0/62.0) + (1.0/61.0);
      assertEquals(expectedScore, results.get(0).rrfScore(), TOLERANCE);
    }

    @Test
    @DisplayName("Documents only in one ranking have lower scores")
    void singleRankingDocsHaveLowerScores() {
      // Set up a scenario where the multi-ranking doc should beat single-ranking docs
      harness.addBm25Result("doc-bm25-only", 25.0f);  // Rank 1 in BM25 only
      harness.addBm25Result("doc-both", 10.0f);       // Rank 2 in BM25

      harness.addVectorResult("doc-both", 0.9f);      // Rank 1 in Vector
      harness.addVectorResult("doc-vec-only", 0.8f); // Rank 2 in Vector only

      List<FusedResult> results = harness.fuse(10);

      // doc-both appears in both rankings and should be #1
      assertEquals("doc-both", results.get(0).docId());

      // Find the single-ranking docs
      FusedResult bm25Only = results.stream()
          .filter(r -> r.docId().equals("doc-bm25-only"))
          .findFirst()
          .orElseThrow();
      FusedResult vecOnly = results.stream()
          .filter(r -> r.docId().equals("doc-vec-only"))
          .findFirst()
          .orElseThrow();

      // Both single-ranking docs should have lower scores than doc-both
      assertTrue(results.get(0).rrfScore() > bm25Only.rrfScore());
      assertTrue(results.get(0).rrfScore() > vecOnly.rrfScore());

      // Single-ranking docs should not appear in both
      assertFalse(bm25Only.appearedInBoth());
      assertFalse(vecOnly.appearedInBoth());
    }

    @Test
    @DisplayName("Limit parameter is respected")
    void limitIsRespected() {
      harness.addBm25Result("doc-1", 10.0f);
      harness.addBm25Result("doc-2", 9.0f);
      harness.addBm25Result("doc-3", 8.0f);
      harness.addBm25Result("doc-4", 7.0f);
      harness.addBm25Result("doc-5", 6.0f);

      List<FusedResult> results = harness.fuse(3);

      assertEquals(3, results.size());
      assertEquals("doc-1", results.get(0).docId());
      assertEquals("doc-2", results.get(1).docId());
      assertEquals("doc-3", results.get(2).docId());
    }

    @Test
    @DisplayName("Empty results return empty list")
    void emptyResults() {
      List<FusedResult> results = harness.fuse(10);
      assertTrue(results.isEmpty());
    }
  }

  @Nested
  @DisplayName("Debug Scores")
  class DebugScores {

    @Test
    @DisplayName("Original scores are preserved in fused results")
    void originalScoresPreserved() {
      harness.addBm25Result("doc-a", 15.5f);
      harness.addVectorResult("doc-a", 0.87f);

      List<FusedResult> results = harness.fuse(10);

      assertEquals(15.5f, results.get(0).bm25Score());
      assertEquals(0.87f, results.get(0).vectorScore());
    }

    @Test
    @DisplayName("RRF contributions are tracked separately")
    void rrfContributionsTracked() {
      harness.addBm25Result("doc-a", 15.0f);   // BM25 Rank 1
      harness.addVectorResult("doc-a", 0.9f);  // Vector Rank 1

      List<FusedResult> results = harness.fuse(10);
      FusedResult doc = results.get(0);

      // Both contributions should be 1/(60+1) = 1/61
      double expectedContrib = 1.0 / 61.0;
      assertEquals(expectedContrib, doc.bm25RrfContribution(), TOLERANCE);
      assertEquals(expectedContrib, doc.vectorRrfContribution(), TOLERANCE);

      // Total RRF score should be sum
      assertEquals(expectedContrib * 2, doc.rrfScore(), TOLERANCE);
    }
  }

  @Nested
  @DisplayName("Hybrid Search Verification (Layer 0)")
  class HybridSearchVerification {

    @Test
    @DisplayName("Verify Hybrid > Text for docs appearing in both rankings")
    void hybridBetterThanTextAlone() {
      // Scenario: doc-hybrid appears in both rankings but is not #1 in either
      // It should still beat docs that are #1 in only one ranking

      harness.addBm25Result("doc-text-only", 25.0f);   // BM25 #1
      harness.addBm25Result("doc-hybrid", 15.0f);      // BM25 #2
      harness.addBm25Result("doc-text-only-2", 10.0f); // BM25 #3

      harness.addVectorResult("doc-vector-only", 0.99f);  // Vector #1
      harness.addVectorResult("doc-hybrid", 0.85f);       // Vector #2
      harness.addVectorResult("doc-vector-only-2", 0.70f);// Vector #3

      List<FusedResult> results = harness.fuse(10);

      // doc-hybrid should be #1 due to appearing in both rankings
      assertEquals("doc-hybrid", results.get(0).docId());

      // Find text-only and vector-only docs
      FusedResult textOnly = results.stream()
          .filter(r -> r.docId().equals("doc-text-only"))
          .findFirst().orElseThrow();
      FusedResult vectorOnly = results.stream()
          .filter(r -> r.docId().equals("doc-vector-only"))
          .findFirst().orElseThrow();

      // Both should have lower RRF scores than doc-hybrid
      assertTrue(results.get(0).rrfScore() > textOnly.rrfScore());
      assertTrue(results.get(0).rrfScore() > vectorOnly.rrfScore());
    }

    @Test
    @DisplayName("Verify RRF score formula matches 1/(k+rank)")
    void rrfFormulaVerification() {
      // Add 5 BM25 results and 3 vector results
      harness.addBm25Result("doc-1", 10.0f);  // BM25 rank 1
      harness.addBm25Result("doc-2", 9.0f);   // BM25 rank 2
      harness.addBm25Result("doc-3", 8.0f);   // BM25 rank 3

      harness.addVectorResult("doc-2", 0.9f); // Vector rank 1
      harness.addVectorResult("doc-3", 0.8f); // Vector rank 2
      harness.addVectorResult("doc-4", 0.7f); // Vector rank 3

      List<FusedResult> results = harness.fuse(10);

      // Verify specific RRF scores:
      // doc-1: only BM25 rank 1 = 1/61
      // doc-2: BM25 rank 2 + Vector rank 1 = 1/62 + 1/61
      // doc-3: BM25 rank 3 + Vector rank 2 = 1/63 + 1/62
      // doc-4: only Vector rank 3 = 1/63

      for (FusedResult r : results) {
        double expected;
        switch (r.docId()) {
          case "doc-1" -> expected = 1.0/61.0;
          case "doc-2" -> expected = 1.0/62.0 + 1.0/61.0;
          case "doc-3" -> expected = 1.0/63.0 + 1.0/62.0;
          case "doc-4" -> expected = 1.0/63.0;
          default -> throw new AssertionError("Unexpected doc: " + r.docId());
        }
        assertEquals(expected, r.rrfScore(), TOLERANCE, "RRF score for " + r.docId());
      }
    }
  }
}
