/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.ipc.HitStage;
import io.justsearch.ipc.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 643 (critical-analysis pass): {@link SearchTraceMapper#protoStageScoreAny} — no single
 * stage id is a reliable "the pre-rerank score" across all pipeline shapes, so the judge-blend
 * floor needs a priority-ordered, presence-based fallback. These tests pin that fallback order
 * against hand-built {@code SearchResult}/{@code HitStage} protos (the same construction pattern
 * as {@code GplJobCoordinatorTraceFeatureTest}).
 */
@DisplayName("SearchTraceMapper.protoStageScoreAny")
class SearchTraceMapperTest {

  @Test
  @DisplayName("branch-fusion wins over a stale fusion stage when both are present")
  void branchFusionWinsOverFusion() {
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("fusion").setScore(0.42f))
            .addTrace(HitStage.newBuilder().setId("branch-fusion").setScore(0.91f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(0.91f, score);
  }

  @Test
  @DisplayName("falls back to fusion when branch-fusion is absent (no chunk-branch merge)")
  void fallsBackToFusionWhenNoBranchFusion() {
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setScore(5.5f))
            .addTrace(HitStage.newBuilder().setId("fusion").setScore(1.23f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(1.23f, score);
  }

  @Test
  @DisplayName("falls back to the single active leg when both fusion stages are absent (BM25-only)")
  void fallsBackToSparseLegForBm25OnlyPreset() {
    // HitProvenanceProjector.attachSingleLeg(leg, BM25) -- fusionMethod is null, so no
    // "fusion"/"branch-fusion" stage is ever emitted for this preset.
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setRank(1).setScore(7.0f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(7.0f, score);
  }

  @Test
  @DisplayName("falls back to the single active leg when both fusion stages are absent (dense-only)")
  void fallsBackToDenseLegForVectorOnlyPreset() {
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("dense-retrieval").setRank(1).setScore(0.61f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(0.61f, score);
  }

  @Test
  @DisplayName("falls back to the single active leg when both fusion stages are absent (SPLADE-only)")
  void fallsBackToSpladeLegForSpladeOnlyPreset() {
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("splade-retrieval").setRank(1).setScore(2.4f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(2.4f, score);
  }

  @Test
  @DisplayName("returns 0f when none of the candidate stages are present")
  void returnsZeroWhenNoCandidateStagePresent() {
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("cross-encoder").setScore(3.0f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(0f, score);
  }

  @Test
  @DisplayName("a real score of exactly 0.0 on the top-priority stage is honored, not skipped")
  void realZeroScoreOnPriorityStageIsHonored() {
    // Presence, not value, drives the fallback -- a genuine 0.0 fusion score must not be
    // mistaken for "fusion absent, try the next candidate".
    SearchResult sr =
        SearchResult.newBuilder()
            .addTrace(HitStage.newBuilder().setId("fusion").setScore(0.0f))
            .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setScore(9.9f))
            .build();

    float score = SearchTraceMapper.protoStageScoreAny(
        sr, "branch-fusion", "fusion", "sparse-retrieval", "dense-retrieval", "splade-retrieval");

    assertEquals(0.0f, score);
  }
}
