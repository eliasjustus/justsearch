/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.relevance;

import java.util.List;
import java.util.Set;

/**
 * Pure IR-metric arithmetic — no corpus, model, or search-engine dependency of its own.
 *
 * <p>Implements standard IR metrics:
 * <ul>
 *   <li><b>Recall@K</b> - Fraction of relevant documents found in top K results</li>
 *   <li><b>Precision@K</b> - Fraction of top K results that are relevant</li>
 *   <li><b>NDCG@K</b> - Normalized Discounted Cumulative Gain (position-weighted)</li>
 *   <li><b>MRR</b> - Mean Reciprocal Rank of first relevant result</li>
 * </ul>
 *
 * <p><b>Disambiguation (tempdoc 664):</b> these functions are only as meaningful as the
 * {@code retrievedDocs} list a caller passes in. Callers in this module ({@code
 * GoldenCorpusIntegrationTest}, {@code PassageRetrievalIntegrationTest}) pass ranked lists
 * produced against hand-placed, frozen embedding vectors — so their results measure
 * fusion/ranking-code correctness given a fixed vector, not real embedding-model retrieval
 * quality. {@code RagQualityEvalTest} uses one metric here ({@code recallAtK}) as one of five
 * RAG-quality sub-metrics, also against frozen retrieval vectors (with a real LLM for
 * generation/faithfulness scoring). This class is intentionally shared, generic infrastructure
 * across those three unrelated manifests — not scoped to any one "golden corpus" concept. The
 * canonical retrieval-quality harness (real embedding models, BEIR/mixed/golden corpora) is
 * {@code jseval} (Python, {@code scripts/jseval/}).
 */
public final class RelevanceMetrics {

  private RelevanceMetrics() {}

  /**
   * Computes Recall@K: fraction of relevant documents found in top K results.
   *
   * <p>Formula: |{relevant} ∩ {retrieved@K}| / |{relevant}|
   *
   * @param retrievedDocs List of retrieved document IDs (in rank order)
   * @param relevantDocs Set of relevant document IDs (ground truth)
   * @param k Number of top results to consider
   * @return Recall value between 0.0 and 1.0
   */
  public static double recallAtK(List<String> retrievedDocs, Set<String> relevantDocs, int k) {
    if (relevantDocs.isEmpty()) {
      return 1.0; // By convention, recall is 1 if there are no relevant docs
    }

    int limit = Math.min(k, retrievedDocs.size());
    long found = retrievedDocs.stream()
        .limit(limit)
        .filter(relevantDocs::contains)
        .count();

    return (double) found / relevantDocs.size();
  }

  /**
   * Computes Precision@K: fraction of top K results that are relevant.
   *
   * <p>Formula: |{relevant} ∩ {retrieved@K}| / K
   *
   * @param retrievedDocs List of retrieved document IDs (in rank order)
   * @param relevantDocs Set of relevant document IDs (ground truth)
   * @param k Number of top results to consider
   * @return Precision value between 0.0 and 1.0
   */
  public static double precisionAtK(List<String> retrievedDocs, Set<String> relevantDocs, int k) {
    if (k <= 0) {
      return 0.0;
    }

    int limit = Math.min(k, retrievedDocs.size());
    long found = retrievedDocs.stream()
        .limit(limit)
        .filter(relevantDocs::contains)
        .count();

    return (double) found / k;
  }

