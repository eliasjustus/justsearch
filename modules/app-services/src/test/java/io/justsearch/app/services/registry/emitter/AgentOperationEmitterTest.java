package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentOperationEmitter} per tempdoc 429 §6 + §F.3 closure.
 *
 * <p>Verifies the OpenAI function-calling shape: {@code [{"type":"function","function":{...}}]}
 * with name + description + parameters per entry.
 */
final class AgentOperationEmitterTest {

  private static Operation makeOp(String id, String description, Set<ExecutorTag> executors) {
    return makeOp(id, description, executors, Audience.USER);
  }

  /**
   * Per slice 484 §3.1 closure (C1, 2026-05-08): the {@code AgentOperationEmitter} now
   * filters by {@link Audience} as well as {@link ExecutorTag}. Tests that need to
   * exercise the audience filter use this overload; tests pre-dating the filter use the
   * 3-arg overload above which defaults to {@link Audience#USER} (the most permissive
   * agent-invocable audience under the new rule).
   */
  private static Operation makeOp(
      String id, String description, Set<ExecutorTag> executors, Audience audience) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey(description)),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        executors,
        audience);
  }

  @Test
  void emitProducesOpenAIFunctionShape() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp("core.tool-a", "test.tool-a.desc", Set.of(ExecutorTag.AGENT)),
                makeOp("core.tool-b", "test.tool-b.desc", Set.of(ExecutorTag.AGENT))));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(2, tools.size());
    Map<String, Object> first = tools.get(0);
    assertEquals("function", first.get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> function = (Map<String, Object>) first.get("function");
    assertEquals("core_tool_a", function.get("name"));
    assertEquals("test.tool-a.desc", function.get("description"));
    assertTrue(function.containsKey("parameters"));
  }

  @Test
  void emitFiltersByAgentExecutor() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp("core.agent-only", "x", Set.of(ExecutorTag.AGENT)),
                makeOp("core.ui-only", "y", Set.of(ExecutorTag.UI))));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(1, tools.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
    assertEquals("core_agent_only", function.get("name"));
  }

  // Slice 484 §3.1 closure (C1, 2026-05-08) — audience filter regression coverage.
  // Verified hazard motivating the filter: core.bulk-reindex ships
  // {executors = {UI, AGENT}, audience = OPERATOR}; without the filter the LLM can
  // invoke admin operations via ExecutorTag.AGENT membership alone. AGENT_INVOCABLE_AUDIENCES
  // = {USER, AGENT} restricts the tool list to user-facing + agent-internal operations.

  @Test
  void emitFiltersOutOperatorAudienceEvenWithAgentExecutor() {
    // Concrete reproduction of the pre-C1 hazard: an Operation with
    // executors = {UI, AGENT} + audience = OPERATOR was previously emitted to the
    // LLM. After C1 it must be excluded.
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp(
                    "core.bulk-reindex-like",
                    "admin migration",
                    Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
                    Audience.OPERATOR),
                makeOp(
                    "core.user-search",
                    "user search",
                    Set.of(ExecutorTag.AGENT),
                    Audience.USER)));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    // OPERATOR-audience excluded; USER-audience present.
    assertEquals(1, tools.size(), "OPERATOR audience must not appear in agent tool list");
    @SuppressWarnings("unchecked")
    Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
    assertEquals("core_user_search", function.get("name"));
  }

  @Test
  void emitIncludesAgentAudience() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp(
                    "core.agent-internal",
                    "agent-only tool",
                    Set.of(ExecutorTag.AGENT),
                    Audience.AGENT)));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(1, tools.size(), "AGENT-audience Operations must be invocable by the agent");
  }

  @Test
  void emitIncludesUserAudience() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp(
                    "core.user-action",
                    "user-facing op",
                    Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
                    Audience.USER)));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(
        1,
        tools.size(),
        "USER-audience Operations stay invocable so the agent can act on the user's behalf");
  }

  @Test
  void emitFiltersOutDeveloperAudience() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp(
                    "core.debug-tool",
                    "debug surface",
                    Set.of(ExecutorTag.AGENT),
                    Audience.DEVELOPER)));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(
        0,
        tools.size(),
        "DEVELOPER audience must be excluded from agent tool list per allow-list policy");
  }

  @Test
  void agentInvocableAudiencesAreAllowList() {
    // Allow-list discipline (per AGENT_INVOCABLE_AUDIENCES doc): future audience values
    // are denied by default. This test pins the current allow-list as USER + AGENT only.
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp("core.user-op", "u", Set.of(ExecutorTag.AGENT), Audience.USER),
                makeOp("core.agent-op", "a", Set.of(ExecutorTag.AGENT), Audience.AGENT),
                makeOp("core.operator-op", "o", Set.of(ExecutorTag.AGENT), Audience.OPERATOR),
                makeOp("core.developer-op", "d", Set.of(ExecutorTag.AGENT), Audience.DEVELOPER)));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(
        2, tools.size(), "Only USER + AGENT audiences pass the allow-list");
  }

  @Test
  void emitWithSelectedNamesFiltersExplicitly() {
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOp("core.x", "x", Set.of(ExecutorTag.AGENT)),
                makeOp("core.y", "y", Set.of(ExecutorTag.AGENT)),
                makeOp("core.z", "z", Set.of(ExecutorTag.AGENT))));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of("core_y"));

    assertEquals(1, tools.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
    assertEquals("core_y", function.get("name"));
  }

  // === Tempdoc 550 Preview face: availability filtering ===
  // The emitter consults the same Preview evaluation (AvailabilityEvaluator) the preview
  // endpoint uses, so the model is not offered a tool it provably cannot run right now.

  private static Operation makeOpWithCondition(String id, String conditionId) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey(id + ".desc")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        new OperationAvailability(
            Optional.of(new AvailabilityExpression.ConditionMatches(conditionId)),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT),
        Audience.USER);
  }

  @Test
  void emitOmitsOperationWhoseAvailabilityConditionIsNotFiring() {
    // The conditioned op declares ConditionMatches("backend.ready"); the probe reports it is
    // NOT firing → the op must be filtered out. The unconditioned op stays.
    AgentOperationEmitter emitter =
        new AgentOperationEmitter().withAvailabilityProbe(conditionId -> false);
    OperationCatalog catalog =
        OperationCatalog.of(
            "core",
            List.of(
                makeOpWithCondition("core.needs-backend", "backend.ready"),
                makeOp("core.always-on", "x", Set.of(ExecutorTag.AGENT))));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(1, tools.size(), "Operation with a non-firing availability must be omitted");
    @SuppressWarnings("unchecked")
    Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
    assertEquals("core_always_on", function.get("name"));
  }

  @Test
  void emitIncludesOperationWhoseAvailabilityConditionIsFiring() {
    AgentOperationEmitter emitter =
        new AgentOperationEmitter()
            .withAvailabilityProbe(conditionId -> "backend.ready".equals(conditionId));
    OperationCatalog catalog =
        OperationCatalog.of(
            "core", List.of(makeOpWithCondition("core.needs-backend", "backend.ready")));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(1, tools.size(), "Operation with a firing availability must be included");
  }

  @Test
  void emitWithoutProbePreservesFullListRegardlessOfAvailability() {
    // Null probe (legacy/test wiring) → no availability filtering. The conditioned op is
    // emitted even though no probe could confirm its condition fires.
    AgentOperationEmitter emitter = new AgentOperationEmitter();
    OperationCatalog catalog =
        OperationCatalog.of(
            "core", List.of(makeOpWithCondition("core.needs-backend", "backend.ready")));

    List<Map<String, Object>> tools = emitter.emit(catalog, List.of());

    assertEquals(
        1, tools.size(), "Without an availability probe, the full tool list is preserved");
  }

  /** Builds an op gated on Not(ConditionMatches(conditionId)) — the F3 search-index pattern. */
  private static Operation makeOpUnlessCondition(String id, String conditionId) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey(id + ".desc")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches(conditionId))),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT),
        Audience.USER);
  }

  @Test
  void emitOmitsNotGatedOpWhenTheNegatedConditionIsFiring() {
    // Tempdoc 550 F3: the core.search-index pattern — Not(ConditionMatches("index.unavailable")).
    // When "index.unavailable" IS firing (index not serving), the negation is false → the op is
    // hidden from the agent (don't offer search when the index can't serve it).
    AgentOperationEmitter emitter =
        new AgentOperationEmitter()
            .withAvailabilityProbe(conditionId -> "index.unavailable".equals(conditionId));
    OperationCatalog catalog =
        OperationCatalog.of("core", List.of(makeOpUnlessCondition("core.search-like", "index.unavailable")));

    assertEquals(
        0,
        emitter.emit(catalog, List.of()).size(),
        "Not(firing-condition) evaluates false → the op is omitted while the index is unavailable");
  }

  @Test
  void emitIncludesNotGatedOpWhenTheNegatedConditionIsNotFiring() {
    // The ready state: "index.unavailable" is absent (not firing), so Not(...) is true → shown.
    AgentOperationEmitter emitter =
        new AgentOperationEmitter().withAvailabilityProbe(conditionId -> false);
    OperationCatalog catalog =
        OperationCatalog.of("core", List.of(makeOpUnlessCondition("core.search-like", "index.unavailable")));

    assertEquals(
        1,
        emitter.emit(catalog, List.of()).size(),
        "Not(absent-condition) evaluates true → the op is offered when the index is serving");
  }
}
