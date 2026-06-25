package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Unit tests for {@link McpClient} protocol logic, driven by an in-process fake transport. */
class McpClientTest {
  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void initializeSendsHandshakeAndInitializedNotification() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);

    assertFalse(client.isInitialized());
    client.initialize();

    assertTrue(client.isInitialized());
    assertTrue(transport.requestedMethods.contains("initialize"));
    assertEquals(List.of("notifications/initialized"), transport.notifications);
  }

  @Test
  void listToolsProjectsServerCatalog() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();

    List<McpTool> tools = client.listTools();

    assertEquals(2, tools.size());
    McpTool echo = tools.get(0);
    assertEquals("echo", echo.name());
    assertEquals("Echo back the provided message", echo.description());
    assertTrue(echo.inputSchemaJson().contains("\"message\""), echo.inputSchemaJson());
  }

  @Test
  void callToolReturnsFlattenedText() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();

    ObjectNode args = mapper.createObjectNode();
    args.put("message", "hello world");
    McpToolResult result = client.callTool("echo", args);

    assertFalse(result.isError());
    assertEquals("hello world", result.text());
  }

  @Test
  void callToolEvaluatesStructuredArguments() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();

    ObjectNode args = mapper.createObjectNode();
    args.put("a", 2);
    args.put("b", 40);
    McpToolResult result = client.callTool("add", args);

    assertFalse(result.isError());
    assertEquals("42", result.text());
  }

  @Test
  void toolReportedErrorSurfacesAsIsErrorNotException() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();

    McpToolResult result = client.callTool("boom", mapper.createObjectNode());

    assertTrue(result.isError());
    assertEquals("tool failed", result.text());
  }

  @Test
  void usingClientBeforeInitializeThrows() {
    McpClient client = new McpClient(new FakeMcpTransport());
    assertThrows(McpException.class, client::listTools);
  }

  @Test
  void transportFailurePropagatesAsMcpException() {
    FakeMcpTransport transport = new FakeMcpTransport();
    transport.transportFailure = true;
    McpClient client = new McpClient(transport);

    assertThrows(McpException.class, client::initialize);
  }

  @Test
  void unknownToolThrowsMcpException() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();
    assertThrows(McpException.class, () -> client.callTool("nonexistent", mapper.createObjectNode()));
  }

  // ---- Phase 1 robustness ----

  @Test
  void callToolPreservesNonTextContentBlocks() {
    McpClient client = new McpClient(new FakeMcpTransport());
    client.initialize();
    McpToolResult result = client.callTool("get-image", mapper.createObjectNode());
    assertTrue(result.hasNonTextContent(), "image block must survive, not be flattened to text");
    assertTrue(result.blocks().stream().anyMatch(McpContentBlock::isImage));
    assertEquals("here is an image", result.text(), "text blocks still flatten for the model");
  }

  @Test
  void samplingRequestIsAnsweredByTheHostHandler() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);
    client.initialize();
    client.setSamplingHandler(
        params -> {
          ObjectNode r = mapper.createObjectNode();
          r.put("answer", "from-host-llm");
          return r;
        });
    transport.simulateIncoming(serverRequest(7, "sampling/createMessage"));
    assertEquals(1, transport.responses.size());
    assertEquals("from-host-llm", transport.responses.get(0).path("result").path("answer").asString(""));
  }

  @Test
  void samplingRequestWithoutHandlerRepliesErrorNotHang() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);
    client.initialize();
    transport.simulateIncoming(serverRequest(8, "sampling/createMessage"));
    assertEquals(1, transport.responses.size(), "must reply (not hang) even with no sampler");
    assertTrue(transport.responses.get(0).has("error"));
  }

  @Test
  void toolsListChangedNotificationFiresTheListener() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);
    client.initialize();
    java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
    client.setToolsChangedListener(() -> fired.set(true));
    ObjectNode notif = mapper.createObjectNode();
    notif.put("jsonrpc", "2.0");
    notif.put("method", "notifications/tools/list_changed");
    transport.simulateIncoming(notif);
    assertTrue(fired.get());
  }

  @Test
  void unknownServerRequestRepliesMethodNotFound() {
    FakeMcpTransport transport = new FakeMcpTransport();
    McpClient client = new McpClient(transport);
    client.initialize();
    transport.simulateIncoming(serverRequest(9, "totally/unknown"));
    assertEquals(1, transport.responses.size());
    assertEquals(-32601, transport.responses.get(0).path("error").path("code").asInt(0));
  }

  private ObjectNode serverRequest(int id, String method) {
    ObjectNode req = mapper.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", id);
    req.put("method", method);
    req.set("params", mapper.createObjectNode());
    return req;
  }
}
