package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the pure route-entry to classified OpenAPI projection. */
@DisplayName("OpenApiRenderer")
class OpenApiRendererTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> operation(
      Map<String, Object> document, String path, String method) {
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");
    assertNotNull(paths, "document has paths");
    Map<String, Object> item = (Map<String, Object>) paths.get(path);
    assertNotNull(item, "path present: " + path);
    Map<String, Object> operation = (Map<String, Object>) item.get(method);
    assertNotNull(operation, "operation present: " + method + " " + path);
    return operation;
  }

  @Test
  @DisplayName("render() classifies the inventory and preserves route metadata")
  void rendersClassifiedStructuralInventory() {
    List<RouteEntry> routes =
        List.of(
            route("POST", "/api/knowledge/search", "knowledge", null, List.of("WORKER"),
                "knowledge-search-response.v1.json"),
            route("GET", "/api/knowledge/status", "knowledge", null, List.of(), null),
            route("POST", "/api/chat/agent", "agent", "AgentApiModule",
                List.of("WORKER", "INFERENCE"), null),
            route("GET", "/api/chat/agent/history/{batchId}", "agent", null, List.of(), null));

    Map<String, Object> document = OpenApiRenderer.render(routes);

    assertEquals("3.1.0", document.get("openapi"));
    @SuppressWarnings("unchecked")
    Map<String, Object> surface = (Map<String, Object>) document.get("x-justsearch-surface");
    assertEquals(OpenApiRenderer.CLASSIFICATION, surface.get("classification"));
    assertEquals(Boolean.FALSE, surface.get("runtimeContract"));
    assertEquals("structural-partial", surface.get("schemaScope"));

    @SuppressWarnings("unchecked")
    Map<String, Object> source = (Map<String, Object>) document.get("x-justsearch-route-source");
    assertEquals(4, source.get("routeCount"));
    assertTrue(source.get("routeDigest").toString().matches("sha256:[0-9a-f]{64}"));

    Map<String, Object> search = operation(document, "/api/knowledge/search", "post");
    assertEquals(List.of("knowledge"), search.get("tags"));
    assertEquals(List.of("WORKER"), search.get("x-required-capabilities"));
    assertEquals(
        "AgentApiModule",
        operation(document, "/api/chat/agent", "post").get("x-owning-module"));
    assertFalse(
        operation(document, "/api/knowledge/status", "get")
            .containsKey("x-required-capabilities"));

    @SuppressWarnings("unchecked")
    Map<String, Object> responses = (Map<String, Object>) search.get("responses");
    @SuppressWarnings("unchecked")
    Map<String, Object> ok = (Map<String, Object>) responses.get("200");
    @SuppressWarnings("unchecked")
    Map<String, Object> content = (Map<String, Object>) ok.get("content");
    @SuppressWarnings("unchecked")
    Map<String, Object> json = (Map<String, Object>) content.get("application/json");
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) json.get("schema");
    assertEquals("#/components/schemas/knowledge-search-response", schema.get("$ref"));

    Map<String, Object> history =
        operation(document, "/api/chat/agent/history/{batchId}", "get");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parameters =
        (List<Map<String, Object>>) history.get("parameters");
    assertEquals("batchId", parameters.getFirst().get("name"));
    assertEquals(Boolean.TRUE, parameters.getFirst().get("required"));
  }

  @Test
  @DisplayName("route ordering and digest are deterministic")
  void orderingAndDigestAreDeterministic() {
    RouteEntry first = route("GET", "/api/a", "a", null, List.of(), null);
    RouteEntry second = route("POST", "/api/z", "z", null, List.of("WORKER"), null);

    Map<String, Object> forward = OpenApiRenderer.render(List.of(first, second));
    Map<String, Object> reverse = OpenApiRenderer.render(List.of(second, first));
    assertEquals(forward, reverse);
    assertEquals(
        RouteDescriptorDigest.sha256(List.of(first, second)),
        RouteDescriptorDigest.sha256(List.of(second, first)));

    RouteEntry changed = route("POST", "/api/z", "z", null, List.of(), null);
    assertNotEquals(
        RouteDescriptorDigest.sha256(List.of(first, second)),
        RouteDescriptorDigest.sha256(List.of(first, changed)));
  }

  @Test
  @DisplayName("every descriptor field contributes to the shared digest")
  void completeDescriptorContributesToDigest() {
    RouteEntry baseline =
        route("GET", "/api/a", "cohort", "Owner", List.of("WORKER"), "result.v1.json");
    String digest = RouteDescriptorDigest.sha256(List.of(baseline));

    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "other", "Owner", List.of("WORKER"), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Other", List.of("WORKER"), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Owner", List.of(), "result.v1.json"))));
    assertNotEquals(
        digest,
        RouteDescriptorDigest.sha256(
            List.of(route("GET", "/api/a", "cohort", "Owner", List.of("WORKER"), null))));
  }

  @Test
  @DisplayName("Javalin wildcard paths normalize to OpenAPI path parameters")
  void normalizesWildcards() {
    assertEquals("/{wildcard}", OpenApiRenderer.normalizePath("/*"));
    assertEquals("/assets/{path}", OpenApiRenderer.normalizePath("/assets/<path>"));
  }

  @Test
  @DisplayName("normalized duplicate operations fail instead of silently overwriting")
  void rejectsNormalizedDuplicates() {
    RouteEntry braces = route("GET", "/assets/{path}", "asset", null, List.of(), null);
    RouteEntry angles = route("GET", "/assets/<path>", "asset", null, List.of(), null);

    assertThrows(
        IllegalArgumentException.class, () -> OpenApiRenderer.render(List.of(braces, angles)));
  }

  private static RouteEntry route(
      String method,
      String path,
      String cohort,
      String owner,
      List<String> capabilities,
      String responseSchema) {
    return new RouteEntry(method, path, cohort, owner, capabilities, responseSchema);
  }
}
