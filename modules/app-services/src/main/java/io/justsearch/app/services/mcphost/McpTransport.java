/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/**
 * The wire seam to a single external MCP server: synchronous JSON-RPC 2.0 request/response plus
 * fire-and-forget notifications.
 *
 * <p>Abstracting the transport keeps {@link McpClient} pure protocol logic and lets tests drive the
 * client with an in-process fake instead of spawning a subprocess. The production implementation is
 * {@link StdioMcpTransport} (newline-delimited JSON-RPC over a child process's stdio — the standard
 * MCP local transport, and the cheapest isolation per tempdoc 560 §4.4 / P2).
 */
public interface McpTransport extends AutoCloseable {
  /**
   * Send a JSON-RPC request and block for its response.
   *
   * @return the {@code result} node of the response (a {@code MissingNode} if the server returned an
   *     empty result)
   * @throws McpException on a JSON-RPC error response, a timeout, or a transport failure
   */
  JsonNode request(String method, JsonNode params, Duration timeout);

  /** Send a JSON-RPC notification (no {@code id}, no response expected). */
  void notify(String method, JsonNode params);

  /**
   * Register a handler for <em>server-initiated</em> messages (notifications + server→client
   * requests, e.g. {@code notifications/tools/list_changed}, {@code sampling/createMessage}). The
   * handler receives the raw JSON-RPC message and may reply to requests via {@link #respond} /
   * {@link #respondError}. Without a handler, server requests are auto-answered method-not-found so
   * the server never hangs.
   */
  void setIncomingHandler(java.util.function.Consumer<JsonNode> handler);

  /** Reply to a server→client request with a result. */
  void respond(JsonNode id, JsonNode result);

  /** Reply to a server→client request with a JSON-RPC error. */
  void respondError(JsonNode id, int code, String message);

  @Override
  void close();
}
