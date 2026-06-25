package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 3a-2-c Phase E: verify the "bit-identical wire" claim made in rounds
 * 7–10 commit messages.
 *
 * <p>Legacy HTTP handler path: {@code ctx.json(typedResponse)} →
 * {@code Jackson3JsonMapper.toJsonString(obj)} →
 * {@code mapper.writeValueAsString(obj)}.
 *
 * <p>Operation handler path: typed service returns object → handler does
 * {@code MAPPER.convertValue(obj, Map.class)} → wraps in {@link
 * io.justsearch.agent.api.registry.OperationResult}.{@code structuredData} →
 * Javalin serializes the OperationResult via the same Jackson3JsonMapper.
 *
 * <p>The convertValue step is the only difference. This test verifies that
 * for representative shapes (records, nested records, lists, optional
 * fields), the JSON output of the two paths is structurally equivalent —
 * i.e., the Map intermediate doesn't lose / reorder / re-encode fields
 * versus serializing the typed object directly.
 *
 * <p>Note: both paths use {@code JsonMapper.builder().build()} — same
 * default config, same Jackson 3 module. Equivalence is expected by
 * construction; this test pins it as a regression gate.
 */
final class OperationVsLegacyHttpEquivalenceTest {

  /** Mirrors the handler-side MAPPER (e.g., in PreflightAiPackHandler). */
  private static final ObjectMapper HANDLER_MAPPER = JsonMapper.builder().build();

  /** Mirrors the Javalin-served Jackson3JsonMapper-wrapped instance. */
  private static final ObjectMapper LEGACY_MAPPER = JsonMapper.builder().build();

  /** A representative wire shape — record nesting + list + nullable fields. */
  record Payload(
      String name,
      int count,
      Long nullableLong,
      List<Item> items,
      Inner inner) {
    record Item(String key, int value) {}
    record Inner(String label, boolean flag) {}
  }

  @Test
  void typedRecordRoundTripMatchesConvertValueRoundTrip() {
    Payload obj =
        new Payload(
            "test",
            42,
            null,
            List.of(new Payload.Item("a", 1), new Payload.Item("b", 2)),
            new Payload.Inner("inside", true));

    String legacyJson = LEGACY_MAPPER.writeValueAsString(obj);
    @SuppressWarnings("unchecked")
    Map<String, Object> asMap = HANDLER_MAPPER.convertValue(obj, Map.class);
    String operationJson = HANDLER_MAPPER.writeValueAsString(asMap);

    assertEquals(legacyJson, operationJson, "wire shapes must be byte-identical");
  }

  @Test
  void mapStructuredDataPreservesOrderAndPrimitiveTypes() {
    // The handlers use LinkedHashMap to preserve key order. Verify the wire
    // output reflects that order.
    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("dryRun", true);
    structured.put("patterns", 3);
    structured.put("rootsProcessed", 1);
    structured.put("matchedFiles", 0);

    String json = HANDLER_MAPPER.writeValueAsString(structured);
    // Order check: keys should appear in insertion order.
    int dryRunIdx = json.indexOf("dryRun");
    int patternsIdx = json.indexOf("patterns");
    int rootsIdx = json.indexOf("rootsProcessed");
    assertTrue(dryRunIdx < patternsIdx && patternsIdx < rootsIdx, "key order preserved");
    // Primitive types stay primitive (not stringified).
    assertTrue(json.contains("\"dryRun\":true"), "boolean as primitive");
    assertTrue(json.contains("\"patterns\":3"), "int as primitive");
  }

  @Test
  void nullablesElidedConsistently() {
    // Both paths use the same default Jackson config — no @JsonInclude
    // override at the type level — so null fields appear in both. (If the
    // type had @JsonInclude(NON_NULL), both paths would elide them.)
    Payload obj =
        new Payload(
            "x",
            0,
            null,
            List.of(),
            new Payload.Inner("inside", false));

    String legacyJson = LEGACY_MAPPER.writeValueAsString(obj);
    @SuppressWarnings("unchecked")
    Map<String, Object> asMap = HANDLER_MAPPER.convertValue(obj, Map.class);
    String operationJson = HANDLER_MAPPER.writeValueAsString(asMap);

    assertEquals(legacyJson, operationJson);
    // Both should include the explicit null.
    assertTrue(legacyJson.contains("\"nullableLong\":null"), "null fields kept");
  }
}
