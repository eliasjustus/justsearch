package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.agent.api.AgentRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-D — regression guard for the HTTP→{@link AgentRequest} boundary. Live validation
 * caught that the autonomy dial reached the FE wire but was DROPPED here (defaulting to ASSIST), so
 * an {@code auto}-dial MEDIUM write still gated as {@code typed_confirm}. This pins the pass-through.
 */
final class ToolIteratingShapeRunnerTest {

  @Test
  @DisplayName("parseRequest carries autonomyLevel + conversationId through to the AgentRequest")
  void parseRequestCarriesAutonomyLevel() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hi")),
                "maxIterations", 3,
                "conversationId", "conv-1",
                "autonomyLevel", "auto"));
    assertEquals("auto", r.autonomyLevel());
    assertEquals("conv-1", r.conversationId());
  }

  @Test
  @DisplayName("parseRequest leaves autonomyLevel null when absent (backend defaults to ASSIST)")
  void parseRequestDefaultsAutonomyLevelWhenAbsent() {
    AgentRequest r =
        ToolIteratingShapeRunner.parseRequest(
            Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));
    assertNull(r.autonomyLevel());
  }
}
