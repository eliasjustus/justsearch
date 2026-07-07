/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input to an agent conversation.
 *
 * @param messages chat messages in OpenAI format
 * @param selectedToolNames tool names to make available (empty = all tools)
 * @param maxIterations maximum agent loop iterations (1 for Plan-then-Execute)
 * @param agentProfiles named agent role definitions (empty = single-agent mode)
 * @param initialAgentId starting agent ID; null = first profile or "primary"
 * @param maxHandoffs maximum handoffs per agent pair before cycle detection fires; null = profiles
 *     * 3
 * @param conversationId tempdoc 561 P-A/P-B — the cross-plane interaction-log conversationId (the
 *     chat conversation this agent run belongs to). Null = the run is its own thread (the agent loop
 *     falls back to the run sessionId). The FE supplies the chat conversationId so an agent run and
 *     the surrounding chat turns interleave in one unified thread.
 * @param autonomyLevel tempdoc 561 P-D — the user's autonomy-dial preference as a wire token
 *     ({@code "watch"|"assist"|"auto"}); null/unknown → ASSIST. The FE supplies the dial so the ONE
 *     backend issuance policy ({@code IntentGateEvaluator.agentGate}) computes the gate and the FE
 *     obeys it, instead of the FE re-deriving auto-approval from risk (the collapsed 2nd authority).
 * @param docIds tempdoc S7 — the FE's scope-chip selection (document paths); empty/null = unscoped
 *     (unchanged behavior). When non-empty, every {@code SearchTool} invocation in this run is
 *     filtered to just these paths, regardless of what the LLM's own tool-call arguments say
 *     ({@code AgentToolDispatcher.scopeToolCall} merges it into the search call's arguments;
 *     {@code SearchTool} threads it into {@code KnowledgeSearchRequest.Filters.docIds}).
 */
public record AgentRequest(
    List<Map<String, Object>> messages,
    List<String> selectedToolNames,
    int maxIterations,
    List<AgentProfile> agentProfiles,
    String initialAgentId,
    Integer maxHandoffs,
    String conversationId,
    String autonomyLevel,
    List<String> docIds) {

  public AgentRequest {
    Objects.requireNonNull(messages, "messages");
    selectedToolNames = selectedToolNames == null ? List.of() : List.copyOf(selectedToolNames);
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
    }
    agentProfiles = agentProfiles == null ? List.of() : List.copyOf(agentProfiles);
    docIds = docIds == null ? List.of() : List.copyOf(docIds);
    // initialAgentId, maxHandoffs, conversationId, autonomyLevel null are valid — defaulted at runtime
  }

  /** Back-compat constructor (pre-S7, no docIds) — delegates with docIds empty (unscoped). */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId,
      String autonomyLevel) {
    this(
        messages,
        selectedToolNames,
        maxIterations,
        agentProfiles,
        initialAgentId,
        maxHandoffs,
        conversationId,
        autonomyLevel,
        List.of());
  }

  /** Back-compat constructor (561 P-A/P-B, no autonomyLevel) — delegates with autonomyLevel=null. */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs,
      String conversationId) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, maxHandoffs,
        conversationId, null);
  }

  /** Back-compat constructor (pre-561, no conversationId) — delegates with conversationId=null. */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId,
      Integer maxHandoffs) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, maxHandoffs, null);
  }

  /** Convenience constructor — maxHandoffs defaults to null (auto-computed from profile count). */
  public AgentRequest(
      List<Map<String, Object>> messages,
      List<String> selectedToolNames,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String initialAgentId) {
    this(messages, selectedToolNames, maxIterations, agentProfiles, initialAgentId, null);
  }

  /** Backward-compatible constructor for single-agent (no profiles) requests. */
  public AgentRequest(
      List<Map<String, Object>> messages, List<String> selectedToolNames, int maxIterations) {
    this(messages, selectedToolNames, maxIterations, List.of(), null);
  }

  public static AgentRequest singleTurn(List<Map<String, Object>> messages) {
    return new AgentRequest(messages, List.of(), 1);
  }
}
