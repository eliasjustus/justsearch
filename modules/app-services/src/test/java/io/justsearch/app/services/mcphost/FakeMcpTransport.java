package io.justsearch.app.services.mcphost;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-process {@link McpTransport} simulating an MCP server exposing two tools ({@code echo}, {@code
 * add}) — lets {@link McpClient} be exercised without spawning a subprocess.
 */
final class FakeMcpTransport implements McpTransport {
  private final ObjectMapper mapper = JsonMapper.builder().build();
  final List<String> notifications = new ArrayList<>();
  final List<String> requestedMethods = new ArrayList<>();
  boolean transportFailure = false;

  @Override
  public JsonNode request(String method, JsonNode params, Duration timeout) {
    requestedMethods.add(method);
    if (transportFailure) {
      throw new McpException("simulated transport failure on " + method);
    }
    return switch (method) {
      case "initialize" -> initializeResult();
      case "tools/list" -> toolsList();
      case "tools/call" -> toolsCall(params);
      default -> throw new McpException("unknown method: " + method);
    };
  }

  @Override
  public void notify(String method, JsonNode params) {
    notifications.add(method);
  }

  java.util.function.Consumer<JsonNode> incomingHandler;
  final List<JsonNode> responses = new ArrayList<>();

  @Override
  public void setIncomingHandler(java.util.function.Consumer<JsonNode> handler) {
    this.incomingHandler = handler;
  }

  @Override
  public void respond(JsonNode id, JsonNode result) {
    ObjectNode r = mapper.createObjectNode();
    r.set("id", id);
    r.set("result", result);
    responses.add(r);
  }

  @Override
  public void respondError(JsonNode id, int code, String message) {
    ObjectNode r = mapper.createObjectNode();
    r.set("id", id);
    ObjectNode e = mapper.createObjectNode();
    e.put("code", code);
    e.put("message", message);
    r.set("error", e);
    responses.add(r);
  }

  /** Feed a server-initiated message (notification/request) to the wired client handler (for tests). */
  void simulateIncoming(JsonNode msg) {
    if (incomingHandler != null) {
      incomingHandler.accept(msg);
    }
  }

  @Override
  public void close() {
    // no-op
  }

  private JsonNode initializeResult() {
    ObjectNode result = mapper.createObjectNode();
    result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
    result.set("capabilities", mapper.createObjectNode());
    return result;
  }

  private JsonNode toolsList() {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode tools = mapper.createArrayNode();
    tools.add(
        toolDef(
            "echo",
            "Echo back the provided message",
            "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}"));
    tools.add(
        toolDef(
            "add",
            "Add two integers",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}"));
    result.set("tools", tools);
    return result;
  }

  private ObjectNode toolDef(String name, String description, String schemaJson) {
    ObjectNode tool = mapper.createObjectNode();
    tool.put("name", name);
    tool.put("description", description);
    tool.set("inputSchema", mapper.readTree(schemaJson));
    return tool;
  }

  private JsonNode toolsCall(JsonNode params) {
    String name = params.path("name").asString("");
    JsonNode args = params.path("arguments");
    return switch (name) {
      case "echo" -> textResult(args.path("message").asString(""), false);
      case "add" -> textResult(
          Integer.toString(args.path("a").asInt(0) + args.path("b").asInt(0)), false);
      case "boom" -> textResult("tool failed", true);
      case "get-image" -> imageResult();
      default -> throw new McpException("unknown tool: " + name);
    };
  }

  /** A result with a text block followed by an image block (a real MCP multimodal shape). */
  private JsonNode imageResult() {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode content = mapper.createArrayNode();
    ObjectNode text = mapper.createObjectNode();
    text.put("type", "text");
    text.put("text", "here is an image");
    content.add(text);
    ObjectNode image = mapper.createObjectNode();
    image.put("type", "image");
    image.put("data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    image.put("mimeType", "image/png");
    content.add(image);
    result.set("content", content);
    result.put("isError", false);
    return result;
  }

  private JsonNode textResult(String text, boolean isError) {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode content = mapper.createArrayNode();
    ObjectNode block = mapper.createObjectNode();
    block.put("type", "text");
    block.put("text", text);
    content.add(block);
    result.set("content", content);
    result.put("isError", isError);
    return result;
  }
}
