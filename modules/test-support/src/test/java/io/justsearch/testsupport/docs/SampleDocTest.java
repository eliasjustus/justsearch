package io.justsearch.testsupport.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SampleDocTest {

  @Test
  void normalisesNullFields() {
    Map<String, List<String>> facets = new HashMap<>();
    facets.put("tag", List.of("one", "two"));
    facets.put(null, List.of("ignored"));

    SampleDoc doc =
        new SampleDoc(
            "id-1",
            null,
            null,
            facets,
            Map.of("key", "value"),
            List.of(0.1, 0.2));

    assertEquals("", doc.title());
    assertEquals("", doc.body());
    assertEquals(List.of("one", "two"), doc.facets().get("tag"));
    assertEquals(Map.of("key", "value"), doc.metadata());
    assertEquals(List.of(0.1, 0.2), doc.embedding());
  }

  @Test
  void rejectsBlankIds() {
    assertThrows(IllegalArgumentException.class, () -> new SampleDoc(" ", "", "", Map.of(), Map.of(), List.of()));
  }
}
