package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.api.knowledge.SearchTrace.StageStatus;
import io.justsearch.app.api.knowledge.SearchTrace.TraceStage;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 (full thesis, Phase B/C): {@code SearchTraceMapper.mapSearchTrace} composes
 * the unified app-api {@link SearchTrace} from the worker's proto stages + the head's stages.
 * This guards the wire mapping ({@code StageId.fromWireId} / {@code StageStatus.fromWire}) against
 * throwing on the real wireIds/status strings the producers emit, and pins the two-contributor
 * composition (worker stages first, head stages appended).
 */
@DisplayName("SearchTraceMapper.mapSearchTrace: worker proto + head stages → one SearchTrace")
final class KnowledgeHttpApiAdapterSearchTraceTest {

  @Test
  @DisplayName("composes worker proto stages + head stages into one typed trace, no throw")
  void composesWorkerAndHeadStages() {
    // Worker proto trace (as SearchTraceProjector emits it).
    var resp =
        io.justsearch.ipc.SearchResponse.newBuilder()
            .setSearchTrace(
                io.justsearch.ipc.SearchTrace.newBuilder()
                    .setVersion(1)
                    .addStages(
                        io.justsearch.ipc.TraceStage.newBuilder()
                            .setId("sparse-retrieval")
                            .setStatus("executed")
                            .build())
                    .addStages(
                        io.justsearch.ipc.TraceStage.newBuilder()
                            .setId("dense-retrieval")
                            .setStatus("skipped")
                            .setReason("dense-only")
                            .build())
                    .addStages(
                        io.justsearch.ipc.TraceStage.newBuilder()
                            .setId("chunk-merge")
                            .setStatus("executed")
                            .setMs(5L)
                            .build()))
            .build();

    // Head stages (as buildHeadStages emits them — now SearchTrace.TraceStage directly).
    List<TraceStage> headStages =
        List.of(
            new TraceStage(StageId.QUERY_UNDERSTANDING, StageStatus.EXECUTED, null, null, "KEYWORD", null),
            new TraceStage(StageId.CROSS_ENCODER, StageStatus.EXECUTED, null, 8L, null, null),
            new TraceStage(StageId.FRESHNESS, StageStatus.DISABLED, null, null, null, null));

    SearchTrace trace = SearchTraceMapper.mapSearchTrace(resp, headStages);

    // Worker stages first, head stages appended — one combined artifact.
    List<SearchTrace.StageId> ids =
        trace.stages().stream().map(SearchTrace.TraceStage::id).collect(Collectors.toList());
    assertEquals(
        List.of(
            SearchTrace.StageId.SPARSE_RETRIEVAL,
            SearchTrace.StageId.DENSE_RETRIEVAL,
            SearchTrace.StageId.CHUNK_MERGE,
            SearchTrace.StageId.QUERY_UNDERSTANDING,
            SearchTrace.StageId.CROSS_ENCODER,
            SearchTrace.StageId.FRESHNESS),
        ids);

    // Status + detail mapping is correct.
    var dense =
        trace.stages().stream()
            .filter(s -> s.id() == SearchTrace.StageId.DENSE_RETRIEVAL)
            .findFirst()
            .orElseThrow();
    assertEquals(SearchTrace.StageStatus.SKIPPED, dense.status());
    assertEquals("dense-only", dense.reason());

    var chunk =
        trace.stages().stream()
            .filter(s -> s.id() == SearchTrace.StageId.CHUNK_MERGE)
            .findFirst()
            .orElseThrow();
    assertEquals(SearchTrace.StageStatus.EXECUTED, chunk.status());
    assertEquals(5L, chunk.ms());

    var freshness =
        trace.stages().stream()
            .filter(s -> s.id() == SearchTrace.StageId.FRESHNESS)
            .findFirst()
            .orElseThrow();
    assertEquals(SearchTrace.StageStatus.DISABLED, freshness.status());
  }

