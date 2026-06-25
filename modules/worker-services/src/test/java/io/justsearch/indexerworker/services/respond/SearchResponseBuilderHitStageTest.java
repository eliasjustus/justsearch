package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BranchFusionSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkMergeSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FusionSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.HitProvenanceSignals;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RetrieverSignal;
import io.justsearch.ipc.HitStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 (full thesis, Phase B per-hit): {@code SearchResponseBuilder.buildHitStages} builds
 * the per-doc stage slice. The load-bearing invariant (GPL feature collection, Phase D) is
 * LOSSLESSNESS: the union of all {@code HitStage.detail} maps equals the input {@code debugScores}
 * — every key, including the obscure branch-fusion weight/modifier keys, lands in exactly one
 * stage's detail and none is dropped.
 */
@DisplayName("SearchResponseBuilder.buildHitStages: per-hit slice + lossless debug_scores detail")
final class SearchResponseBuilderHitStageTest {

  private static Map<String, Float> sampleDebugScores() {
    Map<String, Float> ds = new HashMap<>();
    ds.put("sparse", 5.0f);
    ds.put("sparse_rank", 1f);
    ds.put("vector", 0.8f);
    ds.put("vector_rank", 2f);
    ds.put("rrf", 1.2f);
    ds.put("cc_alpha", 0.5f);
    ds.put("chunk_sparse", 3.3f);
    ds.put("chunk_sparse_rank", 9f);
    ds.put("whole_branch", 6.4f);
    ds.put("chunk_branch", 0.6f);
    ds.put("branch_merge_cc", 1.5f);
    // The obscure GPL-collected keys the typed provenance does NOT carry — must survive.
    ds.put("branch_merge_cc_modifier_chunk", 0.25f);
    ds.put("branch_merge_cc_effective_weight_whole", 0.5f);
    ds.put("parent_token_count", 512f);
    return ds;
  }

  @Test
  @DisplayName("LOSSLESS: union of all HitStage.detail equals the input debug_scores")
  void detailTierIsLosslessSupersetOfDebugScores() {
    var sig =
        new HitProvenanceSignals(
            new RetrieverSignal(1, 5.0f),
            null,
            new RetrieverSignal(2, 0.8f),
            new FusionSignal(1.2f, "rrf"),
            new ChunkMergeSignal(9, 0, 0, 3.3f, 0f, 0f, 0.62f),
            new BranchFusionSignal(6.4f, 0.6f, 1.5f, "cc"));
    Map<String, Float> ds = sampleDebugScores();

    List<HitStage> stages = SearchResponseBuilder.buildHitStages(sig, ds, null);

    // Reconstruct the union of every stage's detail map.
    Map<String, Float> union = new HashMap<>();
    for (HitStage s : stages) {
      s.getDetailMap().forEach(union::put);
    }
    assertEquals(ds, union, "every debug_scores key must survive in exactly one stage's detail");
    // No key duplicated across stages (sizes match → partition, not overlap).
    int totalDetailEntries = stages.stream().mapToInt(s -> s.getDetailMap().size()).sum();
    assertEquals(ds.size(), totalDetailEntries, "keys must be partitioned, not duplicated");
  }

  @Test
  @DisplayName("retriever legs carry rank+score from the typed provenance; obscure keys routed")
  void rankScoreAndRouting() {
    var sig =
        new HitProvenanceSignals(
            new RetrieverSignal(1, 5.0f), null, new RetrieverSignal(2, 0.8f),
            new FusionSignal(1.2f, "rrf"), null, new BranchFusionSignal(6.4f, 0.6f, 1.5f, "cc"));
    List<HitStage> stages = SearchResponseBuilder.buildHitStages(sig, sampleDebugScores(), null);
    Map<String, HitStage> byId = new HashMap<>();
    for (HitStage s : stages) {
      byId.put(s.getId(), s);
    }
    assertEquals(1, byId.get("sparse-retrieval").getRank());
    assertEquals(5.0f, byId.get("sparse-retrieval").getScore(), 0.001f);
    assertEquals(2, byId.get("dense-retrieval").getRank());
    // The obscure branch-fusion modifier key routes to branch-fusion (not dropped, not mis-bucketed).
    assertTrue(
        byId.get("branch-fusion").getDetailMap().containsKey("branch_merge_cc_modifier_chunk"));
    // chunk_sparse* routes to chunk-merge.
    assertTrue(byId.get("chunk-merge").getDetailMap().containsKey("chunk_sparse_rank"));
  }

