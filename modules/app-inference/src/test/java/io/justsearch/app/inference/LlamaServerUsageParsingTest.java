package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.OnlineAiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LlamaServerUsageParsingTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("Extract usage from llama-server streaming final chunk")
  void extractsUsageFromStreamingChunk() throws Exception {
    JsonNode root =
        MAPPER.readTree(
            """
            {
              "choices": [],
              "object": "chat.completion.chunk",
              "usage": { "completion_tokens": 8, "prompt_tokens": 24, "total_tokens": 32 },
              "timings": { "prompt_n": 24 }
            }
            """);
    OnlineAiService.AiUsage usage = InferenceLifecycleManager.extractUsageFromChatChunk(root);
    assertNotNull(usage);
    assertEquals(24, usage.promptTokens());
    assertEquals(8, usage.completionTokens());
    assertEquals(32, usage.totalTokens());
  }

  @Test
  @DisplayName("Returns null when usage object is missing")
  void missingUsageReturnsNull() throws Exception {
    JsonNode root =
        MAPPER.readTree(
            """
            { "choices": [ { "delta": { "content": "hi" } } ] }
            """);
    assertNull(InferenceLifecycleManager.extractUsageFromChatChunk(root));
  }
}
