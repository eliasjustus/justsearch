/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-readable HTTP contract metadata keyed by {@code METHOD + route pattern}.
 *
 * <p>The live Javalin router remains the route authority. This policy adds only the contract facets
 * the router cannot express: stability, generated-client exposure, operation ids, declared query
 * parameters, response schemas, and the security projection derived from {@link ApiSecurityFilters}.
 * It supersedes the former one-response {@code RouteResponseSchemas} map.
 */
final class RouteContractPolicy {
  private RouteContractPolicy() {}

  enum Stability {
    INTERNAL,
    PUBLIC_STABLE
  }

  record QueryParameter(String name, boolean required, String schemaType) {
    QueryParameter {
      requireText(name, "query parameter name");
      requireText(schemaType, "query parameter schema type");
    }
  }

  record Contract(
      String method,
      String path,
      Stability stability,
      String sdkOperationId,
      List<QueryParameter> queryParameters,
      Map<Integer, String> responseSchemas,
      ApiSecurityFilters.ContractSecurity security) {
    Contract {
      method = requireText(method, "method").toUpperCase(java.util.Locale.ROOT);
      path = requireText(path, "path");
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("route path must start with '/': " + path);
      }
      if (stability == null) {
        throw new IllegalArgumentException("stability is required for " + method + " " + path);
      }
      queryParameters = List.copyOf(queryParameters == null ? List.of() : queryParameters);
      responseSchemas =
          Collections.unmodifiableMap(
              new java.util.TreeMap<>(responseSchemas == null ? Map.of() : responseSchemas));
      if (responseSchemas.isEmpty()) {
        throw new IllegalArgumentException("at least one response schema is required for " + key());
      }
      for (var response : responseSchemas.entrySet()) {
        if (response.getKey() < 100 || response.getKey() > 599) {
          throw new IllegalArgumentException("invalid HTTP status in " + key() + ": " + response.getKey());
        }
        requireText(response.getValue(), "response schema");
      }
      if (security == null || !security.equals(ApiSecurityFilters.contractSecurity(method, path))) {
        throw new IllegalArgumentException("security projection must come from ApiSecurityFilters for " + key());
      }
      if (sdkOperationId != null) {
        requireText(sdkOperationId, "sdk operation id");
        if (stability != Stability.PUBLIC_STABLE) {
          throw new IllegalArgumentException("SDK operation must be PUBLIC_STABLE: " + key());
        }
        if (!queryParameters.isEmpty()) {
          throw new IllegalArgumentException("v0.1 SDK operations do not accept query parameters: " + key());
        }
      }
    }

    String key() {
      return method + " " + path;
    }

    boolean sdkExposed() {
      return sdkOperationId != null;
    }

    String primaryResponseSchema() {
      return responseSchemas.get(200);
    }
  }

  private static Contract contract(
      String method,
      String path,
      Stability stability,
      String sdkOperationId,
      Map<Integer, String> responses) {
    return new Contract(
        method,
        path,
        stability,
        sdkOperationId,
        List.of(),
        responses,
        ApiSecurityFilters.contractSecurity(method, path));
  }

  private static Contract internal(String method, String path, String responseSchema) {
    return contract(method, path, Stability.INTERNAL, null, Map.of(200, responseSchema));
  }

  static final List<Contract> CONTRACTS =
      List.of(
          internal("GET", "/api/knowledge/search", "knowledge-search-response.v1.json"),
          internal("POST", "/api/knowledge/search", "knowledge-search-response.v1.json"),
          internal("GET", "/api/ai/runtime/status", "ai-runtime-status-response.v1.json"),
          internal("GET", "/api/policy/effective", "effective-policy.v1.json"),
          internal("GET", "/api/runtime-context", "runtime-context.v1.json"),
          internal("GET", "/api/operation-history", "operation-history-entry.v1.json"),
          internal("GET", "/api/registry/resources", "resource.v1.json"),
          internal(
              "GET", "/api/indexing-jobs/failed", "failed-indexing-jobs-response.v1.json"),
          internal(
              "GET",
              "/api/indexing-jobs/failed/by-prefix",
              "failed-indexing-jobs-response.v1.json"),
          contract(
              "GET",
              "/api/runtime/manifest",
              Stability.PUBLIC_STABLE,
              "getRuntimeManifest",
              Map.of(
                  200, "runtime-manifest-public.v1.json",
                  403, "api-error-response.v1.json",
                  500, "api-error-response.v1.json",
                  503, "api-error-response.v1.json")),
          contract(
              "GET",
              "/.well-known/justsearch/manifest.json",
              Stability.PUBLIC_STABLE,
              "getWellKnownRuntimeManifest",
              Map.of(
                  200, "runtime-manifest-public.v1.json",
                  403, "api-error-response.v1.json",
                  500, "api-error-response.v1.json",
                  503, "api-error-response.v1.json")),
          contract(
              "GET",
              "/api/runtime/ready",
              Stability.PUBLIC_STABLE,
              "getRuntimeReadiness",
              Map.of(
                  200, "runtime-ready-response.v1.json",
                  403, "api-error-response.v1.json",
                  503, "runtime-ready-response.v1.json")),
          contract(
              "GET",
              "/api/runtime/live",
              Stability.PUBLIC_STABLE,
              "getRuntimeLiveness",
              Map.of(200, "runtime-live-response.v1.json", 403, "api-error-response.v1.json")),
          contract(
              "GET",
              "/api/health",
              Stability.PUBLIC_STABLE,
              "getLifecycleHealth",
              Map.of(
                  200, "lifecycle-snapshot.v1.json",
                  403, "api-error-response.v1.json",
                  503, "lifecycle-snapshot.v1.json")),
          contract(
              "GET",
              "/api/status",
              Stability.PUBLIC_STABLE,
              "getLifecycleStatus",
              Map.of(200, "lifecycle-snapshot.v1.json", 403, "api-error-response.v1.json")));

  private static final Map<String, Contract> BY_KEY = index(CONTRACTS);

  static Map<String, Contract> index(List<Contract> contracts) {
    Map<String, Contract> byKey = new LinkedHashMap<>();
    Set<String> operationIds = new LinkedHashSet<>();
    for (Contract contract : contracts) {
      Contract previous = byKey.putIfAbsent(contract.key(), contract);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate route contract: " + contract.key());
      }
      if (contract.sdkOperationId() != null && !operationIds.add(contract.sdkOperationId())) {
        throw new IllegalArgumentException("duplicate SDK operation id: " + contract.sdkOperationId());
      }
    }
    return Collections.unmodifiableMap(byKey);
  }

  static Contract forRoute(String method, String path) {
    if (method == null || path == null) return null;
    return BY_KEY.get(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
  }

  static List<Contract> sdkContracts() {
    return CONTRACTS.stream().filter(Contract::sdkExposed).toList();
  }

  @SuppressWarnings("unused") // Called from RouteContractPolicyCoverageTest; tests are excluded by the dead-code scan.
  static Set<String> declaredSchemaFiles() {
    Set<String> names = new LinkedHashSet<>();
    for (Contract contract : CONTRACTS) {
      names.addAll(contract.responseSchemas().values());
    }
    return Collections.unmodifiableSet(names);
  }

  static void validateSdkRoutes(Set<String> registeredMethodPaths) {
    List<String> orphaned = new ArrayList<>();
    for (Contract contract : sdkContracts()) {
      if (!registeredMethodPaths.contains(contract.key())) orphaned.add(contract.key());
    }
    if (!orphaned.isEmpty()) {
      throw new IllegalStateException("SDK contract rows are not registered: " + orphaned);
    }
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must be non-blank");
    }
    return value;
  }
}
