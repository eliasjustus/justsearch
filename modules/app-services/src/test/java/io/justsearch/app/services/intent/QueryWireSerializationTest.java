package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 548 S4-A: pins the production wire serialization of {@link ShellAddress.Query}. The intent
 * stream serializes the envelope graph (IntentEnvelopeEvent → Intent → ShellAddress) with the
 * project's Jackson; the FE deserializes {@code address.query} + {@code address.state} and the
 * router lowers it to a search. This test guards the {@code wire-emitter-elision} failure mode:
 * a newly-added wire-emitted variant whose production emitter silently drops it. The field name
 * MUST be {@code "query"} (byte-for-byte with the TS {@code ShellAddressQuery.query}).
 */
class QueryWireSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void querySerializesWithKindQueryAndStateFields() {
    ShellAddress.Query addr =
        new ShellAddress.Query("rust ownership", new StateSnapshot(Map.of("lang", "en")));
    JsonNode json = mapper.valueToTree(addr);
    assertEquals("query", json.get("kind").asString());
    assertEquals("rust ownership", json.get("query").asString());
    assertEquals("en", json.get("state").get("lang").asString());
  }

  @Test
  void answerSerializesWithKindPromptShapeAndStateFields() {
    // 548 §4.5: the SSE wire field names MUST be "prompt" + "shape" (byte-for-byte with the TS
    // ShellAddressAnswer). Guards wire-emitter-elision for the answer variant.
    ShellAddress.Answer addr =
        new ShellAddress.Answer("what is rust", "core.summarize", new StateSnapshot(Map.of("lang", "en")));
    JsonNode json = mapper.valueToTree(addr);
    assertEquals("answer", json.get("kind").asString());
    assertEquals("what is rust", json.get("prompt").asString());
    assertEquals("core.summarize", json.get("shape").asString());
    assertEquals("en", json.get("state").get("lang").asString());
  }
}
