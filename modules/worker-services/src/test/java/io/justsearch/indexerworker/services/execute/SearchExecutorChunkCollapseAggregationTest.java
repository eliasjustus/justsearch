/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 916 Part 2 (lane E) — aggregate-then-cut parent collapse.
 *
 * <p>The pre-916 collapse stopped scanning the fused chunk list the instant it had enough distinct
 * parents, so a document whose evidence is spread over several mid-ranked chunks could never
 * surface behind a handful of documents that owned the top chunks. These tests pin the replacement:
 * over-fetch, aggregate per parent, cut last — and pin the shipped defaults as an exact
 * reproduction of the old behaviour so the lever doubles as an A/B control.
 */
@DisplayName("SearchExecutor chunk collapse — aggregate-then-cut (916 Part 2)")
final class SearchExecutorChunkCollapseAggregationTest {

  /** A chunk hit as {@code fuseWithCC3} emits it: distinct chunk docId, parent in PARENT_DOC_ID. */
  private static SearchHit chunk(String chunkId, String parentId, float score) {
    return new SearchHit(chunkId, score, Map.of(SchemaFields.PARENT_DOC_ID, parentId));
  }

  private static SearchResult fused(List<SearchHit> hits) {
    return new SearchResult(hits, hits.size(), 7);
  }

  /**
   * The pre-916 collapse, reimplemented here as the no-regression oracle: first-wins by input order,
   * stop the instant {@code limit} distinct parents exist. Asserting the shipped defaults against
   * this rather than against hand-written expectations is what makes "lambda 0 reproduces the old
   * behaviour" a checkable claim instead of a comment.
   */
  private static List<String> pre916Collapse(SearchResult chunkResult, int limit) {
    Map<String, Float> bestPerParent = new LinkedHashMap<>();
    for (SearchHit hit : chunkResult.hits()) {
      String parentId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      if (parentId == null || parentId.isEmpty()) {
        parentId = hit.docId();
      }
      bestPerParent.putIfAbsent(parentId, hit.score());
      if (bestPerParent.size() >= limit) {
        break;
      }
    }
    return new ArrayList<>(bestPerParent.keySet());
  }

  private static List<String> parentIds(SearchResult result) {
    return result.hits().stream().map(SearchHit::docId).collect(Collectors.toList());
  }

  /** Two focused parents plus one whose evidence is spread over four mid-ranked chunks. */
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
  @DisplayName("lambda 0 with over-fetch 1 reproduces the pre-916 collapse exactly")
  void defaultsReproducePre916() {
    SearchResult in = spreadEvidenceFixture();
    for (int limit = 1; limit <= 5; limit++) {
      SearchResult out = SearchExecutor.collapseChunkHitsToParents(in, limit, 1, 0.0);
      assertEquals(
          pre916Collapse(in, limit),
          parentIds(out),
          "shipped defaults must be the pre-916 collapse at limit=" + limit);
    }
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(in, 3, 1, 0.0);
    assertEquals(0.90f, out.hits().get(0).score(), "best chunk score is carried verbatim");
    assertEquals(0.80f, out.hits().get(1).score());
    assertEquals(0.55f, out.hits().get(2).score());
  }

  @Test
  @DisplayName("over-fetch alone (lambda 0) still ranks by max — no parent overtakes another")
  void overfetchWithoutLambdaIsOrderPreserving() {
    SearchResult out =
        SearchExecutor.collapseChunkHitsToParents(spreadEvidenceFixture(), 3, 5, 0.0);
    assertEquals(List.of("p-top", "p-mid", "p-spread"), parentIds(out));
    assertEquals(0.55f, out.hits().get(2).score(), "lambda 0 leaves the aggregate at the max");
  }

  @Test
  @DisplayName("at lambda 0.3 a many-mid-chunk parent overtakes a better single-chunk parent")
  void spreadEvidenceOvertakesAtLambda() {
    SearchResult out =
        SearchExecutor.collapseChunkHitsToParents(spreadEvidenceFixture(), 3, 5, 0.3);
    // p-spread aggregate = 0.55 + 0.3*(1*0.54 + 0.5*0.53 + 0.25*0.52) = 0.8305 > p-mid 0.80.
    assertEquals(List.of("p-top", "p-spread", "p-mid"), parentIds(out));
    // The delivered hit score stays the best chunk score; only the ordering key aggregates.
    assertEquals(0.55f, out.hits().get(1).score());
  }

