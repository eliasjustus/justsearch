/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.RetryAction;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.lifecycle.LifecycleState;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RecoveryAction;
import io.justsearch.agent.api.registry.ResolutionRecoveryPolicy;
import io.justsearch.agent.api.registry.ResolutionResult;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.telemetry.Telemetry;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the agent loop: send prompt with tools to llama-server, parse tool calls, dispatch to
 * tool handlers, and feed results back.
 */
public final class AgentLoopService implements AgentService {
  private static final Logger LOG = LoggerFactory.getLogger(AgentLoopService.class);
  // Lazy lookup via getTracer() instead of static final Tracer — a static field captures
  // the noop tracer at class-load time, before TracingBootstrap registers the real SDK,
  // making agent spans untestable. The per-call lookup cost is negligible (ConcurrentHashMap).
  // Package-private: AgentLlmCaller (tempdoc 240 W4) reuses this scope so chat + tool spans
  // share one tracer name.
  static final String TRACER_SCOPE = "io.justsearch.agent.AgentLoopService";

  /** Reads an int field from ConfigStore if available, falling back to {@code fallback}. */
  private static int resolveFromConfig(ToIntFunction<ResolvedConfig> extractor, int fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.applyAsInt(cs.get()) : fallback;
  }

  /** Reads a boolean field from ConfigStore if available, falling back to {@code fallback}. */
  private static boolean resolveFromConfig(
      Function<ResolvedConfig, Boolean> extractor, boolean fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.apply(cs.get()) : fallback;
  }

  /**
   * Direction I: GBNF grammar enforcing Hermes 2 Pro tool-call format on forced-tool-call turns.
   *
   * <p>Applied alongside {@code tool_choice="required"} as a belt-and-suspenders layer. When the
   * server supports both grammar and tools simultaneously (Hermes 2 Pro / Qwen3), this prevents
   * malformed JSON even if the model samples unusual token sequences. When the server does not
   * support grammar+tools (HTTP 400), {@link OnlineModeOps} silently omits the grammar field and
   * the {@code tool_choice} enforcement alone is used.
   */
  static final String TOOL_CALL_GRAMMAR =
      """
      ws          ::= [ \\t\\n\\r]*
      string      ::= "\\"" characters "\\""
      characters  ::= character*
      character   ::= [^"\\\\] | "\\\\" escape_char
      escape_char ::= "\\"" | "\\\\" | "/" | "b" | "f" | "n" | "r" | "t" | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
      number      ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
      object      ::= "{" ws (pair (ws "," ws pair)*)? ws "}"
      pair        ::= string ws ":" ws value
      array       ::= "[" ws (value (ws "," ws value)*)? ws "]"
      value       ::= string | number | object | array | "true" | "false" | "null"
      root        ::= "<tool_call>" "\\n" call "\\n" "</tool_call>"
      call        ::= "{" ws "\\"name\\"" ws ":" ws string ws "," ws "\\"arguments\\"" ws ":" ws object ws "}"
      """;

  // Tempdoc 584 §B.4: DEFAULT_SYSTEM_PROMPT + the prompt-composition concerns moved to
  // AgentPromptComposer. TOOL_CALL_GRAMMAR (above) stays here — it is shared cross-collaborator
  // infra referenced by AgentLlmCaller / AgentStepRunner, not prompt-composition state.

