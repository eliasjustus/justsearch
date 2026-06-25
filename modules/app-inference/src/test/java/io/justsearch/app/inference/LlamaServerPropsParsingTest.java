package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlamaServerPropsParsingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("Extract n_ctx from top-level n_ctx")
  void extractTopLevelNctx() throws Exception {
    JsonNode root = MAPPER.readTree("{\"n_ctx\":2048}");
    assertEquals(2048, InferenceLifecycleManager.extractContextTokensFromProps(root));
  }

  @Test
  @DisplayName("Extract n_ctx from default_generation_settings.n_ctx")
  void extractNestedNctx() throws Exception {
    JsonNode root = MAPPER.readTree("{\"default_generation_settings\":{\"n_ctx\":4096}}");
    assertEquals(4096, InferenceLifecycleManager.extractContextTokensFromProps(root));
  }

  @Test
  @DisplayName("Extract n_ctx from default_generation_settings.params.n_ctx")
  void extractNestedParamsNctx() throws Exception {
    JsonNode root = MAPPER.readTree("{\"default_generation_settings\":{\"params\":{\"n_ctx\":8192}}}");
    assertEquals(8192, InferenceLifecycleManager.extractContextTokensFromProps(root));
  }

  @Test
  @DisplayName("Handles real-world shape where n_ctx is followed by a close-brace before comma")
  void extractRealWorldShape() throws Exception {
    // Matches the observed llama-server /props structure:
    // {"default_generation_settings":{"params":{...},"n_ctx":4096},"total_slots":4,...}
    JsonNode root =
        MAPPER.readTree("{\"default_generation_settings\":{\"params\":{},\"n_ctx\":4096},\"total_slots\":4}");
    assertEquals(4096, InferenceLifecycleManager.extractContextTokensFromProps(root));
  }

  @Test
  @DisplayName("Returns null when n_ctx is missing")
  void missingNctxReturnsNull() throws Exception {
    JsonNode root = MAPPER.readTree("{\"default_generation_settings\":{\"params\":{}}}");
    assertNull(InferenceLifecycleManager.extractContextTokensFromProps(root));
  }
}
