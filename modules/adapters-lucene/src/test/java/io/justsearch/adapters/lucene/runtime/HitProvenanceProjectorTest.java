package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.HitProvenanceSignals;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RetrieverSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 549 Slice 3c (U2): the projector builds typed per-hit provenance from typed leg
 * results and attaches it to post-fusion hits by docId. These tests exercise leg indexing,
 * retriever/fusion attachment (incl. the always-on fusion leg), chunk-merge attachment, and the
 * branch-fusion re-map that recovers whole-doc + chunk legs onto fresh merged hits.
 */
@DisplayName("HitProvenanceProjector: typed provenance built from leg results")
final class HitProvenanceProjectorTest {

  private static SearchHit hit(String id, float score) {
    return new SearchHit(id, score, Map.of());
  }

  private static SearchResult result(SearchHit... hits) {
    return new SearchResult(List.of(hits), hits.length, 1L);
  }

  @Test
  void indexLegRanksByPosition() {
    Map<String, RetrieverSignal> idx =
        HitProvenanceProjector.indexLeg(result(hit("a", 9f), hit("b", 5f), hit("c", 1f)));
    assertEquals(1, idx.get("a").rank());
    assertEquals(9f, idx.get("a").rawScore(), 0.001f);
    assertEquals(2, idx.get("b").rank());
    assertEquals(3, idx.get("c").rank());
  }

  @Test
  @DisplayName("attachRetrieval sets bm25/dense legs + fusion leg from the fused hit's score")
  void attachRetrievalWithFusion() {
    var bm25 = result(hit("d1", 5.0f), hit("d2", 4.0f));
    var dense = result(hit("d2", 0.9f), hit("d1", 0.8f));
    var fused = result(hit("d1", 1.23f), hit("d2", 0.99f));
    var out =
        HitProvenanceProjector.attachRetrieval(
            fused,
            HitProvenanceProjector.indexLeg(bm25),
            null,
            HitProvenanceProjector.indexLeg(dense),
            "rrf");
    HitProvenanceSignals d1 = out.hits().get(0).provenance();
    assertNotNull(d1.bm25());
    assertEquals(1, d1.bm25().rank());
    assertEquals(5.0f, d1.bm25().rawScore(), 0.001f);
    assertNull(d1.splade());
    assertNotNull(d1.dense());
    assertEquals(2, d1.dense().rank()); // d1 is 2nd in the dense leg
    // Always-on fusion: score comes from the fused hit, no debug flag needed.
    assertNotNull(d1.fusion());
    assertEquals(1.23f, d1.fusion().score(), 0.001f);
    assertEquals("rrf", d1.fusion().method());
  }

  @Test
  @DisplayName("single-leg attach (no fusion) leaves the fusion leg null")
  void attachRetrievalSingleLegNoFusion() {
    var leg = result(hit("x", 3f));
    var out =
        HitProvenanceProjector.attachRetrieval(
            leg, HitProvenanceProjector.indexLeg(leg), null, null, null);
    HitProvenanceSignals s = out.hits().get(0).provenance();
    assertNotNull(s.bm25());
    assertNull(s.fusion());
    assertNull(s.dense());
  }

  @Test
  @DisplayName("attachChunkMerge fills ranks/scores per leg; ccScore = fused chunk score")
  void attachChunkMerge() {
    var sparse = result(hit("p", 3.3f), hit("q", 2.0f));
    var dense = result(hit("q", 0.7f), hit("p", 0.5f));
    var splade = result(); // splade leg empty
    var fusedChunk = result(hit("p", 1.6f), hit("q", 1.1f));
    var out =
        HitProvenanceProjector.attachChunkMerge(
            fusedChunk,
            HitProvenanceProjector.indexLeg(sparse),
            HitProvenanceProjector.indexLeg(dense),
            HitProvenanceProjector.indexLeg(splade));
    var cm = out.hits().get(0).provenance().chunkMerge();
    assertEquals(1, cm.sparseRank());
    assertEquals(2, cm.denseRank()); // p is 2nd in dense leg
    assertEquals(0, cm.spladeRank()); // absent
    assertEquals(3.3f, cm.sparseScore(), 0.001f);
    assertEquals(0f, cm.spladeScore(), 0.001f);
    assertEquals(1.6f, cm.ccScore(), 0.001f); // fused chunk score
  }

