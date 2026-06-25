package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 385: Tests for per-source retrieval merge logic in {@link KnowledgeHttpApiAdapter}.
 *
 * <p>Tests the static {@code mergeSearchResponses()} method which round-robin interleaves hits
 * from multiple per-source SearchResponses and handles backfill, dedup, and stale field clearing.
 */
@DisplayName("KnowledgeHttpApiAdapter — per-source merge (385 #6)")
class KnowledgeHttpApiAdapterPerSourceTest {

  private static final SearchRequest DUMMY_REQ =
      SearchRequest.newBuilder().setQuery("test").setLimit(10).build();

  private static SearchResult hit(String id, float score) {
    return SearchResult.newBuilder().setId(id).setScore(score).build();
  }

  private static SearchResponse response(SearchResult... hits) {
    return response(0, false, hits);
  }

  private static SearchResponse response(long totalHits, boolean spladeExecuted,
      SearchResult... hits) {
    // Tempdoc 549 Phase E5: spladeExecuted is carried on the trace degradation (flat field retired).
    SearchResponse.Builder b = SearchResponse.newBuilder()
        .setTotalHits(totalHits > 0 ? totalHits : hits.length)
        .setTookMs(10)
        .setSearchTrace(
            io.justsearch.ipc.SearchTrace.newBuilder()
                .setDegradation(
                    io.justsearch.ipc.TraceDegradation.newBuilder()
                        .setSpladeExecuted(spladeExecuted)));
    for (SearchResult h : hits) b.addResults(h);
    return b.build();
  }

  @Nested
  @DisplayName("Round-robin interleave")
  class RoundRobinInterleave {

