/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.HybridFusionUtils;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tempdoc 916 Part 2 (lane E) — aggregate-then-cut parent collapse, end-to-end on a real chunked
 * Lucene index.
 *
 * <p>The unit tests pin the aggregator on synthetic scores. This one closes the two seams they
 * cannot: that the two new config keys actually resolve onto the accessors the chunk branch reads,
 * and that on a genuine BM25-scored chunk index the OFF arm really does lose a document whose
 * evidence is spread across mid-ranked chunks while the ON arm recovers it. The chunk leg and the
 * CC fusion are the production ones ({@code ChunkSearchOps.searchChunksText} +
 * {@code HybridFusionUtils.fuseWithCC3}), driven exactly as {@code executeChunkBranchFusion} drives
 * them, so the only thing standing in for the executor is the parameter read — which the first test
 * pins directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SearchExecutor chunk collapse on a real chunked index (916 Part 2)")
final class SearchExecutorChunkCollapseIndexIntegrationTest {

  private static final int VECTOR_DIM = 8;
  private static final String QUERY = "entanglement decoherence";
  /** The collapse cap the branch would use for limit=2 at the default collapse multiplier 2. */
  private static final int COLLAPSE_LIMIT = 4;

  private RunningRuntime runtime;

  @BeforeAll
  void indexCorpus() {
    runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(VECTOR_DIM)).ephemeral().open();

    // p-focused: one short, term-dense chunk — the strongest single passage in the corpus.
    indexParentWithChunks("p-focused", List.of(passage(4, 0)));

    // p-fill-a/b/c: one chunk each, dense enough that every one of them outranks every single
    // p-spread chunk. The pre-916 collapse fills its cap with these three plus p-focused.
    for (String id : List.of("p-fill-a", "p-fill-b", "p-fill-c")) {
      indexParentWithChunks(id, List.of(passage(3, 6)));
    }

