/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.ipc.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §L.3 ("count nondeterminism"): the rerank order application must never change how
 * many candidates survive. The default (judge-blend-off) branch used to apply the Worker's
 * {@code sorted_indices} verbatim, so a list shorter than the cross-encoder window silently dropped
 * the uncovered candidates from the result set; the count then depended on what the Worker happened
 * to return.
 *
 * <p>Exercised directly against the pure static helper, mirroring the testability pattern
 * {@code resolveBlendAlpha} / {@code shouldSkipCrossEncoder} already use in this class — the
 * decision under test is the order application, not the RPC around it.
 */
@DisplayName("KnowledgeSearchEngine.applyRerankOrder")
class KnowledgeSearchEngineRerankOrderTest {

  @Test
  @DisplayName("short sorted-indices list keeps every candidate: reranked prefix, then original order")
  void shortOrder_keepsAllCandidates() {
    List<SearchResult> results = candidates(12);
    // The Worker covered only 3 of the 10 window positions — positions 0,1,2,4..9 must all survive.
    List<SearchResult> out = KnowledgeSearchEngine.applyRerankOrder(results, List.of(4, 0, 2), 10);

    assertEquals(12, out.size(), "count in must equal count out");
    assertEquals(
        List.of(
            "doc4", "doc0", "doc2", // the covered prefix, in the Worker's order
            "doc1", "doc3", "doc5", "doc6", "doc7", "doc8", "doc9", // uncovered window remainder
            "doc10", "doc11"), // beyond the window, never reranked
        ids(out));
  }

  @Test
  @DisplayName("full-coverage order is applied verbatim (pre-821 behavior, element for element)")
  void fullCoverageOrder_isUnchanged() {
    List<SearchResult> results = candidates(12);
    List<Integer> order = List.of(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);

    List<SearchResult> out = KnowledgeSearchEngine.applyRerankOrder(results, order, 10);

    assertEquals(12, out.size());
    assertEquals(
        List.of(
            "doc9", "doc8", "doc7", "doc6", "doc5", "doc4", "doc3", "doc2", "doc1", "doc0",
            "doc10", "doc11"),
        ids(out),
        "a permutation covering the window reproduces the old reorder-then-append result");
  }

  @Test
  @DisplayName("full-coverage order equals the pre-821 reorder loop for every permutation shape")
  void fullCoverageOrder_matchesLegacyReorderLoop() {
    List<SearchResult> results = candidates(12);
    for (List<Integer> order :
        List.of(
            List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            List.of(3, 1, 0, 9, 2, 8, 4, 7, 5, 6),
            List.of(5, 4, 9, 0, 1, 7, 8, 2, 3, 6))) {
      assertEquals(
          legacyReorder(results, order, 10),
          KnowledgeSearchEngine.applyRerankOrder(results, order, 10),
          "well-formed orders must be bit-identical to the pre-821 application");
    }
  }

  @Test
  @DisplayName("empty sorted-indices list leaves every candidate in original order")
  void emptyOrder_leavesOriginalOrder() {
    List<SearchResult> results = candidates(12);

    List<SearchResult> out = KnowledgeSearchEngine.applyRerankOrder(results, List.of(), 10);

    assertEquals(12, out.size());
    assertEquals(ids(results), ids(out));
  }

  @Test
  @DisplayName("window covering every candidate still returns all of them when the order is short")
  void shortOrder_windowEqualsResultCount() {
    List<SearchResult> results = candidates(4);

    List<SearchResult> out = KnowledgeSearchEngine.applyRerankOrder(results, List.of(3), 4);

    assertEquals(List.of("doc3", "doc0", "doc1", "doc2"), ids(out));
  }

  @Test
  @DisplayName("duplicate and out-of-window indices are placed once, never dropped or doubled")
  void malformedIndices_arePlacedExactlyOnce() {
    List<SearchResult> results = candidates(6);

    // 2 repeats, 11 is past the result list, 5 is past the window (its position is appended by the
    // beyond-window pass, not by the order).
    List<SearchResult> out =
        KnowledgeSearchEngine.applyRerankOrder(results, List.of(2, 2, 11, 5, 0), 4);

    assertEquals(6, out.size());
    assertEquals(List.of("doc2", "doc0", "doc1", "doc3", "doc4", "doc5"), ids(out));
  }

  @Test
  @DisplayName("empty candidate list is a no-op regardless of the window")
  void emptyCandidates_returnEmpty() {
    assertEquals(
        List.of(), KnowledgeSearchEngine.applyRerankOrder(List.of(), List.of(0, 1), 10));
  }

  /** The pre-821 application: apply the order verbatim, then append everything beyond the window. */
  private static List<SearchResult> legacyReorder(
      List<SearchResult> results, List<Integer> order, int window) {
    List<SearchResult> out = new ArrayList<>(results.size());
    for (int idx : order) {
      out.add(results.get(idx));
    }
    for (int i = window; i < results.size(); i++) {
      out.add(results.get(i));
    }
    return out;
  }

  private static List<SearchResult> candidates(int n) {
    List<SearchResult> results = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      results.add(SearchResult.newBuilder().setId("doc" + i).build());
    }
    return results;
  }

  private static List<String> ids(List<SearchResult> results) {
    List<String> out = new ArrayList<>(results.size());
    for (SearchResult sr : results) {
      out.add(sr.getId());
    }
    return out;
  }
}
