/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.lifecycle.AgentLifecycle;
import io.justsearch.agent.api.lifecycle.LifecycleState;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 561 P-A / P-A2 — project the durable {@code AgentRunStore} record (its {@code meta.json})
 * into the typed {@link AgentLifecycle} loop object. A read-time projection of the ONE durable loop
 * record — not a second store (the §11 discipline). The loop's state machine + budget already live in
 * the record; this gives them a typed {@code Session ⊃ Turn ⊃ Iteration} shape every consumer reads.
 */
public final class AgentLifecycleProjection {

  private AgentLifecycleProjection() {}

  /** Project one run's persisted meta into its typed lifecycle. */
  public static AgentLifecycle fromRun(Map<String, Object> meta) {
    String sessionId = str(meta.get("sessionId"));
    Instant startedAt = parseTs(meta.get("startedAt"));
    int initial = intOf(meta.get("initialBudget"));
    int consumed = intOf(meta.get("totalTokensUsed"));
    int iterationsUsed = intOf(meta.get("iterationsUsed"));
    int toolCallsExecuted = intOf(meta.get("toolCallsExecuted"));

    Object actor = meta.getOrDefault("activeAgentId", "primary");
    return new AgentLifecycle(
        sessionId,
        str(meta.get("conversationId")),
        LifecycleState.parse(meta.get("state")),
        actor instanceof String s && !s.isBlank() ? s : "primary",
        startedAt,
        parseTs(meta.get("updatedAt")),
        new AgentLifecycle.Budget(initial, consumed, initial - consumed),
        buildTurns(meta, startedAt, iterationsUsed),
        toolCallsExecuted,
        buildActors(meta, actor instanceof String s && !s.isBlank() ? s : "primary"));
  }

  /**
   * Tempdoc 561 P-E actor cardinality — the DISTINCT actor identities that participated in the run:
   * the active actor plus every declared agent profile and every handoff endpoint. Single-agent runs
   * collapse to one; multi-agent / debate-critique runs surface the full set, on the ONE record.
   */
  private static List<String> buildActors(Map<String, Object> meta, String activeActor) {
    java.util.LinkedHashSet<String> actors = new java.util.LinkedHashSet<>();
    actors.add(activeActor);
    addIfString(actors, meta.get("initialAgentId"));
    if (meta.get("agentProfiles") instanceof List<?> profiles) {
      for (Object p : profiles) {
        if (p instanceof Map<?, ?> prof) {
          addIfString(actors, prof.get("agentId"));
        }
      }
    }
    if (meta.get("handoffHistory") instanceof List<?> handoffs) {
      for (Object h : handoffs) {
        if (h instanceof Map<?, ?> hop) {
          addIfString(actors, hop.get("fromAgentId"));
          addIfString(actors, hop.get("toAgentId"));
        }
      }
    }
    return new ArrayList<>(actors);
  }

  private static void addIfString(java.util.Set<String> set, Object v) {
    if (v instanceof String s && !s.isBlank()) {
      set.add(s);
    }
  }

  /**
   * Tempdoc 561 P-A2 — derive the explicit {@code Session ⊃ Turn ⊃ Iteration} hierarchy from the run's
   * message history: each {@code user} message opens a {@link AgentLifecycle.Turn}; each subsequent
   * {@code assistant} message (one LLM call) is an {@link AgentLifecycle.Iteration} in that turn; tool
   * results are not iterations. Multi-turn-within-a-run is first-class — a run with N user messages
   * projects N turns (today a run services one request, so one turn, with no reshape needed when that
   * changes). The authoritative {@code iterationsUsed} is preserved: any iterations not represented as
   * assistant messages in the persisted prefix (e.g. the final synthesis call) are attributed to the
   * last turn, so {@link AgentLifecycle#iterationCount()} == {@code iterationsUsed} (the P-3
   * cannot-disagree invariant).
   */
  private static List<AgentLifecycle.Turn> buildTurns(
      Map<String, Object> meta, Instant startedAt, int iterationsUsed) {
    List<String> prompts = new ArrayList<>();
    List<Integer> iterCounts = new ArrayList<>();
    if (meta.get("messages") instanceof List<?> messages) {
      for (Object m : messages) {
        if (!(m instanceof Map<?, ?> msg)) {
          continue;
        }
        Object role = msg.get("role");
        if ("user".equals(role)) {
          prompts.add(msg.get("content") instanceof String c ? c : "");
          iterCounts.add(0);
        } else if ("assistant".equals(role) && !iterCounts.isEmpty()) {
          int last = iterCounts.size() - 1;
          iterCounts.set(last, iterCounts.get(last) + 1);
        }
      }
    }
    // Degenerate run (no user message recorded yet): one turn, so the structure is always present.
    if (prompts.isEmpty()) {
      prompts.add("");
      iterCounts.add(0);
    }
    // Preserve the authoritative total: attribute any unrepresented iterations to the last turn.
    int counted = iterCounts.stream().mapToInt(Integer::intValue).sum();
    if (iterationsUsed > counted) {
      int last = iterCounts.size() - 1;
      iterCounts.set(last, iterCounts.get(last) + (iterationsUsed - counted));
    }
    List<AgentLifecycle.Turn> turns = new ArrayList<>(prompts.size());
    for (int t = 0; t < prompts.size(); t++) {
      int n = iterCounts.get(t);
      List<AgentLifecycle.Iteration> iters = new ArrayList<>(Math.max(0, n));
      for (int i = 0; i < n; i++) {
        iters.add(new AgentLifecycle.Iteration(i, startedAt));
      }
      turns.add(new AgentLifecycle.Turn(t, prompts.get(t), startedAt, iters));
    }
    return turns;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }

  private static int intOf(Object o) {
    return o instanceof Number n ? n.intValue() : 0;
  }

  private static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    return Instant.EPOCH;
  }
}
