/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines a named agent role within a multi-agent session.
 *
 * <p>Profiles are optional — single-agent requests omit them. When profiles are present, the agent
 * loop exposes each non-active profile as a {@code handoff_to_<agentId>} tool so the LLM can
 * trigger role transitions.
 *
 * @param agentId unique identifier for this agent within the session
 * @param name human-readable name for display (e.g. "Planner", "Executor")
 * @param systemPrompt system prompt override for this agent role; null = use default
 * @param toolSubset tool names this agent may use; empty list = all tools allowed
 */
public record AgentProfile(
    String agentId, String name, String systemPrompt, List<String> toolSubset) {

  public AgentProfile {
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(name, "name");
    toolSubset = toolSubset == null ? List.of() : List.copyOf(toolSubset);
  }

  /**
   * Deserializes an {@link AgentProfile} from a raw {@link Map}.
   *
   * <p>Used in two places that receive untyped JSON: {@code AgentController} (request body) and
   * {@code AgentLoopService.resumeLastSession()} (checkpoint restore). Centralising here prevents
   * duplication and drift.
   */
  @SuppressWarnings("unchecked")
  public static AgentProfile fromMap(Map<String, Object> m) {
    List<String> toolSubset =
        m.get("toolSubset") instanceof List<?> ts
            ? ts.stream()
                .filter(e -> e instanceof String)
                .map(String.class::cast)
                .toList()
            : List.of();
    // Use instanceof to extract required String fields — String.valueOf(null) would produce
    // the literal "null" string and silently bypass the requireNonNull in the compact constructor.
    return new AgentProfile(
        m.get("agentId") instanceof String s ? s : null,
        m.get("name") instanceof String s ? s : null,
        m.get("systemPrompt") instanceof String s ? s : null,
        toolSubset);
  }
}
