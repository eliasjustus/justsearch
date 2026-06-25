package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConversationEngine} + {@link ToolIteratingShapeRunner} — Phase B
 * skeleton + agent encapsulation verification.
 *
 * <p>The live wire-shape compatibility (FE consumes the same SSE event vocabulary) is
 * verified by these tests' byte-for-byte translation assertions. End-to-end live
 * verification against a running backend is Phase 6 of the implementation slice.
 */
final class ConversationEngineTest {

  @Test
  @DisplayName("Engine routes the agent shape to its ShapeRunner")
  void engineRoutesAgentShapeToRunner() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.SessionStarted("test-session-id", TraceContext.none()));
              sink.accept(
                  new AgentEvent.AgentDone("ok", 1, 0, 42, TraceContext.none()));
            });

    ConversationEngine engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(() -> agentService)));

    var events = new ArrayList<SseEvent>();
    engine.run(
        AgentRunShape.ID,
        Map.of(
            "messages", List.of(Map.of("role", "user", "content", "hi")),
            "tools", List.of(),
            "maxIterations", 1),
        Audience.USER,
        events::add);

    assertEquals(2, events.size(), "expected SessionStarted + AgentDone");
    assertEquals("session_started", events.get(0).name());
    assertEquals("test-session-id", events.get(0).payload().get("sessionId"));
    assertEquals("done", events.get(1).name());
    assertEquals("ok", events.get(1).payload().get("finalResponse"));
    assertEquals(1, events.get(1).payload().get("iterationsUsed"));
    assertEquals(42L, ((Number) events.get(1).payload().get("totalTokensUsed")).longValue());

    assertEquals(1, capturedRequest.get().messages().size());
    assertEquals("hi", capturedRequest.get().messages().get(0).get("content"));
  }

  @Test
  @DisplayName("Engine validates audience and denies USER for an OPERATOR-class shape")
  void engineDeniesInsufficientAudience() {
    // Build a shape catalog with one OPERATOR-class shape (none of the CORE shapes are
    // OPERATOR-class today, so we construct a synthetic one).
    var operatorShape =
        new io.justsearch.agent.api.registry.ConversationShape(
            new ConversationShapeRef("core.operator-only"),
            new io.justsearch.agent.api.registry.Presentation(
                new io.justsearch.agent.api.registry.I18nKey("test.label"),
                new io.justsearch.agent.api.registry.I18nKey("test.desc"),
                java.util.Optional.empty(),
                java.util.Optional.empty()),
            Audience.OPERATOR,
            io.justsearch.agent.api.registry.Provenance.core("v1"),
            io.justsearch.agent.api.conversation.ExecutionMode.SHAPE_DRIVEN,
            io.justsearch.agent.api.conversation.IterationMode.ONE_SHOT,
            io.justsearch.agent.api.conversation.PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of());
    var catalog =
        io.justsearch.agent.api.registry.ConversationShapeCatalog.of("core", List.of(operatorShape));
    var engine = new ConversationEngine(catalog, List.of());

    assertThrows(
        ConversationEngine.AudienceDeniedException.class,
        () ->
            engine.run(
                operatorShape.id(),
                Map.of(),
                Audience.USER,
                ev -> {
                  /* sink */
                }));
  }

  @Test
  @DisplayName("Engine throws ShapeNotFoundException for unregistered shape ids")
  void engineRejectsUnknownShape() {
    var engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(AgentService::unavailable)));

    assertThrows(
        ConversationEngine.ShapeNotFoundException.class,
        () ->
            engine.run(
                new ConversationShapeRef("core.no-such-shape"),
                Map.of(),
                Audience.USER,
                ev -> {
                  /* sink */
                }));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner emits service-unavailable when agent isn't available")
  void runnerReportsUnavailable() {
    var runner = new ToolIteratingShapeRunner(AgentService::unavailable);
    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("messages", List.of()), Audience.USER, events::add);
    assertEquals(1, events.size());
    assertEquals("error", events.get(0).name());
    assertEquals("SERVICE_UNAVAILABLE", events.get(0).payload().get("errorCode"));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner translates ToolCallProposed -> tool_call_proposed")
  void runnerTranslatesToolCallEvents() {
    var call =
        new io.justsearch.agent.api.ToolCallRequest(
            "call-1", "core_search_index", "{\"query\":\"x\"}");
    var event =
        new AgentEvent.ToolCallProposed(
            call, io.justsearch.agent.api.registry.RiskTier.LOW, TraceContext.none());
    SseEvent sse = AgentEventSseTranslator.translate(event, null, java.util.Map.of());
    assertEquals("tool_call_proposed", sse.name());
    assertEquals("call-1", sse.payload().get("callId"));
    assertEquals("core_search_index", sse.payload().get("toolName"));
    assertEquals("low", sse.payload().get("risk"));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner parses body with profiles + maxHandoffs")
  void runnerParsesBody() {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
    body.put("tools", List.of("core_search_index"));
    body.put("maxIterations", 5);
    body.put(
        "agentProfiles",
        List.of(
            Map.of(
                "agentId",
                "primary",
                "name",
                "Primary",
                "systemPrompt",
                "You are a helpful assistant.",
                "toolSubset",
                List.<String>of())));
    body.put("initialAgentId", "primary");
    body.put("maxHandoffs", 3);

    AgentRequest request = ToolIteratingShapeRunner.parseRequest(body);
    assertEquals(1, request.messages().size());
    assertEquals("core_search_index", request.selectedToolNames().get(0));
    assertEquals(5, request.maxIterations());
    assertEquals(1, request.agentProfiles().size());
    assertEquals("primary", request.initialAgentId());
    assertEquals(3, request.maxHandoffs().intValue());
  }

  /**
   * F1 — stub StreamConsumer that records onDone invocations + emits a caller-supplied
   * SseEvent. The id() value is critical: it MUST match an id declared in
   * {@code AgentRunShape.streamConsumerIds()} for the runner to resolve and invoke it.
   */
  private static final class RecordingStreamConsumer
      implements io.justsearch.agent.api.conversation.StreamConsumer {
    final String id;
    final io.justsearch.agent.api.conversation.SseEvent emittedEvent;
    final AtomicReference<String> capturedFullText = new AtomicReference<>();

    RecordingStreamConsumer(String id, io.justsearch.agent.api.conversation.SseEvent emitted) {
      this.id = id;
      this.emittedEvent = emitted;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public io.justsearch.agent.api.conversation.StreamConsumerResult onChunk(
        String chunkText, io.justsearch.agent.api.conversation.ConversationContext ctx) {
      return io.justsearch.agent.api.conversation.StreamConsumerResult.empty();
    }

    @Override
    public io.justsearch.agent.api.conversation.StreamConsumerResult onDone(
        String fullText, io.justsearch.agent.api.conversation.ConversationContext ctx) {
      capturedFullText.set(fullText);
      return new io.justsearch.agent.api.conversation.StreamConsumerResult(
          List.of(emittedEvent), List.of(), List.of(), Map.of());
    }
  }

  @Test
  @DisplayName(
      "Slice 491 F1: runner resolves declared streamConsumerId 'core.url-extractor'"
          + " via the registry and fires its onDone on AgentDone")
  void runnerResolvesDeclaredStreamConsumerFromRegistry() {
    var urlExtractor =
        new RecordingStreamConsumer(
            "core.url-extractor",
            new io.justsearch.agent.api.conversation.SseEvent(
                "navigate.url_extracted",
                Map.of("index", 0, "target", "core.library-surface")));
    var registry = StreamConsumerRegistry.of(List.of(urlExtractor));

    var agent =
        new StubAgentService(
            (req, sink) -> {
              sink.accept(new AgentEvent.TextChunk("Take me to ", TraceContext.none()));
              sink.accept(
                  new AgentEvent.TextChunk(
                      "justsearch://surface/core.library-surface", TraceContext.none()));
              sink.accept(new AgentEvent.AgentDone("ok", 1, 0, 0, TraceContext.none()));
            });

    var runner = new ToolIteratingShapeRunner(() -> agent, registry);
    var events = new ArrayList<SseEvent>();
    runner.run(
        Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
        Audience.USER,
        events::add);

    assertEquals(
        "Take me to justsearch://surface/core.library-surface",
        urlExtractor.capturedFullText.get());
    // Expected order: chunk, chunk, navigate.url_extracted (from extractor),
    // done (translated AgentDone). Total 4 events.
    assertEquals(4, events.size(), "chunk + chunk + url_extracted + done");
    assertEquals("chunk", events.get(0).name());
    assertEquals("chunk", events.get(1).name());
    assertEquals("navigate.url_extracted", events.get(2).name());
    assertEquals("done", events.get(3).name());
  }

  @Test
  @DisplayName(
      "Slice 491 F1: runner with empty registry warns but doesn't break — declared"
          + " streamConsumerId without registry registration logs + skips, agent run"
          + " completes")
  void runnerWithEmptyRegistryDegradesCleanly() {
    var agent =
        new StubAgentService(
            (req, sink) -> {
              sink.accept(new AgentEvent.TextChunk("hello", TraceContext.none()));
              sink.accept(new AgentEvent.AgentDone("hello", 1, 0, 0, TraceContext.none()));
            });
    var runner = new ToolIteratingShapeRunner(() -> agent);
    var events = new ArrayList<SseEvent>();
    runner.run(
        Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
        Audience.USER,
        events::add);
    // The shape declares core.url-extractor; registry doesn't have it → warn + skip.
    // Only the translated AgentEvents appear in the sink.
    assertEquals(2, events.size(), "missing consumer registration → only chunk + done");
    assertEquals("chunk", events.get(0).name());
    assertEquals("done", events.get(1).name());
  }

  // ---------------------- Test stub ----------------------

  /** Stub {@link AgentService} that delegates {@code runAgent} to a caller-supplied lambda. */
  private static final class StubAgentService implements AgentService {

    private final java.util.function.BiConsumer<AgentRequest, Consumer<AgentEvent>> runner;

    StubAgentService(java.util.function.BiConsumer<AgentRequest, Consumer<AgentEvent>> runner) {
      this.runner = runner;
    }

    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer) {
      runner.accept(request, eventConsumer);
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    @Override
    public OperationResult undoOperation(String toolName, String executionId) {
      return null;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @SuppressWarnings("unused")
    private void noWarnIsAvailable() {
      assertTrue(true);
      assertFalse(false);
    }
  }
}
