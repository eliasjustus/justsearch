package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRequestTest {

  @Test
  void recordAccessorsWork() {
    SearchRequest.TimeRange tr = new SearchRequest.TimeRange(1000L, 2000L);
    SearchRequest.Filters filters = new SearchRequest.Filters("text/plain", "en", tr);
    SearchRequest.Clause clause = new SearchRequest.Clause("term", "title", "hello", List.of("hello"));
    SearchRequest.Cursor cursor = new SearchRequest.Cursor(null, "opaque-token", null, null);

    SearchRequest req = new SearchRequest(
        10,
        0,
        true,
        filters,
        List.of("-date"),
        List.of(clause),
        cursor
    );

    assertEquals(10, req.limit());
    assertEquals(0, req.offset());
    assertTrue(req.highlight());
    assertEquals("text/plain", req.filters().mime());
    assertEquals("en", req.filters().language());
    assertEquals(1000L, req.filters().timeRange().fromMs());
    assertEquals(2000L, req.filters().timeRange().toMs());
    assertEquals("term", req.clauses().getFirst().type());
    assertEquals("title", req.clauses().getFirst().field());
    assertEquals("hello", req.clauses().getFirst().value());
    assertEquals(List.of("hello"), req.clauses().getFirst().tokens());
    assertEquals("opaque-token", req.cursor().token());
    assertEquals("legacy", req.cursor().mode());
    assertNull(req.cursor().expiresAtEpochMs());
    assertTrue(req.cursor().extras().isEmpty());
    assertNotNull(req.context());
    assertTrue(req.context().translatorMeta().isEmpty());
  }

  @Test
  void contextStoresTranslatorMetadata() {
    SearchRequest.Context context =
        new SearchRequest.Context(java.util.Map.of("intent_json", "{\"ok\":true}"));
    SearchRequest req =
        new SearchRequest(5, 0, false, null, null, null, null, context);

    assertEquals("{\"ok\":true}", req.context().translatorMeta().get("intent_json"));
    SearchRequest.Context updated =
        req.context().withTranslatorMeta(java.util.Map.of("intent_json", "{\"ok\":false}"));
    assertEquals("{\"ok\":false}", updated.translatorMeta().get("intent_json"));
  }
}
