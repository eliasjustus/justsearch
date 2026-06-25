package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 585 §D Phase 3 (C2) — the time-travel fork's message-truncation boundary logic. */
class AgentRunQueryServiceForkTest {

  private static Map<String, Object> msg(String role, String content) {
    return Map.of("role", role, "content", content);
  }

  @Test
  @DisplayName("forks at the last user turn, replacing that question with the edited one")
  void editsTheLastUserTurnKeepingPriorContext() {
    List<Map<String, Object>> conversation =
        List.of(
            msg("system", "You are an agent."),
            msg("user", "find my taxes"),
            msg("assistant", "Found tax-2023.pdf."),
            msg("user", "what about 2024?"),
            msg("assistant", "Found tax-2024.pdf."));

    List<Map<String, Object>> forked =
        AgentRunQueryService.forkMessages(conversation, "what about 2025?");

    // Everything BEFORE the last user turn is preserved as context; the prior response is dropped;
    // the last question is replaced with the edited one (a clean boundary ending at a user turn).
    assertEquals(4, forked.size());
    assertEquals("You are an agent.", forked.get(0).get("content"));
    assertEquals("find my taxes", forked.get(1).get("content"));
    assertEquals("Found tax-2023.pdf.", forked.get(2).get("content"));
    assertEquals("user", forked.get(3).get("role"));
    assertEquals("what about 2025?", forked.get(3).get("content"));
    // The prior run's final assistant turn is NOT carried into the fork.
    assertTrue(forked.stream().noneMatch(m -> "Found tax-2024.pdf.".equals(m.get("content"))));
  }

  @Test
  @DisplayName("a blank edit re-rolls the original last question")
  void blankEditReRollsTheOriginalQuestion() {
    List<Map<String, Object>> conversation =
        List.of(msg("system", "sys"), msg("user", "original q"), msg("assistant", "answer"));
    List<Map<String, Object>> forked = AgentRunQueryService.forkMessages(conversation, "");
    assertEquals(2, forked.size());
    assertEquals("original q", forked.get(1).get("content"));
  }

  @Test
  @DisplayName("returns empty when there is no user turn to fork from")
  void noUserTurnReturnsEmpty() {
    List<Map<String, Object>> conversation = List.of(msg("system", "sys"), msg("assistant", "hi"));
    assertTrue(AgentRunQueryService.forkMessages(conversation, "q").isEmpty());
  }
}
