package io.justsearch.core.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CursorTest {

  @Test
  void constructorRejectsBlankModeOrToken() {
    assertThrows(IllegalArgumentException.class, () -> new Cursor(null, "token", 1L, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new Cursor(" ", "token", 1L, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new Cursor("mode", null, 1L, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new Cursor("mode", "", 1L, Map.of()));
    assertThrows(NullPointerException.class, () -> Cursor.legacy(null));
  }

  @Test
  void extrasIsDefensiveCopy() {
    Map<String, Object> extras = new HashMap<>();
    extras.put("key", "value");
    Cursor cursor = new Cursor("pit", "abc", 10L, extras);

    extras.put("key", "mutated");
    assertEquals("value", cursor.extras().get("key"));
    assertThrows(UnsupportedOperationException.class, () -> cursor.extras().put("other", "x"));
  }

  @Test
  void nullExtrasDefaultsToEmptyUnmodifiableMap() {
    Cursor cursor = new Cursor("pit", "abc", null, null);

    assertTrue(cursor.extras().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> cursor.extras().put("k", "v"));
  }

  @Test
  void equalityAndHashCodeIncludeAllFields() {
    Map<String, Object> extras = Map.of("a", 1);
    Cursor left = new Cursor("pit", "abc", 123L, extras);
    Cursor right = new Cursor("pit", "abc", 123L, extras);
    Cursor different =
        new Cursor("pit", "def", 123L, Map.of("a", 1));

    assertEquals(left, right);
    assertEquals(left.hashCode(), right.hashCode());
    assertNotEquals(left, different);
  }

  @Test
  void legacyFactoryUsesLegacyModeAndIsLegacy() {
    Cursor legacy = Cursor.legacy("token-123");
    Cursor nonLegacy = new Cursor("pit", "token-123", null, Map.of());

    assertEquals("legacy", legacy.mode());
    assertNull(legacy.expiresAtEpochMs());
    assertTrue(legacy.extras().isEmpty());
    assertTrue(legacy.isLegacy());
    assertFalse(nonLegacy.isLegacy());
  }
}
