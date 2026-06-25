package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.TrustGateDeniedException;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.app.observability.advisory.OperationCompletionEvent;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OperationExecutorImpl} per tempdoc 429 §A.5 + §B.D + §F.7 closure.
 *
 * <p>Verifies the three-branch dispatch (CORE / TRUSTED_PLUGIN equivalent / UNTRUSTED
 * throws) and the undo-gating discipline per §E.3 (executor checks
 * {@code op.policy().undoSupported()} before delegating).
 */
final class OperationExecutorImplTest {

  @Test
  void coreProvenanceDispatchesToHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test");
    handlers.register(id, args -> OperationResult.success("invoked"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.CORE, false);
    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
    assertEquals("invoked", result.message());
  }

  @Test
  void trustedPluginProvenanceDispatchesToHandlerSameAsCore() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.plugin");
    handlers.register(id, args -> OperationResult.success("plugin-invoked"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
    assertEquals("plugin-invoked", result.message());
  }

  @Test
  void untrustedPluginThrowsUnsupportedOperation() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.untrusted");
    handlers.register(id, args -> OperationResult.success("should-not-execute"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.UNTRUSTED_PLUGIN, false);
    UnsupportedOperationException ex =
        assertThrows(UnsupportedOperationException.class, () -> executor.dispatch(op, "{}"));
    assertTrue(
        ex.getMessage().contains("V1.5"),
        "UNTRUSTED_PLUGIN error should reference V1.5 sandbox: " + ex.getMessage());
  }

  @Test
  void undoFailsFastWhenPolicyDoesNotSupportIt() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.no-undo");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args) {
            return OperationResult.success("ok");
          }

