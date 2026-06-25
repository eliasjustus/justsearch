package io.justsearch.agent.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventSealedTest {

  @Test
  void textChunkIsRecord() {
    var chunk = new AgentEvent.TextChunk("hello");
    assertEquals("hello", chunk.text());
    assertInstanceOf(AgentEvent.class, chunk);
  }

  @Test
  void toolCallProposedCarriesData() {
    var call = new ToolCallRequest("c1", "search", "{}");
    var event = new AgentEvent.ToolCallProposed(call, RiskTier.LOW);
    assertEquals("c1", event.call().id());
    assertEquals(RiskTier.LOW, event.risk());
  }

  @Test
  void operationResultFactories() {
    var success = OperationResult.success("done");
    assertTrue(success.success());
    assertEquals("done", success.message());
    assertTrue(success.executionId().isEmpty());

    var successWithId = OperationResult.success("done", "exec-1");
    assertEquals("exec-1", successWithId.executionId().orElse(null));

    var failure = OperationResult.failure("oops");
    assertFalse(failure.success());
    assertEquals("oops", failure.message());
  }

  // riskToLegacyName shim deleted in tempdoc 429 §F.21 C2 — RiskTier vocabulary is
  // now the wire format directly. SSE projections emit risk: "low"|"medium"|"high".
  // RiskTier import is preserved for ToolCallProposed/ToolCallPendingApproval coverage.

  @Test
  void agentRequestValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRequest(java.util.List.of(), java.util.List.of(), 0));

    assertThrows(
        NullPointerException.class, () -> new AgentRequest(null, java.util.List.of(), 1));
  }

  @Test
  void agentRequestSingleTurn() {
    var req =
        AgentRequest.singleTurn(java.util.List.of(Map.of("role", "user", "content", "hi")));
    assertEquals(1, req.maxIterations());
    assertTrue(req.selectedToolNames().isEmpty());
    assertTrue(req.agentProfiles().isEmpty());
    assertNull(req.initialAgentId());
  }

  @Test
  void agentRequestThreeArgConstructorIsBackwardCompatible() {
    var req = new AgentRequest(java.util.List.of(), java.util.List.of(), 1);
    assertTrue(req.agentProfiles().isEmpty());
    assertNull(req.initialAgentId());
  }

  @Test
  void agentProfileFromMap() {
    var m = new java.util.HashMap<String, Object>();
    m.put("agentId", "planner");
    m.put("name", "Planner");
    m.put("systemPrompt", "You plan.");
    m.put("toolSubset", java.util.List.of("search"));

    var profile = AgentProfile.fromMap(m);
    assertEquals("planner", profile.agentId());
    assertEquals("Planner", profile.name());
    assertEquals("You plan.", profile.systemPrompt());
    assertEquals(java.util.List.of("search"), profile.toolSubset());
  }

  @Test
  void agentProfileFromMapNullSystemPrompt() {
    var m = new java.util.HashMap<String, Object>();
    m.put("agentId", "executor");
    m.put("name", "Executor");
    // systemPrompt absent — must be null, not the string "null"
    var profile = AgentProfile.fromMap(m);
    assertNull(profile.systemPrompt());
    assertTrue(profile.toolSubset().isEmpty());
  }

  @Test
  void agentProfileFromMapMissingRequiredFieldThrows() {
    // Absent agentId must throw NullPointerException via the compact constructor,
    // not silently produce agentId="null" from String.valueOf(null).
    assertThrows(NullPointerException.class, () -> AgentProfile.fromMap(java.util.Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> AgentProfile.fromMap(java.util.Map.of("agentId", "x"))); // name absent
  }

  @Test
  void handoffEventsCarryFields() {
    var proposed = new AgentEvent.HandoffProposed("planner", "executor", "need execution");
    assertEquals("planner", proposed.fromAgentId());
    assertEquals("executor", proposed.toAgentId());
    assertEquals("need execution", proposed.reason());

    var executed = new AgentEvent.HandoffExecuted("planner", "executor");
    assertEquals("planner", executed.fromAgentId());
    assertEquals("executor", executed.toAgentId());
  }

  @Test
  void allEventTypesAreSealed() {
    // Verify all permitted subtypes exist and are records
    Class<?>[] permitted = AgentEvent.class.getPermittedSubclasses();
    assertNotNull(permitted);
    // Brittle tripwire — bumped whenever a permitted subtype is added (history: 15→16 during the
    // 516-foundation merge). 16→17 for tempdoc 550's ToolBatchProposed (the agent tool-call batch
    // event); 17→18 for DirectiveAcknowledged (added on main without bumping this counter — the
    // skew was reconciled when landing the 569 §14–§19 line). 18→19 for tempdoc 577 §2.12 Move 2's
    // BudgetGatePending (the held budget gate). 19→21 for tempdoc 577 §2.14 Root II's ContextGatePending
    // + ContextCompacted (the context-pressure held gate + the compaction event). 21→22 for tempdoc
    // 585 §D Phase 2 (C4)'s StateSnapshot (the late-attacher state primer). The loop below is the real
    // invariant (every permitted subtype is a record).
    assertEquals(22, permitted.length);
    for (Class<?> subclass : permitted) {
      assertTrue(subclass.isRecord(), subclass.getSimpleName() + " should be a record");
    }
  }
}
