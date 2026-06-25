package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Surface manifest record (slice 449)")
final class SurfaceTest {

  private static Presentation presentationOf(String idStem) {
    return Presentation.of(
        new I18nKey("registry-surface." + idStem + ".label"),
        new I18nKey("registry-surface." + idStem + ".description"));
  }

  private static Surface surfaceOf(Audience audience, Placement placement) {
    return new Surface(
        new SurfaceRef("core.example-surface"),
        presentationOf("example"),
        audience,
        placement,
        SurfaceConsumes.empty(),
        "jf-example-surface",
        Provenance.core("1.0"));
  }

  @Test
  @DisplayName("USER + RAIL is well-formed")
  void userRailWellFormed() {
    Surface s = surfaceOf(Audience.USER, Placement.RAIL);
    assertEquals("core.example-surface", s.id().value());
    assertEquals(Audience.USER, s.audience());
    assertEquals(Placement.RAIL, s.placement());
    assertTrue(s.consumes().resources().isEmpty());
  }

  @Test
  @DisplayName("OPERATOR + STAGE is well-formed")
  void operatorStageWellFormed() {
    Surface s = surfaceOf(Audience.OPERATOR, Placement.STAGE);
    assertEquals(Audience.OPERATOR, s.audience());
  }

  @Test
  @DisplayName("AGENT audience requires HEADLESS_AGENT_TOOL placement")
  void agentRequiresHeadlessPlacement() {
    assertThrows(
        IllegalArgumentException.class,
        () -> surfaceOf(Audience.AGENT, Placement.RAIL),
        "AGENT audience with non-HEADLESS_AGENT_TOOL placement must throw");
  }

  @Test
  @DisplayName("HEADLESS_AGENT_TOOL placement requires AGENT audience")
  void headlessRequiresAgentAudience() {
    assertThrows(
        IllegalArgumentException.class,
        () -> surfaceOf(Audience.USER, Placement.HEADLESS_AGENT_TOOL),
        "HEADLESS_AGENT_TOOL placement with non-AGENT audience must throw");
  }

  @Test
  @DisplayName("AGENT + HEADLESS_AGENT_TOOL is well-formed")
  void agentHeadlessWellFormed() {
    Surface s = surfaceOf(Audience.AGENT, Placement.HEADLESS_AGENT_TOOL);
    assertEquals(Audience.AGENT, s.audience());
    assertEquals(Placement.HEADLESS_AGENT_TOOL, s.placement());
  }

  @Test
  @DisplayName("blank mountTag rejected by compact constructor")
  void blankMountTagRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Surface(
                new SurfaceRef("core.example"),
                presentationOf("example"),
                Audience.USER,
                Placement.RAIL,
                SurfaceConsumes.empty(),
                "",
                Provenance.core("1.0")));
  }

  @Test
  @DisplayName("SurfaceRef rejects malformed namespacing")
  void surfaceIdRejectsBadNamespace() {
    assertThrows(IllegalArgumentException.class, () -> new SurfaceRef("Library"));
    assertThrows(IllegalArgumentException.class, () -> new SurfaceRef("core.UPPERCASE"));
    assertThrows(IllegalArgumentException.class, () -> new SurfaceRef("third-party.x"));
  }

  @Test
  @DisplayName("SurfaceRef accepts core + vendor namespaces")
  void surfaceIdAcceptsValidNamespaces() {
    assertEquals("core.library-surface", new SurfaceRef("core.library-surface").value());
    assertEquals("vendor.acme.dashboard", new SurfaceRef("vendor.acme.dashboard").value());
  }

  // ----- Slice 489 §5 — optional stateSchema field -----

  @Test
  @DisplayName("backwards-compat 7-arg constructor leaves stateSchema empty")
  void backwardsCompatConstructorLeavesStateSchemaEmpty() {
    Surface s = surfaceOf(Audience.USER, Placement.RAIL);
    assertEquals(java.util.Optional.empty(), s.stateSchema());
  }

  @Test
  @DisplayName("8-arg constructor carries a declared stateSchema")
  void stateSchemaDeclared() {
    SurfaceStateSchema schema =
        new SurfaceStateSchema(
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
            java.util.List.of(new StateBinding("/query", "search", "query")));
    Surface s =
        new Surface(
            new SurfaceRef("core.search-surface"),
            presentationOf("search"),
            Audience.USER,
            Placement.RAIL,
            SurfaceConsumes.empty(),
            "jf-search-surface",
            Provenance.core("1.0"),
            java.util.Optional.of(schema));
    assertEquals(java.util.Optional.of(schema), s.stateSchema());
    assertEquals(1, s.stateSchema().get().bindings().size());
    assertEquals("search", s.stateSchema().get().bindings().get(0).storeId());
  }

  @Test
  @DisplayName("null stateSchema rejected by compact constructor")
  void nullStateSchemaRejected() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Surface(
                new SurfaceRef("core.example"),
                presentationOf("example"),
                Audience.USER,
                Placement.RAIL,
                SurfaceConsumes.empty(),
                "jf-example",
                Provenance.core("1.0"),
                null));
  }
}
