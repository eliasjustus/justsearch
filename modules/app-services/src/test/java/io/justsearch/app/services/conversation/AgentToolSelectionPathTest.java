/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import io.justsearch.app.services.registry.preview.CapabilityAvailability;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 868 §D.4 — the {@code POST /api/chat/agent} tool-selection path, end to end over the
 * REAL agent-tool catalog rather than hand-written stub operations.
 *
 * <p>The live report was {@code NO_TOOLS} for a request whose {@code tools} selection named
 * {@code core_search_index} (wire form) or {@code core.search-index} (ref form). Every existing
 * test covered one half of that path — {@code ToolIteratingShapeRunnerTest} binds the body,
 * {@code AgentOperationEmitterTest} filters stub ops — so nothing pinned the two halves agreeing
 * on the identifier space over the operations that actually ship. This closes the seam: the body
 * a client posts goes through the same {@code parseRequest} the runner uses, into the same emitter
 * production wires, over the same catalog the loop is given.
 */
final class AgentToolSelectionPathTest {

  /** The composition the head performs before handing the catalog to the agent loop. */
  private static OperationCatalog productionShapedCatalog() {
    return CapabilityAvailability.withCapabilityDerivedAvailability(
        new AgentToolsOperationCatalog());
  }

  /** The emitter production wires (AgentLoopWiring), with nothing asserted as failing. */
  private static AgentOperationEmitter emitterWithHealthyIndex() {
    return new AgentOperationEmitter(key -> key).withAvailabilityProbe(conditionId -> false);
  }

  private static Set<String> offeredFor(Object toolsSelection) {
    Map<String, Object> body =
        Map.of(
            "messages",
            List.of(Map.of("role", "user", "content", "find the release notes")),
            "tools",
            toolsSelection);
    AgentRequest request = ToolIteratingShapeRunner.parseRequest(body);
    return emitterWithHealthyIndex()
        .offeredWireNames(productionShapedCatalog(), request.selectedToolNames());
  }

  @Test
  @DisplayName("a tools selection by WIRE name offers exactly that tool")
  void selectionByWireNameSurvives() {
    assertEquals(Set.of("core_search_index"), offeredFor(List.of("core_search_index")));
  }

  @Test
  @DisplayName("a tools selection by OperationRef id offers exactly that tool")
  void selectionByRefIdSurvives() {
    assertEquals(Set.of("core_search_index"), offeredFor(List.of("core.search-index")));
  }

  @Test
  @DisplayName("mixed identifier forms in one selection all resolve")
  void mixedIdentifierFormsResolve() {
    assertEquals(
        Set.of("core_search_index", "core_read_document"),
        offeredFor(List.of("core_search_index", "core.read-document")));
  }

  @Test
  @DisplayName("an unknown selection still offers nothing — the NO_TOOLS guard keeps firing")
  void unknownSelectionOffersNothing() {
    assertTrue(offeredFor(List.of("core_not_a_tool")).isEmpty());
  }

  @Test
  @DisplayName("no selection offers the whole agent-tool catalog")
  void absentSelectionOffersEverything() {
    Set<String> offered = offeredFor(List.of());
    assertEquals(
        productionShapedCatalog().definitions().size(),
        offered.size(),
        "an empty selection restricts nothing: " + offered);
    assertTrue(offered.contains("core_search_index"), offered.toString());
  }
}