  @Test
  @DisplayName("null signals + empty debug_scores → empty slice (no throw)")
  void emptyInputs() {
    assertTrue(SearchResponseBuilder.buildHitStages(null, Map.of(), null).isEmpty());
  }

  @Test
  @DisplayName("Phase D2: sparse-only shortcut yields a structural sparse stage even with no detail")
  void sparseOnlyStructuralFallback() {
    // No typed provenance, no detail tier requested (empty map) — the structural slice must still
    // be non-empty for the BM25 fast path: a sparse-retrieval stage carrying the hit score.
    List<HitStage> stages = SearchResponseBuilder.buildHitStages(null, Map.of(), 4.2f);
    assertEquals(1, stages.size());
    assertEquals("sparse-retrieval", stages.get(0).getId());
    assertEquals(4.2f, stages.get(0).getScore(), 0.001f);
    assertTrue(stages.get(0).getDetailMap().isEmpty(), "no numeric detail when not requested");
  }

  @Test
  @DisplayName("Phase D2: a typed bm25 leg wins over the sparse-only fallback (no duplicate stage)")
  void typedBm25WinsOverFallback() {
    var sig =
        new HitProvenanceSignals(
            new RetrieverSignal(3, 9.9f), null, null, null, null, null);
    List<HitStage> stages = SearchResponseBuilder.buildHitStages(sig, Map.of(), 4.2f);
    assertEquals(1, stages.size());
    assertEquals("sparse-retrieval", stages.get(0).getId());
    // The typed leg's rank+score, not the fallback's synthesized score.
    assertEquals(3, stages.get(0).getRank());
    assertEquals(9.9f, stages.get(0).getScore(), 0.001f);
  }

  /** The closed StageId wire vocabulary (mirrors SearchTrace.StageId in app-api). */
  private static final java.util.Set<String> CLOSED_STAGE_IDS =
      java.util.Set.of(
          "query-understanding", "expansion", "correction", "sparse-retrieval", "dense-retrieval",
          "splade-retrieval", "fusion", "chunk-merge", "branch-fusion", "lambdamart",
          "cross-encoder", "freshness");

  @Test
  @DisplayName("Phase D2 privacy: per-hit trace carries only stage-id + numeric data, no free text")
  void perHitTraceIsPrivacySafeByConstruction() {
    // Tempdoc 549 principle 6: the per-hit trace (and thus its LLM-injected form) must carry no
    // query text / filter values — only numeric ranking signals keyed by the closed stage
    // vocabulary. This is a type-level guarantee: HitStage's only non-numeric scalar is `id`
    // (a closed-vocabulary wireId), and detail is map<string,float> (numeric values, stage-internal
    // keys). This test pins both halves so a future field addition can't open a free-text leak.
    var sig =
        new HitProvenanceSignals(
            new RetrieverSignal(1, 5.0f), null, new RetrieverSignal(2, 0.8f),
            new FusionSignal(1.2f, "rrf"), null, new BranchFusionSignal(6.4f, 0.6f, 1.5f, "cc"));
    List<HitStage> stages = SearchResponseBuilder.buildHitStages(sig, sampleDebugScores(), null);
    for (HitStage s : stages) {
      assertTrue(
          CLOSED_STAGE_IDS.contains(s.getId()),
          "stage id must be a closed-vocabulary wireId, never free text: " + s.getId());
    }
    // The only string-typed scalar field on the HitStage message is `id`; `detail` is the sole
    // map and its value type is float. No field can echo the query or filter values.
    var stringScalars =
        HitStage.getDescriptor().getFields().stream()
            .filter(f -> !f.isRepeated() && !f.isMapField())
            .filter(f -> f.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.STRING)
            .map(com.google.protobuf.Descriptors.FieldDescriptor::getName)
            .toList();
    assertEquals(List.of("id"), stringScalars, "the only string scalar must be the stage id");
    var detailField = HitStage.getDescriptor().findFieldByName("detail");
    assertTrue(detailField.isMapField(), "detail must be a map");
    assertEquals(
        com.google.protobuf.Descriptors.FieldDescriptor.JavaType.FLOAT,
        detailField.getMessageType().findFieldByName("value").getJavaType(),
        "detail values must be numeric (float) — no free-text payload");
  }
}
