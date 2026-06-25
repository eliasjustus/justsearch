/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 583 §D.3a — the self-describing route manifest: {@code GET /api/meta/routes}.
 *
 * <p>583 turned the head's API surface from imperative wiring into structured per-domain registration,
 * making it machine-inspectable. This serves that inspection: a read-only list of every live HTTP
 * route — {@code {method, path, cohort, owningModule, requiredCapabilities, responseSchema}} — built
 * from the running Javalin router (the same {@code InternalRouter} enumeration {@code
 * LegacyEndpointGuardTest} uses, but at runtime over the live app, so it can never drift from what's
 * actually registered).
 *
 * <p>Each route carries four derived facets, each from a single authority:
 *
 * <ul>
 *   <li><b>requiredCapabilities</b> — from {@link RouteCapabilityPolicy}, the SAME authority {@link
 *       ApiSecurityFilters} enforces with, so the manifest advertises exactly what's gated.
 *   <li><b>cohort</b> — the domain grouping from {@link RouteCohorts} (the single authority for
 *       "which domain"; there is no other source for domain, so it is not a drifting second copy).
 *   <li><b>owningModule</b> (§D.3a owning-module dimension) — the {@link ApiModule} that actually
 *       registered the route, derived from each module's self-captured {@link
 *       ApiModule#ownedRoutePaths()} — REAL ownership, not a guess. Routes bound by the static
 *       handler-passing {@code *Routes} registrars (deliberately not instance-modules, AHA) have no
 *       owningModule.
 *   <li><b>responseSchema</b> (§D.3a schema dimension) — the generated wire-record JSON Schema a
 *       route returns, from {@link RouteResponseSchemas} (documented wire routes only; partial by
 *       design — see that class).
 * </ul>
 *
 * <p>Stateless and constructed inside {@link MetaApiModule} (no LocalApiServer field — keeps the §D.4
 * thin-composer ceiling). Reads the app + module list lazily via suppliers because LocalApiServer
 * rebuilds the Javalin instance on the ephemeral-port bind fallback and the module list is finalized
 * during construction.
 */
final class RouteManifestController {
  private static final Logger log = LoggerFactory.getLogger(RouteManifestController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final String SCHEMA_VERSION = "1.0";

  /** One route's machine-readable descriptor. {@code owningModule}/{@code responseSchema} may be null. */
  record RouteEntry(
      String method,
      String path,
      String cohort,
      String owningModule,
      List<String> requiredCapabilities,
      String responseSchema) {}

  private final Supplier<Javalin> appSupplier;
  private final Supplier<List<ApiModule>> modulesSupplier;

  RouteManifestController(Supplier<Javalin> appSupplier, Supplier<List<ApiModule>> modulesSupplier) {
    this.appSupplier = appSupplier;
    this.modulesSupplier = modulesSupplier;
  }

  void handle(Context ctx) {
    try {
      List<RouteEntry> routes = build(appSupplier.get(), modulesSupplier.get());
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("$schema", "https://ssot.justsearch/v1/schemas/route-manifest.json");
      envelope.put("schemaVersion", SCHEMA_VERSION);
      envelope.put("namespace", "meta-routes");
      envelope.put("count", routes.size());
      envelope.put("routes", routes);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(envelope));
    } catch (Exception e) {
      log.error("Failed to build/serialize route manifest", e);
      throw new IllegalStateException("Route manifest serialization failed", e);
    }
  }

  /** The set of HTTP-method route paths currently bound on {@code app} (mutable copy). */
  static Set<String> handlerPaths(Javalin app) {
    Set<String> paths = new HashSet<>();
    for (var pe : app.unsafeConfig().pvt.internalRouter.allHttpHandlers()) {
      if (pe.getEndpoint().getMethod().isHttpMethod()) {
        paths.add(pe.getEndpoint().getPath());
      }
    }
    return paths;
  }

  /** Enumerate the live app's HTTP routes and tag each with cohort, owning module, caps, schema. */
  static List<RouteEntry> build(Javalin app, List<ApiModule> modules) {
    Map<String, String> ownerByPath = new HashMap<>();
    for (ApiModule m : modules) {
      for (String path : m.ownedRoutePaths()) {
        ownerByPath.putIfAbsent(path, m.moduleName());
      }
    }
    var router = app.unsafeConfig().pvt.internalRouter;
    return router.allHttpHandlers().stream()
        .filter(pe -> pe.getEndpoint().getMethod().isHttpMethod())
        .map(
            pe -> {
              // This is a read-only diagnostic surface, so one un-classifiable route must DEGRADE the
              // manifest (skip + WARN) rather than 500 the whole endpoint. The WARN keeps the issue
              // visible — graceful degradation with a breadcrumb, not silent swallowing. The per-facet
              // helpers are pure string ops, so this only fires on a genuinely malformed endpoint.
              String method = null;
              String path = null;
              try {
                method = pe.getEndpoint().getMethod().name();
                path = pe.getEndpoint().getPath();
                List<String> caps =
                    RouteCapabilityPolicy.requiredFor(method, path).stream()
                        .map(Enum::name)
                        .toList();
                return new RouteEntry(
                    method,
                    path,
                    RouteCohorts.cohortOf(path),
                    ownerByPath.get(path),
                    caps,
                    RouteResponseSchemas.schemaFor(method, path));
              } catch (RuntimeException e) {
                log.warn(
                    "Route manifest: skipping un-classifiable route {} {} — {}",
                    method == null ? "?" : method,
                    path == null ? "?" : path,
                    e.toString());
                return null;
              }
            })
        .filter(java.util.Objects::nonNull)
        .sorted(
            java.util.Comparator.comparing(RouteEntry::cohort)
                .thenComparing(RouteEntry::path)
                .thenComparing(RouteEntry::method))
        .toList();
  }
}
