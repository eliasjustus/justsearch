/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentRequest;

/**
 * Per-turn policy decisions for the agent loop (tempdoc 240 — extracted from
 * {@code AgentLoopService}). Pure static functions over {@link AgentSession} /
 * {@link AgentRequest} deciding whether to force a tool call (E0a), escalate a
 * text-only PRIMARY response to a forced handoff (Direction F), and which
 * {@link AgentState} the active agent is in (Direction E).
 */
final class AgentTurnPolicy {

  // Direction E: after PRIMARY has executed this many tool-use rounds without handing off,
  // transition to DECIDING state — restrict available tools to handoff-only and apply
  // tool_choice:required so the model is forced to commit rather than continuing to search.
  // Value 3 allows search + browse + search (the pattern in h003/h004) before forcing commit.
  static final int PRIMARY_FORCE_COMMIT_ITERATIONS = 3;

  private AgentTurnPolicy() {}

  /**
   * Returns {@code true} if the current agent's response should be forced to a tool call.
   *
   * <p>Forcing is enabled when: (1) multi-agent mode is active, (2) the current agent is not
   * "primary" (PRIMARY may legitimately produce text), and (3) this is the agent's first LLM
   * call since the last handoff (E0a — the failure is concentrated on the first turn).
   */
  static boolean shouldForceToolCall(AgentSession session) {
    String activeId = session.activeAgentId();
    if (activeId == null) {
      return false; // single-agent mode
    }
    if ("primary".equals(activeId)) {
      return false; // PRIMARY may produce text responses
    }
    // E0a: force only on first turn after handoff
    return session.agentIterationsSinceHandoff() == 0;
  }

  /**
   * Returns {@code true} if PRIMARY's text-only response should be retried with forced handoff.
   *
   * <p>Direction F: after PRIMARY has completed at least one tool-use round (iterations >= 2)
   * and produced text instead of a handoff call, escalate by retrying with
   * {@code tool_choice: "required"} and handoff-only tools.
   */
  static boolean shouldEscalateToHandoff(AgentSession session, AgentRequest request) {
    if (session.activeAgentId() == null) {
      return false; // single-agent mode
    }
    if (!"primary".equals(session.activeAgentId())) {
      return false; // only escalate PRIMARY — other agents have E0a
    }
    if (request.agentProfiles().size() < 2) {
      return false; // no handoff targets
    }
    // Only after tool use: iterations >= 2 means at least one tool-execution round completed
    return session.agentIterationsSinceHandoff() >= 2;
  }

  /**
   * Resolves the current {@link AgentState} for the active agent.
   *
   * <p>Direction E: PRIMARY transitions from SEARCHING to DECIDING after
   * {@link #PRIMARY_FORCE_COMMIT_ITERATIONS} tool-use rounds without a handoff. All other agents
   * and single-agent mode always return SEARCHING (state machine is PRIMARY-only for now).
   */
  static AgentState resolveAgentState(AgentSession session, AgentRequest request) {
    if (!"primary".equals(session.activeAgentId())) {
      return AgentState.SEARCHING; // Organizer and single-agent: state machine not applied
    }
    if (request.agentProfiles().size() < 2) {
      return AgentState.SEARCHING; // no handoff targets — cannot DECIDE to hand off
    }
    if (session.agentIterationsSinceHandoff() >= PRIMARY_FORCE_COMMIT_ITERATIONS) {
      return AgentState.DECIDING;
    }
    return AgentState.SEARCHING;
  }
}
