/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.ToolCallRequest;
import java.util.List;

/**
 * Result of one LLM call: the streamed text content plus any parsed tool calls
 * (tempdoc 240 W4 — extracted from {@code AgentLoopService} so both the agent
 * loop and {@link AgentLlmCaller} reference it without qualification).
 *
 * @param finishReason the runtime's own terminal reason for the completion ({@code "stop"},
 *     {@code "length"}, {@code "tool_calls"}, …), or {@code null} when the stream ended without
 *     one. Tempdoc 881 §B.3: this arrives on every stream and used to be dropped on the floor,
 *     which left the empty-response terminal GUESSING that a truncation had occurred — a guess
 *     that was wrong (measured: {@code stop} at 35–55 of 1024 completion tokens) and that sent
 *     868 §D.3 chasing the token budget. A terminal that reports a cause must be holding one.
 */
record LlmCallResult(String textContent, List<ToolCallRequest> toolCalls, String finishReason) {

  /** Result with no runtime-reported finish reason. */
  LlmCallResult(String textContent, List<ToolCallRequest> toolCalls) {
    this(textContent, toolCalls, null);
  }
}
