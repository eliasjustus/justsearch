package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tempdoc 580 §13.3 — guard tests for the per-query adaptive CC-weight selector (logic seam). */
class AdaptiveWeightSelectorTest {

  private static final double[] STATIC = {0.60, 0.20, 0.20};

  private static SearchHit hitWithTokens(String docId, int tokens) {
    return new SearchHit(
        docId, 1.0f, Map.of(SchemaFields.PARENT_TOKEN_COUNT, Integer.toString(tokens)));
  }

  private static SearchResult resultWithTokens(String idPrefix, int tokens, int n) {
    List<SearchHit> hits = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      hits.add(hitWithTokens(idPrefix + i, tokens));
    }
    return new SearchResult(hits, n, 0);
  }

  @Test
  void longRetrievedSet_selectsBm25Dominant() {
    SearchResult longLeg = resultWithTokens("d", 4000, 5);
    double[] w = AdaptiveWeightSelector.selectWeights(longLeg, null, null, STATIC);
    assertArrayEquals(AdaptiveWeightSelector.BM25_DOMINANT, w, 1e-9);
  }

  @Test
  void shortRetrievedSet_selectsBalanced() {
    SearchResult shortLeg = resultWithTokens("d", 300, 5);
    double[] w = AdaptiveWeightSelector.selectWeights(shortLeg, null, null, STATIC);
    assertArrayEquals(AdaptiveWeightSelector.BALANCED, w, 1e-9);
  }

  @Test
  void atThreshold_isNotLong_selectsBalanced() {
    // median exactly == LONG_MEDIAN_TOKENS is NOT > threshold → balanced (boundary law).
    SearchResult atLeg = resultWithTokens("d", 2048, 5);
    double[] w = AdaptiveWeightSelector.selectWeights(atLeg, null, null, STATIC);
    assertArrayEquals(AdaptiveWeightSelector.BALANCED, w, 1e-9);
  }

  @Test
  void noTokenSignal_returnsStaticWeightsVerbatim() {
    SearchHit noTok = new SearchHit("d0", 1.0f, Map.of("other", "x"));
    SearchResult leg = new SearchResult(List.of(noTok), 1, 0);
    double[] w = AdaptiveWeightSelector.selectWeights(leg, null, null, STATIC);
    assertSame(STATIC, w, "no length signal must return the configured weights unchanged");
  }

  @Test
  void allLegsNull_returnsStaticWeights() {
    double[] w = AdaptiveWeightSelector.selectWeights(null, null, null, STATIC);
    assertSame(STATIC, w);
  }

  @Test
  void medianParentTokens_dedupsByDocIdAcrossLegs() {
    // Same docId in two legs counts once; the union median should reflect distinct docs only.
    SearchResult legA = new SearchResult(List.of(hitWithTokens("dup", 4000)), 1, 0);
    SearchResult legB =
        new SearchResult(
            List.of(hitWithTokens("dup", 4000), hitWithTokens("short", 100)), 2, 0);
    // distinct token set = {4000, 100} → median 2050.0
    Double median = AdaptiveWeightSelector.medianParentTokens(legA, legB);
    assertEquals(2050.0, median, 1e-9);
  }

  @Test
  void medianParentTokens_emptyWhenNoSignal() {
    assertNull(AdaptiveWeightSelector.medianParentTokens((SearchResult) null));
  }
}
