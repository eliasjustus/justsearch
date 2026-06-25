package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SurfaceStateSchema (slice 489 §5 surface state contract)")
final class SurfaceStateSchemaTest {

  private static final String SAMPLE_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}";

  @Test
  @DisplayName("schema-only convenience returns empty bindings")
  void ofSchemaHasEmptyBindings() {
    SurfaceStateSchema s = SurfaceStateSchema.ofSchema(SAMPLE_SCHEMA);
    assertEquals(SAMPLE_SCHEMA, s.schema());
    assertTrue(s.bindings().isEmpty());
  }

  @Test
  @DisplayName("blank schema rejected")
  void blankSchemaRejected() {
    assertThrows(IllegalArgumentException.class, () -> SurfaceStateSchema.ofSchema(""));
    assertThrows(IllegalArgumentException.class, () -> SurfaceStateSchema.ofSchema("   "));
  }

  @Test
  @DisplayName("null schema rejected")
  void nullSchemaRejected() {
    assertThrows(NullPointerException.class, () -> new SurfaceStateSchema(null, List.of()));
  }

  @Test
  @DisplayName("bindings carried through")
  void bindingsCarriedThrough() {
    StateBinding b = new StateBinding("/query", "search", "query");
    SurfaceStateSchema s = new SurfaceStateSchema(SAMPLE_SCHEMA, List.of(b));
    assertEquals(1, s.bindings().size());
    assertEquals("search", s.bindings().get(0).storeId());
  }

  @Test
  @DisplayName("null bindings default to empty list")
  void nullBindingsDefaultToEmpty() {
    SurfaceStateSchema s = new SurfaceStateSchema(SAMPLE_SCHEMA, null);
    assertTrue(s.bindings().isEmpty());
  }

  @Test
  @DisplayName("bindings list is immutable copy")
  void bindingsImmutable() {
    java.util.ArrayList<StateBinding> source = new java.util.ArrayList<>();
    source.add(new StateBinding("/a", "s", "k"));
    SurfaceStateSchema s = new SurfaceStateSchema(SAMPLE_SCHEMA, source);
    source.clear();
    assertEquals(1, s.bindings().size(), "schema must not reflect post-construction mutations");
    assertThrows(
        UnsupportedOperationException.class,
        () -> s.bindings().add(new StateBinding("/b", "s", "k")));
  }
}
