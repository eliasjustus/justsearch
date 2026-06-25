package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 583 §D.3c — verifies the OpenAPI 3.1 document composes the route manifest faithfully:
 * version + info present, paths grouped by method, cohorts surfaced as tags, capability gates carried
 * as {@code x-required-capabilities}, and path parameters declared (OpenAPI validity).
 */
@DisplayName("OpenApiController")
class OpenApiControllerTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> op(Map<String, Object> doc, String path, String method) {
    Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
    assertNotNull(paths, "doc has paths");
    Map<String, Object> item = (Map<String, Object>) paths.get(path);
    assertNotNull(item, "path present: " + path);
    Map<String, Object> operation = (Map<String, Object>) item.get(method);
    assertNotNull(operation, "operation present: " + method + " " + path);
    return operation;
  }

  @Test
  @DisplayName("build() composes a valid OpenAPI 3.1 doc from the live route set")
  void buildComposesOpenApi() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    app.post("/api/knowledge/search", ctx -> {});
    app.get("/api/knowledge/status", ctx -> {});
    app.post("/api/chat/agent", ctx -> {});
    app.get("/api/chat/agent/history/{batchId}", ctx -> {});

    Map<String, Object> doc = OpenApiController.build(app, List.of());

    assertEquals("3.1.0", doc.get("openapi"));
    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) doc.get("info");
    assertEquals("JustSearch Local API", info.get("title"));
    assertEquals("1.0", info.get("version"));

    // Worker-gated mutation → tag = cohort, x-required-capabilities present.
    Map<String, Object> search = op(doc, "/api/knowledge/search", "post");
    assertEquals(List.of("knowledge"), search.get("tags"));
    assertEquals(List.of("WORKER"), search.get("x-required-capabilities"));
    assertNotNull(search.get("responses"), "operation has a responses object");

    // §D.3a schema dimension: a documented wire route $refs its schema + registers a component.
    @SuppressWarnings("unchecked")
    Map<String, Object> responses = (Map<String, Object>) search.get("responses");
    @SuppressWarnings("unchecked")
    Map<String, Object> ok200 = (Map<String, Object>) responses.get("200");
    @SuppressWarnings("unchecked")
    Map<String, Object> content = (Map<String, Object>) ok200.get("content");
    assertNotNull(content, "documented wire route declares response content");
    @SuppressWarnings("unchecked")
    Map<String, Object> json = (Map<String, Object>) content.get("application/json");
    @SuppressWarnings("unchecked")
    Map<String, Object> schemaRef = (Map<String, Object>) json.get("schema");
    assertEquals("#/components/schemas/knowledge-search-response", schemaRef.get("$ref"));
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) doc.get("components");
    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    assertTrue(schemas.containsKey("knowledge-search-response"), "component schema registered");

    // GET under a get-exempt rule → no capability extension.
    Map<String, Object> status = op(doc, "/api/knowledge/status", "get");
    assertFalse(status.containsKey("x-required-capabilities"), "GET is capability-exempt");

    // Agent → WORKER + INFERENCE, order preserved.
    assertEquals(
        List.of("WORKER", "INFERENCE"),
        op(doc, "/api/chat/agent", "post").get("x-required-capabilities"));

    // Path parameter declared (OpenAPI requires {param} segments to be declared).
    Map<String, Object> history = op(doc, "/api/chat/agent/history/{batchId}", "get");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> params = (List<Map<String, Object>>) history.get("parameters");
    assertNotNull(params, "history operation declares parameters");
    assertEquals(1, params.size());
    assertEquals("batchId", params.get(0).get("name"));
    assertEquals("path", params.get(0).get("in"));
    assertEquals(Boolean.TRUE, params.get(0).get("required"));
  }

  @Test
  @DisplayName("the same path with two methods becomes one path item with two operations")
  void mergesMethodsUnderOnePathItem() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    app.get("/api/indexing/roots", ctx -> {});
    app.post("/api/indexing/roots", ctx -> {});

    Map<String, Object> doc = OpenApiController.build(app, List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) paths.get("/api/indexing/roots");
    assertTrue(item.containsKey("get") && item.containsKey("post"), "both methods on one path item");
    // GET exempt, POST worker-gated — proves per-operation capability fidelity.
    assertFalse(
        ((Map<String, Object>) item.get("get")).containsKey("x-required-capabilities"));
    assertEquals(
        List.of("WORKER"),
        ((Map<String, Object>) item.get("post")).get("x-required-capabilities"));
  }
}
