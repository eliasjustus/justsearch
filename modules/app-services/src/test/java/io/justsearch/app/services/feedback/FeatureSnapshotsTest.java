package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        .fields(
                            Map.of(
                                "doc_uid", "stable-d1",
                                "parent_token_count", "1024"))
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
    assertEquals("stable-d1", h.docId());
    assertEquals("d1", h.sourceDocId());
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
                        .fields(Map.of("doc_uid", "stable-d2"))
                        .trace(List.of())
                        .build()))
            .build();
    FeatureSnapshot.HitFeatures h = FeatureSnapshots.capture("i", "q", 1L, resp).hits().get(0);
    assertEquals(0.42f, h.fused(), 1e-6); // no FUSION stage → hit.score() fallback
    assertEquals(0f, h.sparse());
    assertNull(h.parentTokenCount());
  }

  @Test
  void capture_derivesParentUidForChunkHit() {
    KnowledgeSearchResponse resp =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/report.md")
                        .fields(
                            Map.of(
                                "parent_doc_id", "C:/docs/report.md",
                                "chunk_index", "4",
                                "doc_uid", "stable-report#4"))
                        .build()))
            .build();

    FeatureSnapshot.HitFeatures hit =
        FeatureSnapshots.capture("i", "q", 1L, resp).hits().getFirst();

    assertEquals("stable-report", hit.docId());
    assertEquals("C:/docs/report.md", hit.sourceDocId());
  }

  @Test
  void capture_omitsHitWithoutStableUidInsteadOfWritingPathKey() {
    KnowledgeSearchResponse resp =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/legacy.md")
                        .fields(Map.of())
                        .build()))
            .build();

    assertTrue(FeatureSnapshots.capture("i", "q", 1L, resp).hits().isEmpty());
  }

  @Test
  void resolveStableDocIdFailsClosedOnConflictingPathAlias() {
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "i",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-1", "C:/same.md", 1, 1, 1, 1, 1, null),
                    new FeatureSnapshot.HitFeatures(
                        "uid-2", "C:/same.md", 2, 1, 1, 1, 1, null))));

    assertTrue(FeatureSnapshots.resolveStableDocId(snapshots, "i", "C:/same.md").isEmpty());
    assertEquals(
        "uid-1",
        FeatureSnapshots.resolveStableDocId(
                List.of(
                    new FeatureSnapshot(
                        "i",
                        "q",
                        1L,
                        List.of(
                            new FeatureSnapshot.HitFeatures(
                                "uid-1", "C:/same.md", 1, 1, 1, 1, 1, null)))),
                "i",
                "C:/same.md")
            .orElseThrow());
  }

  @Test
  void capture_handlesEmptyResults() {
    FeatureSnapshot snap =
        FeatureSnapshots.capture("i", "q", 1L, KnowledgeSearchResponseBuilder.builder().build());
    assertEquals(0, snap.hits().size());
  }
}
