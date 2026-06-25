package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.services.conversation.AgentRunShape;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 508-followup §β1 — verify that {@link AgentController#resolveShapeRef} honors
 * {@code body.shapeId} when present, defaults to {@link AgentRunShape#ID} when absent or
 * blank, and rejects values outside the whitelist with a structured 400 signal.
 */
final class AgentControllerShapeDispatchTest {

  @Test
  void missingShapeIdDefaultsToAgentRunShape() {
    assertEquals(AgentRunShape.ID, AgentController.resolveShapeRef(Map.of()));
  }

  @Test
  void nullBodyDefaultsToAgentRunShape() {
    assertEquals(AgentRunShape.ID, AgentController.resolveShapeRef(null));
  }

  @Test
  void blankShapeIdDefaultsToAgentRunShape() {
    Map<String, Object> body = new HashMap<>();
    body.put("shapeId", "   ");
    assertEquals(AgentRunShape.ID, AgentController.resolveShapeRef(body));
  }

  @Test
  void knownShapeIdReturnsMatchingRef() {
    Map<String, Object> body = Map.of("shapeId", AgentRunShape.ID.value());
    assertEquals(AgentRunShape.ID, AgentController.resolveShapeRef(body));
  }

  @Test
  void unknownShapeIdThrowsUnknownShapeException() {
    Map<String, Object> body = Map.of("shapeId", "core.does-not-exist");
    var ex =
        assertThrows(
            AgentController.UnknownShapeException.class,
            () -> AgentController.resolveShapeRef(body));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("core.does-not-exist"),
        "error message should include the offending shapeId for debugging");
  }

  @Test
  void nullShapeIdValueDefaultsToAgentRunShape() {
    Map<String, Object> body = new HashMap<>();
    body.put("shapeId", null);
    assertEquals(AgentRunShape.ID, AgentController.resolveShapeRef(body));
  }
}
