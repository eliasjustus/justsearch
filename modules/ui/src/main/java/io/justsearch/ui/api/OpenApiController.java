/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 583 §D.3c — OpenAPI export: {@code GET /api/meta/openapi.json}.
 *
 * <p>The runtime endpoint enumerates the live Javalin router and delegates the structural projection
 * to {@link OpenApiRenderer}. The committed offline snapshot uses that same renderer; OpenAPI
 * semantics therefore have one Java implementation.
 *
 * <p>Scope (honest): a <em>structural</em> export — paths, methods, path-parameters, tags, capability
 * gates, owning module, and (for documented wire routes, §D.3a + the {@link RouteResponseSchemas} map)
 * a per-route response-schema {@code $ref} into {@code components.schemas}. <em>Request</em> bodies and
 * response schemas for the long tail of undocumented routes remain out of scope (a per-route schema
 * authority for all ~200 routes is a separate charter — handler annotations would be a second source
 * of truth). The endpoint remains runtime-derived. A committed build artifact can only project a
 * point-in-time route capture and has a separate live-fidelity proof edge. Stateless; owned by {@link
 * MetaApiModule} (no LocalApiServer field — §D.4 ceiling). The full inventory mixes reference-client
 * and internal routes and is not the narrowly versioned JustSearch Runtime Contract.
 */
final class OpenApiController {
  private static final Logger log = LoggerFactory.getLogger(OpenApiController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private final Supplier<Javalin> appSupplier;
  private final Supplier<List<ApiModule>> modulesSupplier;

  OpenApiController(Supplier<Javalin> appSupplier, Supplier<List<ApiModule>> modulesSupplier) {
    this.appSupplier = appSupplier;
    this.modulesSupplier = modulesSupplier;
  }

  void handle(Context ctx) {
    try {
      Map<String, Object> doc =
          OpenApiRenderer.render(
              RouteManifestController.build(appSupplier.get(), modulesSupplier.get()));
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(doc));
    } catch (Exception e) {
      log.error("Failed to build/serialize OpenAPI document", e);
      throw new IllegalStateException("OpenAPI serialization failed", e);
    }
  }

}
