package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for HybridFusionUtils utility methods.
 *
 * <p>Tests the RRF (Reciprocal Rank Fusion) algorithm for combining search results.
 */
@DisplayName("HybridFusionUtils")
class HybridFusionUtilsTest {

  @Nested
  @DisplayName("fuseWithRRF()")
  class FuseWithRRFTests {

    @Test
    @DisplayName("empty results return empty fused result")
    void emptyResults_returnEmpty() {
      SearchResult bm25 = new SearchResult(List.of(), 0, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertNotNull(fused);
      assertTrue(fused.hits().isEmpty());
      assertEquals(0, fused.totalHits());
    }

    @Test
    @DisplayName("single BM25 result with empty KNN returns BM25 result")
    void singleBm25Only_returnsWithRrfScore() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of("title", "Test"));
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      assertEquals("doc-1", fused.hits().get(0).docId());
      // RRF score = 1/(60+1) ≈ 0.0164
      assertTrue(fused.hits().get(0).score() > 0.016f);
      assertTrue(fused.hits().get(0).score() < 0.017f);
    }

    @Test
    @DisplayName("single KNN result with empty BM25 returns KNN result")
    void singleKnnOnly_returnsWithRrfScore() {
      SearchResult bm25 = new SearchResult(List.of(), 0, 0);
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of("title", "Test"));
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      assertEquals("doc-1", fused.hits().get(0).docId());
    }

    @Test
    @DisplayName("null docId does not consume a rank slot in either leg (tempdoc 554 §B.1)")
    void nullDocId_doesNotConsumeRank() {
      // The KNN leg has a malformed null-docId hit *before* the real hit "B".
      SearchHit nullHit = new SearchHit(null, 9.0f, Map.of());
      SearchHit b = new SearchHit("B", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(), 0, 0);
      SearchResult knn = new SearchResult(List.of(nullHit, b), 2, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      assertEquals("B", fused.hits().get(0).docId());
      // B is rank 1 (the null hit does not consume a rank): RRF = 1/(60+1), not 1/(60+2).
      assertEquals(
          1.0f / 61.0f,
          fused.hits().get(0).score(),
          0.00005f,
          "B must rank at position 1, not be pushed to 2 by the preceding null hit");
    }

    @Test
    @DisplayName("overlapping documents get combined RRF scores")
    void overlappingDocs_combineScores() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      // Combined score should be higher than single source
      // BM25 RRF = 1/(60+1), KNN RRF = 1.0/(60+1), combined ≈ 0.0328
      assertTrue(fused.hits().get(0).score() > 0.032f);
    }

    @Test
    @DisplayName("documents are ordered by RRF score descending")
    void multipleDocuments_orderedByScore() {
      // doc-1: rank 1 in both (highest combined score)
      // doc-2: rank 2 in BM25 only
      // doc-3: rank 1 in KNN only
      SearchHit bm25Hit1 = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit bm25Hit2 = new SearchHit("doc-2", 4.0f, Map.of());
      SearchHit knnHit1 = new SearchHit("doc-1", 0.95f, Map.of());
      SearchHit knnHit3 = new SearchHit("doc-3", 0.90f, Map.of());

      SearchResult bm25 = new SearchResult(List.of(bm25Hit1, bm25Hit2), 2, 0);
      SearchResult knn = new SearchResult(List.of(knnHit1, knnHit3), 2, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(3, fused.hits().size());
      assertEquals(3, fused.totalHits());
      // doc-1 should be first (appears in both)
      assertEquals("doc-1", fused.hits().get(0).docId());
    }

    @Test
    @DisplayName("limit restricts number of results")
    void limitRestrictsResults() {
      SearchHit bm25Hit1 = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit bm25Hit2 = new SearchHit("doc-2", 4.0f, Map.of());
      SearchHit bm25Hit3 = new SearchHit("doc-3", 3.0f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit1, bm25Hit2, bm25Hit3), 3, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 2, false, 10, 1.0, null);

      assertEquals(2, fused.hits().size());
      // totalHits reflects union size, not limit
      assertEquals(3, fused.totalHits());
    }

    @Test
    @DisplayName("vectorOnlyCap limits vector-only documents")
    void vectorOnlyCap_limitsVectorOnlyDocs() {
      // BM25 has doc-1
      // KNN has doc-2, doc-3, doc-4 (all vector-only)
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit2 = new SearchHit("doc-2", 0.9f, Map.of());
      SearchHit knnHit3 = new SearchHit("doc-3", 0.8f, Map.of());
      SearchHit knnHit4 = new SearchHit("doc-4", 0.7f, Map.of());

      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit2, knnHit3, knnHit4), 3, 0);

      // Cap vector-only docs to 1
      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 1, 1.0, null);

      // Should have doc-1 (BM25) + doc-2 (first vector-only, within cap)
      assertEquals(2, fused.hits().size());
      assertTrue(fused.hits().stream().anyMatch(h -> "doc-1".equals(h.docId())));
      assertTrue(fused.hits().stream().anyMatch(h -> "doc-2".equals(h.docId())));
      assertFalse(fused.hits().stream().anyMatch(h -> "doc-3".equals(h.docId())));
    }

    @Test
    @DisplayName("vectorWeight=0 effectively ignores vector results")
    void vectorWeightZero_ignoresVector() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-2", 0.95f, Map.of());

      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 0.0, null);

      // Both docs should be present
      assertEquals(2, fused.hits().size());
      // But doc-1 should be ranked higher since vector weight is 0
      assertEquals("doc-1", fused.hits().get(0).docId());
      // doc-2 has score 0 from vector contribution
      assertEquals("doc-2", fused.hits().get(1).docId());
      assertEquals(0.0f, fused.hits().get(1).score(), 0.0001f);
    }

    @Test
    @DisplayName("vectorWeight is clamped to 0.0-1.0")
    void vectorWeight_isClamped() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      // Weight > 1.0 should be clamped to 1.0
      SearchResult fused1 = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 2.0, null);
      // Weight < 0.0 should be clamped to 0.0
      SearchResult fused2 = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, -0.5, null);
      // Weight = 1.0 (normal)
      SearchResult fused3 = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // Clamped to 1.0 should equal normal 1.0
      assertEquals(fused3.hits().get(0).score(), fused1.hits().get(0).score(), 0.0001f);
      // Clamped to 0.0 should have lower score (no vector contribution)
      assertTrue(fused2.hits().get(0).score() < fused3.hits().get(0).score());
    }

    @Test
    @DisplayName("debug mode populates debugScores")
    void debugMode_populatesDebugScores() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, true, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      SearchHit hit = fused.hits().get(0);
      assertNotNull(hit.debugScores());

      // Assert exact keyset — all 9 debug keys must be present, no more, no less
      assertEquals(
          Set.of("sparse", "vector", "sparse_rank", "vector_rank",
              "sparse_rrf", "vector_rrf", "sparse_boost", "rrf_base", "rrf"),
          hit.debugScores().keySet(),
          "debug mode must emit exactly these 9 score keys");

      assertEquals(5.0f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("vector"), 0.001f);
    }

    @Test
    @DisplayName("non-debug mode always emits raw sparse and vector scores")
    void nonDebugMode_alwaysEmitsRawScores() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      SearchHit hit = fused.hits().get(0);
      // Assert exact keyset — 4 keys: raw scores + ranks, no debug decomposition
      assertNotNull(hit.debugScores());
      assertEquals(
          Set.of("sparse", "vector", "sparse_rank", "vector_rank"),
          hit.debugScores().keySet(),
          "non-debug mode must emit raw scores and ranks for LambdaMART and provenance");
      assertEquals(5.0f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("vector"), 0.001f);
    }

    @Test
    @DisplayName("sparse-only hit has zero vector score in non-debug mode")
    void nonDebugMode_sparseOnlyHit_hasZeroVectorScore() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      SearchHit hit = fused.hits().get(0);
      assertEquals(5.0f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.0f, hit.debugScores().get("vector"), 0.001f);
    }

    @Test
    @DisplayName("null docId hits are skipped")
    void nullDocId_skipped() {
      SearchHit bm25Hit1 = new SearchHit(null, 5.0f, Map.of());
      SearchHit bm25Hit2 = new SearchHit("doc-2", 4.0f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit1, bm25Hit2), 2, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      assertEquals(1, fused.hits().size());
      assertEquals("doc-2", fused.hits().get(0).docId());
    }

    @Test
    @DisplayName("null runtimeConfig uses default K=60")
    void nullConfig_usesDefaultK() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // With K=60, rank 1 score = 1/(60+1) ≈ 0.01639
      float expectedScore = 1.0f / 61;
      assertEquals(expectedScore, fused.hits().get(0).score(), 0.0001f);
    }

    @Test
    @DisplayName("fields are preserved from first occurrence")
    void fieldsPreservedFromFirstOccurrence() {
      Map<String, String> bm25Fields = Map.of("title", "BM25 Title", "source", "bm25");
      Map<String, String> knnFields = Map.of("title", "KNN Title", "source", "knn");
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, bm25Fields);
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, knnFields);

      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // Fields from first occurrence (BM25) should be preserved
      assertEquals("BM25 Title", fused.hits().get(0).fields().get("title"));
      assertEquals("bm25", fused.hits().get(0).fields().get("source"));
    }

    @Test
    @DisplayName("tie-breaker uses lexical score, then vector score, then docId")
    void tieBreaker_usesMultipleCriteria() {
      // Create docs with same RRF score but different raw scores
      SearchHit bm25Hit1 = new SearchHit("doc-a", 5.0f, Map.of());
      SearchHit bm25Hit2 = new SearchHit("doc-b", 4.0f, Map.of());

      SearchResult bm25 = new SearchResult(List.of(bm25Hit1, bm25Hit2), 2, 0);
      SearchResult knn = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // doc-a should come first due to higher BM25 rank
      assertEquals("doc-a", fused.hits().get(0).docId());
      assertEquals("doc-b", fused.hits().get(1).docId());
    }

    @Test
    @DisplayName("scoreKeyPrefix writes prefixed keys in non-debug mode")
    void scoreKeyPrefix_writesPrefixedKeys() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null, "chunk_");

      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("chunk_sparse", "chunk_vector", "chunk_sparse_rank", "chunk_vector_rank"),
          hit.debugScores().keySet(),
          "prefixed non-debug mode must emit chunk scores and ranks");
      assertEquals(5.0f, hit.debugScores().get("chunk_sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("chunk_vector"), 0.001f);
    }

    @Test
    @DisplayName("scoreKeyPrefix writes prefixed keys in debug mode")
    void scoreKeyPrefix_writesPrefixedKeysDebug() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, true, 10, 1.0, null, "chunk_");

      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("chunk_sparse", "chunk_vector", "chunk_sparse_rank", "chunk_vector_rank",
              "chunk_sparse_rrf", "chunk_vector_rrf",
              "chunk_sparse_boost", "chunk_rrf_base", "chunk_rrf"),
          hit.debugScores().keySet(),
          "prefixed debug mode must emit all 9 keys with prefix");
    }

    @Test
    @DisplayName("existing debugScores from input hits are carried forward through fusion")
    void carryForward_preservesOriginalScores() {
      // Input hits already have debug scores from a prior fusion stage
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of(),
          Map.of("sparse", 14.2f, "vector", 0.91f));
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      // Fuse with prefix so new scores don't collide with carried-forward ones
      SearchResult fused = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null, "chunk_");

      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("sparse", "vector", "chunk_sparse", "chunk_vector",
              "chunk_sparse_rank", "chunk_vector_rank"),
          hit.debugScores().keySet(),
          "must carry forward original sparse/vector AND write new chunk_ keys with ranks");

      // Original scores preserved
      assertEquals(14.2f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.91f, hit.debugScores().get("vector"), 0.001f);
      // New fusion scores written with prefix
      assertEquals(5.0f, hit.debugScores().get("chunk_sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("chunk_vector"), 0.001f);
    }

    @Test
    @DisplayName("rank keys present after fusion")
    void rankKeys_presentAfterFusion() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      SearchHit hit = fused.hits().get(0);
      assertNotNull(hit.debugScores().get("sparse_rank"));
      assertNotNull(hit.debugScores().get("vector_rank"));
    }

    @Test
    @DisplayName("rank keys match retriever position")
    void rankKeys_matchRetrieverPosition() {
      SearchHit bm25Hit1 = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit bm25Hit2 = new SearchHit("doc-2", 4.0f, Map.of());
      SearchHit knnHit1 = new SearchHit("doc-2", 0.95f, Map.of());
      SearchHit knnHit2 = new SearchHit("doc-1", 0.80f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit1, bm25Hit2), 2, 0);
      SearchResult knn = new SearchResult(List.of(knnHit1, knnHit2), 2, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // doc-1: BM25 rank 1, KNN rank 2
      SearchHit doc1 = fused.hits().stream()
          .filter(h -> "doc-1".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(1.0f, doc1.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(2.0f, doc1.debugScores().get("vector_rank"), 0.001f);

      // doc-2: BM25 rank 2, KNN rank 1
      SearchHit doc2 = fused.hits().stream()
          .filter(h -> "doc-2".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(2.0f, doc2.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(1.0f, doc2.debugScores().get("vector_rank"), 0.001f);
    }

    @Test
    @DisplayName("rank is zero when absent from a leg")
    void rankKeys_zeroWhenAbsentFromLeg() {
      SearchHit bm25Hit = new SearchHit("doc-bm25", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-knn", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(bm25, knn, 10, false, 10, 1.0, null);

      // BM25-only doc: sparse_rank=1, vector_rank=0
      SearchHit bm25Only = fused.hits().stream()
          .filter(h -> "doc-bm25".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(1.0f, bm25Only.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(0.0f, bm25Only.debugScores().get("vector_rank"), 0.001f);

      // KNN-only doc: sparse_rank=0, vector_rank=1
      SearchHit knnOnly = fused.hits().stream()
          .filter(h -> "doc-knn".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(0.0f, knnOnly.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(1.0f, knnOnly.debugScores().get("vector_rank"), 0.001f);
    }

    @Test
    @DisplayName("rank keys are prefixed with scoreKeyPrefix")
    void rankKeys_prefixed() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null, "chunk_");

      SearchHit hit = fused.hits().get(0);
      assertEquals(1.0f, hit.debugScores().get("chunk_sparse_rank"), 0.001f);
      assertEquals(1.0f, hit.debugScores().get("chunk_vector_rank"), 0.001f);
      // Unprefixed rank keys should not exist
      assertNull(hit.debugScores().get("sparse_rank"));
      assertNull(hit.debugScores().get("vector_rank"));
    }

    @Test
    @DisplayName("rank keys from prior fusion are carried forward")
    void rankKeys_carriedForward() {
      // Input hits already have rank keys from a prior fusion stage
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of(),
          Map.of("sparse", 14.2f, "vector", 0.91f, "sparse_rank", 2.0f, "vector_rank", 3.0f));
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null, "chunk_");

      SearchHit hit = fused.hits().get(0);
      // Original rank keys carried forward
      assertEquals(2.0f, hit.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(3.0f, hit.debugScores().get("vector_rank"), 0.001f);
      // New chunk rank keys written
      assertEquals(1.0f, hit.debugScores().get("chunk_sparse_rank"), 0.001f);
      assertEquals(1.0f, hit.debugScores().get("chunk_vector_rank"), 0.001f);
    }

    @Test
    @DisplayName("empty prefix produces identical behavior to legacy 7-arg overload")
    void emptyPrefix_backwardCompatible() {
      SearchHit bm25Hit = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit knnHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult bm25 = new SearchResult(List.of(bm25Hit), 1, 0);
      SearchResult knn = new SearchResult(List.of(knnHit), 1, 0);

      SearchResult fused7 = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null);
      SearchResult fused8 = HybridFusionUtils.fuseWithRRF(
          bm25, knn, 10, false, 10, 1.0, null, "");

      assertEquals(fused7.hits().get(0).debugScores(), fused8.hits().get(0).debugScores());
      assertEquals(fused7.hits().get(0).score(), fused8.hits().get(0).score(), 0.0001f);
    }

    @Test
    @DisplayName("fuseWithRRFNamed emits explicit branch labels without collisions")
    void fuseWithRrfNamed_usesExplicitBranchLabels() {
      SearchResult whole = new SearchResult(List.of(new SearchHit("doc-1", 0.8f, Map.of())), 1, 0);
      SearchResult chunk = new SearchResult(List.of(new SearchHit("chunk-1", 0.7f, Map.of())), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithRRFNamed(
              whole,
              chunk,
              10,
              true,
              Integer.MAX_VALUE,
              1.0,
              null,
              "whole_branch",
              "chunk_branch",
              "branch_merge_",
              false);

      SearchHit first = fused.hits().get(0);
      assertTrue(first.debugScores().containsKey("whole_branch"));
      assertTrue(first.debugScores().containsKey("whole_branch_rrf"));
      assertTrue(first.debugScores().containsKey("branch_merge_rrf"));
      assertNull(first.debugScores().get("sparse"), "named branch fusion should not invent sparse keys");

      SearchHit second = fused.hits().get(1);
      assertTrue(second.debugScores().containsKey("chunk_branch"));
      assertTrue(second.debugScores().containsKey("chunk_branch_rrf"));
      assertTrue(second.debugScores().containsKey("branch_merge_rrf"));
      assertNull(second.debugScores().get("vector"), "named branch fusion should not invent vector keys");
    }
  }

  @Nested
  @DisplayName("fuseWithCC()")
  class FuseWithCCTests {

    @Test
    @DisplayName("empty results return empty fused result")
    void emptyResults_returnEmpty() {
      SearchResult sparse = new SearchResult(List.of(), 0, 0);
      SearchResult dense = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertNotNull(fused);
      assertTrue(fused.hits().isEmpty());
      assertEquals(0, fused.totalHits());
    }

    @Test
    @DisplayName("sparse-only results returned with normalized scores")
    void sparseOnly_returnsNormalizedScores() {
      SearchHit hit1 = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);
      SearchResult dense = new SearchResult(List.of(), 0, 0);

      // alpha=0.5 → sparse weight = 0.5
      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals(2, fused.hits().size());
      assertEquals("doc-1", fused.hits().get(0).docId());
      // doc-1: norm_sparse = (10-5)/(10-5) = 1.0, score = 0.5 * 0 + 0.5 * 1.0 = 0.5
      assertEquals(0.5f, fused.hits().get(0).score(), 0.001f);
      // doc-2: norm_sparse = (5-5)/(10-5) = 0.0, score = 0.5 * 0 + 0.5 * 0.0 = 0.0
      assertEquals(0.0f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("dense-only results returned with normalized scores")
    void denseOnly_returnsNormalizedScores() {
      SearchResult sparse = new SearchResult(List.of(), 0, 0);
      SearchHit hit1 = new SearchHit("doc-1", 0.95f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 0.80f, Map.of());
      SearchResult dense = new SearchResult(List.of(hit1, hit2), 2, 0);

      // alpha=0.5 → dense weight = 0.5
      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals(2, fused.hits().size());
      assertEquals("doc-1", fused.hits().get(0).docId());
      // doc-1: norm_dense = (0.95-0.80)/(0.95-0.80) = 1.0, score = 0.5 * 1.0 = 0.5
      assertEquals(0.5f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("overlapping documents combine normalized scores")
    void overlap_combinesNormalizedScores() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.95f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals(1, fused.hits().size());
      // Single doc in each leg → range=0 → norm=1.0 for both
      // score = 0.5 * 1.0 + 0.5 * 1.0 = 1.0
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("alpha=0 gives full weight to sparse")
    void alphaZero_sparseOnly() {
      SearchHit sparseHit = new SearchHit("doc-s", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-d", 0.95f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.0, false, false);

      assertEquals(2, fused.hits().size());
      // doc-s: norm_sparse=1.0, score = 0*0 + 1*1.0 = 1.0
      assertEquals("doc-s", fused.hits().get(0).docId());
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
      // doc-d: norm_sparse=0 (not in sparse), score = 0*1.0 + 1*0 = 0.0
      assertEquals("doc-d", fused.hits().get(1).docId());
      assertEquals(0.0f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("alpha=1 gives full weight to dense")
    void alphaOne_denseOnly() {
      SearchHit sparseHit = new SearchHit("doc-s", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-d", 0.95f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 1.0, false, false);

      assertEquals(2, fused.hits().size());
      // doc-d: norm_dense=1.0, score = 1*1.0 + 0*0 = 1.0
      assertEquals("doc-d", fused.hits().get(0).docId());
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
      // doc-s: norm_dense=0 (not in dense), score = 1*0 + 0*1.0 = 0.0
      assertEquals("doc-s", fused.hits().get(1).docId());
      assertEquals(0.0f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("range=0 (all same scores) uses 1.0 for all normalized scores")
    void rangeZero_usesOneForAll() {
      // Two docs with identical sparse scores
      SearchHit hit1 = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);
      SearchResult dense = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals(2, fused.hits().size());
      // Both norm to 1.0 when range=0, score = 0.5 * 0 + 0.5 * 1.0 = 0.5
      assertEquals(0.5f, fused.hits().get(0).score(), 0.001f);
      assertEquals(0.5f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("limit restricts number of results")
    void limitRestrictsResults() {
      SearchHit hit1 = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 8.0f, Map.of());
      SearchHit hit3 = new SearchHit("doc-3", 6.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2, hit3), 3, 0);
      SearchResult dense = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 2, 0.5, false, false);

      assertEquals(2, fused.hits().size());
      assertEquals(3, fused.totalHits());
    }

    @Test
    @DisplayName("null docId hits are skipped")
    void nullDocId_skipped() {
      SearchHit hit1 = new SearchHit(null, 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);
      SearchResult dense = new SearchResult(List.of(), 0, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals(1, fused.hits().size());
      assertEquals("doc-2", fused.hits().get(0).docId());
    }

    @Test
    @DisplayName("debug mode populates cc and cc_alpha scores")
    void debugMode_populatesCCScores() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.7, true, false);

      assertEquals(1, fused.hits().size());
      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("sparse", "vector", "sparse_rank", "vector_rank", "cc", "cc_alpha"),
          hit.debugScores().keySet(),
          "debug mode must emit sparse, vector, ranks, cc, and cc_alpha");
      assertEquals(10.0f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("vector"), 0.001f);
      assertEquals(0.7f, hit.debugScores().get("cc_alpha"), 0.001f);
    }

    @Test
    @DisplayName("non-debug mode emits only sparse and vector raw scores")
    void nonDebugMode_emitsRawScoresOnly() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("sparse", "vector", "sparse_rank", "vector_rank"),
          hit.debugScores().keySet(),
          "non-debug mode must emit sparse, vector, and ranks");
    }

    @Test
    @DisplayName("fields are preserved from first occurrence")
    void fieldsPreservedFromFirstOccurrence() {
      Map<String, String> sparseFields = Map.of("title", "Sparse Title");
      Map<String, String> denseFields = Map.of("title", "Dense Title");
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, sparseFields);
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, denseFields);

      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      assertEquals("Sparse Title", fused.hits().get(0).fields().get("title"));
    }

    @Test
    @DisplayName("rank keys present after CC fusion")
    void ccRankKeys_presentAfterFusion() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      SearchHit hit = fused.hits().get(0);
      assertEquals(1.0f, hit.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(1.0f, hit.debugScores().get("vector_rank"), 0.001f);
    }

    @Test
    @DisplayName("CC rank is zero when absent from a leg")
    void ccRankKeys_zeroWhenAbsentFromLeg() {
      SearchHit sparseHit = new SearchHit("doc-s", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-d", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);

      // Sparse-only doc
      SearchHit sOnly = fused.hits().stream()
          .filter(h -> "doc-s".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(1.0f, sOnly.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(0.0f, sOnly.debugScores().get("vector_rank"), 0.001f);

      // Dense-only doc
      SearchHit dOnly = fused.hits().stream()
          .filter(h -> "doc-d".equals(h.docId())).findFirst().orElseThrow();
      assertEquals(0.0f, dOnly.debugScores().get("sparse_rank"), 0.001f);
      assertEquals(1.0f, dOnly.debugScores().get("vector_rank"), 0.001f);
    }

    @Test
    @DisplayName("zeroExclude: sparse-only doc gets full normalized score, not penalized")
    void zeroExclude_sparseOnlyDocGetsFullScore() {
      SearchHit sparseHit1 = new SearchHit("doc-both", 10.0f, Map.of());
      SearchHit sparseHit2 = new SearchHit("doc-sparse", 8.0f, Map.of());
      SearchHit denseHit1 = new SearchHit("doc-both", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit1, sparseHit2), 2, 0);
      SearchResult dense = new SearchResult(List.of(denseHit1), 1, 0);

      // Without zeroExclude: doc-sparse gets score = 0.5 * 0.0 (no dense) + 0.5 * 0.0 (lowest sparse) = 0.0
      SearchResult penalty = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);
      float sparseDocPenaltyScore = penalty.hits().stream()
          .filter(h -> "doc-sparse".equals(h.docId()))
          .findFirst().orElseThrow().score();
      assertEquals(0.0f, sparseDocPenaltyScore, 0.001f);

      // With zeroExclude: doc-sparse gets score = normSparse (absent dense leg excluded)
      // norm_sparse for doc-sparse = (8-8)/(10-8) = 0.0 (it's the lowest in its own leg)
      // So even with zeroExclude, the lowest-scored sparse doc still gets 0.0 from normalization
      SearchResult excluded = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, true);
      float sparseDocExcludedScore = excluded.hits().stream()
          .filter(h -> "doc-sparse".equals(h.docId()))
          .findFirst().orElseThrow().score();
      assertEquals(0.0f, sparseDocExcludedScore, 0.001f);
    }

    @Test
    @DisplayName("zeroExclude: dense-only doc gets full normalized score")
    void zeroExclude_denseOnlyDocGetsFullScore() {
      SearchHit sparseHit = new SearchHit("doc-sparse", 10.0f, Map.of());
      SearchHit denseHit1 = new SearchHit("doc-dense-hi", 0.9f, Map.of());
      SearchHit denseHit2 = new SearchHit("doc-dense-lo", 0.5f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit1, denseHit2), 2, 0);

      // Without zeroExclude (alpha=0.5): doc-dense-hi score = 0.5 * 1.0 + 0.5 * 0.0 = 0.5
      SearchResult penalty = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, false);
      float hiPenalty = penalty.hits().stream()
          .filter(h -> "doc-dense-hi".equals(h.docId()))
          .findFirst().orElseThrow().score();
      assertEquals(0.5f, hiPenalty, 0.001f);

      // With zeroExclude (alpha=0.5): doc-dense-hi score = normDense = 1.0 (full score, no sparse penalty)
      SearchResult excluded = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.5, false, true);
      float hiExcluded = excluded.hits().stream()
          .filter(h -> "doc-dense-hi".equals(h.docId()))
          .findFirst().orElseThrow().score();
      assertEquals(1.0f, hiExcluded, 0.001f);
    }

    @Test
    @DisplayName("zeroExclude: both-leg docs still use alpha weighting")
    void zeroExclude_bothLegDocsUnchanged() {
      SearchHit sparseHit = new SearchHit("doc-both", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-both", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult penalty = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.7, false, false);
      SearchResult excluded = HybridFusionUtils.fuseWithCC(sparse, dense, 10, 0.7, false, true);

      // Single doc in each leg → range=0 → norm=1.0. score = 0.7*1.0 + 0.3*1.0 = 1.0
      assertEquals(1.0f, penalty.hits().get(0).score(), 0.001f);
      assertEquals(1.0f, excluded.hits().get(0).score(), 0.001f);
    }
  }

  @Nested
  @DisplayName("fuseWithCC3()")
  class FuseWithCC3Tests {

    private static final double[] EQUAL_WEIGHTS = {1.0 / 3, 1.0 / 3, 1.0 / 3};
    private static final double[] DEFAULT_WEIGHTS = {0.35, 0.35, 0.30};

    private static SearchResult empty() {
      return new SearchResult(List.of(), 0, 0);
    }

    @Test
    @DisplayName("empty results return empty fused result")
    void emptyResults_returnEmpty() {
      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(empty(), empty(), empty(), 10, EQUAL_WEIGHTS, false, false);

      assertNotNull(fused);
      assertTrue(fused.hits().isEmpty());
      assertEquals(0, fused.totalHits());
    }

    @Test
    @DisplayName("sparse-only results returned with weighted score")
    void sparseOnly_returnsNormalizedScore() {
      SearchHit hit1 = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(sparse, empty(), empty(), 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(2, fused.hits().size());
      assertEquals("doc-1", fused.hits().get(0).docId());
      // doc-1: normSparse = 1.0, score = 0.35 * 1.0 + 0 + 0 = 0.35
      assertEquals(0.35f, fused.hits().get(0).score(), 0.001f);
      // doc-2: normSparse = 0.0, score = 0.35 * 0.0 = 0.0
      assertEquals(0.0f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("dense-only results returned with weighted score")
    void denseOnly_returnsNormalizedScore() {
      SearchHit hit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult dense = new SearchResult(List.of(hit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(empty(), dense, empty(), 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // Single doc → range=0 → norm=1.0, score = 0.35 * 1.0 = 0.35
      assertEquals(0.35f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("splade-only results returned with weighted score")
    void spladeOnly_returnsNormalizedScore() {
      SearchHit hit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult splade = new SearchResult(List.of(hit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(empty(), empty(), splade, 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // Single doc → range=0 → norm=1.0, score = 0.30 * 1.0 = 0.30
      assertEquals(0.30f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("doc in all 3 legs combines weighted normalized scores")
    void allThree_combinesWeightedScores() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // Each leg single doc → range=0 → norm=1.0
      // score = 0.35 * 1.0 + 0.35 * 1.0 + 0.30 * 1.0 = 1.0
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("doc in sparse+dense only (no splade)")
    void twoLegsOverlap_sparse_dense() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, empty(), 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // score = 0.35 * 1.0 + 0.35 * 1.0 + 0.30 * 0.0 = 0.70
      assertEquals(0.70f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("doc in sparse+splade only (no dense)")
    void twoLegsOverlap_sparse_splade() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, empty(), splade, 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // score = 0.35 * 1.0 + 0 + 0.30 * 1.0 = 0.65
      assertEquals(0.65f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("doc in dense+splade only (no sparse)")
    void twoLegsOverlap_dense_splade() {
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              empty(), dense, splade, 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      // score = 0 + 0.35 * 1.0 + 0.30 * 1.0 = 0.65
      assertEquals(0.65f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("zeroExclude renormalizes weights for single-leg docs")
    void zeroExclude_renormalizesWeights() {
      // Distinct docs: one per leg
      SearchHit sparseHit = new SearchHit("doc-s", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-d", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-p", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, DEFAULT_WEIGHTS, false, true);

      assertEquals(3, fused.hits().size());
      // Each single-leg doc: weight is re-normalized to 1.0 → score = norm (1.0 for single doc)
      for (SearchHit hit : fused.hits()) {
        assertEquals(1.0f, hit.score(), 0.001f,
            "Single-leg doc " + hit.docId() + " should have re-normalized score 1.0");
      }
    }

    @Test
    @DisplayName("zeroExclude=false penalizes missing-leg docs")
    void zeroExclude_false_penalizesMissingLegs() {
      SearchHit sparseHit = new SearchHit("doc-s", 10.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, empty(), empty(), 10, DEFAULT_WEIGHTS, false, false);

      // doc-s: normSparse=1.0, score = 0.35 * 1.0 + 0 + 0 = 0.35 (penalized by missing legs)
      assertEquals(0.35f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("zeroExclude renormalizes 2-leg overlapping docs")
    void zeroExclude_twoLegOverlap_renormalized() {
      // Doc appears in sparse+dense only
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, empty(), 10, DEFAULT_WEIGHTS, false, true);

      // Re-normalized: (0.35*1.0 + 0.35*1.0) / (0.35+0.35) = 0.70/0.70 = 1.0
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("weight edge case: one weight = 0")
    void weightEdgeCases_zeroWeight() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      // SPLADE weight = 0 → effectively 2-way
      double[] weights = {0.50, 0.50, 0.00};
      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, weights, false, false);

      // score = 0.5*1.0 + 0.5*1.0 + 0.0*1.0 = 1.0
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("asymmetric weights")
    void weightEdgeCases_asymmetric() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      double[] weights = {0.90, 0.05, 0.05};
      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, weights, false, false);

      // All single-doc legs → norm=1.0, score = 0.9+0.05+0.05 = 1.0
      assertEquals(1.0f, fused.hits().get(0).score(), 0.001f);
    }

    @Test
    @DisplayName("range=0 normalizes all docs in that leg to 1.0")
    void rangeZero_normalizesToOne() {
      // Two docs with identical sparse scores
      SearchHit hit1 = new SearchHit("doc-1", 5.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, empty(), empty(), 10, DEFAULT_WEIGHTS, false, false);

      // Both norm to 1.0 when range=0, score = 0.35 * 1.0 = 0.35
      assertEquals(0.35f, fused.hits().get(0).score(), 0.001f);
      assertEquals(0.35f, fused.hits().get(1).score(), 0.001f);
    }

    @Test
    @DisplayName("limit restricts number of results")
    void limitRestrictsResults() {
      SearchHit hit1 = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 8.0f, Map.of());
      SearchHit hit3 = new SearchHit("doc-3", 6.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2, hit3), 3, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, empty(), empty(), 2, DEFAULT_WEIGHTS, false, false);

      assertEquals(2, fused.hits().size());
      assertEquals(3, fused.totalHits());
    }

    @Test
    @DisplayName("null docId hits are skipped")
    void nullDocId_skipped() {
      SearchHit hit1 = new SearchHit(null, 10.0f, Map.of());
      SearchHit hit2 = new SearchHit("doc-2", 5.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(hit1, hit2), 2, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, empty(), empty(), 10, DEFAULT_WEIGHTS, false, false);

      assertEquals(1, fused.hits().size());
      assertEquals("doc-2", fused.hits().get(0).docId());
    }

    @Test
    @DisplayName("debug mode populates all 3-way CC score keys")
    void debugMode_populatesAllScoreKeys() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, DEFAULT_WEIGHTS, true, false);

      assertEquals(1, fused.hits().size());
      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("sparse", "sparse_rank", "vector", "vector_rank",
              "splade", "splade_rank", "cc", "cc_weight_sparse",
              "cc_weight_dense", "cc_weight_splade"),
          hit.debugScores().keySet(),
          "debug mode must emit all 10 score keys");
      assertEquals(10.0f, hit.debugScores().get("sparse"), 0.001f);
      assertEquals(0.9f, hit.debugScores().get("vector"), 0.001f);
      assertEquals(3.0f, hit.debugScores().get("splade"), 0.001f);
      assertEquals(0.35f, hit.debugScores().get("cc_weight_sparse"), 0.001f);
      assertEquals(0.35f, hit.debugScores().get("cc_weight_dense"), 0.001f);
      assertEquals(0.30f, hit.debugScores().get("cc_weight_splade"), 0.001f);
    }

    @Test
    @DisplayName("non-debug mode emits only raw scores and ranks")
    void nonDebugMode_emitsRawScoresOnly() {
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, Map.of());
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, Map.of());
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, Map.of());
      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, DEFAULT_WEIGHTS, false, false);

      SearchHit hit = fused.hits().get(0);
      assertEquals(
          Set.of("sparse", "sparse_rank", "vector", "vector_rank", "splade", "splade_rank"),
          hit.debugScores().keySet(),
          "non-debug mode must emit only raw scores and ranks");
    }

    @Test
    @DisplayName("fields are preserved from first occurrence")
    void fieldsPreservedFromFirstOccurrence() {
      Map<String, String> sparseFields = Map.of("title", "Sparse Title");
      Map<String, String> denseFields = Map.of("title", "Dense Title");
      Map<String, String> spladeFields = Map.of("title", "SPLADE Title");
      SearchHit sparseHit = new SearchHit("doc-1", 10.0f, sparseFields);
      SearchHit denseHit = new SearchHit("doc-1", 0.9f, denseFields);
      SearchHit spladeHit = new SearchHit("doc-1", 3.0f, spladeFields);

      SearchResult sparse = new SearchResult(List.of(sparseHit), 1, 0);
      SearchResult dense = new SearchResult(List.of(denseHit), 1, 0);
      SearchResult splade = new SearchResult(List.of(spladeHit), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse, dense, splade, 10, DEFAULT_WEIGHTS, false, false);

      assertEquals("Sparse Title", fused.hits().get(0).fields().get("title"));
    }

    @Test
    @DisplayName("multi-doc normalization and ordering")
    void multiDoc_normalizationAndOrdering() {
      // BM25: doc-1=10, doc-2=5. Dense: doc-2=0.9, doc-3=0.5. SPLADE: doc-1=3, doc-3=1.
      SearchResult sparse = new SearchResult(
          List.of(new SearchHit("doc-1", 10.0f, Map.of()),
              new SearchHit("doc-2", 5.0f, Map.of())), 2, 0);
      SearchResult dense = new SearchResult(
          List.of(new SearchHit("doc-2", 0.9f, Map.of()),
              new SearchHit("doc-3", 0.5f, Map.of())), 2, 0);
      SearchResult splade = new SearchResult(
          List.of(new SearchHit("doc-1", 3.0f, Map.of()),
              new SearchHit("doc-3", 1.0f, Map.of())), 2, 0);

      // Equal weights for clarity
      double[] w = {1.0 / 3, 1.0 / 3, 1.0 / 3};
      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(sparse, dense, splade, 10, w, false, false);

      assertEquals(3, fused.hits().size());
      assertEquals(3, fused.totalHits());

      // doc-1: normS=(10-5)/5=1.0, normD=0 (absent), normP=(3-1)/2=1.0
      //   score = (1/3)*1.0 + 0 + (1/3)*1.0 = 2/3
      // doc-2: normS=(5-5)/5=0.0, normD=(0.9-0.5)/0.4=1.0, normP=0 (absent)
      //   score = 0 + (1/3)*1.0 + 0 = 1/3
      // doc-3: normS=0 (absent), normD=(0.5-0.5)/0.4=0.0, normP=(1-1)/2=0.0
      //   score = 0 + 0 + 0 = 0
      assertEquals("doc-1", fused.hits().get(0).docId());
      assertEquals(2.0 / 3, fused.hits().get(0).score(), 0.01f);
      assertEquals("doc-2", fused.hits().get(1).docId());
      assertEquals(1.0 / 3, fused.hits().get(1).score(), 0.01f);
      assertEquals("doc-3", fused.hits().get(2).docId());
      assertEquals(0.0f, fused.hits().get(2).score(), 0.01f);
    }

    @Test
    @DisplayName("SPLADE parent-length multiplier uses Stage 3A thresholds")
    void spladeParentLengthMultiplier_usesStage3aThresholds() {
      assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(512L), 0.0001);
      assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(1024L), 0.0001);
      assertEquals(0.5, HybridFusionUtils.spladeParentLengthMultiplier(2560L), 0.0001);
      assertEquals(0.0, HybridFusionUtils.spladeParentLengthMultiplier(4096L), 0.0001);
      assertEquals(0.0, HybridFusionUtils.spladeParentLengthMultiplier(8192L), 0.0001);
    }

    @Test
    @DisplayName("advanced CC3 debug includes effective weights and namespaced parent token count")
    void advancedCc3Debug_includesEffectiveWeightsAndParentTokenCount() {
      Map<String, String> fields = Map.of("parent_token_count", "4096");
      SearchResult sparse = new SearchResult(List.of(new SearchHit("doc-1", 10.0f, fields)), 1, 0);
      SearchResult dense = new SearchResult(List.of(new SearchHit("doc-1", 0.9f, fields)), 1, 0);
      SearchResult splade = new SearchResult(List.of(new SearchHit("doc-1", 3.0f, fields)), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse,
              dense,
              splade,
              10,
              DEFAULT_WEIGHTS,
              true,
              false,
              "chunk_",
              true);

      SearchHit hit = fused.hits().get(0);
      assertEquals(1.0f, hit.score(), 0.001f, "SPLADE should be fully suppressed at 4096 tokens");
      assertEquals(4096.0f, hit.debugScores().get("chunk_parent_token_count"), 0.001f);
      assertEquals(0.35f, hit.debugScores().get("chunk_cc_weight_sparse"), 0.001f);
      assertEquals(0.35f, hit.debugScores().get("chunk_cc_weight_dense"), 0.001f);
      assertEquals(0.30f, hit.debugScores().get("chunk_cc_weight_splade"), 0.001f);
      assertEquals(0.0f, hit.debugScores().get("chunk_cc_modifier_splade"), 0.001f);
      assertEquals(0.5f, hit.debugScores().get("chunk_cc_effective_weight_sparse"), 0.001f);
      assertEquals(0.5f, hit.debugScores().get("chunk_cc_effective_weight_dense"), 0.001f);
      assertEquals(0.0f, hit.debugScores().get("chunk_cc_effective_weight_splade"), 0.001f);
    }

    @Test
    @DisplayName("zeroExclude removes absent-leg effective weights in CC3")
    void zeroExclude_removesAbsentLegEffectiveWeightsInCc3() {
      SearchResult sparse =
          new SearchResult(List.of(new SearchHit("doc-1", 10.0f, Map.of("parent_token_count", "900"))), 1, 0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCC3(
              sparse,
              empty(),
              empty(),
              10,
              DEFAULT_WEIGHTS,
              true,
              true,
              "chunk_",
              true);

      SearchHit hit = fused.hits().get(0);
      assertEquals(1.0f, hit.debugScores().get("chunk_cc_effective_weight_sparse"), 0.001f);
      assertEquals(0.0f, hit.debugScores().get("chunk_cc_effective_weight_dense"), 0.001f);
      assertEquals(0.0f, hit.debugScores().get("chunk_cc_effective_weight_splade"), 0.001f);
    }
  }

  @Nested
  @DisplayName("fuseWithCCNamed()")
  class FuseWithCCNamedTests {

    @Test
    @DisplayName("chunk branch parent-length multiplier uses Stage 3B thresholds")
    void chunkBranchParentLengthMultiplier_usesStage3bThresholds() {
      assertEquals(0.25, HybridFusionUtils.chunkBranchParentLengthMultiplier(512L, 0.25), 0.0001);
      assertEquals(0.25, HybridFusionUtils.chunkBranchParentLengthMultiplier(1024L, 0.25), 0.0001);
      assertEquals(0.625, HybridFusionUtils.chunkBranchParentLengthMultiplier(2560L, 0.25), 0.0001);
      assertEquals(1.0, HybridFusionUtils.chunkBranchParentLengthMultiplier(4096L, 0.25), 0.0001);
    }

    @Test
    @DisplayName("named branch CC emits branch debug keys and preserves zeroExclude branch-exclusive hits")
    void fuseWithCcNamed_emitsDebugKeysAndPreservesExclusiveHits() {
      SearchResult whole =
          new SearchResult(
              List.of(
                  new SearchHit("doc-whole", 1.0f, Map.of("parent_token_count", "1024")),
                  new SearchHit("doc-both", 0.2f, Map.of("parent_token_count", "2048"))),
              2,
              0);
      SearchResult chunk =
          new SearchResult(
              List.of(
                  new SearchHit("doc-both", 0.2f, Map.of("parent_token_count", "2048")),
                  new SearchHit("doc-chunk", 1.0f, Map.of("parent_token_count", "1024"))),
              2,
              0);

      SearchResult fused =
          HybridFusionUtils.fuseWithCCNamed(
              whole,
              chunk,
              10,
              new double[] {0.50, 0.50},
              true,
              true,
              "whole_branch",
              "chunk_branch",
              "branch_merge_",
              "whole",
              "chunk",
              true,
              0.25);

      assertEquals(3, fused.hits().size());

      SearchHit wholeOnly =
          fused.hits().stream()
              .filter(hit -> "doc-whole".equals(hit.docId()))
              .findFirst()
              .orElseThrow();
      assertEquals(1.0f, wholeOnly.debugScores().get("branch_merge_cc_effective_weight_whole"), 0.001f);
      assertEquals(0.0f, wholeOnly.debugScores().get("branch_merge_cc_effective_weight_chunk"), 0.001f);
      assertEquals(1.0f, wholeOnly.debugScores().get("whole_branch_norm"), 0.001f);
      assertEquals(0.0f, wholeOnly.debugScores().get("chunk_branch_norm"), 0.001f);

      SearchHit chunkOnly =
          fused.hits().stream()
              .filter(hit -> "doc-chunk".equals(hit.docId()))
              .findFirst()
              .orElseThrow();
      assertEquals(1.0f, chunkOnly.score(), 0.001f, "zeroExclude should preserve chunk-exclusive hits");
      assertEquals(0.25f, chunkOnly.debugScores().get("branch_merge_cc_modifier_chunk"), 0.001f);
      assertEquals(1.0f, chunkOnly.debugScores().get("branch_merge_cc_effective_weight_chunk"), 0.001f);
      assertEquals(1024.0f, chunkOnly.debugScores().get("branch_merge_parent_token_count"), 0.001f);
      assertEquals(0.50f, chunkOnly.debugScores().get("branch_merge_cc_weight_whole"), 0.001f);
      assertEquals(0.50f, chunkOnly.debugScores().get("branch_merge_cc_weight_chunk"), 0.001f);
      assertTrue(chunkOnly.debugScores().containsKey("whole_branch"));
      assertTrue(chunkOnly.debugScores().containsKey("chunk_branch"));
      assertTrue(chunkOnly.debugScores().containsKey("branch_merge_cc"));
    }
  }

  @Nested
  @DisplayName("spliceRecallComplete() — tempdoc 636 Design v3 (recall-complete rerank pool)")
  class SpliceRecallCompleteTests {

    private SearchHit hit(String id, float score) {
      return new SearchHit(id, score, Map.of("title", id));
    }

    @Test
    @DisplayName("no-op (same list reference) when every protected doc is already present")
    void allPresent_returnsSameReference() {
      List<SearchHit> fused = List.of(hit("a", 3f), hit("b", 2f), hit("c", 1f));
      List<SearchHit> protectedHits = List.of(hit("a", 9f), hit("b", 8f));
      List<SearchHit> out = HybridFusionUtils.spliceRecallComplete(fused, protectedHits, 10);
      assertSame(fused, out, "all-present must be the byte-identical flag-off path");
    }

    @Test
    @DisplayName("a buried protected hit (dropped by fusion) is spliced into the pool")
    void buriedProtected_splicedIn() {
      List<SearchHit> fused = List.of(hit("a", 3f), hit("b", 2f), hit("c", 1f));
      List<SearchHit> protectedHits = List.of(hit("needle", 0.9f)); // a leg top-N fusion dropped
      List<SearchHit> out = HybridFusionUtils.spliceRecallComplete(fused, protectedHits, 3);
      Set<String> ids = out.stream().map(SearchHit::docId).collect(Collectors.toSet());
      assertTrue(ids.contains("needle"), "buried protected hit must reach the rerank pool");
      assertTrue(out.size() <= 3, "must respect limit");
      // keepFused = 3 - 1 = 2 → fused prefix a, b preserved verbatim; c displaced for the needle.
      assertEquals("a", out.get(0).docId());
      assertEquals("b", out.get(1).docId());
      assertEquals("needle", out.get(2).docId());
    }

    @Test
    @DisplayName("spliced hit is re-scored below the fused prefix (list stays descending)")
    void splicedHit_scoredBelowPrefix() {
      List<SearchHit> fused = List.of(hit("a", 3f), hit("b", 2f));
      List<SearchHit> protectedHits = List.of(hit("needle", 99f)); // high raw leg score ignored
      List<SearchHit> out = HybridFusionUtils.spliceRecallComplete(fused, protectedHits, 3);
      SearchHit last = out.get(out.size() - 1);
      assertEquals("needle", last.docId());
      assertTrue(
          last.score() < out.get(out.size() - 2).score(),
          "spliced hit must sort below the fused prefix, not jump it on its raw leg score");
    }

    @Test
    @DisplayName("disjoint legs: each leg's top-N is represented in the pool")
    void disjointLegs_bothLegsTopNPresent() {
      // Paraphrase shape: lexical filler fills the fused prefix; the dense answer is absent.
      List<SearchHit> fused = List.of(hit("bm25-1", 5f), hit("bm25-2", 4f), hit("bm25-3", 3f));
      List<SearchHit> denseTopN = List.of(hit("dense-1", 0.9f), hit("dense-2", 0.8f));
      List<SearchHit> out = HybridFusionUtils.spliceRecallComplete(fused, denseTopN, 4);
      Set<String> ids = out.stream().map(SearchHit::docId).collect(Collectors.toSet());
      assertTrue(ids.contains("dense-1"), "dense leg top-N must survive");
      assertTrue(ids.contains("dense-2"), "dense leg top-N must survive");
      assertTrue(ids.contains("bm25-1"), "top fused candidate must survive");
      assertTrue(out.size() <= 4);
    }

    @Test
    @DisplayName("null / empty inputs are safe no-ops")
    void nullEmpty_safe() {
      assertNull(HybridFusionUtils.spliceRecallComplete(null, List.of(hit("a", 1f)), 10));
      List<SearchHit> fused = List.of(hit("a", 1f));
      assertSame(fused, HybridFusionUtils.spliceRecallComplete(fused, List.of(), 10));
      assertSame(fused, HybridFusionUtils.spliceRecallComplete(fused, List.of(hit("a", 9f)), 0));
    }

    @Test
    @DisplayName("topN returns the leading hits bounded by n")
    void topN_bounded() {
      SearchResult r = new SearchResult(List.of(hit("a", 3f), hit("b", 2f), hit("c", 1f)), 3, 0);
      assertEquals(2, HybridFusionUtils.topN(r, 2).size());
      assertEquals("a", HybridFusionUtils.topN(r, 2).get(0).docId());
      assertEquals(3, HybridFusionUtils.topN(r, 10).size());
      assertTrue(HybridFusionUtils.topN(null, 5).isEmpty());
    }
  }
}
