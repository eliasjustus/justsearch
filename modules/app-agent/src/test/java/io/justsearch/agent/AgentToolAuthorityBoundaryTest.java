/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.AgentLoopServiceTest.ScriptedAiService;
import io.justsearch.agent.AgentLoopServiceTest.ScriptedResponse;
import io.justsearch.agent.AgentLoopServiceTest.StubTool;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 875 finding 3 / Move 3 — <strong>offering IS authorization</strong>.
 *
 * <p>Before this change {@code AgentStepRunner} resolved a model-named tool through
 * {@code OperationCatalog.resolveByWireName}, which iterates the RAW {@code definitions()} and
 * applies none of the filters the emitter applied when it decided what to offer. A tool the emitter
 * deliberately withheld — for audience, for availability, or because it was outside the run's
 * {@code selectedToolNames()} — was still dispatchable. Each test below names an operation that IS
 * in the catalog but was NOT emitted, and pins that it is refused with a typed
 * {@link AgentEvent.ToolCallRejected}, never reaches its handler, and does not kill the run.
 *
 * <p>The emitter used here is not the trivial one in {@code AgentLoopServiceTest}: it mirrors
 * {@code AgentOperationEmitter}'s actual filter chain (executor tag → audience allow-list →
 * availability expression → selection). Without that, every test in this file would pass
 * vacuously, because the withheld tool would never have been withheld.
 */
class AgentToolAuthorityBoundaryTest {

