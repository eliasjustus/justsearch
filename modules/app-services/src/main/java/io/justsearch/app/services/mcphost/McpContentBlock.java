/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import tools.jackson.databind.JsonNode;

/**
 * One block of an MCP tool result's {@code content} array (tempdoc 560 Phase 1 — non-text content).
 *
 * <p>MCP content is typed: {@code text} (a {@code text} field), {@code image} / {@code audio} (base64
 * {@code data} + {@code mimeType}), and {@code resource} (an embedded resource carrying {@code uri} /
 * {@code mimeType} / {@code text}). Carrying the blocks structurally — rather than flattening to a
 * single string — lets non-text blocks (e.g. an image a tool returns) survive all the way to the UI
 * instead of being silently dropped (the de-risk found a real server returns image blocks).
 */
public record McpContentBlock(String type, String text, String data, String mimeType, String uri) {

  public static McpContentBlock fromJson(JsonNode block) {
    String type = block.path("type").asString("");
    String text = block.path("text").asString("");
    String data = block.path("data").asString("");
    String mimeType = block.path("mimeType").asString("");
    String uri = "";
    JsonNode resource = block.get("resource");
    if (resource != null && !resource.isNull()) {
      uri = resource.path("uri").asString("");
      if (mimeType.isEmpty()) {
        mimeType = resource.path("mimeType").asString("");
      }
      if (text.isEmpty()) {
        text = resource.path("text").asString("");
      }
    }
    return new McpContentBlock(type, text, data, mimeType, uri);
  }

  public boolean isText() {
    return "text".equals(type);
  }

  public boolean isImage() {
    return "image".equals(type);
  }
}
