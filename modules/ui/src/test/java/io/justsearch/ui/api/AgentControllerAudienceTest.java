package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 508 §13 critical-analysis Phase C — backend audience
 * filter for virtual-operations publish.
 *
 * <p>Tests the {@code hasAgentAudience} validation in isolation.
 * Integration testing of the full publish endpoint (with auth +
 * Javalin Context) is covered by live verification.
 */
final class AgentControllerAudienceTest {

  @Test
  void acceptsToolWithAgentOnlyAudience() {
    Map<String, Object> tool = Map.of(
        "type", "function",
        "function", Map.of("name", "vop_x", "description", "d", "parameters", Map.of()),
        "audience", List.of("AGENT"));
    assertTrue(AgentToolsController.hasAgentAudience(tool));
  }

  @Test
  void acceptsToolWithMultiAudienceIncludingAgent() {
    Map<String, Object> tool = Map.of("audience", List.of("USER", "AGENT"));
    assertTrue(AgentToolsController.hasAgentAudience(tool));
  }

  @Test
  void acceptsCaseInsensitiveAgent() {
    assertTrue(AgentToolsController.hasAgentAudience(Map.of("audience", List.of("agent"))));
    assertTrue(AgentToolsController.hasAgentAudience(Map.of("audience", List.of("Agent"))));
  }

  @Test
  void rejectsToolWithoutAudienceField() {
    Map<String, Object> tool = Map.of(
        "type", "function",
        "function", Map.of("name", "vop_x"));
    assertFalse(AgentToolsController.hasAgentAudience(tool));
  }

  @Test
  void rejectsToolWithAudienceNotAList() {
    assertFalse(AgentToolsController.hasAgentAudience(Map.of("audience", "AGENT")));
  }

  @Test
  void rejectsToolWithEmptyAudienceList() {
    assertFalse(AgentToolsController.hasAgentAudience(Map.of("audience", List.of())));
  }

  @Test
  void rejectsToolWithUserOnlyAudience() {
    assertFalse(AgentToolsController.hasAgentAudience(Map.of("audience", List.of("USER"))));
  }

  @Test
  void rejectsToolWithOperatorOnlyAudience() {
    assertFalse(AgentToolsController.hasAgentAudience(Map.of("audience", List.of("OPERATOR"))));
  }
}
