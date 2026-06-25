package io.justsearch.app.observability.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.StateBinding;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.SurfaceStateSchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SurfaceStateSchemaLoader} — slice 489 round-7 §F7.
 *
 * <p>Covers: classpath load happy-path, absent-resource → Optional.empty,
 * required-but-absent throws, and the boot-time well-formedness check is
 * implicit in the happy-path load (the shipped resource parses successfully
 * via the loader's Jackson tree probe).
 */
@DisplayName("SurfaceStateSchemaLoader (slice 489 round-7 §F7)")
final class SurfaceStateSchemaLoaderTest {

  private static final SurfaceRef SEARCH = new SurfaceRef("core.search-surface");
  private static final SurfaceRef GHOST = new SurfaceRef("core.ghost-surface");

  private static List<StateBinding> sampleBindings() {
    return List.of(
        new StateBinding("/query", "search", "query"),
        new StateBinding("/modifiedFromMs", "search.filters", "modifiedFromMs"),
        new StateBinding("/modifiedToMs", "search.filters", "modifiedToMs"));
  }

  @Test
  @DisplayName("load returns SurfaceStateSchema for an existing resource")
  void loadExistingResource() {
    Optional<SurfaceStateSchema> result =
        SurfaceStateSchemaLoader.load(SEARCH, sampleBindings());
    assertTrue(result.isPresent(), "search-surface schema resource must be on classpath");
    SurfaceStateSchema schema = result.get();
    assertTrue(schema.schema().contains("\"query\""), "loaded schema source carries query property");
    assertTrue(
        schema.schema().contains("\"modifiedFromMs\""),
        "loaded schema source carries modifiedFromMs property");
    assertEquals(3, schema.bindings().size(), "bindings preserved from caller");
  }

  @Test
  @DisplayName("load returns Optional.empty for absent resource")
  void loadAbsentResourceReturnsEmpty() {
    Optional<SurfaceStateSchema> result =
        SurfaceStateSchemaLoader.load(GHOST, sampleBindings());
    assertFalse(result.isPresent());
  }

  @Test
  @DisplayName("require throws for absent resource (fail-fast at boot)")
  void requireAbsentResourceThrows() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> SurfaceStateSchemaLoader.require(GHOST, sampleBindings()));
    assertTrue(
        ex.getMessage().contains("core.ghost-surface"),
        "error message names the missing surface");
    assertTrue(
        ex.getMessage().contains("SSOT/schemas/surface/"),
        "error message points at the expected classpath location");
  }

  @Test
  @DisplayName("require returns schema for existing resource")
  void requireExistingResource() {
    SurfaceStateSchema schema = SurfaceStateSchemaLoader.require(SEARCH, sampleBindings());
    assertTrue(schema.schema().length() > 0);
    assertEquals(3, schema.bindings().size());
  }

  @Test
  @DisplayName("loaded schema source declares integer types for filter bounds (F3 validator demonstrable)")
  void schemaDeclaresIntegerTypesForFilterBounds() {
    SurfaceStateSchema schema = SurfaceStateSchemaLoader.require(SEARCH, sampleBindings());
    // The schema must declare modifiedFromMs / modifiedToMs as integer so the FE
    // stateValidator can reject `?modifiedFromMs=banana` per slice 489 round-7 F3.
    assertTrue(
        schema.schema().contains("\"modifiedFromMs\"")
            && schema.schema().contains("\"type\": \"integer\""),
        "filter bounds declared with integer type — required for F3 rejection semantics");
  }
}
