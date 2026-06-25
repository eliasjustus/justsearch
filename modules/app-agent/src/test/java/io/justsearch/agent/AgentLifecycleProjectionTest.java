package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.lifecycle.AgentLifecycle;
import io.justsearch.agent.api.lifecycle.LifecycleState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 561 P-A / P-A2 — typed loop object projected from the durable AgentRunStore record. */
final class AgentLifecycleProjectionTest {

  @Test
  @DisplayName("projects run meta into a typed Session > Turn > Iteration lifecycle + honest budget")
  void projectsTypedLifecycle() {
    Map<String, Object> meta =
        Map.of(
            "sessionId", "run-1",
            "conversationId", "conv-1",
            "state", "DONE",
            "activeAgentId", "primary",
            "startedAt", "2026-01-01T00:00:01Z",
            "updatedAt", "2026-01-01T00:00:09Z",
            "initialBudget", 4000,
            "totalTokensUsed", 4272,
            "iterationsUsed", 3,
            "messages",
                List.of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "user", "content", "find invoices")));

    AgentLifecycle lc = AgentLifecycleProjection.fromRun(meta);
    assertEquals("run-1", lc.sessionId());
    assertEquals("conv-1", lc.conversationId());
    assertEquals(LifecycleState.DONE, lc.state());
    assertTrue(lc.state().isTerminal());
    assertEquals("primary", lc.actor());

    // One turn (one user request), three iterations (the LLM calls).
    assertEquals(1, lc.turns().size());
    assertEquals("find invoices", lc.turns().get(0).userPrompt());
    assertEquals(3, lc.turns().get(0).iterations().size());
    assertEquals(3, lc.iterationCount());

    // Budget honest (P-A / §2.2): consumed + remaining == initial even when over budget.
    assertEquals(4000, lc.budget().initial());
    assertEquals(4272, lc.budget().consumed());
    assertEquals(4000 - 4272, lc.budget().remaining());
    assertTrue(lc.budget().overBudget());
  }

  @Test
  @DisplayName("unknown state defaults to READY_FOR_LLM; missing fields are tolerated")
  void tolerantDefaults() {
    AgentLifecycle lc = AgentLifecycleProjection.fromRun(Map.of("sessionId", "run-2"));
    assertEquals(LifecycleState.READY_FOR_LLM, lc.state());
    assertEquals("primary", lc.actor());
    assertEquals(0, lc.iterationCount());
    assertEquals(1, lc.turns().size());
  }

  @Test
  @DisplayName("561 P-A2: multi-turn within a run is first-class — N user messages project N turns,"
      + " iterations grouped per turn, total preserved")
  void multiTurnIsFirstClass() {
    // A run that serviced TWO user requests (multi-turn within one run). Walk: user1 -> 2 assistant
    // iterations -> user2 -> 1 assistant iteration. iterationsUsed (3) is the authoritative total.
    Map<String, Object> meta =
        Map.of(
            "sessionId", "run-mt",
            "state", "DONE",
            "iterationsUsed", 3,
            "toolCallsExecuted", 1,
            "messages",
                List.of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "user", "content", "find invoices"),
                    Map.of("role", "assistant", "content", "searching"),
                    Map.of("role", "assistant", "content", "found 12"),
                    Map.of("role", "user", "content", "now summarize them"),
                    Map.of("role", "assistant", "content", "summary")));

    AgentLifecycle lc = AgentLifecycleProjection.fromRun(meta);
    // Two explicit turns, one per user request.
    assertEquals(2, lc.turns().size());
    assertEquals("find invoices", lc.turns().get(0).userPrompt());
    assertEquals("now summarize them", lc.turns().get(1).userPrompt());
    // Iterations grouped per turn (2 then 1) ...
    assertEquals(2, lc.turns().get(0).iterations().size());
    assertEquals(1, lc.turns().get(1).iterations().size());
    // ... and the authoritative total is preserved (the P-3 cannot-disagree invariant).
    assertEquals(3, lc.iterationCount());
  }

  @Test
  @DisplayName("561 P-E actor cardinality: single-agent run = one actor; multi-agent surfaces the set")
  void actorCardinality() {
    // Single-agent run: actors collapse to one.
    AgentLifecycle single =
        AgentLifecycleProjection.fromRun(
            Map.of("sessionId", "run-1", "activeAgentId", "primary", "state", "DONE"));
    assertEquals(1, single.actorCount());
    assertEquals(List.of("primary"), single.actors());

    // Multi-agent run: the distinct participants (profiles + handoff endpoints) surface on the record.
    Map<String, Object> meta =
        Map.of(
            "sessionId", "run-2",
            "state", "DONE",
            "activeAgentId", "researcher",
            "initialAgentId", "manager",
            "agentProfiles",
                List.of(
                    Map.of("agentId", "manager"),
                    Map.of("agentId", "researcher"),
                    Map.of("agentId", "critic")),
            "handoffHistory",
                List.of(Map.of("fromAgentId", "manager", "toAgentId", "researcher")));
    AgentLifecycle multi = AgentLifecycleProjection.fromRun(meta);
    assertEquals(3, multi.actorCount(), "manager, researcher, critic");
    assertTrue(multi.actors().contains("manager"));
    assertTrue(multi.actors().contains("researcher"));
    assertTrue(multi.actors().contains("critic"));
  }

  @Test
  @DisplayName("561 P-A2: a single-request run is still exactly one turn carrying all iterations")
  void singleTurnPreserved() {
    Map<String, Object> meta =
        Map.of(
            "sessionId", "run-st",
            "state", "DONE",
            "iterationsUsed", 3,
            "messages",
                List.of(
                    Map.of("role", "system", "content", "sys"),
                    Map.of("role", "user", "content", "find invoices")));
    AgentLifecycle lc = AgentLifecycleProjection.fromRun(meta);
    // One turn; the 3 authoritative iterations (none represented as assistant messages in this
    // prefix) attribute to it — single-turn behavior is unchanged.
    assertEquals(1, lc.turns().size());
    assertEquals(3, lc.turns().get(0).iterations().size());
    assertEquals(3, lc.iterationCount());
  }
}
