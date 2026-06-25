/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.IterationDecision;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.core.util.TokenEstimation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversation substrate runtime.
 *
 * <p>Per tempdoc 491 §5.4: one engine, two execution modes.
 *
 * <ul>
 *   <li>{@link ExecutionMode#SHAPE_DRIVEN} — the engine delegates to a registered
 *       {@link ShapeRunner}, which is responsible for the entire conversation lifecycle.
 *       Used to encapsulate existing implementations (the agent loop is the canonical
 *       example).
 *   <li>{@link ExecutionMode#SUBSTRATE_DRIVEN} — the engine controls the per-iteration loop
 *       and invokes the shape's SPIs in declaration order. Phase C implementation:
 *       (1) assemble system prompt from {@link PromptContributor}s ordered by priority,
 *       (2) run {@link ContextInjector}s in declaration order, prepending injected messages,
 *       (3) call {@link OnlineAiService#streamChat} with the assembled message list,
 *       (4) dispatch streamed chunks + final {@code onDone} to {@link StreamConsumer}s,
 *       collecting their {@link StreamConsumerResult#messageDeltas} for the next iteration,
 *       (5) invoke {@link IterationController#next} to decide whether to loop.
 * </ul>
 *
 * <p>Per §5.4 trust gating: the engine validates the request's invocation audience against
 * the shape's declared {@link Audience} before invoking either path.
 */
public final class ConversationEngine {

  private static final Logger LOG = LoggerFactory.getLogger(ConversationEngine.class);
  private static final int DEFAULT_MAX_TOKENS = 1024;
  private static final int MAX_ITERATIONS_HARD_CAP = 20;
  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

  private final ConversationShapeCatalog catalog;
  private final Map<ConversationShapeRef, ShapeRunner> runnersByShape;
  private final PromptContributorRegistry promptContributors;
  private final ContextInjectorRegistry contextInjectors;
  private final StreamConsumerRegistry streamConsumers;
  private final IterationControllerRegistry iterationControllers;
  private final Supplier<OnlineAiService> onlineAiSupplier;
  private final io.justsearch.agent.api.conversation.ConversationStore conversationStore;

  /**
   * Constructs the engine. Phase B-compatible overload (registries + LLM source default to
   * empty / unavailable) — used by tests and pre-substrate-driven assembly.
   */
  public ConversationEngine(ConversationShapeCatalog catalog, Iterable<ShapeRunner> shapeRunners) {
    this(
        catalog,
        shapeRunners,
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of()),
        StreamConsumerRegistry.of(List.of()),
        IterationControllerRegistry.of(List.of()),
        OnlineAiService::unavailable,
        io.justsearch.agent.api.conversation.ConversationStore.noop());
  }

  /**
   * Full constructor. Phase C wiring uses this overload to inject the substrate-driven
   * SPI registries, the LLM service supplier, and the conversation store.
   */
  public ConversationEngine(
      ConversationShapeCatalog catalog,
      Iterable<ShapeRunner> shapeRunners,
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers,
      Supplier<OnlineAiService> onlineAiSupplier) {
    this(catalog, shapeRunners, promptContributors, contextInjectors,
        streamConsumers, iterationControllers, onlineAiSupplier,
        io.justsearch.agent.api.conversation.ConversationStore.noop());
  }

  /**
   * Slice 496 §3.B — constructor with ConversationStore for persistent shapes.
   */
  public ConversationEngine(
      ConversationShapeCatalog catalog,
      Iterable<ShapeRunner> shapeRunners,
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers,
      Supplier<OnlineAiService> onlineAiSupplier,
      io.justsearch.agent.api.conversation.ConversationStore conversationStore) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    Map<ConversationShapeRef, ShapeRunner> idx = new LinkedHashMap<>();
    for (ShapeRunner r : shapeRunners) {
      Objects.requireNonNull(r, "shapeRunner");
      ShapeRunner existing = idx.putIfAbsent(r.shapeId(), r);
      if (existing != null) {
        throw new IllegalArgumentException(
            "Duplicate ShapeRunner registration for " + r.shapeId().value());
      }
    }
    this.runnersByShape = Map.copyOf(idx);
    this.promptContributors = Objects.requireNonNull(promptContributors, "promptContributors");
    this.contextInjectors = Objects.requireNonNull(contextInjectors, "contextInjectors");
    this.streamConsumers = Objects.requireNonNull(streamConsumers, "streamConsumers");
    this.iterationControllers =
        Objects.requireNonNull(iterationControllers, "iterationControllers");
    this.onlineAiSupplier = Objects.requireNonNull(onlineAiSupplier, "onlineAiSupplier");
    this.conversationStore =
        Objects.requireNonNull(conversationStore, "conversationStore");
  }

  /** Returns the catalog the engine resolves shapes against. */
  public ConversationShapeCatalog catalog() {
    return catalog;
  }

  /**
   * Run a registered shape. Lookup → audience-validate → dispatch by mode. Blocks until the
   * shape's runner (or substrate-driven loop) returns.
   */
  public void run(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink) {
    Objects.requireNonNull(shapeId, "shapeId");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(sink, "sink");
    Map<String, Object> safeBody = body == null ? Map.of() : body;

    ConversationShape shape =
        catalog
            .findById(shapeId)
            .orElseThrow(
                () ->
                    new ShapeNotFoundException(
                        "No ConversationShape registered for id " + shapeId.value()));

    validateAudience(shape, audience);

    switch (shape.executionMode()) {
      case SHAPE_DRIVEN -> dispatchShapeDriven(shape, safeBody, audience, sink);
      case SUBSTRATE_DRIVEN -> dispatchSubstrateDriven(shape, safeBody, audience, sink);
    }
  }

  private static void validateAudience(ConversationShape shape, Audience requestAudience) {
    if (shape.audience() != requestAudience) {
      boolean adminOverride =
          (requestAudience == Audience.OPERATOR || requestAudience == Audience.DEVELOPER)
              && (shape.audience() == Audience.USER || shape.audience() == Audience.AGENT);
      if (!adminOverride) {
        throw new AudienceDeniedException(
            "Audience "
                + requestAudience
                + " is not permitted to invoke shape "
                + shape.id().value()
                + " (requires "
                + shape.audience()
                + ")");
      }
    }
  }

  private void dispatchShapeDriven(
      ConversationShape shape,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink) {
    ShapeRunner runner = runnersByShape.get(shape.id());
    if (runner == null) {
      throw new IllegalStateException(
          "Shape "
              + shape.id().value()
              + " declares SHAPE_DRIVEN execution but no ShapeRunner is registered for it");
    }
    LOG.debug("Dispatching shape-driven {} (audience={})", shape.id().value(), audience);
    runner.run(body, audience, sink);
  }

  /**
   * The substrate-driven orchestration. Resolves SPIs from registries, assembles the system
   * prompt, runs injectors on iteration 0, then loops: LLM call → consumer dispatch →
   * controller decision.
   *
   * <p>Per tempdoc 491 §5.1: contributors are priority-sorted (stable). Injectors run once
   * per request (iteration 0 only) — re-running e.g. RAG retrieval every iteration would
   * be wrong; injection is a per-request concern, not a per-iteration concern. Consumers
   * fire on every iteration's {@code onChunk} + {@code onDone}.
   */
  private void dispatchSubstrateDriven(
      ConversationShape shape,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink) {
    LOG.debug("Dispatching substrate-driven {} (audience={})", shape.id().value(), audience);

    // Resolve SPI implementations from registries.
    List<PromptContributor> contributors = resolveContributors(shape);
    List<ContextInjector> injectors = resolveInjectors(shape);
    List<StreamConsumer> consumers = resolveConsumers(shape);
    IterationController controller = resolveController(shape);

    // Slice 496 §3.B: for PERSISTENT shapes, load history from ConversationStore.
    // The prior stub (Phase C) left the message list empty and said "later phase."
    // Phase 496 implements the integration: sessionId from the request body seeds
    // the context with the conversation history. EPHEMERAL shapes still start empty.
    String sessionId = shape.persistenceMode() == io.justsearch.agent.api.conversation.PersistenceMode.PERSISTENT
        ? (String) body.get("sessionId")
        : null;
    List<Map<String, Object>> initialMessages = new ArrayList<>();
    if (sessionId != null && conversationStore != null) {
      // Tempdoc 610 Phase C — seed the prompt from the EFFECTIVE context (the
      // history trimmed to the session's context floor, if set), NOT the full
      // displayed history. With no floor this is identical to loadHistory. The
      // display path (/history) keeps loadHistory, so the transcript still
      // shows everything above the floor as out-of-context.
      initialMessages.addAll(conversationStore.loadEffectiveContext(sessionId));
    }
    // Tempdoc 561 P-A/P-B — the canonical-record WRITE key, decoupled from the history-LOAD key
    // (sessionId). PERSISTENT shapes already record under sessionId below (and load their history
    // for multi-turn context). A recordsToThread shape that is NOT on the persistent path (e.g. the
    // EPHEMERAL RAG-ask shape — fresh LLM context every turn) records its CLEAN user turn + assistant
    // turn (with citations + producer calibration) under the request's conversationId, so the unified
    // thread / History / Timeline project the grounded answer + its evidence. EPHEMERAL context-load
    // semantics are untouched: threadId only writes, it never seeds initialMessages.
    String threadId = threadRecordId(shape, sessionId, body);
    EngineConversationContext ctx =
        new EngineConversationContext(initialMessages, audience, sessionId, body);

    // Tempdoc 610 §J.3 — seed the conversation's hidden retrieved-source ids (the store is the source
    // of truth, mirroring per-message exclude) so RAGContext can drop them from this turn's retrieval.
    // Keyed by the conversation the FE stamps: sessionId for PERSISTENT shapes, else the threadId
    // (= body.conversationId) for EPHEMERAL recordsToThread shapes like the documents RAG-ask path —
    // which is where retrieved-source exclusion actually applies.
    String excludeKey = sessionId != null ? sessionId : threadId;
    if (excludeKey != null && conversationStore != null) {
      ctx.attributes().put(
          io.justsearch.app.services.conversation.spi.RAGContext.ATTR_EXCLUDED_SOURCES,
          conversationStore.excludedSourceIds(excludeKey));
    }

    int maxTokens = parseMaxTokens(body);
    SamplingParams sampling = applySchemaConstraint(parseSamplingParams(body), body);

    // Assemble the system prompt once per request — contributors are stateless and don't
    // re-render between iterations (their content depends on ctx but iteration is the only
    // mutable field they typically care about, and for iteration-stable contributors the
    // re-render would produce identical output).
    String systemPrompt = assembleSystemPrompt(contributors, ctx);

    // Run injectors once per request (iteration 0 only). Each injector may emit SSE events
    // (forwarded to sink) and may declare a terminal error that aborts before the LLM call.
    InjectorRunResult injectorRun = runInjectors(injectors, ctx, sink);
    if (injectorRun.terminated) {
      return;
    }
    ctx.appendMessages(injectorRun.messages);
    // Slice 496 §3.B: persist injected messages (which include the user's input)
    // for PERSISTENT shapes so the conversation store has the full thread.
    if (sessionId != null && conversationStore != null) {
      for (Map<String, Object> msg : injectorRun.messages) {
        conversationStore.appendMessage(sessionId, shape.id().value(), msg);
      }
    } else if (threadId != null && conversationStore != null) {
      // Tempdoc 561 P-A/P-B: record the CLEAN user turn (the user's actual question), NOT the
      // context-augmented injector message — RAGContext injects "Documents:\n<retrieved>\n\nQuestion:
      // <q>" for the LLM, which must never become the thread's user bubble.
      conversationStore.appendMessage(threadId, shape.id().value(), threadUserMessage(body));
    }

    // Tempdoc 610 §I.2/§J — per-phase token attribution for the meter breakdown + inspector. The three
    // phases are stable per request: system (contributors), conversation (the floor-trimmed effective
    // context; empty for EPHEMERAL), retrieved (the injected RAG/doc messages). Estimated via
    // TokenEstimation (which over-estimates) — the authoritative total is `promptTokens` from usage, so
    // the FE presents these as the split (scaled/≈), never false precision.
    Map<String, Object> contextBreakdown = new LinkedHashMap<>();
    contextBreakdown.put("system", TokenEstimation.estimateTokens(systemPrompt));
    contextBreakdown.put("conversation", estimatePhaseTokens(initialMessages));
    contextBreakdown.put("retrieved", estimatePhaseTokens(injectorRun.messages));

    OnlineAiService ai = onlineAiSupplier.get();
    if (ai == null || !ai.isAvailable()) {
      emitError(sink, "AI service unavailable", "AI_OFFLINE");
      return;
    }

    int hardCap = shape.iterationMode() == IterationMode.ONE_SHOT ? 1 : MAX_ITERATIONS_HARD_CAP;

    String accumulatedFinalText = "";
    Map<String, Object> mergedDoneEntries = new LinkedHashMap<>();
    for (int i = 0; i < hardCap; i++) {
      List<Map<String, Object>> llmInput = buildLlmInput(systemPrompt, ctx.messages());

      String finalText;
      AtomicReference<OnlineAiService.AiUsage> usageRef = new AtomicReference<>();
      try {
        finalText = streamLlm(ai, llmInput, maxTokens, sampling, consumers, ctx, usageRef, sink);
      } catch (LlmStreamException e) {
        emitError(sink, e.getMessage(), e.errorCode);
        return;
      }
      accumulatedFinalText = finalText;

      // Stream consumers: onDone dispatch. Collect message deltas + done-payload entries.
      List<Map<String, Object>> aggregateDeltas = new ArrayList<>();
      mergedDoneEntries.clear();
      for (StreamConsumer consumer : consumers) {
        StreamConsumerResult result;
        try {
          result = consumer.onDone(finalText, ctx);
        } catch (Exception e) {
          LOG.warn("StreamConsumer {} onDone threw; emitting error event", consumer.id(), e);
          emitError(sink, "StreamConsumer " + consumer.id() + " failed: " + e.getMessage(), "CONSUMER_ERROR");
          return;
        }
        result.events().forEach(sink);
        aggregateDeltas.addAll(result.messageDeltas());
        mergedDoneEntries.putAll(result.donePayloadEntries());
      }

      // Tempdoc 610 §E.4 / §G — surface the prompt-token occupancy on the done payload so the FE can
      // render a context-budget meter. The model context window (the meter's denominator) is FE-side
      // (aiState). The usage is captured per LLM call; the last iteration's value is what the meter shows.
      OnlineAiService.AiUsage usage = usageRef.get();
      if (usage != null && usage.promptTokens() != null) {
        mergedDoneEntries.put("promptTokens", usage.promptTokens());
        if (usage.totalTokens() != null) {
          mergedDoneEntries.put("totalTokens", usage.totalTokens());
        }
      }
      // Tempdoc 610 §I.2 — the per-phase split rides the done payload alongside the real total.
      mergedDoneEntries.put("contextBreakdown", contextBreakdown);

      // Append the assistant message and any consumer message deltas before the next
      // iteration's decision + LLM call.
      Map<String, Object> assistantMsg = assistantMessage(finalText);
      ctx.appendMessage(assistantMsg);
      ctx.appendMessages(aggregateDeltas);
      // Slice 496 §3.B / tempdoc 561 P-A: persist the assistant message WITH its evidence (citations +
      // producer calibration from the done-payload), so the unified thread surfaces grounding FROM the
      // record, not an FE-side content re-match. Evidence is kept off the LLM-context copy so it never
      // pollutes the next prompt. PERSISTENT shapes record under sessionId; recordsToThread ephemeral
      // shapes (RAG) record under threadId (the request's conversationId).
      if (sessionId != null && conversationStore != null) {
        conversationStore.appendMessage(
            sessionId, shape.id().value(), persistedAssistant(assistantMsg, mergedDoneEntries));
      } else if (threadId != null && conversationStore != null) {
        conversationStore.appendMessage(
            threadId, shape.id().value(), persistedAssistant(assistantMsg, mergedDoneEntries));
      }

      // Iteration decision.
      IterationDecision decision;
      try {
        decision = controller.next(ctx);
      } catch (Exception e) {
        LOG.warn("IterationController {} threw; treating as STOP_ERROR", controller.id(), e);
        emitError(sink, "IterationController " + controller.id() + " failed: " + e.getMessage(), "CONTROLLER_ERROR");
        return;
      }

      switch (decision) {
        case STOP_SUCCESS -> {
          emitDone(sink, accumulatedFinalText, ctx.iteration() + 1, mergedDoneEntries);
          return;
        }
        case STOP_ERROR -> {
          emitError(sink, "Conversation terminated with error", "CONTROLLER_STOP");
          return;
        }
        case CONTINUE -> ctx.incrementIteration();
      }
    }

    // Reached hard cap without STOP_*
    LOG.warn("Substrate-driven shape {} reached iteration hard cap {}", shape.id().value(), hardCap);
    emitDone(sink, accumulatedFinalText, hardCap, mergedDoneEntries);
  }

  /**
   * Stream the LLM call. Blocks until {@code onComplete} or {@code onError} fires (via
   * CountDownLatch). Returns the full text on success; throws {@link LlmStreamException} on
   * error. Emits {@code chunk} SSE events to the sink for each streamed chunk, and dispatches
   * each chunk to all {@link StreamConsumer}s.
   */
  /** Tempdoc 610 §I.2 — estimated token sum over a phase's messages (the content field). */
  private static int estimatePhaseTokens(List<Map<String, Object>> messages) {
    int sum = 0;
    for (Map<String, Object> m : messages) {
      Object content = m.get("content");
      if (content instanceof String s) {
        sum += TokenEstimation.estimateTokens(s);
      }
    }
    return sum;
  }

  private String streamLlm(
      OnlineAiService ai,
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      List<StreamConsumer> consumers,
      EngineConversationContext ctx,
      AtomicReference<OnlineAiService.AiUsage> usageOut,
      Consumer<SseEvent> sink)
      throws LlmStreamException {

    CountDownLatch latch = new CountDownLatch(1);
    StringBuilder fullText = new StringBuilder();
    AtomicReference<String> completionText = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    ai.stream(
        new OnlineAiService.StreamRequest(messages, maxTokens, sampling),
        new OnlineAiService.StreamSink(
            chunk -> {
              fullText.append(chunk);
              sink.accept(new SseEvent("chunk", Map.of("text", chunk)));
              for (StreamConsumer consumer : consumers) {
                try {
                  StreamConsumerResult r = consumer.onChunk(chunk, ctx);
                  r.events().forEach(sink);
                } catch (RuntimeException e) {
                  LOG.warn("StreamConsumer {} onChunk threw; continuing stream", consumer.id(), e);
                }
              }
            },
            reasoning -> sink.accept(new SseEvent("reasoning_chunk", Map.of("text", reasoning))),
            toolDelta -> {},
            usage -> usageOut.set(usage),
            complete -> {
              completionText.set(complete);
              latch.countDown();
            },
            err -> {
              error.set(err);
              latch.countDown();
            }));

    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmStreamException("LLM call interrupted", "INTERRUPTED");
    }

    if (error.get() != null) {
      Throwable err = error.get();
      throw new LlmStreamException(err.getMessage() == null ? err.toString() : err.getMessage(), "LLM_ERROR");
    }

    // Always return the chunk-accumulator; `completionText` is the LLM
    // finish_reason, not the response body. See the comment on the onComplete
    // callback above.
    return fullText.toString();
  }

  /** Resolve all {@link PromptContributor}s referenced by the shape; missing ids throw. */
  private List<PromptContributor> resolveContributors(ConversationShape shape) {
    List<PromptContributor> out = new ArrayList<>(shape.promptContributorIds().size());
    for (String id : shape.promptContributorIds()) {
      PromptContributor c =
          promptContributors
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "PromptContributor not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(c);
    }
    return out;
  }

  private List<ContextInjector> resolveInjectors(ConversationShape shape) {
    List<ContextInjector> out = new ArrayList<>(shape.contextInjectorIds().size());
    for (String id : shape.contextInjectorIds()) {
      ContextInjector inj =
          contextInjectors
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "ContextInjector not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(inj);
    }
    return out;
  }

  private List<StreamConsumer> resolveConsumers(ConversationShape shape) {
    List<StreamConsumer> out = new ArrayList<>(shape.streamConsumerIds().size());
    for (String id : shape.streamConsumerIds()) {
      StreamConsumer sc =
          streamConsumers
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "StreamConsumer not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(sc);
    }
    return out;
  }

  /**
   * Resolve the {@link IterationController}. Defaults to {@link SingleHopController#INSTANCE}
   * for shapes whose manifest leaves {@code iterationControllerId} null (legal only for
   * {@link IterationMode#ONE_SHOT} shapes per {@link ConversationShape}'s compact constructor).
   */
  private IterationController resolveController(ConversationShape shape) {
    String id = shape.iterationControllerId();
    if (id == null) {
      return SingleHopController.INSTANCE;
    }
    return iterationControllers
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "IterationController not registered: " + id + " (shape " + shape.id().value() + ")"));
  }

  /**
   * Compose the system prompt: collect each contributor's fragment, stable-sort by priority,
   * and join with double-newline. Empty fragments are filtered.
   */
  private static String assembleSystemPrompt(
      List<PromptContributor> contributors, EngineConversationContext ctx) {
    List<PromptFragment> fragments = new ArrayList<>();
    for (PromptContributor c : contributors) {
      Optional<PromptFragment> f;
      try {
        f = c.contribute(ctx);
      } catch (Exception e) {
        LOG.warn("PromptContributor {} threw; skipping fragment", c.id(), e);
        continue;
      }
      f.ifPresent(fragments::add);
    }
    // Stable sort by priority ascending; declaration order ties handled by sort stability.
    fragments.sort(Comparator.comparingInt(PromptFragment::priority));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fragments.size(); i++) {
      if (i > 0) {
        sb.append("\n\n");
      }
      sb.append(fragments.get(i).text());
    }
    return sb.toString();
  }

  /**
   * Run each {@link ContextInjector} in declaration order. Per substrate enhancement E2:
   *
   * <ul>
   *   <li>Each injector returns an {@link InjectorResult} carrying messages, events, and
   *       optional terminal error.
   *   <li>Events are forwarded to {@code sink} in the order injectors produce them.
   *   <li>If any injector signals a terminal error, the engine emits that error event and
   *       returns {@code terminated = true}; downstream injectors are skipped and no LLM
   *       call is made.
   *   <li>An injector that throws is logged and skipped (non-fatal — the conversation
   *       continues with whatever context remains).
   * </ul>
   */
  private static InjectorRunResult runInjectors(
      List<ContextInjector> injectors,
      EngineConversationContext ctx,
      Consumer<SseEvent> sink) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ContextInjector inj : injectors) {
      InjectorResult result;
      try {
        result = inj.inject(ctx);
      } catch (Exception e) {
        LOG.warn("ContextInjector {} threw; skipping injection", inj.id(), e);
        continue;
      }
      if (result == null) {
        continue;
      }
      result.events().forEach(sink);
      if (result.terminalError().isPresent()) {
        sink.accept(result.terminalError().get());
        return new InjectorRunResult(out, true);
      }
      out.addAll(result.messages());
    }
    return new InjectorRunResult(out, false);
  }

  /** Output of {@link #runInjectors}: accumulated messages + whether any injector aborted. */
  private record InjectorRunResult(List<Map<String, Object>> messages, boolean terminated) {}

  /**
   * Build the LLM message list: system prompt as message[0] (omitted if empty), then the
   * accumulated context messages.
   */
  private static List<Map<String, Object>> buildLlmInput(
      String systemPrompt, List<Map<String, Object>> contextMessages) {
    List<Map<String, Object>> out = new ArrayList<>(contextMessages.size() + 1);
    if (!systemPrompt.isEmpty()) {
      Map<String, Object> system = new LinkedHashMap<>();
      system.put("role", "system");
      system.put("content", systemPrompt);
      out.add(system);
    }
    out.addAll(contextMessages);
    return out;
  }

  private static Map<String, Object> assistantMessage(String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", "assistant");
    m.put("content", text);
    return m;
  }

  /**
   * Tempdoc 561 P-A/P-B — the canonical-record WRITE key for a recordsToThread shape that is NOT on
   * the persistent (sessionId) path. Returns the request's {@code conversationId} for EPHEMERAL
   * recordsToThread shapes (e.g. RAG ask) so their turns land on the unified thread; returns null for
   * persistent shapes (they record under sessionId), shapes that opt out of the thread, blank ids, and
   * internal throwaway sessions (auto-title generation).
   */
  private static String threadRecordId(
      ConversationShape shape, String sessionId, Map<String, Object> body) {
    if (sessionId != null) {
      return null; // PERSISTENT shapes already record under sessionId
    }
    if (!shape.recordsToThread()) {
      return null;
    }
    Object cid = body.get("conversationId");
    if (!(cid instanceof String s) || s.isBlank()) {
      return null;
    }
    if (s.startsWith(
        io.justsearch.agent.api.conversation.ConversationStore.THROWAWAY_SESSION_PREFIX)) {
      return null;
    }
    return s;
  }

  /**
   * Tempdoc 561 P-A/P-B — the CLEAN user turn for the thread: the user's actual input, taken from the
   * shape's input field, NOT the context-augmented injector message (RAGContext rewrites the user
   * message into a documents+question blob for the LLM, which must never be the thread's user bubble).
   */
  private static Map<String, Object> threadUserMessage(Map<String, Object> body) {
    String text =
        firstNonBlank(
            body.get("question"), body.get("prompt"), body.get("message"), body.get("text"));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", "user");
    m.put("content", text == null ? "" : text);
    return m;
  }

  /**
   * Tempdoc 561 P-A — the assistant turn persisted to the canonical record, with its evidence
   * (citations + producer-owned calibration) attached from the done-payload. Evidence is on this
   * persisted copy only, never on the LLM-context copy, so it cannot pollute the next prompt.
   */
  private static Map<String, Object> persistedAssistant(
      Map<String, Object> assistantMsg, Map<String, Object> mergedDoneEntries) {
    Map<String, Object> persisted = new LinkedHashMap<>(assistantMsg);
    Object citations = mergedDoneEntries.get("citations");
    if (citations != null) {
      persisted.put("citations", citations);
    }
    Object calibration = mergedDoneEntries.get("calibration");
    if (calibration != null) {
      persisted.put("calibration", calibration);
    }
    // Tempdoc 561 P-A (evidence non-divergence): persist the per-claim grounding (sentence->chunk
    // matches the Worker's cross-encoder produced) so a reloaded conversation renders the inline
    // per-claim marks FROM the record, matching the live render (the two paths cannot diverge).
    Object claimMatches = mergedDoneEntries.get("claimMatches");
    if (claimMatches != null) {
      persisted.put("claimMatches", claimMatches);
    }
    return persisted;
  }

  private static String firstNonBlank(Object... values) {
    for (Object v : values) {
      if (v instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    return null;
  }

  private static int parseMaxTokens(Map<String, Object> body) {
    Object raw = body.get("maxTokens");
    if (raw instanceof Number n) {
      int v = n.intValue();
      return v > 0 ? v : DEFAULT_MAX_TOKENS;
    }
    return DEFAULT_MAX_TOKENS;
  }

  private static SamplingParams parseSamplingParams(Map<String, Object> body) {
    Object raw = body.get("enableThinking");
    if (raw instanceof Boolean b) {
      return new SamplingParams(0.8, 0.95, null, null, b);
    }
    return null;
  }

  /**
   * Slice 496 §3.C hardening (tempdoc 569 Phase 5) — when a substrate-driven request declares a
   * JSON {@code schema} (the {@code ExtractShape} contract — its {@code ValidationConsumer} already
   * reads {@code body["schema"]} for the correction prompt), promote that schema to a server-side
   * {@code response_format} constraint so the FIRST iteration is schema-valid by construction
   * (llama-server converts schema→GBNF internally; {@code OnlineModeOps} applies it). This turns the
   * generative-authoring path from "emit-then-validate-retry" into "constrained-emit", with the
   * existing validate-retry loop kept as the safety net for any backend that ignores the constraint.
   *
   * <p>The schema arrives as a JSON STRING (the caller posts {@code schema: JSON.stringify(...)}).
   * If it is absent, blank, or unparseable, the sampling params are returned unchanged — the
   * validate-retry loop still guards correctness, so a bad schema degrades rather than fails.
   *
   * <p>Structured extraction wants near-deterministic, non-thinking output; when the caller supplied
   * no explicit sampling we base the constrained params on {@link SamplingParams#DETERMINISTIC} with
   * thinking disabled (mirroring the QueryUnderstanding structured-output preset).
   */
  private static SamplingParams applySchemaConstraint(
      SamplingParams sampling, Map<String, Object> body) {
    if (!(body.get("schema") instanceof String schemaJson) || schemaJson.isBlank()) {
      return sampling;
    }
    final Map<String, Object> schemaMap;
    try {
      schemaMap = SCHEMA_MAPPER.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      LOG.warn(
          "Request declared a schema but it did not parse as JSON; falling back to validate-retry"
              + " only (response_format not applied): {}",
          e.getMessage());
      return sampling;
    }
    SamplingParams base =
        sampling != null ? sampling : SamplingParams.DETERMINISTIC.withEnableThinking(false);
    // The codebase-standard schema→GBNF response_format form (QueryUnderstandingService +
    // OnlineModeOps' non-streaming path): a response_format-enforcing llama.cpp build converts this to
    // a server-side grammar so the FIRST emission is schema-valid. On a build that does NOT enforce it,
    // the ExtractShape's validate-retry loop + the FE self-repair remain the correctness safety net
    // (live-verified: every run yields valid, schema-conforming JSON regardless).
    return base.withResponseFormat(Map.of("type", "json_object", "schema", schemaMap));
  }

  /**
   * Emit the substrate-default {@code done} event with consumer-contributed enrichment.
   *
   * <p>Per substrate enhancement E1: {@code consumerEntries} are merged in first (consumers'
   * declaration order is preserved via the caller's LinkedHashMap), then the substrate
   * defaults are written last so they cannot be overridden by consumers.
   */
  private static void emitDone(
      Consumer<SseEvent> sink,
      String finalText,
      int iterationsUsed,
      Map<String, Object> consumerEntries) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (consumerEntries != null) {
      payload.putAll(consumerEntries);
    }
    payload.put("finalResponse", finalText);
    payload.put("iterationsUsed", iterationsUsed);
    sink.accept(new SseEvent("done", payload));
  }

  private static void emitError(Consumer<SseEvent> sink, String message, String errorCode) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", message);
    payload.put("errorCode", errorCode);
    payload.put("i18nKey", "errors." + errorCode);
    sink.accept(new SseEvent("error", payload));
  }

  /** Thrown when {@link #run} is invoked with an unregistered shape id. */
  public static final class ShapeNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ShapeNotFoundException(String message) {
      super(message);
    }
  }

  /** Thrown when the request's audience is insufficient for the shape. */
  public static final class AudienceDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AudienceDeniedException(String message) {
      super(message);
    }
  }

  /** Internal exception used to communicate stream-call failures. */
  private static final class LlmStreamException extends Exception {
    private static final long serialVersionUID = 1L;
    final String errorCode;

    LlmStreamException(String message, String errorCode) {
      super(message);
      this.errorCode = errorCode;
    }
  }
}