  private final OnlineAiService onlineAiService;
  private final OperationCatalog operationCatalog;
  private final OperationDispatcher operationExecutor;
  /**
   * Slice 487 Phase 1.7: setter-injected by {@code HeadAssembly} after
   * construction. When non-null, {@link AgentToolDispatcher#dispatchToolCall}
   * (resolved lazily via the router supplier passed to the dispatcher) routes the
   * tool-call dispatch through the {@link BackendIntentRouter} (preserving the
   * trust-lattice's per-step re-validation) rather than calling
   * {@link OperationDispatcher#dispatch} directly. When null, the legacy
   * direct-dispatch path runs — preserved for test wiring that doesn't construct
   * the full intent substrate.
   */
  private volatile BackendIntentRouter backendIntentRouter;
  // Tempdoc 550 C2 step 2: late-bound consent-capsule authority (mirrors
  // backendIntentRouter). Set by AgentLoopWiring after construction; the tool dispatcher
  // resolves it per-call via a supplier and mints a bound capsule for approved tool calls.
  private volatile io.justsearch.agent.api.registry.ConsentCapsuleAuthority consentCapsuleAuthority;
  // Tempdoc 561 P-D1: late-bound read-only window onto the ONE intent-gate authority
  // (IntentGateEvaluator). Set by AgentLoopWiring after construction; the tool dispatcher resolves
  // it per-call via a supplier and stamps the backend GateBehavior onto the pending-approval event.
  private volatile io.justsearch.agent.api.registry.IntentPreviewer intentPreviewer;
  private final AgentToolEmitter agentToolEmitter;
  private final FileOperationLog fileOperationLog; // nullable
  // Tempdoc 584 §B.4: prompt assembly (the indexed-root preamble + the slice-447 condition-recovery
  // context) lives in AgentPromptComposer. The loop holds it and delegates buildSystemPrompt() and
  // setConditionContextSupplier().
  private final AgentPromptComposer promptComposer;
  private final AgentRunStore runStore;
  private final AgentTelemetry agentTelemetry;
  private final AgentSessionTerminationObserver terminationObserver;
  private final AgentContextCompressor compressor;
  private final AgentLlmCaller llmCaller;
  private final AgentToolDispatcher toolDispatcher;
  private final AgentSessionFinalizer finalizer;
  private final AgentStepRunner stepRunner;
  // Tempdoc 584 §B.4: the read-time query/projection + resume surface lives in AgentRunQueryService;
  // the loop holds it and delegates the AgentRunQueries methods to it.
  private final AgentRunQueryService queries;
  // Tempdoc 584 §B.4: the live-run session map + per-run control surface lives in
  // AgentSessionRegistry; the loop registers/evicts at run start/end and delegates the control verbs.
  private final AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();

  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        null,
        null);
  }

  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      Telemetry telemetry) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        telemetry,
        // Default supplier: this constructor doesn't get a holder reference, so the
        // agent.session.active_count gauge reports 0. Production wiring uses the
        // longer constructor below to thread through a live supplier.
        () -> 0,
        // Tempdoc 430 Phase 7: this back-compat constructor doesn't wire the
        // health-event observer; callers needing the agent.session.* events use
        // the longer constructor below.
        AgentSessionTerminationObserver.NOOP);
  }

  /**
   * Production constructor (tempdoc 415 + 429 Phase 10): threads through a live
   * {@code activeSessionSupplier} so the {@code agent.session.active_count} gauge reports
   * the running service's session count. {@code HeadAssembly} captures a holder array
   * and passes {@code () -> holder[0].activeSessionCount()} so the supplier resolves once
   * the service is constructed.
   *
   * <p>Tempdoc 430 Phase 7: accepts an optional {@link AgentSessionTerminationObserver}
   * called once per session at terminal disposition (alongside the existing
   * {@link AgentTelemetry#recordSessionEnd} emit). Defaults to
   * {@link AgentSessionTerminationObserver#NOOP} when null.
   */
  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      Telemetry telemetry,
      java.util.function.IntSupplier activeSessionSupplier,
      AgentSessionTerminationObserver terminationObserver) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        // Tempdoc 417 Phase 2d: AgentTelemetry now wraps typed catalogs. When telemetry is a
        // LocalTelemetry, build catalogs against its registry; otherwise noop.
        telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt
            ? new AgentTelemetry(
                new AgentMetricCatalog(lt.registry(), activeSessionSupplier),
                new GenAiMetricCatalog(lt.registry()))
            : AgentTelemetry.noop(),
        terminationObserver,
        Boolean.TRUE); // marker — selects the AgentTelemetry-taking private constructor
  }

  /**
   * Test factory: constructs an {@link AgentLoopService} with the supplied {@link
   * AgentTelemetry} (typically backed by a {@code TestMetricRegistry}) instead of routing
   * through {@link Telemetry} casting.
   */
  static AgentLoopService forTesting(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry) {
    return forTesting(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        agentTelemetry,
        AgentSessionTerminationObserver.NOOP);
  }

  /**
   * Test factory variant accepting an explicit {@link AgentSessionTerminationObserver} for the
   * tempdoc 430 Phase 7 health-event emitter integration tests.
   */
  static AgentLoopService forTesting(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry,
      AgentSessionTerminationObserver terminationObserver) {
    return new AgentLoopService(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        agentTelemetry,
        terminationObserver,
        // marker for the private constructor below
        Boolean.TRUE);
  }


  /**
   * Private constructor used by {@link #forTesting} — distinct signature (extra unused {@code
   * Boolean marker}) so it doesn't clash with the public {@code (... Telemetry)} overload.
   */
  private AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Supplier<List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry,
      AgentSessionTerminationObserver terminationObserver,
      @SuppressWarnings("unused") Boolean marker) {
    this.onlineAiService = onlineAiService;
    this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog");
    this.operationExecutor = Objects.requireNonNull(operationExecutor, "operationExecutor");
    this.agentToolEmitter = Objects.requireNonNull(agentToolEmitter, "agentToolEmitter");
    this.fileOperationLog = fileOperationLog;
    this.promptComposer = new AgentPromptComposer(rootPathsSupplier);
    this.runStore = runStore != null ? runStore : AgentRunStore.noop();
    this.agentTelemetry = agentTelemetry != null ? agentTelemetry : AgentTelemetry.noop();
    this.terminationObserver =
        terminationObserver != null ? terminationObserver : AgentSessionTerminationObserver.NOOP;
    this.compressor =
        new AgentContextCompressor(
            resolveFromConfig(rc -> rc.agent().contextCompressionEnabled(), true),
            Math.max(1, resolveFromConfig(rc -> rc.agent().contextCompressionMinChars(), 200)),
            Math.max(0, resolveFromConfig(rc -> rc.agent().contextCompressionKeepLastResults(), 0)));
    this.llmCaller = new AgentLlmCaller(this.onlineAiService, this.agentTelemetry, this.compressor);
    this.toolDispatcher =
        new AgentToolDispatcher(
            this.operationExecutor,
            this.agentTelemetry,
            () -> this.backendIntentRouter,
            () -> this.consentCapsuleAuthority,
            () -> this.intentPreviewer);
    this.finalizer =
        new AgentSessionFinalizer(this.agentTelemetry, this.terminationObserver, this.runStore);
    this.stepRunner =
        new AgentStepRunner(
            this.onlineAiService,
            this.operationCatalog,
            this.agentToolEmitter,
            this.agentTelemetry,
            this.runStore,
            this.compressor,
            this.llmCaller,
            this.toolDispatcher,
            this::checkpoint,
            this::emitError,
            this::swapSystemPrompt);
    this.queries =
        new AgentRunQueryService(
            this.runStore,
            this.operationCatalog,
            this.operationExecutor,
            this.fileOperationLog,
            this::emitError,
            (req, sink) -> runAgent(req, sink));
  }

  /**
   * Slice 447 §X.11.5 Phase 5: wires the agent retrospection consumer onto the prompt composer.
   * The supplier is called at prompt-construction time; its returned String is appended to the
   * system prompt. Pass {@code null} to disable. Tempdoc 584 §B.4: delegates to
   * {@link AgentPromptComposer}.
   */
  public void setConditionContextSupplier(Supplier<String> supplier) {
    promptComposer.setConditionContextSupplier(supplier);
  }

  /**
   * Slice 487 Phase 1.7: wire the backend intent router. Called by
   * {@code HeadAssembly} after construction. When non-null, the agent's
   * tool-call dispatch flows through the intent layer (trust lattice, audit,
   * unified observability) rather than calling {@code OperationDispatcher} directly.
   */
  /** Tempdoc 550 C2 step 2: late-bind the shared consent-capsule authority (mint side). */
  public void setConsentCapsuleAuthority(
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority authority) {
    this.consentCapsuleAuthority = authority;
  }

  public void setBackendIntentRouter(BackendIntentRouter router) {
    this.backendIntentRouter = router;
  }

  /**
   * Tempdoc 561 P-D1: late-bind the intent-gate previewer (backed by IntentGateEvaluator) so the
   * pending-approval event carries the backend's authoritative GateBehavior.
   */
  public void setIntentPreviewer(io.justsearch.agent.api.registry.IntentPreviewer previewer) {
    this.intentPreviewer = previewer;
  }


  /** Creates handoff tool descriptors for all agent profiles except the active one. */

  /** Swaps the first system message for the new agent's system prompt. */
  private void swapSystemPrompt(AgentSession session, AgentProfile toProfile) {
    String basePrompt = toProfile.systemPrompt() != null
        ? toProfile.systemPrompt()
        : promptComposer.buildSystemPrompt();
    String newPrompt = basePrompt;
    List<Map<String, Object>> messages = session.messages();
    if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
      messages.set(0, Map.of("role", "system", "content", newPrompt));
    } else {
      messages.add(0, Map.of("role", "system", "content", newPrompt));
    }
  }


  // Tempdoc 561 P-D — threads the background flag onto the session created inside the synchronous
  // runAgent body without re-plumbing its 100-line signature. runAgent blocks the calling thread, so a
  // per-thread flag set before / cleared after is exact and race-free.
  private final ThreadLocal<Boolean> backgroundRun = ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  public void runAgent(
      AgentRequest request, Consumer<AgentEvent> eventConsumer, boolean background) {
    backgroundRun.set(background);
    try {
      runAgent(request, eventConsumer);
    } finally {
      backgroundRun.remove();
    }
  }

  @Override
  public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer) {
    String sessionId = UUID.randomUUID().toString();

    // Calculate initial token budget
    int contextWindow =
        onlineAiService.llmContextTokens() != null
            ? onlineAiService.llmContextTokens()
            : onlineAiService.configuredContextTokens();
    int safetyMargin = 256; // Reserve for response generation
    int initialBudget = Math.max(0, contextWindow - safetyMargin);

    // Resolve effective initial agent ID: explicit > first profile > "primary"
    String effectiveAgentId = request.initialAgentId();
    if (effectiveAgentId == null && !request.agentProfiles().isEmpty()) {
      effectiveAgentId = request.agentProfiles().get(0).agentId();
    }
    var session = new AgentSession(request.messages(), initialBudget, effectiveAgentId);
    // Tempdoc 577 §2.14 Root II (#14) — carry the model's context window (n_ctx) onto the session so
    // each budget event can report cognitive headroom (promptTokens ÷ n_ctx) beside the economic budget.
    session.contextWindow(contextWindow);
    // Tempdoc 561 P-D: the user's autonomy dial rides the request; the ONE backend issuance policy
    // (IntentGateEvaluator.agentGate) reads it so the FE obeys the verdict instead of re-deriving.
    session.setAutonomyLevel(
        io.justsearch.agent.api.registry.AutonomyLevel.fromWire(request.autonomyLevel()));
    if (Boolean.TRUE.equals(backgroundRun.get())) {
      session.markBackground(); // Tempdoc 561 P-D: safe-by-default safety gate for unwatched runs
    }
    sessionRegistry.register(sessionId, session);
    agentTelemetry.recordSessionStart(); // tempdoc 415
    var traceSequencerRef = new AtomicReference<>(
        new AgentEventTracing.Sequencer(sessionId, session.activeAgentId()));
    Consumer<AgentEvent> sink = wrapEventConsumer(sessionId, session, eventConsumer,
        traceSequencerRef);
    // Tempdoc 577 §2.14 Root I (#13) — the initiating SSE writer is the FIRST observer of the run's
    // hub (not a privileged direct delegate): it subscribes like any reattaching observer, so N
    // views share ONE run authority. Subscribing before the loop starts means it receives every
    // event live (the buffer is empty at this point; a later /attach replays it).
    session.eventHub().subscribe(eventConsumer);

    // Prepend default system prompt if the conversation doesn't already have one
    if (session.messages().isEmpty()
        || !"system".equals(session.messages().get(0).get("role"))) {
      session.messages().add(0, Map.of("role", "system", "content", promptComposer.buildSystemPrompt()));
    }
    // For multi-agent: override system prompt with the initial profile's prompt
    if (!request.agentProfiles().isEmpty()) {
      AgentProfile initialProfile = AgentHandoff.findProfile(request.agentProfiles(), session.activeAgentId());
      if (initialProfile != null) {
        swapSystemPrompt(session, initialProfile);
      }
    }
    runStore.startRun(sessionId, request, session.messages(), initialBudget);
    if (session.isBackground()) {
      // Tempdoc 561 P-D2: stamp the durable record so the presence projection (presenceSince) surfaces
      // this run in the render-on-return inbox as work done while the user was away.
      runStore.markBackground(sessionId);
    }
    checkpoint(sessionId, session, LifecycleState.READY_FOR_LLM.name(), "");

    Span agentSpan = GlobalOpenTelemetry.getTracer(TRACER_SCOPE).spanBuilder("invoke_agent primary")
        .setSpanKind(SpanKind.INTERNAL)
        .setAttribute("gen_ai.operation.name", "invoke_agent")
        .setAttribute("gen_ai.agent.id", "primary")
        .setAttribute("gen_ai.agent.name", "justsearch-agent")
        .setAttribute("gen_ai.conversation.id", sessionId)
        .startSpan();

    boolean agentSuccess = false;
    try (Scope ignored = agentSpan.makeCurrent()) {
      sink.accept(new AgentEvent.SessionStarted(sessionId));
      sink.accept(
          new AgentEvent.AgentProgress("init", "Starting agent session", 0, // 561 #4: no raw UUID
              request.maxIterations()));

      List<Map<String, Object>> baseTools =
          agentToolEmitter.emit(operationCatalog, request.selectedToolNames());

      if (baseTools.isEmpty() && request.agentProfiles().isEmpty()) {
        emitError(
            sink,
            "No tools available",
            AgentErrorCode.NO_TOOLS,
            AgentErrorClass.PERMANENT,
            RetryAction.ABORT,
            null);
        // Tempdoc 415 F1: state-first, durability-second.
        session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.NO_TOOLS, null);
        checkpoint(sessionId, session, LifecycleState.ERROR.name(), "No tools available");
        return;
      }

      for (int iteration = 0; iteration < request.maxIterations(); iteration++) {
        IterationOutcome outcome =
            stepRunner.executeIteration(
                iteration, request, session, sessionId, baseTools, traceSequencerRef, sink);
        if (outcome.terminated()) {
          agentSuccess = outcome.success();
          return;
        }
      }

      // Max iterations reached
      agentSuccess = true;
      sink.accept(
          new AgentEvent.AgentDone(
              "", session.iterationsUsed(), session.toolCallsExecuted(), session.totalTokens()));
      // F1: state-first, durability-second.
      session.markTerminated(TerminalDisposition.MAX_ITERATIONS, null, null);
      checkpoint(sessionId, session, LifecycleState.DONE.name(), "Max iterations reached");

    } catch (Exception e) {
      LOG.error("Agent loop error", e);
      agentSpan.recordException(e);
      agentSpan.setStatus(StatusCode.ERROR, "agent-loop-error");
      emitError(
          sink,
          e.getMessage(),
          AgentErrorCode.INTERNAL_ERROR,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      // F1: state-first, durability-second.
      session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.INTERNAL_ERROR, null);
      checkpoint(sessionId, session, LifecycleState.ERROR.name(), e.getMessage());
    } finally {
      // Tempdoc 565 §33 — end-of-run drain: a steering directive queued AFTER the last step boundary
      // (during the final answer, or a single-LLM-call run with no second boundary) would otherwise be
      // silently dropped. Drain any still-pending directive once and emit DirectiveAcknowledged so the
      // human's direction is ALWAYS recorded as a run event — even when it landed too late to change the
      // output. The session is still in the map and the SSE sink open until this handler returns.
      String lateDirective = session.drainInterject();
      if (lateDirective != null && !lateDirective.isBlank()) {
        sink.accept(new AgentEvent.DirectiveAcknowledged(lateDirective));
      }
      if (agentSuccess) {
        agentSpan.setStatus(StatusCode.OK);
      } else {
        // Covers both: catch-path (setStatus(ERROR) already called — second call is no-op)
        // and error-return paths (emitError + return without exception)
        agentSpan.setStatus(StatusCode.ERROR, "agent-error");
      }
      agentSpan.end();
      // Tempdoc 415 / 430 Phase 7: centralized session-end emit (metric family + health-event
      // Occurrence + runStore typed reason). Each return site marks the session via
      // session.markTerminated(...) before returning; the finalizer reads the typed reason,
      // defaults a missing mark to ERRORED/INTERNAL_ERROR (F6, loud ERROR), and fans out.
      finalizer.emitSessionEnd(sessionId, session);
      sessionRegistry.remove(sessionId);
      // Tempdoc 577 §2.14 Root I (#13) — the run is terminal: close the hub (drop observers + the
      // replay buffer, refuse late publishes). A reattach after this point reads the persisted record
      // (events.ndjson), not the live hub — the run is no longer an in-flight observed entity.
      session.eventHub().close();
    }
  }


  // Tempdoc 584 §B.4 — the live-run control surface delegates to AgentSessionRegistry. Bodies (the
  // uniform get-session-then-mutate shape) moved there; new per-run control verbs attach to that
  // collaborator. This is the cluster that re-pinned the file via 561 / 565 / 577.

  @Override
  public void approveToolCall(String sessionId, String callId) {
    sessionRegistry.approveToolCall(sessionId, callId);
  }

  @Override
  public void rejectToolCall(String sessionId, String callId, String reason) {
    sessionRegistry.rejectToolCall(sessionId, callId, reason);
  }

  @Override
  public boolean tryApproveToolCall(String sessionId, String callId) {
    return sessionRegistry.tryApproveToolCall(sessionId, callId);
  }

  @Override
  public boolean tryRejectToolCall(String sessionId, String callId, String reason) {
    return sessionRegistry.tryRejectToolCall(sessionId, callId, reason);
  }

  @Override
  public void cancelSession(String sessionId) {
    sessionRegistry.cancelSession(sessionId);
  }

  @Override
  public void setSessionAutonomy(String sessionId, String autonomyLevelWire) {
    sessionRegistry.setSessionAutonomy(sessionId, autonomyLevelWire);
  }

  @Override
  public boolean injectSteeringDirective(String sessionId, String text) {
    return sessionRegistry.injectSteeringDirective(sessionId, text);
  }

  @Override
  public boolean raiseSessionBudget(String sessionId, int addTokens) {
    return sessionRegistry.raiseSessionBudget(sessionId, addTokens);
  }

  @Override
  public boolean resolveBudgetGate(String sessionId, String decision) {
    return sessionRegistry.resolveBudgetGate(sessionId, decision);
  }

  @Override
  public boolean resolveContextGate(String sessionId, String decision) {
    return sessionRegistry.resolveContextGate(sessionId, decision);
  }

  @Override
  public boolean attachToRun(String sessionId, Consumer<AgentEvent> eventConsumer) {
    return sessionRegistry.attachToRun(sessionId, eventConsumer);
  }

  @Override
  public boolean attachToRun(String sessionId, long fromSeq, Consumer<AgentEvent> eventConsumer) {
    return sessionRegistry.attachToRun(sessionId, fromSeq, eventConsumer);
  }

  @Override
  public boolean completeVirtualToolCall(
      String sessionId, String callId, boolean success, String output, String errorDetail) {
    return sessionRegistry.completeVirtualToolCall(
        sessionId, callId, success, output, errorDetail);
  }

  @Override
  public int activeSessionCount() {
    return sessionRegistry.activeSessionCount();
  }

  // Tempdoc 584 §B.4 — the AgentRunQueries read/projection/resume surface delegates to
  // AgentRunQueryService. Bodies (and the toBatchSummary / backgroundBoundary / firstUserMessage
  // helpers + resumeFromSnapshot) moved there; new read features attach to that collaborator.

  @Override
  public List<Operation> availableOperations() {
    return queries.availableOperations();
  }

  @Override
  public OperationResult undoOperation(String toolName, String executionId) {
    return queries.undoOperation(toolName, executionId);
  }

  @Override
  public List<Map<String, Object>> operationHistory(int limit) {
    return queries.operationHistory(limit);
  }

  @Override
  public Map<String, Object> operationDetail(String batchId) {
    return queries.operationDetail(batchId);
  }

  @Override
  public Map<String, Object> lastSessionSnapshot() {
    return queries.lastSessionSnapshot();
  }

  @Override
  public List<Map<String, Object>> listSessions(int limit) {
    return queries.listSessions(limit);
  }

  @Override
  public Map<String, Object> sessionSnapshot(String sessionId) {
    return queries.sessionSnapshot(sessionId);
  }

  @Override
  public void resumeLastSession(Consumer<AgentEvent> eventConsumer) {
    queries.resumeLastSession(eventConsumer);
  }

  @Override
  public void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer) {
    queries.resumeSession(sessionId, eventConsumer);
  }

  @Override
  public void forkSession(String sessionId, String editedMessage, Consumer<AgentEvent> eventConsumer) {
    queries.forkSession(sessionId, editedMessage, eventConsumer);
  }

  @Override
  public List<Map<String, Object>> sessionEvents(String sessionId) {
    return queries.sessionEvents(sessionId);
  }

  @Override
  public List<InteractionEvent> threadEvents(String conversationId) {
    return queries.threadEvents(conversationId);
  }

  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> lifecycles(String conversationId) {
    return queries.lifecycles(conversationId);
  }

  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> presenceSince(
      java.time.Instant since) {
    return queries.presenceSince(since);
  }

  @Override
  public boolean isAvailable() {
    return !operationCatalog.definitions().isEmpty();
  }

  /** Tempdoc 560 WS5 — forward the streaming workflow-as-tool runner to the step runner. */
  @Override
  public void setWorkflowToolRunner(
      io.justsearch.agent.api.registry.WorkflowToolRunner runner) {
    stepRunner.setWorkflowToolRunner(runner);
  }

  /**
   * Tempdoc 565 §3.A — late-bind the answer↔source matcher from a {@link DocumentService} (available
   * only at bootstrap). Builds the resolver here (keeping it package-private to app-agent) and
   * forwards it to the step runner so the terminal answer carries inline citations. A null service
   * leaves answers citing their grounding sources without per-sentence marks.
   */
  public void setCitationDocumentService(io.justsearch.app.api.DocumentService documentService) {
    stepRunner.setCitationResolver(
        documentService == null ? null : new AgentCitationResolver(documentService));
  }

  // --- Internal ---

  private Consumer<AgentEvent> wrapEventConsumer(
      String sessionId, AgentSession session, Consumer<AgentEvent> delegate,
      AtomicReference<AgentEventTracing.Sequencer> traceSequencerRef) {
    return event -> {
      AgentEvent enriched = event;
      TraceContext trace = event.trace();
      if (trace == null || !trace.hasIdentity()) {
        enriched = AgentEventTracing.withTrace(event,
            traceSequencerRef.get().next(event, session.iterationsUsed()));
      }
      // Tempdoc 577 §2.14 Root I (#13) — publish to the session-local hub (the run's ONE event
      // authority) instead of writing the SSE socket directly. The hub fans out to every observer
      // (the initiating SSE writer + any reattaching tab) and SWALLOWS a broken observer's
      // exception — so a closed/dropped socket can no longer kill the loop, and the run survives the
      // socket close (the §2.15 V3 root cause: a synchronous write to a dead socket aborted the loop).
      session.eventHub().publish(enriched);
      runStore.appendEvent(sessionId, enriched);
      // Tempdoc 585 §D Phase 1 (A1): per-event-type observability from the ONE publish chokepoint.
      agentTelemetry.recordEventEmitted(io.justsearch.agent.api.AgentEventPayloads.name(enriched));
    };
  }


  /**
   * Persist a run checkpoint. Collapses the 7-arg {@code runStore.updateCheckpoint}
   * call whose five session-snapshot arguments are always the same
   * (tempdoc 240 W0). All 24 call sites pass {@code (sessionId, session, state, note)}.
   */
  private void checkpoint(String sessionId, AgentSession session, String state, String resumeNote) {
    runStore.updateCheckpoint(
        sessionId,
        state,
        session.messages(),
        session.iterationsUsed(),
        session.toolCallsExecuted(),
        session.totalTokens(),
        resumeNote);
  }

  private void emitError(
      Consumer<AgentEvent> eventConsumer,
      String message,
      AgentErrorCode code,
      AgentErrorClass errorClass,
      RetryAction retryAction,
      Integer retryAttempt) {
    agentTelemetry.recordError(code, errorClass);
    eventConsumer.accept(
        new AgentEvent.AgentError(message, code, errorClass, retryAction, retryAttempt));
  }


}
