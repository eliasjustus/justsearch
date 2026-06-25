/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 583 §D.3c — OpenAPI export: {@code GET /api/meta/openapi.json}.
 *
 * <p>Composes (does not fork) the §D.3a route manifest into an OpenAPI 3.1 document, so the live HTTP
 * surface is consumable by standard OpenAPI tooling (Swagger UI, Postman import, generated clients).
 * Because it derives from {@link RouteManifestController#build} — itself built from the live Javalin
 * router + {@link RouteCapabilityPolicy} — the document can never drift from what is actually
 * registered or gated. Each route's cohort becomes an OpenAPI tag and its required capabilities an
 * {@code x-required-capabilities} extension (the OpenAPI spec reserves {@code x-} for vendor data).
 *
 * <p>Scope (honest): a <em>structural</em> export — paths, methods, path-parameters, tags, capability
 * gates, owning module, and (for documented wire routes, §D.3a + the {@link RouteResponseSchemas} map)
 * a per-route response-schema {@code $ref} into {@code components.schemas}. <em>Request</em> bodies and
 * response schemas for the long tail of undocumented routes remain out of scope (a per-route schema
 * authority for all ~200 routes is a separate charter — handler annotations would be a second source
 * of truth). Runtime endpoint (not a build artifact) because the manifest is a runtime enumeration of
 * the live router — there is no static route source to read at build time. Stateless; owned by {@link
 * MetaApiModule} (no LocalApiServer field — §D.4 ceiling).
 */
final class OpenApiController {
  private static final Logger log = LoggerFactory.getLogger(OpenApiController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String OPENAPI_VERSION = "3.1.0";
  private static final String API_VERSION = "1.0";
  // Javalin path params are {name}; wildcards are <name>. Both normalize to OpenAPI {name}.
  private static final Pattern PARAM = Pattern.compile("\\{([^}]+)\\}");

  private final Supplier<Javalin> appSupplier;
  private final Supplier<List<ApiModule>> modulesSupplier;

  OpenApiController(Supplier<Javalin> appSupplier, Supplier<List<ApiModule>> modulesSupplier) {
    this.appSupplier = appSupplier;
    this.modulesSupplier = modulesSupplier;
  }

  void handle(Context ctx) {
    try {
      Map<String, Object> doc = build(appSupplier.get(), modulesSupplier.get());
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(doc));
    } catch (Exception e) {
      log.error("Failed to build/serialize OpenAPI document", e);
      throw new IllegalStateException("OpenAPI serialization failed", e);
    }
  }

  /** Compose the route manifest into an OpenAPI 3.1 document (ordered for stable output). */
  static Map<String, Object> build(Javalin app, List<ApiModule> modules) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("title", "JustSearch Local API");
    info.put("version", API_VERSION);
    info.put(
        "description",
        "Loopback-only local HTTP surface of the JustSearch Head process. Generated from the live"
            + " route manifest (tempdoc 583 §D.3c) — derived from the running router and the single"
            + " RouteCapabilityPolicy authority, so it cannot drift from what is registered/gated.");

    // path -> (method-lowercase -> operation). LinkedHashMap preserves the manifest's sorted order.
    Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
    Map<String, Object> schemas = new java.util.TreeMap<>(); // components.schemas (sorted, deduped)
    for (RouteEntry r : RouteManifestController.build(app, modules)) {
      String openApiPath = normalizePath(r.path());
      Map<String, Object> pathItem =
          paths.computeIfAbsent(openApiPath, k -> new LinkedHashMap<>());
      pathItem.put(r.method().toLowerCase(java.util.Locale.ROOT), operation(r, openApiPath, schemas));
    }

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("openapi", OPENAPI_VERSION);
    doc.put("info", info);
    doc.put("paths", paths);
    if (!schemas.isEmpty()) {
      doc.put("components", Map.of("schemas", schemas));
    }
    return doc;
  }

  private static Map<String, Object> operation(
      RouteEntry r, String openApiPath, Map<String, Object> schemas) {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("summary", r.method() + " " + r.path());
    op.put("tags", List.of(r.cohort()));
    if (r.owningModule() != null) {
      op.put("x-owning-module", r.owningModule());
    }
    if (!r.requiredCapabilities().isEmpty()) {
      op.put("x-required-capabilities", r.requiredCapabilities());
    }
    List<Map<String, Object>> params = pathParameters(openApiPath);
    if (!params.isEmpty()) {
      op.put("parameters", params);
    }
    Map<String, Object> ok = new LinkedHashMap<>();
    ok.put("description", "Successful response.");
    // §D.3a schema dimension: for documented wire routes, $ref the generated canonical schema (served
    // by SchemaController at /api/schemas/{name}); register it in components.schemas. Other routes get
    // a generic success response so the document still validates.
    String schema = r.responseSchema();
    if (schema != null) {
      String componentName = schema.replaceAll("\\.v\\d+\\.json$", "").replace(".json", "");
      schemas.putIfAbsent(componentName, Map.of("$ref", "/api/schemas/" + schema));
      ok.put(
          "content",
          Map.of(
              "application/json",
              Map.of("schema", Map.of("$ref", "#/components/schemas/" + componentName))));
    }
    op.put("responses", Map.of("200", ok));
    return op;
  }

  /** Declare each {param} segment as a required string path parameter (OpenAPI validity). */
  private static List<Map<String, Object>> pathParameters(String openApiPath) {
    List<Map<String, Object>> params = new ArrayList<>();
    Matcher m = PARAM.matcher(openApiPath);
    while (m.find()) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("name", m.group(1));
      p.put("in", "path");
      p.put("required", true);
      p.put("schema", Map.of("type", "string"));
      params.add(p);
    }
    return params;
  }

  /** Javalin {name} stays {name}; wildcard <name> becomes {name}; trailing /* becomes /{wildcard}. */
  private static String normalizePath(String javalinPath) {
    String p = javalinPath.replace('<', '{').replace('>', '}');
    if (p.endsWith("/*")) {
      p = p.substring(0, p.length() - 1) + "{wildcard}";
    }
    return p.replace("*", "{wildcard}");
  }
}
