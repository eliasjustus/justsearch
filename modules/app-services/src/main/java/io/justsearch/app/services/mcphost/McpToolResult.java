/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The outcome of an external MCP {@code tools/call}.
 *
 * <p>{@code isError} is the server's own {@code isError} flag (a tool-level failure the model should
 * see and may recover from) — distinct from {@link McpException}, which is a host-side transport
 * failure. {@code text} is the flattened textual content blocks (what the model reads); {@code blocks}
 * is the full typed content array (text + image + resource — so non-text survives to the UI);
 * {@code raw} is the full result node for callers that need anything else.
 */
public record McpToolResult(boolean isError, String text, List<McpContentBlock> blocks, JsonNode raw) {
  public McpToolResult {
    blocks = blocks == null ? List.of() : List.copyOf(blocks);
  }

  /** True when any block carries non-text content (image / resource) the UI should render richly. */
  public boolean hasNonTextContent() {
    return blocks.stream().anyMatch(b -> !b.isText());
  }
}
