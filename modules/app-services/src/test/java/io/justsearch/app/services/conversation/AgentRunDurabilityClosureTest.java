package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.EventDescriptor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 865 §7.7 / §8.3 item 6 — the record projection's vocabulary must be EXHAUSTIVE over the
 * wire vocabulary.
 *
 * <p>The asymmetry this closes: every {@code AgentRunShape} kind is born durable on the wire, where
 * {@code AgentEventPayloads}' switches are over a SEALED interface and the compiler forces a
 * decision about each one — and born NON-durable in the record, where {@code
 * AgentInteractionMapper.fromRunEvent} switches over a {@code String} with a {@code default}, so
 * silence is the fallback. {@code budget_raised} was that asymmetry's output, not an oversight.
 *
 * <p>The compiler cannot help on the String switch, so this test is the enforcement: a kind added to
 * {@code AgentRunShape} and claimed by NEITHER list below fails the build, which is the same
 * two-site-agreement shape {@code AgentInteractionMapperTest.everyDeclaredPhaseConstantIsClassified}
 * already uses for the durable-progress phases. Deciding is cheap; the defect this prevents is a
 * kind whose durability nobody ever chose.
 */
final class AgentRunDurabilityClosureTest {

  /** Kinds {@code fromRunEvent} projects onto the thread record. */
  private static final Set<String> PROJECTING =
      Set.of(
          "tool_call_proposed",
          "tool_call_pending",
          "tool_exec_started",
          "tool_exec_completed",
          "tool_call_rejected",
          "done",
          "error",
          "progress",
          "handoff_executed");

  /**
   * Kinds {@code fromRunEvent} declares non-projecting, written there as explicit cases. See that
   * switch for the per-group reasons — run plumbing, streamed text the terminal already persists,
   * proposals whose outcome is durable, and live meters whose accountability outcome rides {@code
   * progress} or the terminal disposition.
   */
  private static final Set<String> DECLARED_NON_PROJECTING =
      Set.of(
          "session_started",
          "chunk",
          "reasoning_chunk",
          "tool_batch_proposed",
          "tool_call_approved",
          "tool_call_virtual",
          "directive_acknowledged",
          "handoff_proposed",
          "budget_update",
          "budget_gate",
          "context_gate",
          "context_compacted",
          "state_snapshot",
          "intent.resolution");

  @Test
  @DisplayName("865 §7.7: every AgentRunShape event kind is classified — projected or declared non-projecting")
  void everyWireKindIsClassified() {
    List<EventDescriptor> schema = AgentRunShape.definition().eventSchema();
    assertTrue(schema.size() >= 20, "sanity: the schema was found and is the agent run's");

    var unclassified = new LinkedHashSet<String>();
    for (EventDescriptor descriptor : schema) {
      String name = descriptor.name();
      if (!PROJECTING.contains(name) && !DECLARED_NON_PROJECTING.contains(name)) {
        unclassified.add(name);
      }
    }

    assertEquals(
        Set.of(),
        unclassified,
        "a new agent event kind must DECIDE its record durability. Add a projecting case to"
            + " AgentInteractionMapper.fromRunEvent and list it in PROJECTING, or add it to that"
            + " switch's explicit non-projecting arm and to DECLARED_NON_PROJECTING. Letting it"
            + " reach `default` makes 'nobody decided' indistinguishable from 'someone decided no'"
            + " — the asymmetry that produced budget_raised.");
  }

  @Test
  @DisplayName("865 §7.7: the two classification lists stay disjoint")
  void theListsDoNotOverlap() {
    var overlap = new LinkedHashSet<>(PROJECTING);
    overlap.retainAll(DECLARED_NON_PROJECTING);
    assertEquals(Set.of(), overlap, "a kind cannot be both projected and declared non-projecting");
  }
}