    @Test
    void twoSourcesInterleaved() {
      SearchResponse a = response(hit("a1", 0.9f), hit("a2", 0.8f), hit("a3", 0.7f));
      SearchResponse b = response(hit("b1", 0.95f), hit("b2", 0.85f), hit("b3", 0.75f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      // Round-robin: a1, b1, a2, b2, a3, b3
      assertEquals(List.of("a1", "b1", "a2", "b2", "a3", "b3"), ids);
    }

    @Test
    void unevenSourcesInterleaved() {
      SearchResponse a = response(hit("a1", 0.9f), hit("a2", 0.8f));
      SearchResponse b = response(hit("b1", 0.95f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      // a1, b1, a2 — b has only 1 hit, a fills remaining
      assertEquals(List.of("a1", "b1", "a2"), ids);
    }

    @Test
    void respectsLimit() {
      SearchResponse a = response(hit("a1", 0.9f), hit("a2", 0.8f), hit("a3", 0.7f));
      SearchResponse b = response(hit("b1", 0.95f), hit("b2", 0.85f), hit("b3", 0.75f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 4, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertEquals(4, merged.getResultsCount());
      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      assertEquals(List.of("a1", "b1", "a2", "b2"), ids);
    }
  }

  @Nested
  @DisplayName("Deduplication")
  class Deduplication {

    @Test
    void duplicateHitIdAppearsOnce() {
      SearchResponse a = response(hit("shared", 0.9f), hit("a2", 0.8f));
      SearchResponse b = response(hit("shared", 0.95f), hit("b2", 0.85f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      long sharedCount = ids.stream().filter(id -> id.equals("shared")).count();
      assertEquals(1, sharedCount, "Duplicate ID should appear only once");
      assertEquals(3, ids.size()); // shared, b2 (or a2 depending on interleave), remaining
    }
  }

  @Nested
  @DisplayName("Backfill")
  class Backfill {

    @Test
    void backfillFillsRemainingSlots() {
      SearchResponse a = response(hit("a1", 0.9f));
      SearchResponse b = response(hit("b1", 0.95f));
      SearchResponse backfillResp = response(hit("c1", 0.5f), hit("c2", 0.4f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 4, DUMMY_REQ, req -> backfillResp);

      assertEquals(4, merged.getResultsCount());
      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      assertTrue(ids.contains("a1"));
      assertTrue(ids.contains("b1"));
      assertTrue(ids.contains("c1"));
      assertTrue(ids.contains("c2"));
    }

    @Test
    void backfillDeduplicatesAgainstInterleaved() {
      SearchResponse a = response(hit("a1", 0.9f));
      SearchResponse b = response(hit("b1", 0.95f));
      // Backfill contains a1 (already interleaved) + c1 (new)
      SearchResponse backfillResp = response(hit("a1", 0.5f), hit("c1", 0.4f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 4, DUMMY_REQ, req -> backfillResp);

      List<String> ids = merged.getResultsList().stream().map(SearchResult::getId).toList();
      long a1Count = ids.stream().filter(id -> id.equals("a1")).count();
      assertEquals(1, a1Count, "Backfill should not duplicate already-interleaved hits");
      assertTrue(ids.contains("c1"));
    }

    @Test
    void backfillFailureDoesNotCrash() {
      SearchResponse a = response(hit("a1", 0.9f));

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a), 4, DUMMY_REQ, req -> { throw new RuntimeException("network error"); });

      assertEquals(1, merged.getResultsCount());
      assertEquals("a1", merged.getResults(0).getId());
    }
  }

  @Nested
  @DisplayName("Stale field handling")
  class StaleFieldHandling {

    @Test
    void facetsAreCleared() {
      SearchResponse a = SearchResponse.newBuilder()
          .setTotalHits(1)
          .setTookMs(10)
          .addResults(hit("a1", 0.9f))
          .putFacets("meta_source", FacetCounts.newBuilder()
              .putCounts("techcrunch", 5L).build())
          .build();

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertTrue(merged.getFacetsMap().isEmpty(), "Facets should be cleared in merged response");
    }

    @Test
    void qppSignalsAreZeroedAcrossMerge() {
      // Tempdoc 549 Phase E5: the flat corrected_query field is retired (the corrected query is on
      // the trace CORRECTION stage). The merge zeroes QPP on the trace (per-query, meaningless
      // merged); covered by qppSignalsAreZeroed below. This placeholder keeps the nested-class
      // structure; the corrected-query-clearing concern is moot now the flat field is gone.
      SearchResponse a = response(1, false, hit("a1", 0.9f));
      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());
      assertTrue(merged.hasSearchTrace(), "merged response carries the unified trace");
    }

    @Test
    void booleanFlagsOrMergedAcrossResponses() {
      SearchResponse a = response(1, true, hit("a1", 0.9f));  // spladeExecuted=true
      SearchResponse b = response(1, false, hit("b1", 0.9f)); // spladeExecuted=false

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertTrue(merged.getSearchTrace().getDegradation().getSpladeExecuted(),
          "Boolean flags should be OR-merged (true if any sub-query was true)");
    }

    @Test
    void qppSignalsAreZeroed() {
      SearchResponse a = SearchResponse.newBuilder()
          .setTotalHits(1)
          .setTookMs(10)
          .addResults(hit("a1", 0.9f))
          .setSearchTrace(
              io.justsearch.ipc.SearchTrace.newBuilder()
                  .setQpp(
                      io.justsearch.ipc.TraceQpp.newBuilder()
                          .setMaxIdf(5.2f).setAvgIctf(3.1f).setQueryScope(0.8f)))
          .build();

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertEquals(0f, merged.getSearchTrace().getQpp().getMaxIdf(), "QPP signals should be zeroed in merged response");
      assertEquals(0f, merged.getSearchTrace().getQpp().getAvgIctf());
      assertEquals(0f, merged.getSearchTrace().getQpp().getQueryScope());
    }
  }

  @Nested
  @DisplayName("Metadata")
  class Metadata {

    @Test
    void totalHitsSummedAcrossResponses() {
      SearchResponse a = SearchResponse.newBuilder()
          .setTotalHits(50).setTookMs(10).addResults(hit("a1", 0.9f)).build();
      SearchResponse b = SearchResponse.newBuilder()
          .setTotalHits(30).setTookMs(20).addResults(hit("b1", 0.8f)).build();

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertEquals(80, merged.getTotalHits());
    }

    @Test
    void tookMsIsMaxAcrossResponses() {
      SearchResponse a = SearchResponse.newBuilder()
          .setTotalHits(1).setTookMs(10).addResults(hit("a1", 0.9f)).build();
      SearchResponse b = SearchResponse.newBuilder()
          .setTotalHits(1).setTookMs(25).addResults(hit("b1", 0.8f)).build();

      SearchResponse merged = SearchPerSourceExecutor.mergeSearchResponses(
          List.of(a, b), 6, DUMMY_REQ, req -> SearchResponse.getDefaultInstance());

      assertEquals(25, merged.getTookMs());
    }
  }
}
