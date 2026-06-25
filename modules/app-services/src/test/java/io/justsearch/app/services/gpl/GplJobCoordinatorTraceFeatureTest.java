package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.HitStage;
import io.justsearch.ipc.SearchResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 Phase D/E1: GPL feature collection reads the unified trace's per-hit detail tier
 * (union of all {@code HitStage.detail}) — the sole source now that the legacy {@code debug_scores}
 * wire field is retired (Phase E1). This test pins the GPL read path: {@code unifiedDetailTier}
 * reconstructs exactly the numeric detail the worker partitioned across stages (every key, including
 * the obscure branch-fusion modifier keys, survives in the union), which is what keeps the LambdaMART
 * training corpus byte-equivalent.
 */
@DisplayName("GplJobCoordinator.unifiedDetailTier: union of HitStage.detail (sole detail source)")
final class GplJobCoordinatorTraceFeatureTest {

  @Test
  @DisplayName("union of HitStage.detail reconstructs the full per-hit numeric tier")
  void unionReconstructsDetailTier() {
    SearchResult result =
        SearchResult.newBuilder()
            .setId("d1")
            .setScore(5.0f)
            // The detail tier partitioned across the per-hit stage slices (as the worker emits it).
            .addTrace(
                HitStage.newBuilder().setId("sparse-retrieval").setRank(1).setScore(5.0f)
                    .putDetail("sparse", 5.0f))
            .addTrace(
                HitStage.newBuilder().setId("chunk-merge")
                    .putDetail("chunk_sparse", 3.3f)
                    .putDetail("chunk_sparse_rank", 9f))
            .addTrace(
                HitStage.newBuilder().setId("branch-fusion")
                    .putDetail("whole_branch", 6.4f)
                    .putDetail("branch_merge_cc_modifier_chunk", 0.25f))
            .build();

    Map<String, Float> expected =
        Map.of(
            "sparse", 5.0f,
            "chunk_sparse", 3.3f,
            "chunk_sparse_rank", 9f,
            "whole_branch", 6.4f,
            "branch_merge_cc_modifier_chunk", 0.25f);
    assertEquals(
        expected,
        GplJobCoordinator.unifiedDetailTier(result),
        "every detail key must survive in the union the GPL feature collection reads");
  }

  @Test
  @DisplayName("no trace → empty detail tier (debug_scores fallback retired in E1)")
  void emptyWhenNoTrace() {
    SearchResult result = SearchResult.newBuilder().setId("d2").build();
    assertTrue(GplJobCoordinator.unifiedDetailTier(result).isEmpty());
  }
}