  @Test
  @DisplayName("attachBranchFusion recovers whole-doc retriever legs + chunk-merge leg onto merged hits")
  void attachBranchFusionRemapsByDocId() {
    // whole-doc branch carries retriever + top-level fusion (attached upstream).
    var wholeHit =
        hit("doc", 6.4f)
            .withProvenance(
                new HitProvenanceSignals(
                    new RetrieverSignal(1, 5.0f), null, new RetrieverSignal(2, 0.8f),
                    new LuceneRuntimeTypes.FusionSignal(0.9f, "cc"), null, null));
    var wholeDoc = result(wholeHit);
    // chunk branch carries the chunk-merge leg.
    var chunkHit =
        hit("doc", 0.62f)
            .withProvenance(
                HitProvenanceSignals.EMPTY.withChunkMerge(
                    new LuceneRuntimeTypes.ChunkMergeSignal(3, 1, 0, 2.0f, 0.5f, 0f, 0.7f)));
    var chunkParent = result(chunkHit);
    // merged: a FRESH hit (the fuser dropped provenance) — projector must re-map.
    var merged = result(hit("doc", 1.5f));

    var out = HitProvenanceProjector.attachBranchFusion(merged, wholeDoc, chunkParent, "cc");
    HitProvenanceSignals s = out.hits().get(0).provenance();
    // retriever + fusion legs recovered from the whole-doc branch:
    assertNotNull(s.bm25());
    assertEquals(1, s.bm25().rank());
    assertNotNull(s.dense());
    assertNotNull(s.fusion());
    assertEquals("cc", s.fusion().method());
    // chunk-merge leg recovered from the chunk branch:
    assertNotNull(s.chunkMerge());
    assertEquals(3, s.chunkMerge().sparseRank());
    // branch-fusion leg built from the two branch scores + fused branch score:
    assertNotNull(s.branchFusion());
    assertEquals(6.4f, s.branchFusion().wholeBranchScore(), 0.001f);
    assertEquals(0.62f, s.branchFusion().chunkBranchScore(), 0.001f);
    assertEquals(1.5f, s.branchFusion().fusionScore(), 0.001f);
    assertEquals("cc", s.branchFusion().method());
  }

  @Test
  @DisplayName(
      "549 6b: typed provenance agrees with the debug_scores the fuser writes (divergence guard)")
  void provenanceAgreesWithDebugScores() {
    // The fuser writes debug_scores (for LambdaMART); the projector builds typed provenance from
    // the same leg results — independently. This guards against the two computations silently
    // diverging (e.g. a future change to one rank/score convention but not the other).
    var sparse = result(hit("a", 5.0f), hit("b", 3.0f));
    var dense = result(hit("b", 0.9f), hit("a", 0.8f));
    // Real fuser path: writes sparse/sparse_rank/vector/vector_rank into each fused hit's
    // debugScores (unconditional — not debug-gated).
    var fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);
    var annotated = HitProvenanceProjector.attachRetrieval(fused, sparse, null, dense, "cc");

    assertFalse(annotated.hits().isEmpty());
    for (SearchHit h : annotated.hits()) {
      Map<String, Float> ds = h.debugScores();
      HitProvenanceSignals p = h.provenance();
      assertNotNull(p, "every fused hit carries typed provenance");
      assertNotNull(p.bm25());
      assertNotNull(p.dense());
      // Ranks: typed provenance == the debug_scores the fuser emitted for the same hit.
      assertEquals(ds.get("sparse_rank").intValue(), p.bm25().rank(), "bm25 rank vs sparse_rank");
      assertEquals(ds.get("vector_rank").intValue(), p.dense().rank(), "dense rank vs vector_rank");
      // Raw scores agree too.
      assertEquals(ds.get("sparse"), p.bm25().rawScore(), 0.0001f, "bm25 score vs sparse");
      assertEquals(ds.get("vector"), p.dense().rawScore(), 0.0001f, "dense score vs vector");
    }
  }
}
