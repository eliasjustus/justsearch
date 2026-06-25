/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declaration of one external MCP server the host should connect to and project tools from.
 *
 * <p>{@code id} is a stable, OperationRef-safe segment ({@code [a-z][a-z0-9-]*}) used to namespace
 * the server's tools as {@code vendor.mcphost.<id>-<tool>} operations (tempdoc 560 §4.4 — the
 * EXECUTABLE axis). {@code command} is the subprocess argv (e.g. {@code ["node", "server.mjs"]}).
 */
public record McpServerConfig(String id, List<String> command, Map<String, String> env) {
  private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]*$");

  public McpServerConfig {
    Objects.requireNonNull(id, "id");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("MCP server id must match " + ID.pattern() + ": " + id);
    }
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("MCP server command must not be empty");
    }
    command = List.copyOf(command);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  public McpServerConfig(String id, List<String> command) {
    this(id, command, Map.of());
  }
}
