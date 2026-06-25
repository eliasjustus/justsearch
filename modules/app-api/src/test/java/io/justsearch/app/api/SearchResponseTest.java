package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchResponseTest {

  @Test
  void recordAccessorsWork() {
    SearchResponse.Hit hit = new SearchResponse.Hit(
        "doc-1",
        1.23,
        Map.of("title", List.of("<em>Hello</em> world"))
    );
    SearchResponse.Cursor cursor = new SearchResponse.Cursor("pit", "pit-123", 1762069200000L, Map.of());
    SearchResponse resp = new SearchResponse(
        List.of(hit),
        Map.of("type", Map.of("pdf", 2)),
        cursor,
        Map.of("intent.translation.degraded", true)
    );

    assertEquals(1, resp.hits().size());
    assertEquals("doc-1", resp.hits().getFirst().doc_id());
    assertEquals(1.23, resp.hits().getFirst().score(), 1e-9);
    assertEquals(List.of("<em>Hello</em> world"), resp.hits().getFirst().highlights().get("title"));
    assertEquals("pit", resp.cursor().mode());
    assertEquals("pit-123", resp.cursor().token());
    assertEquals(2, resp.facets().get("type").get("pdf"));
    assertTrue(resp.metadata().containsKey("intent.translation.degraded"));
    assertEquals(true, resp.metadata().get("intent.translation.degraded"));
  }
}
