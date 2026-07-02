/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.api.mcp.McpContractVersions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layer 1: MCP Streamable HTTP protocol transport.
 *
 * <p>Handles JSON-RPC framing, session management, and method dispatch. Delegates tool,
 * prompt, and resource logic to {@link McpToolSurface} (Layer 2). Per tempdoc 500's
 * three-layer architecture, this class has no knowledge of tool definitions, descriptions,
 * or backend dispatch paths.
 */
public final class McpProtocolHandler {

  private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String JSONRPC_VERSION = "2.0";
  // Tempdoc 654: single-sourced from app-api so the manifest's RuntimeContract and this
  // initialize response report identical versions by construction (projection, not fork).
  private static final String MCP_PROTOCOL_VERSION = McpContractVersions.PROTOCOL_VERSION;
  private static final String SERVER_NAME = "JustSearch";
  private static final String SERVER_VERSION = McpContractVersions.TOOL_SURFACE_VERSION;
  private static final Duration SESSION_TTL = Duration.ofMinutes(30);

  private final McpToolSurface surface;
  private final List<ResourceCatalog> resourceCatalogs;
  private final Clock clock;
  private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

  public McpProtocolHandler(McpToolSurface surface, List<ResourceCatalog> resourceCatalogs,
      Clock clock) {
    this.surface = Objects.requireNonNull(surface);
    this.resourceCatalogs = List.copyOf(Objects.requireNonNull(resourceCatalogs));
    this.clock = Objects.requireNonNull(clock);
  }

  public McpProtocolHandler(McpToolSurface surface, List<ResourceCatalog> resourceCatalogs) {
    this(surface, resourceCatalogs, Clock.systemUTC());
  }

  public void handlePost(Context ctx) {
    String sessionId = ctx.header("Mcp-Session-Id");
    String body = ctx.body();

    try {
      var node = MAPPER.readTree(body);
      String method = node.has("method") ? node.get("method").asText() : null;
      var id = node.has("id") ? node.get("id") : null;
      var params = node.has("params") ? node.get("params") : MAPPER.createObjectNode();

      if (method == null) {
        writeError(ctx, id, -32600, "Invalid Request: missing method");
        return;
      }

      Object result = switch (method) {
        case "initialize" -> handleInitialize(ctx);
        case "initialized" -> {
          yield null;
        }
        case "tools/list" -> surface.listTools();
        case "tools/call" -> handleToolsCall(params, sessionId);
        case "resources/list" -> surface.listResources(resourceCatalogs);
        case "resources/read" -> handleResourcesRead(params);
        case "resources/subscribe" -> handleResourcesSubscribe(params, sessionId);
        case "resources/unsubscribe" -> handleResourcesUnsubscribe(params, sessionId);
        case "prompts/list" -> surface.listPrompts();
        case "prompts/get" -> handlePromptsGet(params);
        case "ping" -> Map.of();
        default -> {
          writeError(ctx, id, -32601, "Method not found: " + method);
          yield null;
        }
      };

      if (result != null) {
        writeResult(ctx, id, result);
      } else if ("initialized".equals(method)) {
        ctx.status(204);
      }
    } catch (Exception e) {
      log.warn("MCP protocol error", e);
      writeError(ctx, null, -32603, "Internal error: " + e.getMessage());
    }
  }

  public void handleDelete(Context ctx) {
    String sessionId = ctx.header("Mcp-Session-Id");
    if (sessionId != null) sessions.remove(sessionId);
    ctx.status(204);
  }

  private Map<String, Object> handleInitialize(Context ctx) {
    cleanStaleSessions();
    String sessionId = UUID.randomUUID().toString();
    sessions.put(sessionId, new McpSession(clock.instant()));
    ctx.header("Mcp-Session-Id", sessionId);

    return Map.of(
        "protocolVersion", MCP_PROTOCOL_VERSION,
        "capabilities", Map.of(
            "tools", Map.of("listChanged", true),
            "resources", Map.of("subscribe", true, "listChanged", true),
            "prompts", Map.of("listChanged", false)),
        "serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleToolsCall(Object paramsObj, String sessionId) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    if (params == null) return McpToolSurface.errorContent("Invalid params");
    String toolName = (String) params.get("name");
    Map<String, Object> arguments =
        (Map<String, Object>) params.getOrDefault("arguments", Map.of());
    if (toolName == null) return McpToolSurface.errorContent("Tool name is required");
    touchSession(sessionId);
    return surface.callTool(toolName, arguments, sessionId);
  }

  private Map<String, Object> handleResourcesRead(Object paramsObj) {
    @SuppressWarnings("unchecked")
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    return surface.readResource(uri);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handlePromptsGet(Object paramsObj) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    if (params == null) return Map.of("messages", List.of());
    String name = (String) params.get("name");
    Map<String, String> arguments =
        (Map<String, String>) params.getOrDefault("arguments", Map.of());
    return surface.getPrompt(name, arguments);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleResourcesSubscribe(Object paramsObj, String sessionId) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    if (sessionId != null && uri != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.subscriptions.add(uri);
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleResourcesUnsubscribe(Object paramsObj, String sessionId) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    if (sessionId != null && uri != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.subscriptions.remove(uri);
    }
    return Map.of();
  }

  private void touchSession(String sessionId) {
    if (sessionId != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.lastActivity = clock.instant();
    }
  }

  private void cleanStaleSessions() {
    Instant cutoff = clock.instant().minus(SESSION_TTL);
    Iterator<Map.Entry<String, McpSession>> it = sessions.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().lastActivity.isBefore(cutoff)) {
        log.info("Evicting stale MCP session (idle > {})", SESSION_TTL);
        it.remove();
      }
    }
  }

  private void writeResult(Context ctx, Object id, Object result) {
    try {
      var response = new LinkedHashMap<String, Object>();
      response.put("jsonrpc", JSONRPC_VERSION);
      response.put("id", id);
      response.put("result", result);
      ctx.contentType("application/json");
      ctx.result(MAPPER.writeValueAsString(response));
    } catch (Exception e) {
      log.error("Failed to write MCP result", e);
      ctx.status(500);
    }
  }

  private void writeError(Context ctx, Object id, int code, String message) {
    try {
      var response = new LinkedHashMap<String, Object>();
      response.put("jsonrpc", JSONRPC_VERSION);
      response.put("id", id);
      response.put("error", Map.of("code", code, "message", message));
      ctx.contentType("application/json");
      ctx.result(MAPPER.writeValueAsString(response));
    } catch (Exception e) {
      log.error("Failed to write MCP error", e);
      ctx.status(500);
    }
  }

  private static final class McpSession {
    volatile Instant lastActivity;
    final java.util.Set<String> subscriptions = ConcurrentHashMap.newKeySet();

    McpSession(Instant createdAt) {
      this.lastActivity = createdAt;
    }
  }
}
