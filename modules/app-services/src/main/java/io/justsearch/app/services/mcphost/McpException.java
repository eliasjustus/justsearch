/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

/**
 * Transport- or protocol-level failure talking to an external MCP server.
 *
 * <p>Unchecked so it composes cleanly with {@code OperationHandler} dispatch (tempdoc 560 §4.4 — the
 * EXECUTABLE axis; the MCP-host first consumer). Distinguishes a host-side infrastructure failure
 * (process died, request timed out, malformed JSON-RPC) from a tool-reported error, which surfaces
 * as {@link McpToolResult#isError()} instead.
 */
public final class McpException extends RuntimeException {
  public McpException(String message) {
    super(message);
  }

  public McpException(String message, Throwable cause) {
    super(message, cause);
  }
}
