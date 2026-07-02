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
}
