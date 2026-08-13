/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * One enrichment stage's completeness audit (tempdoc 821 §3-C3).
 *
 * <p>A projection over the counts the worker already holds, not a second authority. It answers
 * "did every document this stage applies to actually get its artifact?", which the sibling
 * coverage percentages cannot: they divide by every document, so a stage that silently lost a
 * sub-population reads the same as one with nothing to do.
 *
 * @param stageId {@code "embed"} | {@code "splade"} | {@code "ner"} | {@code "chunk_embed"}
 * @param tier {@code "ARTIFACT"} when {@code present} counted the artifact itself (a lying status
 *     field cannot inflate it) or {@code "STATUS"} when only the bookkeeping status field was
 *     countable — SPLADE's feature field is {@code docValues:false} and NER writes no per-document
 *     artifact, so those two declare the weaker tier rather than implying a verification they
 *     cannot perform
 * @param expected documents in scope carrying the stage's status field (absent = not applicable)
 * @param present ARTIFACT: documents carrying the artifact; STATUS: documents at terminal success
 * @param missing {@code expected - present - failed}, floored at 0
 * @param failed documents at the terminal FAILED value
 */
public record StageCompletenessView(
    String stageId, String tier, long expected, long present, long missing, long failed) {
  public StageCompletenessView {
    stageId = stageId == null ? "" : stageId;
    tier = tier == null ? "" : tier;
  }
}
