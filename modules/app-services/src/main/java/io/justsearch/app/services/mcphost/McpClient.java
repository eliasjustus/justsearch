/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client for one external MCP server: handshake, tool discovery, tool invocation.
 *
 * <p>Pure protocol logic over an {@link McpTransport} (so it is unit-testable with an in-process
 * fake). This is the genuinely-new build the MCP-host first consumer requires (tempdoc 560 §6 — the
 * rest is collapse-and-join); JustSearch previously had only an MCP <em>server</em>. The host owns
 * truth (§4.5): the client <em>reads</em> the server's catalog and <em>feeds</em> tool calls; it
 * never grants the server authority over JustSearch's own operations.
 */
public final class McpClient implements AutoCloseable {
  /** MCP protocol revision advertised in {@code initialize} (widely supported baseline). */
  public static final String PROTOCOL_VERSION = "2024-11-05";

  private static final Logger log = LoggerFactory.getLogger(McpClient.class);

  private final McpTransport transport;
  private final ObjectMapper mapper;
  private final Duration timeout;
  private volatile boolean initialized;
  private volatile String serverProtocolVersion = "";
  // Server→client request/notification handlers (tempdoc 560 Phase 1). When the server asks the host
  // to run an LLM completion (sampling/createMessage), answer via this; on tools/list_changed, refresh.
  private volatile Function<JsonNode, JsonNode> samplingHandler;
  private volatile Runnable toolsChangedListener;

  public McpClient(McpTransport transport) {
    this(transport, JsonMapper.builder().build(), Duration.ofSeconds(30));
  }

  public McpClient(McpTransport transport, ObjectMapper mapper, Duration timeout) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.transport.setIncomingHandler(this::onIncoming);
  }

  /** Answer server→client {@code sampling/createMessage} (host LLM); null ⇒ reply not-supported. */
  public void setSamplingHandler(Function<JsonNode, JsonNode> handler) {
    this.samplingHandler = handler;
  }

  /** Run on {@code notifications/tools/list_changed} (e.g. re-list + re-project the server's tools). */
  public void setToolsChangedListener(Runnable listener) {
    this.toolsChangedListener = listener;
  }

  /** The protocol version the server reported at {@code initialize} (empty until initialized). */
  public String serverProtocolVersion() {
    return serverProtocolVersion;
  }

  private void onIncoming(JsonNode msg) {
    String method = msg.path("method").asString("");
    JsonNode id = msg.get("id");
    boolean isRequest = id != null && !id.isNull();
    switch (method) {
      case "notifications/tools/list_changed" -> {
        Runnable listener = toolsChangedListener;
        if (listener != null) {
          listener.run();
        }
      }
      case "notifications/progress", "notifications/message", "notifications/cancelled" ->
          log.debug("MCP notification: {}", method);
      case "sampling/createMessage" -> {
        if (!isRequest) {
          return;
        }
        Function<JsonNode, JsonNode> handler = samplingHandler;
        if (handler == null) {
          transport.respondError(id, -32601, "sampling not supported by host");
          return;
        }
        try {
          transport.respond(id, handler.apply(msg.path("params")));
        } catch (RuntimeException e) {
          transport.respondError(id, -32603, "sampling failed: " + e.getMessage());
        }
      }
      case "roots/list" -> {
        if (isRequest) {
          ObjectNode roots = mapper.createObjectNode();
          roots.set("roots", mapper.createArrayNode());
          transport.respond(id, roots);
        }
      }
      default -> {
        if (isRequest) {
          transport.respondError(id, -32601, "method not handled by host: " + method);
        }
      }
    }
  }

  /** Perform the MCP {@code initialize} handshake and send {@code notifications/initialized}. */
  public synchronized void initialize() {
    ObjectNode params = mapper.createObjectNode();
    params.put("protocolVersion", PROTOCOL_VERSION);
    params.set("capabilities", mapper.createObjectNode());
    ObjectNode clientInfo = mapper.createObjectNode();
    clientInfo.put("name", "justsearch-mcp-host");
    clientInfo.put("version", "1.0");
    params.set("clientInfo", clientInfo);

    JsonNode init = transport.request("initialize", params, timeout);
    serverProtocolVersion = init.path("protocolVersion").asString("");
    if (!serverProtocolVersion.isEmpty() && !serverProtocolVersion.equals(PROTOCOL_VERSION)) {
      log.info("MCP server protocol version {} differs from client {}", serverProtocolVersion, PROTOCOL_VERSION);
    }
    transport.notify("notifications/initialized", mapper.createObjectNode());
    initialized = true;
  }

  /** Discover the tools the server advertises ({@code tools/list}), following pagination cursors. */
  public List<McpTool> listTools() {
    requireInitialized();
    List<McpTool> tools = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      ObjectNode params = mapper.createObjectNode();
      if (cursor != null) {
        params.put("cursor", cursor);
      }
      JsonNode result = transport.request("tools/list", params, timeout);
      JsonNode toolsNode = result.get("tools");
      if (toolsNode != null && toolsNode.isArray()) {
        for (JsonNode tool : toolsNode) {
          String name = tool.path("name").asString("");
          if (name.isBlank()) {
            continue;
          }
          String description = tool.path("description").asString("");
          JsonNode schema = tool.get("inputSchema");
          String schemaJson = (schema == null || schema.isNull()) ? "{}" : mapper.writeValueAsString(schema);
          tools.add(new McpTool(name, description, schemaJson));
        }
      }
      String next = result.path("nextCursor").asString("");
      cursor = next.isEmpty() ? null : next;
    } while (cursor != null && ++pages < 100); // 100-page guard against a misbehaving server
    return List.copyOf(tools);
  }

  /**
   * Invoke a tool ({@code tools/call}). A tool-reported failure returns {@link McpToolResult} with
   * {@code isError=true}; a transport/protocol failure throws {@link McpException}.
   */
  public McpToolResult callTool(String name, JsonNode arguments) {
    requireInitialized();
    ObjectNode params = mapper.createObjectNode();
    params.put("name", name);
    params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);

    JsonNode result = transport.request("tools/call", params, timeout);
    boolean isError = result.path("isError").asBoolean(false);
    StringBuilder text = new StringBuilder();
    List<McpContentBlock> blocks = new ArrayList<>();
    JsonNode content = result.get("content");
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        McpContentBlock parsed = McpContentBlock.fromJson(block);
        blocks.add(parsed);
        if (parsed.isText() && !parsed.text().isEmpty()) {
          if (text.length() > 0) {
            text.append('\n');
          }
          text.append(parsed.text());
        }
      }
    }
    return new McpToolResult(isError, text.toString(), blocks, result);
  }

  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void close() {
    transport.close();
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new McpException("MCP client used before initialize()");
    }
  }
}