  @Test
  @DisplayName("per-hit: worker stages + appended head cross-encoder stage")
  void perHitWorkerPlusCrossEncoder() {
    var sr =
        io.justsearch.ipc.SearchResult.newBuilder()
            .setId("doc-1")
            .addTrace(
                io.justsearch.ipc.HitStage.newBuilder()
                    .setId("sparse-retrieval")
                    .setRank(1)
                    .setScore(5.5f)
                    .build())
            .build();
    var stages = SearchTraceMapper.mapHitStages(sr, 0.91f);
    assertEquals(2, stages.size());
    assertEquals(SearchTrace.StageId.SPARSE_RETRIEVAL, stages.get(0).id());
    assertEquals(SearchTrace.StageId.CROSS_ENCODER, stages.get(1).id());
    assertEquals(0.91f, stages.get(1).score(), 0.001f);
  }

  @Test
  @DisplayName("per-hit: cross-encoder-only hit (no worker trace) still gets its CE stage")
  void perHitCrossEncoderOnly() {
    var sr = io.justsearch.ipc.SearchResult.newBuilder().setId("doc-2").build();
    var stages = SearchTraceMapper.mapHitStages(sr, 0.42f);
    assertEquals(1, stages.size());
    assertEquals(SearchTrace.StageId.CROSS_ENCODER, stages.get(0).id());
  }

  @Test
  @DisplayName("per-hit: no worker trace + no cross-encoder → null slice")
  void perHitEmpty() {
    var sr = io.justsearch.ipc.SearchResult.newBuilder().setId("doc-3").build();
    assertNull(SearchTraceMapper.mapHitStages(sr, null));
  }

  // ---------------------------------------------------------------------------------------------
  // 549 Phase D1 NPE regression (live-caught): a hybrid/multi-leg search with the cross-encoder
  // DISABLED left ceScoresByDocId null, and the Hit-construction call site dereferenced it
  // (ceScoresByDocId.get(id)) → NPE → 500. The fix routes the lookup through ceScoreFor, which
  // must tolerate a null map. These pin the guard so removing it fails a test (mapHitStages's
  // own perHitEmpty cannot catch this — the crash happened *before* mapHitStages was reached).
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("ceScoreFor: null map (cross-encoder did not run) → null, no NPE")
  void ceScoreForNullMapDoesNotThrow() {
    assertNull(SearchTraceMapper.ceScoreFor(null, "doc-1"));
  }

  @Test
  @DisplayName("ceScoreFor: present docId → its score; absent docId → null")
  void ceScoreForLooksUpPresentAndAbsent() {
    var ceScores = java.util.Map.of("doc-1", 0.91f, "doc-2", 0.42f);
    assertEquals(0.91f, SearchTraceMapper.ceScoreFor(ceScores, "doc-1"), 0.001f);
    assertNull(SearchTraceMapper.ceScoreFor(ceScores, "doc-absent"));
  }

  @Test
  @DisplayName("ceScoreFor → mapHitStages: null map yields a structural-only per-hit slice (no CE)")
  void ceScoreForFeedsMapHitStagesWithoutCrossEncoder() {
    // The exact shape the NPE hit: a worker hit with a retrieval stage but no cross-encoder.
    var sr =
        io.justsearch.ipc.SearchResult.newBuilder()
            .setId("doc-1")
            .addTrace(
                io.justsearch.ipc.HitStage.newBuilder()
                    .setId("sparse-retrieval")
                    .setRank(1)
                    .setScore(5.5f)
                    .build())
            .build();
    var stages = SearchTraceMapper.mapHitStages(sr, SearchTraceMapper.ceScoreFor(null, "doc-1"));
    assertEquals(1, stages.size());
    assertEquals(SearchTrace.StageId.SPARSE_RETRIEVAL, stages.get(0).id());
  }

  @Test
  @DisplayName("absent worker trace → head-only stages (no throw)")
  void headOnlyWhenWorkerTraceAbsent() {
    var resp = io.justsearch.ipc.SearchResponse.newBuilder().build();
    SearchTrace trace =
        SearchTraceMapper.mapSearchTrace(
            resp,
            List.of(new TraceStage(StageId.EXPANSION, StageStatus.SKIPPED, "dense-leg-active", null, null, null)));
    assertEquals(1, trace.stages().size());
    assertEquals(SearchTrace.StageId.EXPANSION, trace.stages().get(0).id());
  }
}
