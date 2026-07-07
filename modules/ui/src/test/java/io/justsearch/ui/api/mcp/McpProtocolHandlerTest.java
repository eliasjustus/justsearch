package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.mcp.McpContractVersions;
import io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class McpProtocolHandlerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-16T12:00:00Z"), ZoneId.of("UTC"));

  private McpProtocolHandler handler;
  private OperationDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = mock(OperationDispatcher.class);
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);
    handler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);
  }

  @Test
  void initialize_returnsCapabilities() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    assertEquals("2.0", response.get("jsonrpc"));
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    // Tempdoc 654: both versions are single-sourced from McpContractVersions — assert against the
    // constants (not literals) so the manifest's RuntimeContract and this response can't desync.
    assertEquals(McpContractVersions.PROTOCOL_VERSION, result.get("protocolVersion"));
    @SuppressWarnings("unchecked")
    Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
    assertEquals(
        McpContractVersions.TOOL_SURFACE_VERSION,
        serverInfo.get("version"),
        "serverInfo.version is the MCP-native slot for the curated tool-surface version");
    @SuppressWarnings("unchecked")
    Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
    assertNotNull(caps.get("tools"));
    assertNotNull(caps.get("resources"));
    assertNotNull(caps.get("prompts"));
    // Tempdoc 655 fix: neither the tool list nor the resource list can change at runtime (both
    // are fixed at compile time) — locks in the corrected, honest capability declaration so a
    // future accidental revert to the over-declared `true` is caught.
    @SuppressWarnings("unchecked")
    Map<String, Object> toolsCap = (Map<String, Object>) caps.get("tools");
    assertEquals(Boolean.FALSE, toolsCap.get("listChanged"));
    @SuppressWarnings("unchecked")
    Map<String, Object> resourcesCap = (Map<String, Object>) caps.get("resources");
    assertEquals(Boolean.FALSE, resourcesCap.get("listChanged"));
    assertEquals(Boolean.TRUE, resourcesCap.get("subscribe"));

    // Tempdoc 655 (agent-legibility layer): initialize now carries the MCP `instructions` steering
    // field — the one server-level surface an autonomous agent reads at tool-selection time. It must
    // be present, non-blank, and COMPARATIVE (state when to prefer the index), not a bare feature list.
    Object instructions = result.get("instructions");
    assertNotNull(instructions, "initialize must return the MCP `instructions` steering field");
    String instr = (String) instructions;
    assertFalse(instr.isBlank(), "instructions must not be blank");
    assertTrue(
        instr.contains("justsearch_answer"),
        "instructions must steer toward the primary retrieval tool");
    assertTrue(
        instr.toLowerCase().contains("prefer"),
        "instructions must be comparative (when to prefer the index), not a bare feature list");
  }

  @Test
  void instructionsAndPromptPath_shareSingleSourcedGuidance() throws Exception {
    // Tempdoc 655: the connect-time instructions() surface and the user-invoked prompt path
    // (getStatusContext) must read ONE guidance string — 654 "projection, not fork". A distinctive
    // phrase that lives only in TOOL_SELECTION_GUIDANCE must appear in both.
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);
    String marker = "ordinary file tools are equally good";
    assertTrue(
        surface.instructions().contains(marker),
        "instructions() must carry the single-sourced comparative guidance");
    String promptJson =
        MAPPER.writeValueAsString(surface.getPrompt("search_files", Map.of("topic", "x")));
    assertTrue(
        promptJson.contains(marker),
        "the user-invoked prompt path must read the SAME single-sourced guidance (no fork)");
  }

  @Test
  void comparativeAnswerHint_countsDistinctDocuments_notChunks() {
    // Regression (review finding F1): the hint must key on DISTINCT cited documents, not chunksFound
    // — multiple chunks from ONE document must NOT produce a "spanning multiple documents" claim.
    assertEquals(
        "",
        McpToolSurface.comparativeAnswerHint(List.of(cite("docA"), cite("docA"), cite("docA"))),
        "multiple chunks from a single document must not claim 'multiple documents'");

    // The fallback-dump path (chunksFound>1 but nothing assembled) yields empty citations → no hint.
    assertEquals("", McpToolSurface.comparativeAnswerHint(List.of()));
    assertEquals("", McpToolSurface.comparativeAnswerHint(null));

    // Genuinely multi-document assembly → fires with the honest distinct-document count.
    String hint =
        McpToolSurface.comparativeAnswerHint(List.of(cite("docA"), cite("docB"), cite("docC")));
    assertTrue(hint.contains("3 documents"), "distinct-document count must drive the hint: " + hint);
    assertTrue(hint.contains("single retrieval call"));

    // Blank parentDocId is not counted as a document (only docA is real → 1 distinct → no claim).
    assertEquals("", McpToolSurface.comparativeAnswerHint(List.of(cite(""), cite("docA"))));
  }

  private static DocumentService.ContextCitation cite(String parentDocId) {
    return new DocumentService.ContextCitation(
        parentDocId, 0, 1, 0, 0, 1.0f, "excerpt", 0, 0, "", 0);
  }

  @Test
  void toolsList_returnsCuratedFiveTools() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

    // Tempdoc 501 Phase 15 added justsearch_runtime_manifest as the sixth tool.
    assertEquals(6, tools.size());
    assertEquals("justsearch_answer", tools.get(0).get("name"));
    assertEquals("justsearch_search", tools.get(1).get("name"));
    assertEquals("justsearch_browse", tools.get(2).get("name"));
    assertEquals("justsearch_ingest", tools.get(3).get("name"));
    assertEquals("justsearch_status", tools.get(4).get("name"));
    assertEquals("justsearch_runtime_manifest", tools.get(5).get("name"));

    String answerDesc = (String) tools.get(0).get("description");
    assertTrue(answerDesc.contains("primary tool for question-answering"));
  }

  @Test
  void toolsCall_unknownTool_returnsError() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nonexistent\",\"arguments\":{}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
  }

  @Test
  void toolsCall_statusWithoutKnowledge_returnsUnavailable() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"justsearch_status\",\"arguments\":{}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
  }

  @Test
  void promptsList_returnsThreeTemplates() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");

    assertEquals(3, prompts.size());
    assertEquals("search_files", prompts.get(0).get("name"));
    assertEquals("answer_question", prompts.get(1).get("name"));
    assertEquals("index_folder", prompts.get(2).get("name"));
  }

  @Test
  void promptsGet_expandsSearchTemplate() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"search_files\","
                + "\"arguments\":{\"topic\":\"climate change\"}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
    assertEquals(2, messages.size());
    assertEquals("assistant", messages.get(0).get("role"));
    assertEquals("user", messages.get(1).get("role"));
  }

  @Test
  void ping_returnsEmptyResult() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    assertNotNull(response.get("result"));
  }

  // =========================================================================
  // Tempdoc 655: gated confirmation now creates a real pending record instead of a fabricated
  // (and never-honored) "_confirmationToken" hint, and all 6 tools validate arguments at the
  // MCP boundary before dispatch.
  // =========================================================================

  private String callTool(McpProtocolHandler h, int id, String toolName, String argumentsJson)
      throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + toolName
                + "\",\"arguments\":"
                + argumentsJson
                + "}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    h.handlePost(ctx);
    return resultCaptor.getValue();
  }

  @Test
  void toolsCall_gatedIngest_createsPendingAndReturnsTruthfulMessage() throws Exception {
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    assertEquals(0, pendingStore.size());
    String raw =
        callTool(gatedHandler, 10, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");

    // The old, never-honored retry hint must be gone.
    assertFalse(text.contains("_confirmationToken"), "must not advertise the dead retry path");
    // The new message is truthful: approval lives in the app; no retry needed.
    assertTrue(text.contains("JustSearch app"), "must point at the app as the approval surface");
    assertTrue(
        text.contains("do not need to retry"), "must not tell the agent to retry this call");

    // A real pending record was created — the SAME mechanism the browser UI's gate path uses.
    assertEquals(1, pendingStore.size());
  }

  @Test
  void initialize_capturesClientInfo_surfacesAsRequestedByOnGatedPending() throws Exception {
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore =
        new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    // initialize with clientInfo — capture the minted session id off the response header setter.
    Context initCtx = mock(Context.class);
    when(initCtx.header("Mcp-Session-Id")).thenReturn(null);
    when(initCtx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"clientInfo\":{\"name\":\"Claude Code\",\"version\":\"1.0\"}}}");
    ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
    when(initCtx.header(org.mockito.ArgumentMatchers.eq("Mcp-Session-Id"), sessionIdCaptor.capture()))
        .thenReturn(initCtx);
    when(initCtx.result(anyString())).thenReturn(initCtx);
    when(initCtx.contentType(anyString())).thenReturn(initCtx);
    gatedHandler.handlePost(initCtx);
    String sessionId = sessionIdCaptor.getValue();
    assertNotNull(sessionId, "initialize must mint and return a session id");

    // tools/call reusing that session — the gate fires, creating a pending record.
    java.util.concurrent.atomic.AtomicReference<String> pendingId =
        new java.util.concurrent.atomic.AtomicReference<>();
    pendingChanges.subscribeTyped(event -> pendingId.set(event.pendingId()));
    Context callCtx = mock(Context.class);
    when(callCtx.header("Mcp-Session-Id")).thenReturn(sessionId);
    when(callCtx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"justsearch_ingest\",\"arguments\":{\"paths\":[\"C:/tmp/notes\"]}}}");
    when(callCtx.result(anyString())).thenReturn(callCtx);
    when(callCtx.contentType(anyString())).thenReturn(callCtx);
    gatedHandler.handlePost(callCtx);

    assertEquals(1, pendingStore.size());
    assertNotNull(pendingId.get(), "the advisory subscription must have observed the broadcast");
    var pending = pendingStore.peek(pendingId.get());
    assertTrue(pending.isPresent());
    assertEquals(
        "Claude Code",
        pending.get().requestedBy(),
        "the MCP client's self-reported clientInfo.name must flow through to the pending record");
  }

  @Test
  void toolsCall_noClientInfo_pendingRequestedByIsNull() throws Exception {
    // A client that omits clientInfo (or never calls initialize with a session at all) must not
    // fabricate a requester name — requestedBy stays null, not "" or "unknown".
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore =
        new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    // No prior initialize for this session id — matches this file's existing callTool() helper,
    // which always uses a bare "s1" session id never registered via initialize.
    java.util.concurrent.atomic.AtomicReference<String> pendingId =
        new java.util.concurrent.atomic.AtomicReference<>();
    pendingChanges.subscribeTyped(event -> pendingId.set(event.pendingId()));
    callTool(gatedHandler, 20, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    assertEquals(1, pendingStore.size());
    assertNotNull(pendingId.get());
    var pending = pendingStore.peek(pendingId.get());
    assertTrue(pending.isPresent());
    assertNull(
        pending.get().requestedBy(),
        "requestedBy must stay null (not a fabricated placeholder) when the session has no"
            + " captured clientInfo");
  }

  @Test
  void toolsCall_dispatchSucceeds_createsNoPendingRecord() throws Exception {
    // Simulates the already-working durable-grant path: the trust lattice was satisfied
    // (by an existing allow-always grant) before McpToolSurface ever sees a gate exception, so
    // dispatch just succeeds. Proves the new pending-authorization wiring only activates on an
    // actual gate firing, not on every ingest call.
    OperationDispatcher successDispatcher = mock(OperationDispatcher.class);
    when(successDispatcher.dispatch(any(), any(), any()))
        .thenReturn(OperationResult.success("Indexed 1 item", Map.of()));
    PendingAuthorizationStore pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            successDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var successHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    String raw =
        callTool(successHandler, 11, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertFalse((Boolean) result.get("isError"));
    assertEquals(0, pendingStore.size());
  }

  @Test
  void toolsCall_malformedSearchArgs_rejectedAtBoundaryBeforeDispatch() throws Exception {
    // justsearch_search declares "query" as required + type "string" — a numeric query must be
    // rejected by the new boundary validation, not reach the (null, in this test) knowledge
    // lookup and fail with an unrelated "Knowledge server not available" message.
    String raw = callTool(handler, 12, "justsearch_search", "{\"query\":123}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");
    assertTrue(text.contains("Invalid arguments"), "must be the boundary-validation error: " + text);
  }

  @Test
  void toolsCall_malformedNestedFilter_rejectedAtBoundaryBeforeDispatch() throws Exception {
    // Tempdoc 655 fix pass: `filters` was previously an opaque "object" in the schema, so a
    // malformed NESTED field (path_prefix as a number instead of a string) fell through the
    // boundary validation and reached McpToolSurface#parseFilters's unchecked cast. Declaring
    // filters' nested shape closes that gap — this must now be a clean boundary-validation error,
    // not an unrelated failure surfaced from deeper in the call.
    String raw =
        callTool(
            handler, 13, "justsearch_search", "{\"query\":\"x\",\"filters\":{\"path_prefix\":123}}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");
    assertTrue(text.contains("Invalid arguments"), "must be the boundary-validation error: " + text);
  }
}
