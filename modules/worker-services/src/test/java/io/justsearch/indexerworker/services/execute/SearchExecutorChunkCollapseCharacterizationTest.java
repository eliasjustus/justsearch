/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 916 Part 2 — characterization of {@code SearchExecutor.collapseChunkHitsToParents} as it
 * actually behaves, including the limitation the lane E audit named as finding 2.
 *
 * <p>Part 2 proposed replacing this collapse with an aggregate-then-cut design. That design was
 * built, wired so its aggregate reached the branch blend, and measured across three campaigns; it
 * was **refuted** (916 §J.4) and reverted. What survives is this: the collapse's real properties,
 * which were untested before, and an executable statement of the limitation — so the next person to
 * rediscover audit finding 2 finds the measurement rather than repeating it.
 *
 * <p>These tests deliberately assert current behaviour, including behaviour that is arguably
 * undesirable ({@code parentDominatingTheTopChunksCrowdsOutSpreadEvidence}). They are a record, not
 * an endorsement.
 */
@DisplayName("SearchExecutor chunk collapse — characterization (916 Part 2, mechanism refuted)")
final class SearchExecutorChunkCollapseCharacterizationTest {

  private static SearchHit chunk(String chunkId, String parentId, float score) {
    return new SearchHit(chunkId, score, Map.of(SchemaFields.PARENT_DOC_ID, parentId));
  }

  private static SearchResult fused(List<SearchHit> hits) {
    return new SearchResult(hits, hits.size(), 7);
  }

  private static List<String> parentIds(SearchResult result) {
    return result.hits().stream().map(SearchHit::docId).collect(Collectors.toList());
  }

  /** Two focused parents, plus one whose evidence is spread over four mid-ranked chunks. */
  private static SearchResult spreadEvidenceFixture() {
    return fused(
        List.of(
            chunk("c-top", "p-top", 0.90f),
            chunk("c-mid", "p-mid", 0.80f),
            chunk("c-spread-0", "p-spread", 0.55f),
            chunk("c-spread-1", "p-spread", 0.54f),
            chunk("c-spread-2", "p-spread", 0.53f),
            chunk("c-spread-3", "p-spread", 0.52f)));
  }

  @Test
  @DisplayName("audit finding 2, pinned: a spread-evidence parent is dropped at a tight cap")
  void parentDominatingTheTopChunksCrowdsOutSpreadEvidence() {
    // The collapse stops as soon as it has `limit` distinct parents, so p-spread — four
    // corroborating chunks — never enters the result while two single-chunk parents do.
    // 916 measured whether aggregating that evidence helps: it does not (§J.4). Recorded so the
    // next reader of the audit finds the answer instead of re-running the campaign.
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(spreadEvidenceFixture(), 2);
    assertEquals(List.of("p-top", "p-mid"), parentIds(out));
    assertFalse(parentIds(out).contains("p-spread"));
  }

  @Test
  @DisplayName("each parent keeps its best chunk's score, and the output stays descending")
  void bestChunkScoreIsCarriedAndOrderIsDescending() {
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(spreadEvidenceFixture(), 3);
    assertEquals(List.of("p-top", "p-mid", "p-spread"), parentIds(out));
    assertEquals(0.90f, out.hits().get(0).score());
    assertEquals(0.80f, out.hits().get(1).score());
    assertEquals(0.55f, out.hits().get(2).score(), "first-seen chunk of a parent is its best");
  }

  @Test
  @DisplayName("collapse is a pure function of its input — the eval gate depends on it")
  void deterministicAcrossInvocations() {
    SearchResult in = spreadEvidenceFixture();
    SearchResult first = SearchExecutor.collapseChunkHitsToParents(in, 3);
    for (int i = 0; i < 5; i++) {
      SearchResult again = SearchExecutor.collapseChunkHitsToParents(in, 3);
      assertEquals(first, again);
    }
  }

  @Test
  @DisplayName("parents are emitted in fused order, which fuseWithCC3 ties by chunk docId")
  void outputFollowsFusedOrderNotParentDocId() {
    // z-parent is seen first despite sorting last by docId; the collapse must not reorder it.
    SearchResult in =
        fused(List.of(chunk("c-1", "z-parent", 0.40f), chunk("c-2", "a-parent", 0.40f)));
    assertEquals(
        List.of("z-parent", "a-parent"),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 2)));
  }

  @Test
  @DisplayName("a hit without PARENT_DOC_ID collapses onto its own docId")
  void parentlessHitIsItsOwnParent() {
    SearchResult in =
        fused(List.of(new SearchHit("standalone", 0.9f, Map.of()), chunk("c", "p", 0.5f)));
    assertEquals(
        List.of("standalone", "p"),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 2)));
  }

  @Test
  @DisplayName("sibling chunks of a surviving parent merge their evidence scores by max")
  void siblingEvidenceMergesByMax() {
    SearchHit a =
        new SearchHit("c-a0", 0.9f, Map.of(SchemaFields.PARENT_DOC_ID, "p"),
            Map.of("chunk_sparse", 0.4f, "chunk_vector_rank", 3f));
    SearchHit b =
        new SearchHit("c-a1", 0.7f, Map.of(SchemaFields.PARENT_DOC_ID, "p"),
            Map.of("chunk_sparse", 0.8f, "chunk_vector_rank", 1f));
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(fused(List.of(a, b)), 5);
    assertEquals(1, out.hits().size());
    assertEquals(0.8f, out.hits().get(0).debugScores().get("chunk_sparse"), "max evidence wins");
    assertEquals(
        1f, out.hits().get(0).debugScores().get("chunk_vector_rank"), "best positive rank wins");
  }

  @Test
  @DisplayName("a non-positive limit still returns one parent rather than throwing")
  void nonPositiveLimitReturnsOneParent() {
    SearchResult in = spreadEvidenceFixture();
    for (int limit : new int[] {0, -1}) {
      List<String> ids = parentIds(SearchExecutor.collapseChunkHitsToParents(in, limit));
      assertEquals(List.of("p-top"), ids, "limit=" + limit);
    }
  }

  @Test
  @DisplayName("every chunk of one parent collapses to a single hit")
  void manyChunksOneParent() {
    List<SearchHit> many = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      many.add(chunk("c-" + i, "p-many", 0.30f));
    }
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(fused(many), 5);
    assertEquals(List.of("p-many"), parentIds(out));
    assertEquals(1, out.totalHits());
  }
}
