/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.ToolCallRequest;
import java.util.List;

/**
 * Result of one LLM call: the streamed text content plus any parsed tool calls
 * (tempdoc 240 W4 — extracted from {@code AgentLoopService} so both the agent
 * loop and {@link AgentLlmCaller} reference it without qualification).
 */
record LlmCallResult(String textContent, List<ToolCallRequest> toolCalls) {}
