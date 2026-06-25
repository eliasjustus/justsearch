/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.ToolCallRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses streaming tool call deltas from llama-server SSE responses. Accumulates incremental
 * argument chunks into complete {@link ToolCallRequest} instances.
 */
public final class ToolCallParser {

  private final Map<Integer, ToolCallAccumulator> pending = new HashMap<>();

  /** Accumulate a single SSE chunk's tool_calls deltas. */
  public void accumulateChunk(JsonNode chunkNode) {
    JsonNode choices = chunkNode.path("choices");
    if (choices.isMissingNode() || choices.isEmpty()) {
      return;
    }
    JsonNode delta = choices.get(0).path("delta");
    JsonNode toolCalls = delta.path("tool_calls");
    if (toolCalls.isMissingNode() || !toolCalls.isArray()) {
      return;
    }
    for (JsonNode tc : toolCalls) {
      int index = tc.path("index").asInt(0);
      var acc = pending.computeIfAbsent(index, k -> new ToolCallAccumulator());

      String id = tc.path("id").asText(null);
      if (id != null) {
        acc.id = id;
      }

      JsonNode function = tc.path("function");
      if (!function.isMissingNode()) {
        String name = function.path("name").asText(null);
        if (name != null) {
          acc.name = name;
        }
        String args = function.path("arguments").asText(null);
        if (args != null) {
          acc.arguments.append(args);
        }
      }
    }
  }

  /** Extract all completed tool calls and reset state. */
  public List<ToolCallRequest> drainCompleted() {
    List<ToolCallRequest> result = new ArrayList<>();
    for (var acc : pending.values()) {
      if (acc.id != null && acc.name != null) {
        result.add(new ToolCallRequest(acc.id, acc.name, acc.arguments.toString()));
      }
    }
    pending.clear();
    return result;
  }

  /** Whether there are any pending (partial) tool calls. */
  public boolean hasPending() {
    return !pending.isEmpty();
  }

  /** Check if a chunk contains a tool_calls finish reason. */
  public static boolean isToolCallFinishReason(JsonNode chunkNode) {
    JsonNode choices = chunkNode.path("choices");
    if (choices.isMissingNode() || choices.isEmpty()) {
      return false;
    }
    String finishReason = choices.get(0).path("finish_reason").asText(null);
    return "tool_calls".equals(finishReason);
  }

  private static final class ToolCallAccumulator {
    String id;
    String name;
    final StringBuilder arguments = new StringBuilder();
  }
}
