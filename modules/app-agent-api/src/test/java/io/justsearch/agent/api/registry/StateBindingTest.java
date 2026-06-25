package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StateBinding (slice 489 §5 surface-state to FE-store binding)")
final class StateBindingTest {

  @Test
  @DisplayName("valid binding is constructable")
  void validBinding() {
    StateBinding b = new StateBinding("/query", "search", "query");
    assertEquals("/query", b.schemaPath());
    assertEquals("search", b.storeId());
    assertEquals("query", b.storeKey());
  }

  @Test
  @DisplayName("schemaPath must start with /")
  void schemaPathRequiresLeadingSlash() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StateBinding("query", "search", "query"));
  }

  @Test
  @DisplayName("schemaPath must be non-blank")
  void schemaPathRequiresNonBlank() {
    assertThrows(IllegalArgumentException.class, () -> new StateBinding("", "search", "query"));
    assertThrows(IllegalArgumentException.class, () -> new StateBinding("   ", "search", "query"));
  }

  @Test
  @DisplayName("storeId must be non-blank")
  void storeIdRequiresNonBlank() {
    assertThrows(IllegalArgumentException.class, () -> new StateBinding("/query", "", "query"));
    assertThrows(
        IllegalArgumentException.class, () -> new StateBinding("/query", "   ", "query"));
  }

  @Test
  @DisplayName("empty storeKey is allowed (means 'whole store value')")
  void emptyStoreKeyIsAllowed() {
    StateBinding b = new StateBinding("/value", "inspector", "");
    assertEquals("", b.storeKey());
  }

  @Test
  @DisplayName("null fields rejected")
  void nullFieldsRejected() {
    assertThrows(NullPointerException.class, () -> new StateBinding(null, "s", "k"));
    assertThrows(NullPointerException.class, () -> new StateBinding("/p", null, "k"));
    assertThrows(NullPointerException.class, () -> new StateBinding("/p", "s", null));
  }
}
