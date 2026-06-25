package io.justsearch.app.observability.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.SubCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("DiagnosticEventEnvelope")
final class DiagnosticEventEnvelopeTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static DiagnosticEvent sampleEvent() {
    return new DiagnosticEvent(
        "INFO",
        "test message",
        "io.justsearch.example.X",
        "main",
        1L,
        Instant.parse("2026-05-07T10:00:00Z"),
        Map.of("trace_id", "abc"),
        Set.of(DataClass.USER_PATHS),
        SubCategory.CORE_DIAGNOSTIC);
  }

  @Test
  @DisplayName("ofLogEvent emits the wire-stable kind discriminator")
  void ofLogEventKind() {
    DiagnosticEventEnvelope env = DiagnosticEventEnvelope.ofLogEvent(sampleEvent());
    assertEquals("log-event", env.kind());
    assertEquals(DiagnosticEventEnvelope.KIND_LOG_EVENT, env.kind());
  }

  @Test
  @DisplayName("blank kind rejected by compact constructor")
  void blankKindRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiagnosticEventEnvelope("", sampleEvent()));
  }

  @Test
  @DisplayName("Jackson serializes envelope with kind + nested event")
  void jacksonSerialization() throws Exception {
    DiagnosticEventEnvelope env = DiagnosticEventEnvelope.ofLogEvent(sampleEvent());
    String json = MAPPER.writeValueAsString(env);
    JsonNode tree = MAPPER.readTree(json);
    assertEquals("log-event", tree.get("kind").asText());
    assertTrue(tree.has("event"));
    assertEquals("INFO", tree.get("event").get("level").asText());
    assertEquals("io.justsearch.example.X", tree.get("event").get("loggerName").asText());
    assertEquals("CORE_DIAGNOSTIC", tree.get("event").get("subCategory").asText());
    assertEquals("abc", tree.get("event").get("mdc").get("trace_id").asText());
  }
}