  @Test
  @DisplayName("the limit cut happens AFTER aggregation — a parent first-wins would drop is kept")
  void cutAfterAggregation() {
    SearchResult in = spreadEvidenceFixture();
    assertEquals(List.of("p-top", "p-mid"), pre916Collapse(in, 2));
    assertEquals(
        List.of("p-top", "p-mid"),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 2, 1, 0.0)));
    SearchResult on = SearchExecutor.collapseChunkHitsToParents(in, 2, 5, 0.3);
    assertEquals(List.of("p-top", "p-spread"), parentIds(on));
    assertEquals(2, on.hits().size());
    assertEquals(2, on.totalHits());
  }

  @Test
  @DisplayName("the decayed remainder is bounded by 2*lambda*max — chunk count alone cannot win")
  void remainderIsBounded() {
    List<SearchHit> many = new ArrayList<>();
    many.add(chunk("c-rival", "p-rival", 0.60f));
    for (int i = 0; i < 40; i++) {
      many.add(chunk("c-many-" + i, "p-many", 0.30f));
    }
    SearchResult out = SearchExecutor.collapseChunkHitsToParents(fused(many), 2, 5, 0.3);
    // p-many aggregate is at most 0.30 * (1 + 2*0.3) = 0.48 < 0.60, whatever its chunk count.
    assertEquals(List.of("p-rival", "p-many"), parentIds(out));
  }

  @Test
  @DisplayName("the result does not depend on the order of equal-scored sibling chunks")
  void permutationOfEqualScoredSiblings() {
    SearchHit head = chunk("c-head", "p-head", 0.70f);
    SearchHit a = chunk("c-a", "p-x", 0.50f);
    SearchHit b = chunk("c-b", "p-x", 0.50f);
    SearchResult ab =
        SearchExecutor.collapseChunkHitsToParents(fused(List.of(head, a, b)), 2, 5, 0.3);
    SearchResult ba =
        SearchExecutor.collapseChunkHitsToParents(fused(List.of(head, b, a)), 2, 5, 0.3);
    assertEquals(parentIds(ab), parentIds(ba));
    assertEquals(ab.hits().get(1).score(), ba.hits().get(1).score());
  }

  @Test
  @DisplayName("repeated invocation on the same input is identical (the eval gate depends on it)")
  void deterministicAcrossInvocations() {
    SearchResult in = spreadEvidenceFixture();
    SearchResult first = SearchExecutor.collapseChunkHitsToParents(in, 3, 5, 0.3);
    for (int i = 0; i < 5; i++) {
      SearchResult again = SearchExecutor.collapseChunkHitsToParents(in, 3, 5, 0.3);
      assertEquals(parentIds(first), parentIds(again));
      for (int h = 0; h < first.hits().size(); h++) {
        assertEquals(first.hits().get(h).score(), again.hits().get(h).score());
      }
    }
  }

  @Test
  @DisplayName("equal aggregates keep first-seen fused order (stable sort, no docId re-ordering)")
  void tiesKeepFusedOrder() {
    SearchResult in =
        fused(List.of(chunk("c-1", "z-parent", 0.40f), chunk("c-2", "a-parent", 0.40f)));
    assertEquals(
        List.of("z-parent", "a-parent"),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 2, 5, 0.3)));
  }

  @Test
  @DisplayName("a chunk hit without PARENT_DOC_ID collapses onto its own docId")
  void parentlessHitIsItsOwnParent() {
    SearchResult in =
        fused(List.of(new SearchHit("standalone", 0.9f, Map.of()), chunk("c", "p", 0.5f)));
    assertEquals(
        List.of("standalone", "p"),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 2, 5, 0.3)));
  }

  @Test
  @DisplayName("an over-fetch multiplier below 1 is clamped, not treated as a zero scan cap")
  void overfetchClampedToOne() {
    SearchResult in = spreadEvidenceFixture();
    assertEquals(
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 3, 1, 0.0)),
        parentIds(SearchExecutor.collapseChunkHitsToParents(in, 3, 0, 0.0)));
  }
}
