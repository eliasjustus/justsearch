/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpl;

import java.util.List;

/**
 * Contract for the LambdaMART feature-based reranker. Implemented by {@code LambdaMartReranker}
 * in {@code app-services}; consumed by {@code KnowledgeHttpApiAdapter} and the UI layer without
 * importing the concrete class.
 *
 * <p>Lifecycle (open/close) is owned by {@code HeadAssembly}, not by consumers of this
 * interface.
 */
public interface RerankerService {

  /** Returns {@code true} if a model is loaded and ready to rerank. */
  boolean isLoaded();

  /**
   * Reranks candidate results using LambdaMART feature scoring.
   *
   * @param sparseScores BM25 / lexical sparse scores for each candidate
   * @param vectors vector similarity scores for each candidate
   * @param spladeScores learned-sparse (SPLADE) scores for each candidate (tempdoc 580 §17 P5 V2)
   * @param n number of candidates
   * @return indices of candidates in descending relevance order
   */
  List<Integer> rerank(float[] sparseScores, float[] vectors, float[] spladeScores, int n);

  /** Returns the current training status snapshot. */
  LambdaMartTrainingStatus getTrainingStatus();
}