    // p-spread: six chunks, each carrying the query terms but diluted enough by BM25 length
    // normalization to sit below every filler. Exactly the shape the pre-916 collapse discards:
    // real corroborating evidence, no single winning passage.
    List<String> spread = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      spread.add(passage(3, 14));
    }
    indexParentWithChunks("p-spread", spread);

    // A long tail of weak single-chunk parents. Without it p-spread would BE the pool minimum, and
    // fuseWithCC3 min-max normalizes the worst candidate to exactly 0.0 — an aggregate of zeros is
    // still zero, so no lambda could lift it. Real corpora always have this tail; the fixture must
    // too, or it would test an artefact of the normalization floor rather than the collapse.
    for (int i = 0; i < 6; i++) {
      indexParentWithChunks("p-tail-" + i, List.of(passage(1, 60)));
    }

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @AfterAll
  void close() {
    if (runtime != null) {
      runtime.close();
    }
  }

  /** A chunk carrying the query terms {@code hits} times, padded with {@code filler} words. */
  private static String passage(int hits, int filler) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < hits; i++) {
      sb.append("entanglement decoherence ");
    }
    for (int i = 0; i < filler; i++) {
      sb.append("lattice thermal reservoir calibration apparatus notes ");
    }
    return sb.toString().trim();
  }

  private void indexParentWithChunks(String parentId, List<String> chunkTexts) {
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, parentId,
                    SchemaFields.DOC_UID, parentId + "#0",
                    SchemaFields.CONTENT, String.join("\n\n", chunkTexts),
                    SchemaFields.PATH, parentId,
                    SchemaFields.LANGUAGE, "en")));
    for (int i = 0; i < chunkTexts.size(); i++) {
      String chunkId = parentId + "#chunk_" + i;
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, chunkId);
      fields.put(SchemaFields.DOC_UID, chunkId + "#0");
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parentId);
      fields.put(SchemaFields.CHUNK_INDEX, String.valueOf(i));
      fields.put(SchemaFields.CHUNK_TOTAL, String.valueOf(chunkTexts.size()));
      fields.put(SchemaFields.CHUNK_CONTENT, chunkTexts.get(i));
      fields.put(SchemaFields.PATH, parentId);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
  }

  /** The production chunk leg, fused exactly as {@code executeChunkBranchFusion} fuses it. */
  private SearchResult fusedChunkLeg(int candidateBudget) {
    SearchResult bm25 = runtime.chunkSearchOps().searchChunksText(QUERY, candidateBudget, null);
    SearchResult empty = new SearchResult(List.of(), 0, 0);
    return HybridFusionUtils.fuseWithCC3(
        bm25, empty, empty, candidateBudget, new double[] {1.0, 0.0, 0.0}, false, true, "chunk_", true);
  }

  private static List<String> parentIds(SearchResult result) {
    return result.hits().stream().map(SearchHit::docId).collect(Collectors.toList());
  }

  @Test
  @DisplayName("the two new keys resolve onto the accessors the chunk branch reads")
  void configReachesTheCollapseParameters() {
    ResolvedConfig.HybridSearch off = new ResolvedConfigBuilder().build().hybridSearch();
    assertEquals(1, off.chunkCollapseScanCapMultiplier(), "shipped default is the control arm");
    assertEquals(0.0, off.chunkCollapseAggregationLambda(), 0.0);

    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.put("index.hybrid.chunk_collapse_scan_cap_multiplier", 400, "env_var", null, "5");
    builder.put("index.hybrid.chunk_collapse_aggregation_lambda", 400, "env_var", null, "0.3");
    ResolvedConfig.HybridSearch on = builder.build().hybridSearch();
    assertEquals(5, on.chunkCollapseScanCapMultiplier());
    assertEquals(0.3, on.chunkCollapseAggregationLambda(), 0.0001);
  }

  @Test
  @DisplayName("the chunk leg really does bury the spread parent below the fillers")
  void fixturePrecondition() {
    List<String> fusedChunkOrder =
        fusedChunkLeg(COLLAPSE_LIMIT * 5).hits().stream()
            .map(h -> h.fields().get(SchemaFields.PARENT_DOC_ID))
            .distinct()
            .collect(Collectors.toList());
    assertEquals("p-focused", fusedChunkOrder.get(0), "the dense passage still wins its chunk");
    assertTrue(
        fusedChunkOrder.indexOf("p-spread") >= COLLAPSE_LIMIT,
        "fixture is only meaningful if p-spread sits beyond the collapse cap, got order "
            + fusedChunkOrder);
  }

  @Test
  @DisplayName("OFF (1, 0.0) drops the spread parent; ON (5, 0.3) recovers it inside the same cap")
  void aggregationRecoversTheSpreadParent() {
    SearchResult fusedChunks = fusedChunkLeg(COLLAPSE_LIMIT * 5);

    SearchResult off = SearchExecutor.collapseChunkHitsToParents(fusedChunks, COLLAPSE_LIMIT, 1, 0.0);
    assertEquals(
        List.of("p-focused", "p-fill-a", "p-fill-b", "p-fill-c"),
        parentIds(off),
        "pre-916 behaviour fills the cap with single-passage parents and loses p-spread");

    SearchResult on = SearchExecutor.collapseChunkHitsToParents(fusedChunks, COLLAPSE_LIMIT, 5, 0.3);
    assertEquals(COLLAPSE_LIMIT, on.hits().size(), "the cap is unchanged — only its contents move");
    assertTrue(parentIds(on).contains("p-spread"), "aggregation must surface the spread parent");
    // Six corroborating chunks at 0.7966 aggregate to 0.7966 * (1 + 0.3 * 1.9375) = 1.26, which on
    // this fixture is enough to pass the single 1.0 passage as well. That is the lever working as
    // specified, and precisely why lambda is measured rather than assumed (916 §D decision rule).
    assertEquals(
        List.of("p-spread", "p-focused", "p-fill-a", "p-fill-b"),
        parentIds(on),
        "aggregate-then-cut reorders by corroboration, then cuts");
    assertTrue(
        parentIds(on).stream().noneMatch(id -> id.startsWith("p-tail")),
        "the zero-scoring tail stays out — over-fetch widens the scan, not the output");
  }
}