  // ---------------------------------------------------------------------------
  // 1. Withheld by AUDIENCE
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an OPERATOR-audience AGENT-tagged operation is refused, not dispatched")
  void audienceWithheldTool_isRejectedNotDispatched() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "admin ran");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Both carry ExecutorTag.AGENT; only the audience differs. `core.admin-op` is exactly the
    // shape of a hidden-but-dispatchable MCP contribution (875 §B row 3c).
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());

    var events =
        runNamingTool(catalog, "core_admin_op", List.of(), alwaysFiring(), admin, search);

    assertRejectedNotDispatched(events, admin, "core_admin_op");
  }

  // ---------------------------------------------------------------------------
  // 2. Withheld by AVAILABILITY
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an operation whose availability expression is false is refused, not dispatched")
  void availabilityWithheldTool_isRejectedNotDispatched() {
    var readDoc = new StubTool("read_document", RiskTier.LOW, "doc text");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Mirrors core.read-document being withheld while `index.unavailable` fires: the operation
    // stays in the catalog, so resolveByWireName still finds it.
    var catalog =
        catalogOf(
            requiring(readDoc.toOperation(), "index.available"), search.toOperation());

    var events =
        runNamingTool(
            catalog,
            "core_read_document",
            List.of(),
            // "index.available" is NOT firing → the expression evaluates false → withheld.
            conditionId -> false,
            readDoc,
            search);

    assertRejectedNotDispatched(events, readDoc, "core_read_document");
  }

  // ---------------------------------------------------------------------------
  // 3. Withheld by SELECTION
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("an operation outside request.selectedToolNames() is refused, not dispatched")
  void selectionWithheldTool_isRejectedNotDispatched() {
    var fileOps = new StubTool("file_operations", RiskTier.LOW, "moved");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    var catalog = catalogOf(fileOps.toOperation(), search.toOperation());

    var events =
        runNamingTool(
            catalog,
            "core_file_operations",
            // The run authorized search only.
            List.of("core_search_index"),
            alwaysFiring(),
            fileOps,
            search);

    assertRejectedNotDispatched(events, fileOps, "core_file_operations");
  }

  // ---------------------------------------------------------------------------
  // 4. CONTROL — an offered tool still dispatches
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("control: an offered tool still resolves, is approved, and executes")
  void offeredTool_stillDispatches() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "admin ran");
    var search = new StubTool("search_index", RiskTier.LOW, "hits");
    // Same catalog as the audience test — only the tool the model names changes. So a green here
    // proves the three rejections above are the filter biting, not the harness failing to dispatch
    // anything at all.
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());

    var events =
        runNamingTool(catalog, "core_search_index", List.of(), alwaysFiring(), admin, search);

    assertNull(
        firstOfType(events, AgentEvent.ToolCallRejected.class),
        "an offered tool must not be rejected");
    assertEquals(1, search.callCount.get(), "the offered tool's handler should have run");
    assertEquals(0, admin.callCount.get(), "the un-named tool must not run");
    var completed = firstOfType(events, AgentEvent.ToolExecutionCompleted.class);
    assertNotNull(completed, "the offered tool should reach execution");
    assertTrue(completed.result().success(), "the offered tool should succeed");
    assertNotNull(
        firstOfType(events, AgentEvent.AgentDone.class), "the run should still complete");
  }

  // ---------------------------------------------------------------------------
  // 5. offeredWireNames is a projection of emit, not a second list
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("offeredWireNames() returns exactly the function names emit() produced")
  void offeredWireNames_projectsEmit() {
    var admin = new StubTool("admin_op", RiskTier.LOW, "x");
    var search = new StubTool("search_index", RiskTier.LOW, "y");
    var catalog =
        catalogOf(withAudience(admin.toOperation(), Audience.OPERATOR), search.toOperation());
    AgentToolEmitter emitter = filteringEmitter(alwaysFiring());

    Set<String> offered = emitter.offeredWireNames(catalog, List.of());

    assertEquals(
        emitter.emit(catalog, List.of()).stream()
            .map(AgentToolAuthorityBoundaryTest::functionName)
            .toList(),
        List.copyOf(offered),
        "the offered set must be derived from emit(), in emission order");
    assertEquals(Set.of("core_search_index"), offered);
  }

  @Test
  @DisplayName("offeredWireNames() skips malformed entries instead of throwing")
  void offeredWireNames_skipsMalformedEntries() {
    AgentToolEmitter malformed =
        (catalog, selected) -> {
          List<Map<String, Object>> out = new ArrayList<>();
          out.add(Map.<String, Object>of("type", "function")); // no `function` object
          out.add(Map.<String, Object>of("type", "function", "function", "not-a-map"));
          out.add(
              Map.<String, Object>of(
                  "type", "function", "function", Map.of("description", "no name")));
          out.add(
              Map.<String, Object>of("type", "function", "function", Map.of("name", "core_ok")));
          return out;
        };

    assertEquals(Set.of("core_ok"), malformed.offeredWireNames(catalogOf(), List.of()));
  }

  // ===========================================================================
  // Harness
  // ===========================================================================

  /**
   * Drives one run in which the model's single tool call names {@code toolName}, then answers with
   * text. Returns every emitted event.
   */
  private static List<AgentEvent> runNamingTool(
      OperationCatalog catalog,
      String toolName,
      List<String> selectedToolNames,
      Predicate<String> conditionFiring,
      StubTool... handlers) {
    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("call-1", toolName, "{}"),
            ScriptedResponse.textOnly("done"));
    var service =
        AgentLoopServiceTest.observed(
            new AgentLoopService(
                ai, catalog, AgentLoopServiceTest.stubExecutor(handlers),
                filteringEmitter(conditionFiring), null, null));
    var request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", "go")), selectedToolNames, 3);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add);
    return events;
  }

  private static void assertRejectedNotDispatched(
      List<AgentEvent> events, StubTool withheld, String wireName) {
    var rejected = firstOfType(events, AgentEvent.ToolCallRejected.class);
    assertNotNull(rejected, "an un-offered tool call must emit ToolCallRejected");
    assertEquals("call-1", rejected.callId(), "the rejection must name the model's call id");
    assertTrue(
        rejected.reason().contains(wireName)
            && rejected.reason().contains("not available in this session"),
        "the rejection reason should name the tool and say it is unavailable; actual: "
            + rejected.reason());

    assertEquals(
        0, withheld.callCount.get(), "the withheld operation's handler must never be invoked");
    assertNull(
        firstOfType(events, AgentEvent.ToolExecutionStarted.class),
        "a refused tool call must not reach execution");

    // Not terminal: the run continues and finishes normally — a model mis-step is not fatal.
    assertNull(
        firstOfType(events, AgentEvent.AgentError.class),
        "an un-offered tool call must not error the run");
    assertNotNull(
        firstOfType(events, AgentEvent.AgentDone.class),
        "the run should complete after the refusal");
    assertFalse(
        events.stream()
            .anyMatch(e -> e instanceof AgentEvent.ToolCallApproved),
        "a refused tool call must never pass the safety gate");
  }

  /**
   * A test emitter mirroring {@code AgentOperationEmitter}'s filter chain — executor tag, audience
   * allow-list, availability expression, selection — because a trivial emitter would make the
   * withheld-tool tests vacuous. (app-agent tests deliberately do not depend on app-services; see
   * {@code AgentLoopServiceTest.stubEmitter}.)
   */
  private static AgentToolEmitter filteringEmitter(Predicate<String> conditionFiring) {
    Set<Audience> allowed = EnumSet.of(Audience.USER, Audience.AGENT);
    return (catalog, selectedNames) -> {
      var mapper = new tools.jackson.databind.ObjectMapper();
      List<Map<String, Object>> result = new ArrayList<>();
      for (Operation op : catalog.definitions()) {
        if (!op.executors().contains(ExecutorTag.AGENT)) continue;
        if (!allowed.contains(op.audience())) continue;
        if (!evaluate(op.availability().expression().orElse(null), conditionFiring)) continue;
        String wire = OperationCatalog.toWireName(op.id());
        if (selectedNames != null
            && !selectedNames.isEmpty()
            && !selectedNames.contains(wire)
            && !selectedNames.contains(op.id().value())) {
          continue;
        }
        try {
          var function = mapper.createObjectNode();
          function.put("name", wire);
          function.put("description", op.presentation().descriptionKey().value());
          function.set("parameters", mapper.readTree(op.intf().inputs()));
          var toolObj = mapper.createObjectNode();
          toolObj.put("type", "function");
          toolObj.set("function", function);
          @SuppressWarnings("unchecked")
          Map<String, Object> entry = mapper.convertValue(toolObj, Map.class);
          result.add(new java.util.LinkedHashMap<>(entry));
        } catch (Exception e) {
          throw new IllegalStateException("Failed to emit " + op.id(), e);
        }
      }
      return List.copyOf(result);
    };
  }

  /** Minimal stand-in for {@code AvailabilityEvaluator} (which lives in app-services). */
  private static boolean evaluate(AvailabilityExpression expr, Predicate<String> firing) {
    if (expr == null) {
      return true;
    }
    return switch (expr) {
      case AvailabilityExpression.Always ignored -> true;
      case AvailabilityExpression.ConditionMatches cm -> firing.test(cm.conditionId());
      case AvailabilityExpression.AllOf allOf ->
          allOf.children().stream().allMatch(child -> evaluate(child, firing));
      case AvailabilityExpression.AnyOf anyOf ->
          anyOf.children().stream().anyMatch(child -> evaluate(child, firing));
      case AvailabilityExpression.Not not -> !evaluate(not.child(), firing);
    };
  }

  private static Predicate<String> alwaysFiring() {
    return conditionId -> true;
  }

  private static Operation withAudience(Operation op, Audience audience) {
    return new Operation(
        op.id(),
        op.presentation(),
        op.intf(),
        op.policy(),
        op.availability(),
        op.lineage(),
        op.binding(),
        op.provenance(),
        op.executors(),
        audience,
        op.consumers());
  }

  private static Operation requiring(Operation op, String conditionId) {
    return op.withAvailability(
        new OperationAvailability(
            Optional.of(new AvailabilityExpression.ConditionMatches(conditionId)),
            Optional.empty()));
  }

  private static OperationCatalog catalogOf(Operation... ops) {
    return OperationCatalog.of("core", List.of(ops));
  }

  private static String functionName(Map<String, Object> tool) {
    return String.valueOf(((Map<?, ?>) tool.get("function")).get("name"));
  }

  private static <T extends AgentEvent> T firstOfType(List<AgentEvent> events, Class<T> type) {
    for (AgentEvent e : events) {
      if (type.isInstance(e)) {
        return type.cast(e);
      }
    }
    return null;
  }
}