  /**
   * Computes NDCG@K: Normalized Discounted Cumulative Gain.
   *
   * <p>DCG formula: sum(relevance[i] / log2(i + 1)) for i in 1..K
   * <p>NDCG = DCG / IDCG (ideal DCG with perfect ranking)
   *
   * <p>Uses binary relevance (1 for relevant, 0 for not relevant).
   *
   * @param retrievedDocs List of retrieved document IDs (in rank order)
   * @param relevantDocs Set of relevant document IDs (ground truth)
   * @param k Number of top results to consider
   * @return NDCG value between 0.0 and 1.0
   */
  public static double ndcgAtK(List<String> retrievedDocs, Set<String> relevantDocs, int k) {
    if (relevantDocs.isEmpty()) {
      return 1.0; // Perfect score if no relevant docs to find
    }

    // Compute DCG (Discounted Cumulative Gain)
    double dcg = 0.0;
    int limit = Math.min(k, retrievedDocs.size());
    for (int i = 0; i < limit; i++) {
      if (relevantDocs.contains(retrievedDocs.get(i))) {
        // Binary relevance: 1 if relevant
        // Discount: 1 / log2(rank + 1) where rank is 1-based
        dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // log2(i+2) because i is 0-based
      }
    }

    // Compute IDCG (Ideal DCG) - perfect ranking would place all relevant docs first
    double idcg = 0.0;
    int idealLimit = Math.min(k, relevantDocs.size());
    for (int i = 0; i < idealLimit; i++) {
      idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
    }

    return idcg == 0 ? 0.0 : dcg / idcg;
  }

  /**
   * Computes MRR: Mean Reciprocal Rank.
   *
   * <p>Returns 1/rank of the first relevant document, or 0 if none found.
   *
   * @param retrievedDocs List of retrieved document IDs (in rank order)
   * @param relevantDocs Set of relevant document IDs (ground truth)
   * @return Reciprocal rank value between 0.0 and 1.0
   */
  public static double reciprocalRank(List<String> retrievedDocs, Set<String> relevantDocs) {
    for (int i = 0; i < retrievedDocs.size(); i++) {
      if (relevantDocs.contains(retrievedDocs.get(i))) {
        return 1.0 / (i + 1); // 1-based rank
      }
    }
    return 0.0; // No relevant document found
  }

  /**
   * Result container for a single query evaluation.
   */
  public record QueryResult(
      String queryId,
      String queryText,
      List<String> retrievedDocs,
      Set<String> relevantDocs,
      double recallAt3,
      double precisionAt3,
      double ndcgAt3,
      double reciprocalRank
  ) {
    /**
     * Computes all metrics for a query.
     */
    public static QueryResult compute(
        String queryId,
        String queryText,
        List<String> retrievedDocs,
        Set<String> relevantDocs) {
      return new QueryResult(
          queryId,
          queryText,
          retrievedDocs,
          relevantDocs,
          recallAtK(retrievedDocs, relevantDocs, 3),
          precisionAtK(retrievedDocs, relevantDocs, 3),
          ndcgAtK(retrievedDocs, relevantDocs, 3),
          RelevanceMetrics.reciprocalRank(retrievedDocs, relevantDocs)
      );
    }

    /**
     * Returns true if all metrics meet their thresholds.
     */
    public boolean meetsThresholds(double recallThreshold, double ndcgThreshold) {
      return recallAt3 >= recallThreshold && ndcgAt3 >= ndcgThreshold;
    }
  }

  /**
   * Aggregated metrics across multiple queries.
   */
  public record AggregatedMetrics(
      int queryCount,
      double meanRecallAt3,
      double meanPrecisionAt3,
      double meanNdcgAt3,
      double meanReciprocalRank,
      int passCount,
      int failCount
  ) {
    /**
     * Computes aggregated metrics from a list of query results.
     */
    public static AggregatedMetrics aggregate(
        List<QueryResult> results,
        double recallThreshold,
        double ndcgThreshold) {

      if (results.isEmpty()) {
        return new AggregatedMetrics(0, 0, 0, 0, 0, 0, 0);
      }

      double totalRecall = 0, totalPrecision = 0, totalNdcg = 0, totalMrr = 0;
      int pass = 0, fail = 0;

      for (QueryResult r : results) {
        totalRecall += r.recallAt3();
        totalPrecision += r.precisionAt3();
        totalNdcg += r.ndcgAt3();
        totalMrr += r.reciprocalRank();

        if (r.meetsThresholds(recallThreshold, ndcgThreshold)) {
          pass++;
        } else {
          fail++;
        }
      }

      int n = results.size();
      return new AggregatedMetrics(
          n,
          totalRecall / n,
          totalPrecision / n,
          totalNdcg / n,
          totalMrr / n,
          pass,
          fail
      );
    }
  }
}