          @Override
          public OperationResult undo(String executionId) {
            handlerInvoked[0] = true;
            return OperationResult.success("should-not-be-called");
          }
        });
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.CORE, false); // undoSupported = false
    OperationResult result = executor.undo(op, "exec-123");

    assertFalse(result.success());
    assertTrue(
        result.message().contains("Undo not supported"),
        "Failure message should explain undo unsupported: " + result.message());
    assertFalse(handlerInvoked[0], "Handler.undo must NOT be called when policy disallows");
  }

  @Test
  void undoDelegatesToHandlerWhenPolicySupportsIt() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.with-undo");
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args) {
            return OperationResult.success("ok", "exec-456");
          }

          @Override
          public OperationResult undo(String executionId) {
            return OperationResult.success("undone " + executionId);
          }
        });
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.CORE, true); // undoSupported = true
    OperationResult result = executor.undo(op, "exec-456");

    assertTrue(result.success());
    assertEquals("undone exec-456", result.message());
  }

  @Test
  void successfulDispatchEmitsHistoryEntryWithSuccessOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-success");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}");

    assertTrue(result.success());
    assertEquals(1, emitted.size(), "exactly one history entry should be emitted per dispatch");
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(id, entry.operationId());
    assertEquals(OperationOutcome.SUCCESS, entry.outcome());
    assertTrue(
        !entry.endTime().isBefore(entry.startTime()),
        "endTime must be at or after startTime");
  }

  @Test
  void failedDispatchEmitsHistoryEntryWithFailureOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-failure");
    handlers.register(id, args -> OperationResult.failure("nope"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}");

    assertFalse(result.success());
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  @Test
  void thrownDispatchStillEmitsFailureHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-throw");
    handlers.register(
        id,
        args -> {
          throw new RuntimeException("boom");
        });
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    assertThrows(
        RuntimeException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}"));
    assertEquals(1, emitted.size(), "history entry must still be emitted on thrown failure");
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  @Test
  void historyEmitterFailureDoesNotBreakDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-emit-throws");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers,
            entry -> {
              throw new RuntimeException("emitter broke");
            },
            Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}");

    assertTrue(result.success(), "emitter failure must not propagate to dispatch caller");
  }

  // -------------------- Slice 3a-2-c Phase C: schema validation --------------------

  /**
   * Required-arg missing → dispatcher returns BAD_REQUEST without ever calling the
   * handler. Catches the typo / missing-arg case centrally so handlers can simplify
   * their per-arg parsing.
   */
  @Test
  void schemaValidationRejectsMissingRequiredArg() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.requires-path");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        args -> {
          handlerInvoked[0] = true;
          return OperationResult.success("should not reach here");
        });
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
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
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{}");

    assertFalse(result.success(), "missing required arg must fail validation");
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
    assertFalse(
        handlerInvoked[0],
        "handler must NOT be invoked when args fail schema validation");
  }

  /**
   * Valid args → dispatcher passes through to the handler unchanged. Schema
   * enforcement is gating, not transforming.
   */
  @Test
  void schemaValidationPassesValidArgsThrough() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.requires-path-ok");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        args -> {
          handlerInvoked[0] = true;
          return OperationResult.success("got args: " + args);
        });
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
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
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{\"path\":\"/some/path\"}");

    assertTrue(result.success());
    assertTrue(handlerInvoked[0], "handler must be invoked when args validate");
  }

  /**
   * Wrong-type arg (path=number when schema expects string) → BAD_REQUEST. Validates
   * that the schema's type constraint is enforced, not just required-key presence.
   */
  @Test
  void schemaValidationRejectsWrongType() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.wrong-type");
    handlers.register(id, args -> OperationResult.success("should not reach"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
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
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{\"path\":42}");

    assertFalse(result.success(), "wrong-type arg must fail validation");
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
  }

  /**
   * Empty schema (Interface.of with `{"type":"object"}`) → no validation; handler
   * receives whatever args were sent. This is the existing behavior for
   * no-arg Operations like restart-worker.
   */
  @Test
  void schemaValidationSkippedForEmptySchema() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.empty-schema");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false), "{\"anything\":\"goes\"}");

    assertTrue(result.success());
  }

  /**
   * Validation failure emits a FAILURE history entry — consistent with the
   * uncaught-exception path which also emits FAILURE before propagating.
   * Validation rejection counts as a real dispatch attempt.
   */
  @Test
  void schemaValidationFailureEmitsFailureHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.validation-history");
    handlers.register(id, args -> OperationResult.success("unreached"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
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
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{}");

    assertFalse(result.success());
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  // -------------------- Slice 490 §4.B: InvocationProvenance retrofit --------------------

  /**
   * 3-arg dispatch threads typed provenance onto the emitted history entry. Slice 490
   * §4.B retrofit — the typed answer to "who triggered this?" is recorded on every
   * dispatch that supplies provenance.
   */
  @Test
  void threeArgDispatchThreadsProvenanceOntoHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-success");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("user:alice"),
            java.time.Instant.parse("2026-05-12T08:30:00Z"));
    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", provenance);

    assertTrue(result.success());
    assertEquals(1, emitted.size());
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(provenance, entry.provenance());
    assertEquals("head", entry.actor());
  }

  /**
   * 2-arg legacy dispatch defaults to system-internal provenance and derives an actor
   * string from the executor enum. Existing callers compile unchanged and observe
   * provenance.isPresent() == true with TransportTag.SYSTEM_INTERNAL.
   */
  @Test
  void legacyTwoArgDispatchDefaultsToSystemInternalProvenance() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-default");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}");

    assertTrue(result.success());
    assertEquals(1, emitted.size());
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(TransportTag.SYSTEM_INTERNAL, entry.provenance().transport());
    assertEquals("head", entry.actor());
  }

  /** Provenance is also threaded onto FAILURE entries when the handler throws. */
  @Test
  void thrownDispatchStillCarriesProvenanceOnFailureEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-throw");
    handlers.register(
        id,
        args -> {
          throw new RuntimeException("boom");
        });
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, emitted::add, Clock.systemUTC());

    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.AGENT_LOOP,
            ExecutorTag.AGENT,
            Optional.empty(),
            java.time.Instant.parse("2026-05-12T08:30:00Z"));

    assertThrows(
        RuntimeException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", provenance));
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
    assertEquals(provenance, emitted.get(0).provenance());
  }

  // -------------- Slice 490 follow-up: provenance integrity validation --------------

  @Test
  void coreTierDispatchAcceptsUserFacingTransports() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-core");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.CORE, false);
    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON, ExecutorTag.UI, Optional.empty(), Instant.now());

    OperationResult result = executor.dispatch(op, "{}", provenance);
    assertTrue(result.success());
  }

  @Test
  void trustedPluginCannotSpoofUserFacingButtonTransport() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    InvocationProvenance spoofed =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("plugin:malicious"),
            Instant.now());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> executor.dispatch(op, "{}", spoofed));
    assertTrue(ex.getMessage().contains("TRUSTED_PLUGIN"));
    assertTrue(ex.getMessage().contains("BUTTON"));
  }

  @Test
  void trustedPluginMayDispatchWithPluginEmittedTransport() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin-ok");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    InvocationProvenance ok =
        new InvocationProvenance(
            TransportTag.PLUGIN_EMITTED,
            ExecutorTag.AGENT,
            Optional.of("plugin:advisor"),
            Instant.now());

    OperationResult result = executor.dispatch(op, "{}", ok);
    assertTrue(result.success());
  }

  @Test
  void trustedPluginCannotSpoofUrlBarOrLlmEmission() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin-mixed");
    handlers.register(id, args -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    for (TransportTag forbidden :
        new TransportTag[] {
          TransportTag.URL_BAR,
          TransportTag.URL_DEEPLINK,
          TransportTag.LLM_EMISSION,
          TransportTag.PALETTE,
          TransportTag.RAIL,
          TransportTag.MCP
        }) {
      InvocationProvenance spoof =
          new InvocationProvenance(
              forbidden, ExecutorTag.UI, Optional.empty(), Instant.now());
      assertThrows(
          IllegalArgumentException.class,
          () -> executor.dispatch(op, "{}", spoof),
          "expected rejection of " + forbidden);
    }
  }

  // -------- Slice 490 §6.3 + Group B2: advisory emission via advisoryClass routing -------

  private static final ResourceRef TEST_ADVISORY_CLASS =
      new ResourceRef("core.advisory-operation-completed");

  @Test
  void advisoryEmittedWhenPolicyDeclaresAdvisoryClass() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-emit-test");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers,
            history::add,
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS));
    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("user:advisor-test"),
            java.time.Instant.parse("2026-05-12T09:00:00Z"));

    OperationResult result = executor.dispatch(op, "{}", provenance);

    assertTrue(result.success());
    assertEquals(1, history.size(), "exactly one history entry per dispatch");
    assertEquals(1, advisories.size(), "exactly one advisory per declared-class dispatch");
    OperationCompletionEvent advisory = advisories.get(0);
    assertEquals(id, advisory.operationId());
    assertEquals(OperationOutcome.SUCCESS, advisory.outcome());
    assertEquals(provenance, advisory.provenance());
    assertTrue(advisory.diagnosticsLink().isEmpty());
  }

  @Test
  void noAdvisoryEmittedWhenPolicyDoesNotDeclareAdvisoryClass() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-noop-test");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers,
            history::add,
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.empty());

    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
    assertEquals(1, history.size(), "history entry still emitted regardless of advisory class");
    assertEquals(0, advisories.size(), "no advisory emitted for class-less Operation");
  }

  @Test
  void unmappedAdvisoryClassDoesNotBreakDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-unmapped-test");
    handlers.register(id, args -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(handlers, history::add, Map.of(), Clock.systemUTC());

    Operation op =
        makeOpWithAdvisoryClass(
            id, Optional.of(new ResourceRef("core.advisory-not-registered")));

    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
    assertEquals(1, history.size());
    assertEquals(0, advisories.size(), "no emitter registered for the declared class");
  }

  @Test
  void advisoryEmittedOnFailureOutcomeWhenAdvisoryClassDeclared() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-failure-test");
    handlers.register(id, args -> OperationResult.failure("nope"));
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers,
            entry -> {},
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS));

    OperationResult result = executor.dispatch(op, "{}");

    assertFalse(result.success());
    assertEquals(1, advisories.size());
    assertEquals(OperationOutcome.FAILURE, advisories.get(0).outcome());
  }

  private static Operation makeOpWithAdvisoryClass(
      OperationRef id, Optional<ResourceRef> advisoryClass) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false,
            advisoryClass),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  @Test
  void capabilityUnavailableReturnsFailureWithoutInvokingHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, args -> OperationResult.success("should-not-run"));
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}");

    assertFalse(result.success());
    assertEquals(Optional.of("CAPABILITY_UNAVAILABLE"), result.errorCode());
    assertEquals(Optional.of(true), result.retryable());
  }

  @Test
  void capabilityAvailableAllowsDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> true);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
    assertEquals("ran", result.message());
  }

  @Test
  void noCapabilityResolverSkipsCheck() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, args -> OperationResult.success("ran-without-resolver"));
    OperationDispatcher executor = new OperationExecutorImpl(handlers);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
  }

  @Test
  void emptyCapabilitySetAlwaysPasses() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-no-cap");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op = makeOp(id, TrustTier.CORE, false);
    OperationResult result = executor.dispatch(op, "{}");

    assertTrue(result.success());
  }

  @Test
  void undoBlockedWhenCapabilityUnavailable() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-undo-gated");
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args) {
            return OperationResult.success("ran");
          }

          @Override
          public OperationResult undo(String executionId) {
            return OperationResult.success("undone — should not reach here");
          }
        });
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op =
        new Operation(
            id,
            Presentation.of(
                new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(RequiredCapability.WorkerOnline.INSTANCE),
                true),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.undo(op, "exec-123");
    assertFalse(result.success());
    assertEquals(Optional.of("CAPABILITY_UNAVAILABLE"), result.errorCode());
  }

  private static Operation makeOpWithCapability(OperationRef id, RequiredCapability cap) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(cap),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  // ----------------------------------------------------------------------------------
  // Tempdoc 550 Slice A1 (Authorize face) — consent capsule satisfies the trust gate.
  // ----------------------------------------------------------------------------------

  private static OperationDispatcher latticeExecutorWithCapsule(
      HandlerRegistry handlers, ConsentCapsuleService capsule) {
    TrustEvaluator trust = new CoreTrustEvaluator();
    IntentSourceCatalog sources = CoreIntentSourceCatalog.catalog();
    return new OperationExecutorImpl(
        handlers, null, Map.of(), Clock.systemUTC(), trust, sources, null, capsule);
  }

  private static OperationDispatcher latticeExecutorWithGateSink(
      HandlerRegistry handlers,
      ConsentCapsuleService capsule,
      java.util.List<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry> sink) {
    return new OperationExecutorImpl(
        handlers,
        null,
        Map.of(),
        Clock.systemUTC(),
        new CoreTrustEvaluator(),
        CoreIntentSourceCatalog.catalog(),
        null,
        capsule,
        sink::add);
  }

  /**
   * Tempdoc 550 E2: when the Global Hard Stop is engaged, an UNTRUSTED dispatch is DENIED (and
   * recorded), a TRUSTED (BUTTON) dispatch is unaffected, and releasing restores normal gating.
   */
  @Test
  void globalHardStop_deniesUntrusted_leavesUserActionsAlone() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    var sink =
        new java.util.ArrayList<
            io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    var executor =
        new OperationExecutorImpl(
            handlers,
            null,
            java.util.Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService(),
            sink::add);
    var hardStop = new GlobalHardStop();
    executor.setGlobalHardStop(hardStop);
    Operation op = makeMediumOp(id);
    InvocationProvenance agent =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());
    InvocationProvenance user =
        InvocationProvenance.fromTransport(TransportTag.BUTTON, Optional.empty(), Instant.now());

    // Engaged: the agent (UNTRUSTED) dispatch is DENIED outright + recorded DENIED.
    hardStop.engage();
    assertThrows(
        TrustGateDeniedException.class,
        () -> executor.dispatch(op, "{}", agent, Optional.empty()),
        "hard stop denies the UNTRUSTED dispatch");
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED,
        sink.get(sink.size() - 1).disposition(),
        "the hard-stop denial is recorded as a DENIED ledger row");

    // Engaged: a user (TRUSTED BUTTON) dispatch is unaffected — TRUSTED×MEDIUM=AUTO → runs.
    OperationResult userResult = executor.dispatch(op, "{}", user, Optional.empty());
    assertTrue(userResult.success(), "user/BUTTON action proceeds while the hard stop is engaged");

    // Released: the agent dispatch returns to normal gating (MEDIUM → confirmation-required).
    hardStop.release();
    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", agent, Optional.empty()),
        "released → the normal lattice gate applies again (not a hard-stop deny)");
  }

  /** Tempdoc 550 Outcome face: a gate firing emits a GATED record AND still throws (fail-closed). */
  @Test
  void gateFireEmitsGatedOutcomeAndStillThrows() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    var sink = new java.util.ArrayList<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    OperationDispatcher executor = latticeExecutorWithGateSink(handlers, new ConsentCapsuleService(), sink);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty()),
        "the gate still throws — the emit is additive, fail-closed preserved");
    assertEquals(1, sink.size(), "the gate firing was recorded");
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.GATED,
        sink.get(0).disposition());
    assertEquals("core.test-medium", sink.get(0).operationId());
  }

  /** A capsule-satisfied gate records APPROVED and proceeds to the handler. */
  @Test
  void capsuleApprovalEmitsApprovedOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    var sink = new java.util.ArrayList<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    OperationDispatcher executor = latticeExecutorWithGateSink(handlers, capsule, sink);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", untrusted, Optional.of(token));
    assertTrue(result.success(), "capsule-authorized dispatch reaches the handler");
    assertEquals(1, sink.size());
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED,
        sink.get(0).disposition());
  }

  /**
   * Tempdoc 550 critical-analysis: the gate-firing emit is ADDITIVE and security-gate-adjacent.
   * The production emitter fans into the unified ledger SSE (publish + subscriber callbacks). A
   * throwing emitter MUST NOT change the gate's fail-closed semantics — the gated dispatch still
   * throws ConfirmationRequiredException, it does not leak the emitter's RuntimeException.
   */
  @Test
  void gateStillFailsClosedWhenOutcomeEmitterThrows() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(
            handlers,
            null,
            Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService(),
            entry -> {
              throw new IllegalStateException("boom — SSE publish failed");
            });
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty()),
        "a throwing outcome emitter must not break the gate — still ConfirmationRequired, not the emitter's exception");
  }

  /** UNTRUSTED source (LLM emission) × MEDIUM risk = TYPED_CONFIRM; no token => gated. */
  @Test
  void untrustedMediumWithoutTokenIsGated() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty()),
        "LLM-emitted MEDIUM op with no token hits the gate (the dead-end)");
  }

  /** A valid consent capsule bound to (op, args) satisfies the same gate. */
  @Test
  void validCapsuleSatisfiesTheGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", untrusted, Optional.of(token));
    assertTrue(result.success(), "capsule-authorized dispatch reaches the handler");
  }

  /**
   * Tempdoc 550 C2 step 4: an UNTRUSTED source (LLM emission, agent loop, MCP) can no
   * longer satisfy the gate with a fabricated non-blank placeholder — it REQUIRES a valid
   * consent capsule. The capsule has replaced the nominal token where the fabrication
   * threat lives. (The legacy non-blank path is retained only for non-UNTRUSTED tiers
   * until their consumers migrate — C2 step 3.) This was the inverse assertion before the
   * tightening; flipping it makes the security upgrade visible and intentional.
   */
  @Test
  void untrustedNonCapsuleTokenIsRejected() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromTransport(TransportTag.LLM_EMISSION, Optional.empty(), Instant.now());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.of("not-a-capsule")),
        "a fabricated non-capsule token no longer satisfies the gate for an UNTRUSTED source");
  }

  /**
   * Tempdoc 550 C2 step 3: with the legacy non-blank acceptance removed, the gate now fails
   * closed for NON-UNTRUSTED tiers too. A TRUSTED source (BUTTON transport) invoking a
   * HIGH-risk op (TRUSTED × HIGH = TYPED_CONFIRM) with a fabricated/nominal non-capsule
   * token is rejected — proving the nominal-token weakness is closed for ALL tiers, not
   * just UNTRUSTED. (Before C2 step 3, the op-id stand-in satisfied this gate.)
   */
  @Test
  void trustedHighNonCapsuleTokenIsRejected() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-high");
    handlers.register(id, args -> OperationResult.success("ran"));
    OperationDispatcher executor =
        latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeHighOp(id);
    InvocationProvenance trusted =
        InvocationProvenance.fromTransport(TransportTag.BUTTON, Optional.empty(), Instant.now());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", trusted, Optional.of("core.test-high")),
        "a nominal (op-id) token from a TRUSTED source no longer satisfies the gate after C2"
            + " step 3");
  }

  /** A valid capsule from the same TRUSTED source DOES satisfy the gate (right-reason check). */
  @Test
  void trustedHighValidCapsuleSatisfiesGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-high");
    handlers.register(id, args -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeHighOp(id);
    InvocationProvenance trusted =
        InvocationProvenance.fromTransport(TransportTag.BUTTON, Optional.empty(), Instant.now());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", trusted, Optional.of(token));
    assertTrue(result.success(), "a bound capsule authorizes the TRUSTED HIGH dispatch");
  }

  private static Operation makeHighOp(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.HIGH,
            new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation makeMediumOp(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation makeOp(OperationRef id, TrustTier tier, boolean undoSupported) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            undoSupported),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(tier, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }
}
