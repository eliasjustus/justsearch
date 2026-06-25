/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import io.justsearch.app.api.OnlineAiService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers an MCP {@code sampling/createMessage} server→client request by running the <b>host LLM</b>
 * (tempdoc 560 Phase 1; §2 "AI is just another contributor"). An external MCP server may ask the host
 * to perform an LLM completion; the host already owns an LLM, so it answers rather than hanging.
 *
 * <p>Maps the MCP sampling params (a system prompt + a list of {@code {role, content:{text}}}
 * messages + {@code maxTokens}) onto {@link OnlineAiService.StreamRequest}, blocks for the streamed
 * completion, and returns the MCP sampling result shape ({@code {role:"assistant", content:{type,
 * text}, model, stopReason}}). The {@link OnlineAiService} is resolved lazily so sampling works once
 * the LLM is online; if unavailable, the call throws and the transport replies with an error.
 */
public final class McpSamplingAdapter {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private McpSamplingAdapter() {}

  public static Function<JsonNode, JsonNode> of(Supplier<OnlineAiService> aiSupplier) {
    return params -> sample(aiSupplier.get(), params);
  }

  private static JsonNode sample(OnlineAiService ai, JsonNode params) {
    if (ai == null || !ai.isAvailable()) {
      throw new McpException("host LLM is not available for sampling");
    }
    List<Map<String, Object>> messages = new ArrayList<>();
    String system = params.path("systemPrompt").asString("");
    if (!system.isEmpty()) {
      messages.add(Map.of("role", "system", "content", system));
    }
    JsonNode msgs = params.get("messages");
    if (msgs != null && msgs.isArray()) {
      for (JsonNode m : msgs) {
        String role = m.path("role").asString("user");
        JsonNode content = m.path("content");
        String text = content.isObject() ? content.path("text").asString("") : content.asString("");
        messages.add(Map.of("role", role, "content", text));
      }
    }
    int maxTokens = params.path("maxTokens").asInt(512);

    StringBuilder out = new StringBuilder();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    ai.stream(
        new OnlineAiService.StreamRequest(messages, maxTokens),
        OnlineAiService.StreamSink.of(
            out::append,
            finishReason -> done.countDown(),
            t -> {
              error.set(t);
              done.countDown();
            }));
    try {
      if (!done.await(60, TimeUnit.SECONDS)) {
        throw new McpException("host LLM sampling timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpException("host LLM sampling interrupted");
    }
    if (error.get() != null) {
      throw new McpException("host LLM sampling failed: " + error.get().getMessage());
    }

    ObjectNode result = MAPPER.createObjectNode();
    result.put("role", "assistant");
    ObjectNode content = MAPPER.createObjectNode();
    content.put("type", "text");
    content.put("text", out.toString());
    result.set("content", content);
    result.put("model", "justsearch-host");
    result.put("stopReason", "endTurn");
    return result;
  }
}
