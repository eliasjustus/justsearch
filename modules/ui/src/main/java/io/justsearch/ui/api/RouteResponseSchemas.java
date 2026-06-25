/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.Map;

/**
 * Tempdoc 583 §D.3a (schema dimension) — the response wire-record JSON Schema a route returns.
 *
 * <p>The route manifest enumerates the live router (method/path), but the router holds no link from a
 * route to the record it returns. This declarative table supplies that link for the first-class wire
 * routes, pointing at the generated canonical schemas under {@code SSOT/schemas/} (the same ones the
 * {@code SchemaController} serves at {@code /api/schemas/{name}} and the wire-type codegen projects to
 * the FE). The manifest carries the schema name; {@link OpenApiController} turns it into a resolvable
 * {@code $ref}.
 *
 * <p><b>Partial by design.</b> Only routes that return a documented wire record (those in
 * {@code governance/contract-surfaces.v1.json}) are mapped — a deliberately small, hand-maintained
 * set, because a per-route schema authority for all ~200 routes is a separate charter (handler
 * annotations would be a second source of truth, rejected per §D.3c). A wrong mapping is worse than
 * none, so only high-confidence routes are listed. <b>This map is the one maintenance point</b> when a
 * listed route's response type changes; unlisted routes simply have no {@code responseSchema}.
 */
final class RouteResponseSchemas {
  private RouteResponseSchemas() {}

  /** {@code "<METHOD> <path>"} → canonical schema file name under {@code SSOT/schemas/}. */
  private static final Map<String, String> SCHEMAS =
      Map.ofEntries(
          Map.entry("GET /api/knowledge/search", "knowledge-search-response.v1.json"),
          Map.entry("POST /api/knowledge/search", "knowledge-search-response.v1.json"),
          Map.entry("GET /api/ai/runtime/status", "ai-runtime-status-response.v1.json"),
          Map.entry("GET /api/policy/effective", "effective-policy.v1.json"),
          Map.entry("GET /api/runtime-context", "runtime-context.v1.json"),
          Map.entry("GET /api/operation-history", "operation-history-entry.v1.json"),
          Map.entry("GET /api/registry/resources", "resource.v1.json"));

  /** The schema file name for a route, or {@code null} if none is declared. */
  static String schemaFor(String method, String path) {
    return SCHEMAS.get(method + " " + path);
  }

  /**
   * The distinct schema file names this map references. Every one MUST be served by {@link
   * SchemaController} (else the OpenAPI {@code $ref} dangles); {@code RouteResponseSchemasCoverageTest}
   * enforces that closure.
   */
  static java.util.Set<String> declaredSchemaFiles() {
    return java.util.Set.copyOf(SCHEMAS.values());
  }
}
