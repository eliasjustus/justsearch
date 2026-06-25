/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared HTTP request helpers for {@link InferenceLifecycleManager} companion classes.
 *
 * <p>All methods are pure static with no instance state. Extracted to avoid duplicating URI/request
 * construction across {@link TokenEndpointOps}, {@link OnlineModeOps}, and {@link
 * LlamaServerProcessOps}.
 */
final class InferenceHttpHelpers {
  private static final Logger LOG = LoggerFactory.getLogger(InferenceHttpHelpers.class);

  private InferenceHttpHelpers() {}

  /** Safely parse a JSON node as an {@link Integer}, returning {@code null} on failure. */
  static Integer asIntOrNull(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      if (node.isInt() || node.isLong() || node.isNumber()) {
        return node.asInt();
      }
      if (node.isTextual()) {
        String s = node.asText().trim();
        if (s.isBlank()) return null;
        return Integer.parseInt(s);
      }
    } catch (Exception e) {
      LOG.debug("asIntOrNull: parsing failed: {}", e.getMessage());
      return null;
    }
    return null;
  }

  static URI serverUri(int port, String path) {
    return URI.create("http://localhost:" + port + path);
  }

  static HttpRequest buildJsonPostRequest(int port, String path, String json, Duration timeout) {
    return HttpRequest.newBuilder()
        .uri(serverUri(port, path))
        .header("Content-Type", "application/json")
        .timeout(timeout)
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
  }

  static HttpRequest buildGetRequest(int port, String path, Duration timeout) {
    return HttpRequest.newBuilder().uri(serverUri(port, path)).timeout(timeout).GET().build();
  }
}
