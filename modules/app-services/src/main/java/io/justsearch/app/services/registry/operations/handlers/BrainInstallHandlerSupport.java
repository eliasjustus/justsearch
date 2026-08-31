/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for {@link StartAiInstallHandler} + {@link RepairAiInstallHandler}.
 * Both parse the same {@code {"acceptTerms"?: boolean}} arg shape; this avoids
 * duplicating the Jackson tree-read across the two handlers.
 */
final class BrainInstallHandlerSupport {

  private BrainInstallHandlerSupport() {}

  static boolean parseAcceptTerms(String argumentsJson) {
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode v = root.get("acceptTerms");
      return v != null && v.isBoolean() && v.asBoolean();
    } catch (Exception e) {
      // tolerate parse errors — default false (matches HTTP handler semantics).
      return false;
    }
  }
}
