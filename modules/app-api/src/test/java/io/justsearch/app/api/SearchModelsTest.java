package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchModelsTest {

  @Test
  void searchRequestCursorNormalisesModeAndExtras() {
    Map<String, Object> extras = new HashMap<>();
    extras.put("foo", "bar");

    SearchRequest.Cursor cursor = new SearchRequest.Cursor(null, "token", 123L, extras);

    assertEquals("legacy", cursor.mode());
    assertEquals("token", cursor.token());
    assertEquals(123L, cursor.expiresAtEpochMs());
    assertEquals(Map.of("foo", "bar"), cursor.extras());

    extras.put("foo", "mutated");
    assertEquals("bar", cursor.extras().get("foo"));
  }

  @Test
  void searchRequestCursorRejectsBlankToken() {
    assertThrows(IllegalArgumentException.class, () -> new SearchRequest.Cursor("", "", null, null));
  }

  @Test
  void searchResponseNormalisesMetadataAndCursorExtras() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("from", "api");
    Map<String, Object> extras = new HashMap<>();
    extras.put("cursor", 42);

    SearchResponse response =
        new SearchResponse(
            java.util.List.of(),
            Map.of(),
            new SearchResponse.Cursor("pit", "token", 456L, extras),
            metadata);

    assertEquals(Map.of("from", "api"), response.metadata());
    assertEquals(Map.of("cursor", 42), response.cursor().extras());

    extras.put("cursor", 99);
    metadata.put("from", "mutated");
    assertEquals(42, response.cursor().extras().get("cursor"));
    assertEquals("api", response.metadata().get("from"));
  }

  @Test
  void searchResponseCursorRequiresNonNullFields() {
    assertThrows(NullPointerException.class, () -> new SearchResponse.Cursor(null, "token", null, null));
    assertThrows(NullPointerException.class, () -> new SearchResponse.Cursor("mode", null, null, null));
  }
}
