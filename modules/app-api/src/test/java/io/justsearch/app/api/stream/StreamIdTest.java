package io.justsearch.app.api.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamId")
final class StreamIdTest {

  @Test
  @DisplayName("registry/surface/system constructors produce kind-prefixed slugs")
  void factoryConstructors() {
    assertEquals("registry:capabilities", StreamId.registry("capabilities").value());
    assertEquals("surface:health-events", StreamId.surface("health-events").value());
    assertEquals("system:status", StreamId.system("status").value());
  }

  @Test
  @DisplayName("kind() and id() split on the colon")
  void kindAndId() {
    StreamId s = StreamId.registry("capabilities");
    assertEquals("registry", s.kind());
    assertEquals("capabilities", s.id());
  }

  @Test
  @DisplayName("rejects bad kind prefixes")
  void rejectsBadKind() {
    assertThrows(IllegalArgumentException.class, () -> new StreamId("foo:bar"));
    assertThrows(IllegalArgumentException.class, () -> new StreamId("REGISTRY:capabilities"));
  }

  @Test
  @DisplayName("rejects ids with capital letters or underscores")
  void rejectsBadId() {
    assertThrows(IllegalArgumentException.class, () -> new StreamId("registry:Capabilities"));
    assertThrows(IllegalArgumentException.class, () -> new StreamId("registry:my_stream"));
  }

  @Test
  @DisplayName("rejects empty / colon-only / no-colon")
  void rejectsMalformed() {
    assertThrows(IllegalArgumentException.class, () -> new StreamId(""));
    assertThrows(IllegalArgumentException.class, () -> new StreamId(":foo"));
    assertThrows(IllegalArgumentException.class, () -> new StreamId("registry:"));
    assertThrows(IllegalArgumentException.class, () -> new StreamId("nokind"));
  }

  @Test
  @DisplayName("rejects null")
  void rejectsNull() {
    assertThrows(NullPointerException.class, () -> new StreamId(null));
  }
}
