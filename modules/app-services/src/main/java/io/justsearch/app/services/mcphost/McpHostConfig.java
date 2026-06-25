/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the list of external MCP servers to host from a JSON file.
 *
 * <p>The path is resolved by the configuration authority ({@code EnvRegistry.MCP_HOST_CONFIG} —
 * {@code -Djustsearch.mcp.host.config} / {@code JUSTSEARCH_MCP_HOST_CONFIG}) at the bootstrap
 * entrypoint and passed in; this class only reads + parses the file (app-services may not read env
 * or system properties directly — {@code AppServicesWorkerGuardrailsTest}). Absent or empty ⇒ <b>no
 * servers</b> — the MCP-host is off by default and adds zero startup cost, so wiring it into
 * bootstrap is safe (tempdoc 560 §6: consumer-first, opt-in).
 *
 * <p>File shape:
 *
 * <pre>{@code
 * [ { "id": "reference",
 *     "command": ["node", "scripts/mcp/reference-server.mjs"],
 *     "env": { "FOO": "bar" } } ]
 * }</pre>
 */
public final class McpHostConfig {
  private static final Logger log = LoggerFactory.getLogger(McpHostConfig.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private McpHostConfig() {}

  /**
   * Resolve the configured servers from a path (null/empty ⇒ no servers). The path itself is
   * resolved by the configuration authority at the entrypoint, not read here.
   */
  public static List<McpServerConfig> fromPath(Path file) {
    return file == null ? List.of() : fromFile(file);
  }

  /** Parse a server list from a specific JSON file (empty + warn on any problem — never throws). */
  public static List<McpServerConfig> fromFile(Path file) {
    if (!Files.isRegularFile(file)) {
      log.warn("MCP-host config not found: {}", file);
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(Files.readString(file));
      return parse(root);
    } catch (RuntimeException | java.io.IOException e) {
      log.warn("MCP-host config at {} could not be read: {}", file, e.toString());
      return List.of();
    }
  }

  /** Parse a server list from an already-loaded JSON node. */
  public static List<McpServerConfig> parse(JsonNode root) {
    List<McpServerConfig> servers = new ArrayList<>();
    if (root == null || !root.isArray()) {
      return List.copyOf(servers);
    }
    for (JsonNode node : root) {
      try {
        servers.add(parseServer(node));
      } catch (RuntimeException e) {
        log.warn("MCP-host: skipping malformed server entry {}: {}", node, e.toString());
      }
    }
    return List.copyOf(servers);
  }

  private static McpServerConfig parseServer(JsonNode node) {
    String id = node.path("id").asString("");
    List<String> command = new ArrayList<>();
    JsonNode cmd = node.get("command");
    if (cmd != null && cmd.isArray()) {
      for (JsonNode arg : cmd) {
        command.add(arg.asString(""));
      }
    }
    Map<String, String> env = new LinkedHashMap<>();
    JsonNode envNode = node.get("env");
    if (envNode != null && envNode.isObject()) {
      envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asString("")));
    }
    return new McpServerConfig(id, command, env);
  }
}
