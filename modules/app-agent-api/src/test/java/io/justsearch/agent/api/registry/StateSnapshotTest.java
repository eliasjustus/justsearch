package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StateSnapshot (slice 489 §4 navigation state carrier)")
final class StateSnapshotTest {

  @Test
  @DisplayName("empty() returns immutable empty snapshot")
  void emptySnapshotIsImmutable() {
    StateSnapshot snap = StateSnapshot.empty();
    assertTrue(snap.values().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> snap.values().put("k", "v"));
  }

  @Test
  @DisplayName("non-empty values preserved")
  void nonEmptyValuesPreserved() {
    Map<String, Object> values = new HashMap<>();
    values.put("query", "rust ownership");
    values.put("limit", 25);
    StateSnapshot snap = new StateSnapshot(values);
    assertEquals("rust ownership", snap.values().get("query"));
    assertEquals(25, snap.values().get("limit"));
  }

  @Test
  @DisplayName("values map is defensively copied")
  void valuesAreDefensivelyCopied() {
    Map<String, Object> source = new HashMap<>();
    source.put("k", "v1");
    StateSnapshot snap = new StateSnapshot(source);
    source.put("k", "v2"); // mutate source after construction
    assertEquals("v1", snap.values().get("k"), "snapshot must not reflect post-construction mutations");
  }

  @Test
  @DisplayName("null values map rejected")
  void nullValuesMapRejected() {
    assertThrows(NullPointerException.class, () -> new StateSnapshot(null));
  }
}
