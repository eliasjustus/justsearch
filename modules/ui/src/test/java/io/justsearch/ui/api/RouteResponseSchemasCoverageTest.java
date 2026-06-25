package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.telemetry.Telemetry;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 583 §D.3a / §C independent review — closure guard for the schema dimension.
 *
 * <p>The OpenAPI export (§D.3c) emits a {@code $ref: /api/schemas/<name>} for every route in {@link
 * RouteResponseSchemas}. {@link SchemaController} only serves names in its hardcoded allowlist, so a
 * schema named in the map but absent from the served set would dangle (404). This pins
 * {@code RouteResponseSchemas ⊆ SchemaController.servedNames()} so the two cannot drift — the exact
 * defect the §C review caught (3 of 7 referenced schemas were unserved).
 */
@DisplayName("RouteResponseSchemas ⊆ SchemaController served set")
class RouteResponseSchemasCoverageTest {

  @Test
  @DisplayName("every responseSchema the manifest declares is actually served (no dangling $ref)")
  void everyDeclaredSchemaIsServed() {
    Set<String> served = new SchemaController(mock(Telemetry.class)).servedNames();
    Set<String> missing = new TreeSet<>();
    for (String name : RouteResponseSchemas.declaredSchemaFiles()) {
      if (!served.contains(name)) {
        missing.add(name);
      }
    }
    assertTrue(
        missing.isEmpty(),
        "RouteResponseSchemas references schemas SchemaController does not serve (their OpenAPI"
            + " $refs would 404): "
            + missing
            + " — add them to SchemaController.SCHEMA_NAMES or remove the mapping.");
  }
}
