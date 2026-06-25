package io.justsearch.systemtests.relevance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test harness for verifying RRF (Reciprocal Rank Fusion) math in isolation.
 *
 * <p>This harness allows testing the RRF algorithm separate from Lucene,
 * using synthetically constructed scored document lists.
 *
 * <p>RRF formula: {@code score = sum(1 / (k + rank_i))} where:
 * <ul>
 *   <li>k = 60 (standard RRF constant)</li>
 *   <li>rank_i = 1-based rank in each result set</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * RrfFusionHarness harness = new RrfFusionHarness();
 *
 * // Add BM25 results (rank 1 = highest BM25 score)
 * harness.addBm25Result("doc-a", 15.2f);
 * harness.addBm25Result("doc-b", 12.1f);
 * harness.addBm25Result("doc-c", 8.5f);
 *
 * // Add Vector results (rank 1 = highest vector similarity)
 * harness.addVectorResult("doc-c", 0.95f);  // doc-c is #1 in vector
 * harness.addVectorResult("doc-a", 0.82f);  // doc-a is #2 in vector
 *
 * // Compute fusion
 * List<FusedResult> results = harness.fuse(10);
 *
 * // Verify: doc-a should be top (appears in both rankings)
 * assert results.get(0).docId().equals("doc-a");
 * }</pre>
 */
public final class RrfFusionHarness {

  /** Standard RRF constant from literature. */
  public static final int RRF_K = 60;

  private final List<ScoredDoc> bm25Results = new ArrayList<>();
  private final List<ScoredDoc> vectorResults = new ArrayList<>();

  /**
   * Adds a BM25 search result. Results should be added in score order (highest first).
   *
   * @param docId Document identifier
   * @param score BM25 score
   */
  public void addBm25Result(String docId, float score) {
    bm25Results.add(new ScoredDoc(docId, score));
  }

  /**
   * Adds a Vector search result. Results should be added in score order (highest first).
   *
   * @param docId Document identifier
   * @param score Vector similarity score
   */
  public void addVectorResult(String docId, float score) {
    vectorResults.add(new ScoredDoc(docId, score));
  }

  /**
   * Clears all results for a fresh test.
   */
  public void clear() {
    bm25Results.clear();
    vectorResults.clear();
  }

  /**
   * Computes RRF fusion and returns the fused results.
   *
   * @param limit Maximum number of results to return
   * @return Fused results ordered by RRF score (highest first)
   */
  public List<FusedResult> fuse(int limit) {
    Map<String, Double> rrfScores = new HashMap<>();
    Map<String, Float> bm25Scores = new HashMap<>();
    Map<String, Float> vectorScores = new HashMap<>();
    Map<String, Double> bm25RrfContributions = new HashMap<>();
    Map<String, Double> vectorRrfContributions = new HashMap<>();

    // Process BM25 results
    int rank = 1;
    for (ScoredDoc doc : bm25Results) {
      double rrfContribution = 1.0 / (RRF_K + rank);
      rrfScores.merge(doc.docId(), rrfContribution, Double::sum);
      bm25Scores.put(doc.docId(), doc.score());
      bm25RrfContributions.put(doc.docId(), rrfContribution);
      rank++;
    }

    // Process Vector results
    rank = 1;
    for (ScoredDoc doc : vectorResults) {
      double rrfContribution = 1.0 / (RRF_K + rank);
      rrfScores.merge(doc.docId(), rrfContribution, Double::sum);
      vectorScores.put(doc.docId(), doc.score());
      vectorRrfContributions.put(doc.docId(), rrfContribution);
      rank++;
    }

    // Sort by fused score and build results
    return rrfScores.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
        .limit(limit)
        .map(entry -> new FusedResult(
            entry.getKey(),
            entry.getValue(),
            bm25Scores.getOrDefault(entry.getKey(), 0.0f),
            vectorScores.getOrDefault(entry.getKey(), 0.0f),
            bm25RrfContributions.getOrDefault(entry.getKey(), 0.0),
            vectorRrfContributions.getOrDefault(entry.getKey(), 0.0)
        ))
        .toList();
  }

  /**
   * Calculates the expected RRF contribution for a given rank.
   *
   * @param rank 1-based rank
   * @return The RRF contribution: 1 / (k + rank)
   */
  public static double expectedRrfContribution(int rank) {
    return 1.0 / (RRF_K + rank);
  }

  /**
   * A scored document from a single retriever.
   */
  public record ScoredDoc(String docId, float score) {}

  /**
   * A fused result with debug information.
   */
  public record FusedResult(
      String docId,
      double rrfScore,
      float bm25Score,
      float vectorScore,
      double bm25RrfContribution,
      double vectorRrfContribution
  ) {
    /**
     * Returns true if this document appeared in both BM25 and Vector results.
     */
    public boolean appearedInBoth() {
      return bm25RrfContribution > 0 && vectorRrfContribution > 0;
    }

    /**
     * Returns the boost factor from appearing in multiple rankings.
     * Documents appearing in both rankings get a higher score than documents
     * that appear only in one.
     */
    public double multiRankBoost() {
      if (!appearedInBoth()) {
        return 0.0;
      }
      // The boost is essentially the sum of contributions from both rankings
      return bm25RrfContribution + vectorRrfContribution;
    }
  }
}
