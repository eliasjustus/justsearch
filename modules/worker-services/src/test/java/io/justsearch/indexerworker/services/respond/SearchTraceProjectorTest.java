package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.plan.ChunkMergeDirective;
import io.justsearch.indexerworker.services.plan.LegSet;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceStage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 (full thesis, Phase B): the worker projects its slice of the unified stage-keyed
 * trace from the typed {@code SearchDecision}/{@code SearchOutcome}. These tests pin the
 * decision→stage mapping for the tractable decision shapes (the fusion-executed multi-leg
 * variants need encoding objects and are covered by the exhaustive {@code legsOfMultiLeg}
 * switch + the Phase-B live verification).
 */
@DisplayName("SearchTraceProjector: worker query-level Stage nodes from typed decision/outcome")
final class SearchTraceProjectorTest {

  private static SearchOutcome emptyOutcome() {
    return SearchOutcome.empty(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0));
  }

  private static String status(SearchTrace t, String wireId) {
    return t.getStagesList().stream()
        .filter(s -> s.getId().equals(wireId))
        .map(TraceStage::getStatus)
        .findFirst()
        .orElse("<absent>");
  }

  @Test
  @DisplayName("every query emits the full closed worker stage set, once each")
  void emitsFullWorkerStageSet() {
    SearchTrace t =
        SearchTraceProjector.project(
            new SearchDecision.EmptyQueryDecision(10, "TEXT"), emptyOutcome(), null);
    List<String> ids = t.getStagesList().stream().map(TraceStage::getId).toList();
    // Worker-owned stages (head adds query-classification/expansion/lambdamart/cross-encoder/
    // freshness in Phase C). Exactly one node each.
    assertEquals(
        List.of(
            "correction",
            "sparse-retrieval",
            "dense-retrieval",
            "splade-retrieval",
            "fusion",
            "chunk-merge",
            "branch-fusion"),
        ids);
  }

  @Test
  @DisplayName("empty query disables retrieval legs; correction/fusion/merge skipped")
  void emptyQueryDisablesLegs() {
    SearchTrace t =
        SearchTraceProjector.project(
            new SearchDecision.EmptyQueryDecision(10, "TEXT"), emptyOutcome(), null);
    assertEquals("disabled", status(t, "sparse-retrieval"));
    assertEquals("disabled", status(t, "dense-retrieval"));
    assertEquals("disabled", status(t, "splade-retrieval"));
    assertEquals("skipped", status(t, "fusion"));
    assertEquals("skipped", status(t, "correction"));
    assertEquals("skipped", status(t, "chunk-merge"));
    assertEquals("skipped", status(t, "branch-fusion"));
  }

  @Test
  @DisplayName("bm25-only multi-leg: sparse executed, dense/splade skipped, no fusion")
  void bm25OnlyLeg() {
    var decision =
        new SearchDecision.MultiLegDecision(
            new LegSet.Bm25Only(10),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_DISABLED));
    SearchTrace t = SearchTraceProjector.project(decision, emptyOutcome(), null);
    assertEquals("executed", status(t, "sparse-retrieval"));
    assertEquals("skipped", status(t, "dense-retrieval"));
    assertEquals("skipped", status(t, "splade-retrieval"));
    assertEquals("skipped", status(t, "fusion"));
  }

  @Test
  @DisplayName("sparse shortcut: sparse executed, dense/splade skipped")
  void sparseShortcut() {
    var decision =
        new SearchDecision.SparseShortcut(
            LuceneRuntimeTypes.QuerySyntax.SIMPLE,
            10,
            true,
            Optional.empty(),
            new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_DISABLED));
    SearchTrace t = SearchTraceProjector.project(decision, emptyOutcome(), null);
    assertEquals("executed", status(t, "sparse-retrieval"));
    assertEquals("skipped", status(t, "dense-retrieval"));
    assertEquals("skipped", status(t, "splade-retrieval"));
  }

  @Test
  @DisplayName("stage ms is a lossless superset of the legacy IntrospectionTiming (Phase D0)")
  void stageTimingDivergence() {
    // An outcome with every timed phase populated. These raw values are exactly what
    // SearchIntrospectionProjector.projectTiming reads into the legacy IntrospectionTiming
    // (retrievalMs / chunkMergeMs / branchFusionNs); the trace must carry them per-stage so
    // PipelineExecution + IntrospectionTiming can retire in Phase E3 without losing a signal.
    var outcome =
        new SearchOutcome(
            new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0),
            null, null, "", 42L,
            false, null,
            true, SearchReasonCode.APPLIED,
            "cc", true, true,
            7L, 0L, 0L, 0L, false, 9_000_000L);
    var decision =
        new SearchDecision.MultiLegDecision(
            new LegSet.Bm25Only(10),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_DISABLED));
    SearchTrace t = SearchTraceProjector.project(decision, outcome, null);

    // retrievalMs → FUSION.ms (carried even though bm25-only fusion is skipped).
    TraceStage fusion = stageNode(t, "fusion");
    assertEquals("skipped", fusion.getStatus());
    assertEquals(42L, fusion.getMs());
    // chunkMergeMs → CHUNK_MERGE.ms; branchFusionNs/1e6 → BRANCH_FUSION.ms.
    TraceStage chunk = stageNode(t, "chunk-merge");
    assertEquals("executed", chunk.getStatus());
    assertEquals(7L, chunk.getMs());
    TraceStage branch = stageNode(t, "branch-fusion");
    assertEquals("executed", branch.getStatus());
    assertEquals(9L, branch.getMs());
    assertEquals("cc", branch.getDetail());
  }

  @Test
  @DisplayName("tempdoc 597: the funnel rungs — matched on the sparse stage, ranked on the merge stage")
  void funnelCardinalities() {
    // chunkMergeApplied = true, totalHits (union/ranked) = 167; matchCount (matched) = 428.
    var outcome =
        new SearchOutcome(
            new LuceneRuntimeTypes.SearchResult(List.of(), 167, 0),
            null, null, "", 5L,
            false, null,
            true, SearchReasonCode.APPLIED,
            "cc", true, false,
            7L, 0L, 0L, 0L, false, 0L);
    var decision =
        new SearchDecision.SparseShortcut(
            LuceneRuntimeTypes.QuerySyntax.SIMPLE,
            10,
            true,
            Optional.empty(),
            new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_DISABLED));
    SearchTrace t = SearchTraceProjector.project(decision, outcome, null, 428L);

    // "matched M" — the lexical retrieval stage carries the true matched count.
    TraceStage sparse = stageNode(t, "sparse-retrieval");
    assertEquals("executed", sparse.getStatus());
    assertTrue(sparse.hasCardinality(), "sparse stage should carry the matched cardinality");
    assertEquals(428L, sparse.getCardinality());

    // "ranked R" — fusion is skipped on the sparse path, so the first executed narrowing stage
    // (chunk-merge) carries the ranked-union size; it is shown exactly once.
    TraceStage chunk = stageNode(t, "chunk-merge");
    assertEquals("executed", chunk.getStatus());
    assertTrue(chunk.hasCardinality(), "chunk-merge should carry the ranked-union cardinality");
    assertEquals(167L, chunk.getCardinality());

    // The skipped fusion stage carries no cardinality (ranked is shown on chunk-merge, not twice).
    assertFalse(stageNode(t, "fusion").hasCardinality());
  }

  private static TraceStage stageNode(SearchTrace t, String wireId) {
    return t.getStagesList().stream()
        .filter(s -> s.getId().equals(wireId))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("correction stage reflects an applied correction with the corrected query detail")
  void correctionApplied() {
    var outcome =
        new SearchOutcome(
            new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0),
            null, null, "", 0L,
            true, "corrected text",
            false, null, null, false, false, 0L, 0L, 0L, 0L, false, 0L);
    SearchTrace t =
        SearchTraceProjector.project(
            new SearchDecision.EmptyQueryDecision(10, "TEXT"), outcome, null);
    TraceStage corr =
        t.getStagesList().stream().filter(s -> s.getId().equals("correction")).findFirst().orElseThrow();
    assertEquals("executed", corr.getStatus());
    assertEquals("corrected text", corr.getDetail());
  }
}
