package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tempdoc 580 §17 P1 — guard tests for response→FeatureSnapshot extraction. */
class FeatureSnapshotsTest {

  @Test
  void capture_extractsPerStageScoresAndTokenCount() {
    KnowledgeSearchResponse resp =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("d1")
                        .score(0.85)
                        .fields(Map.of("parent_token_count", "1024"))
                        .trace(
                            List.of(
                                new SearchTrace.HitStage(
                                    SearchTrace.StageId.SPARSE_RETRIEVAL, 1, 0.9f, Map.of()),
                                new SearchTrace.HitStage(
                                    SearchTrace.StageId.DENSE_RETRIEVAL, 1, 0.8f, Map.of()),
                                new SearchTrace.HitStage(
                                    SearchTrace.StageId.SPLADE_RETRIEVAL, 1, 0.7f, Map.of()),
                                new SearchTrace.HitStage(
                                    SearchTrace.StageId.FUSION, 1, 0.6f, Map.of())))
                        .build()))
            .build();

    FeatureSnapshot snap = FeatureSnapshots.capture("iid", "q", 7L, resp);
    assertEquals("iid", snap.interactionId());
    assertEquals(1, snap.hits().size());
    FeatureSnapshot.HitFeatures h = snap.hits().get(0);
    assertEquals("d1", h.docId());
    assertEquals(1, h.rank());
    assertEquals(0.9f, h.sparse());
    assertEquals(0.8f, h.dense());
    assertEquals(0.7f, h.splade());
    assertEquals(0.6f, h.fused());
    assertEquals(1024L, h.parentTokenCount());
  }

  @Test
  void capture_fallsBackToHitScoreWhenNoFusionStage_andNullTokenCount() {
    KnowledgeSearchResponse resp =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("d2")
                        .score(0.42)
                        .fields(Map.of())
                        .trace(List.of())
                        .build()))
            .build();
    FeatureSnapshot.HitFeatures h = FeatureSnapshots.capture("i", "q", 1L, resp).hits().get(0);
    assertEquals(0.42f, h.fused(), 1e-6); // no FUSION stage → hit.score() fallback
    assertEquals(0f, h.sparse());
    assertNull(h.parentTokenCount());
  }

  @Test
  void capture_handlesEmptyResults() {
    FeatureSnapshot snap =
        FeatureSnapshots.capture("i", "q", 1L, KnowledgeSearchResponseBuilder.builder().build());
    assertEquals(0, snap.hits().size());
  }
}
