/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpl;

import java.time.Instant;

/**
 * Training lifecycle status for the LambdaMART reranker, exposed via {@code /api/debug/state}.
 *
 * @param status current training lifecycle phase
 * @param ndcg10 NDCG@10 score from the last evaluation, or {@code null} if not yet evaluated
 * @param mrr10 MRR@10 score from the last evaluation, or {@code null} if not yet evaluated
 * @param trainGroups number of training groups in the last run, or {@code null}
 * @param evalGroups number of evaluation groups in the last run, or {@code null}
 * @param lastTrainedAt timestamp of the last completed training, or {@code null}
 * @param error error message from the last failed training, or {@code null}
 */
public record LambdaMartTrainingStatus(
    Phase status,
    Double ndcg10,
    Double mrr10,
    Integer trainGroups,
    Integer evalGroups,
    Instant lastTrainedAt,
    String error) {

  /** Training lifecycle phase. */
  public enum Phase {
    PENDING,
    LOADED_FROM_DISK,
    TRAINING,
    SUCCEEDED,
    FAILED
  }
}
