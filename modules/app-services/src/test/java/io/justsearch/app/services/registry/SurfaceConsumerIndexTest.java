package io.justsearch.app.services.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 481 §7 step 3 follow-up — Pass 9 unit tests for {@link SurfaceConsumerIndex}.
 *
 * <p>Pass 8 §6 flagged that the SurfaceConsumerIndex shipped without unit-test
 * coverage. These tests pin: per-primitive lookup correctness, multi-Surface
 * consumer aggregation, merge-de-dup semantics, and immutability of the indexed
 * maps.
 */
final class SurfaceConsumerIndexTest {

  private static final OperationRef OP_A = new OperationRef("core.alpha");
  private static final OperationRef OP_B = new OperationRef("core.bravo");
  private static final ResourceRef RES_X = new ResourceRef("core.x-ray");
  private static final DiagnosticChannelRef DC_Y = new DiagnosticChannelRef("core.yankee");

  private static final SurfaceRef SURFACE_ONE = new SurfaceRef("core.one-surface");
  private static final SurfaceRef SURFACE_TWO = new SurfaceRef("core.two-surface");

  private static Surface surface(SurfaceRef id, Audience audience, SurfaceConsumes consumes) {
    return new Surface(
        id,
        Presentation.of(
            new I18nKey("registry-surface." + id.value() + ".label"),
            new I18nKey("registry-surface." + id.value() + ".description")),
        audience,
        Placement.RAIL,
        consumes,
        "jf-" + id.value().replace('.', '-'),
        Provenance.core("test"));
  }

  @Test
  @DisplayName("consumersOf returns empty list for unreferenced primitive")
  void consumersOf_emptyForUnreferenced() {
    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of());
    assertEquals(List.of(), index.consumersOf(OP_A));
    assertEquals(List.of(), index.consumersOf(RES_X));
    assertEquals(List.of(), index.consumersOf(DC_Y));
  }

  @Test
  @DisplayName("consumersOf returns Realized hook with consumerId = surfaceId")
  void consumersOf_realizedFromSingleSurface() {
    Surface s =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(
                Set.of(RES_X), Set.of(OP_A), Set.of(), Set.of(DC_Y)));
    SurfaceCatalog catalog = SurfaceCatalog.of("core", List.of(s));

    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(catalog));

    List<ConsumerHook> opHooks = index.consumersOf(OP_A);
    assertEquals(1, opHooks.size());
    ConsumerHook.Realized hook = (ConsumerHook.Realized) opHooks.get(0);
    assertEquals(SURFACE_ONE.value(), hook.consumerId());
    assertEquals(Audience.USER, hook.audience());

    assertEquals(1, index.consumersOf(RES_X).size());
    assertEquals(1, index.consumersOf(DC_Y).size());
  }

  @Test
  @DisplayName("consumersOf aggregates across multiple Surfaces consuming same entry")
  void consumersOf_aggregatesAcrossSurfaces() {
    Surface one =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    Surface two =
        surface(
            SURFACE_TWO,
            Audience.OPERATOR,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    SurfaceCatalog catalog = SurfaceCatalog.of("core", List.of(one, two));

    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(catalog));

    List<ConsumerHook> hooks = index.consumersOf(OP_A);
    assertEquals(2, hooks.size());
    Set<String> consumerIds =
        Set.of(hooks.get(0).consumerId(), hooks.get(1).consumerId());
    assertEquals(Set.of(SURFACE_ONE.value(), SURFACE_TWO.value()), consumerIds);
  }

  @Test
  @DisplayName("merge preserves declared order, appends derived, de-dups by consumerId")
  void merge_preservesOrderDeDups() {
    ConsumerHook declared1 = new ConsumerHook.Realized("declared.consumer", Audience.USER);
    ConsumerHook declared2 = new ConsumerHook.Realized("shared.consumer", Audience.USER);
    ConsumerHook derived1 = new ConsumerHook.Realized("shared.consumer", Audience.USER);
    ConsumerHook derived2 = new ConsumerHook.Realized("derived.only", Audience.OPERATOR);

    List<ConsumerHook> merged =
        SurfaceConsumerIndex.merge(List.of(declared1, declared2), List.of(derived1, derived2));

    assertEquals(3, merged.size());
    assertEquals("declared.consumer", merged.get(0).consumerId());
    assertEquals("shared.consumer", merged.get(1).consumerId());
    assertEquals("derived.only", merged.get(2).consumerId());
  }

  @Test
  @DisplayName("merge with empty declared returns derived")
  void merge_emptyDeclared() {
    ConsumerHook derived = new ConsumerHook.Realized("only.derived", Audience.USER);
    assertEquals(List.of(derived), SurfaceConsumerIndex.merge(List.of(), List.of(derived)));
  }

  @Test
  @DisplayName("merge with empty derived returns declared")
  void merge_emptyDerived() {
    ConsumerHook declared = new ConsumerHook.Realized("only.declared", Audience.USER);
    assertEquals(
        List.of(declared), SurfaceConsumerIndex.merge(List.of(declared), List.of()));
  }

  @Test
  @DisplayName("indexed maps are immutable — consumersOf result not mutable")
  void indexedMaps_immutable() {
    Surface s =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    SurfaceCatalog catalog = SurfaceCatalog.of("core", List.of(s));
    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(catalog));

    List<ConsumerHook> hooks = index.consumersOf(OP_A);
    assertNotNull(hooks);
    // List.copyOf returns immutable; mutation should throw
    assertThrows(
        UnsupportedOperationException.class,
        () -> hooks.add(new ConsumerHook.Realized("intruder", Audience.USER)));
  }

  @Test
  @DisplayName("constructor copies surfaceCatalogs defensively (caller mutation does not affect index)")
  void constructor_defensiveCopy() {
    Surface s =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    SurfaceCatalog catalog = SurfaceCatalog.of("core", List.of(s));
    java.util.List<SurfaceCatalog> mutable = new java.util.ArrayList<>(List.of(catalog));

    SurfaceConsumerIndex index = new SurfaceConsumerIndex(mutable);
    assertEquals(1, index.consumersOf(OP_A).size());

    // Subsequent caller mutation does not affect the index — proves constructor copy
    // (or, since SurfaceCatalog.definitions() is itself immutable, that the index
    // doesn't hold mutable references back to the caller's list).
    mutable.clear();
    assertEquals(1, index.consumersOf(OP_A).size());
  }

  @Test
  @DisplayName("Surface.audience flows through to ConsumerHook.audience")
  void audience_flowsThrough() {
    Surface user =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    Surface operator =
        surface(
            SURFACE_TWO,
            Audience.OPERATOR,
            new SurfaceConsumes(Set.of(), Set.of(OP_B), Set.of(), Set.of()));
    SurfaceCatalog catalog = SurfaceCatalog.of("core", List.of(user, operator));

    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(catalog));

    assertEquals(Audience.USER, index.consumersOf(OP_A).get(0).audience());
    assertEquals(Audience.OPERATOR, index.consumersOf(OP_B).get(0).audience());
  }

  @Test
  @DisplayName("multi-catalog: hooks from both catalogs are present")
  void multiCatalog_aggregates() {
    Surface s1 =
        surface(
            SURFACE_ONE,
            Audience.USER,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    Surface s2 =
        surface(
            SURFACE_TWO,
            Audience.OPERATOR,
            new SurfaceConsumes(Set.of(), Set.of(OP_A), Set.of(), Set.of()));
    SurfaceCatalog c1 = SurfaceCatalog.of("core", List.of(s1));
    SurfaceCatalog c2 = SurfaceCatalog.of("vendor.acme", List.of(s2));

    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(c1, c2));

    assertEquals(2, index.consumersOf(OP_A).size());
  }

  @Test
  @DisplayName("Realized hook constructor rejects blank consumerId")
  void realized_rejectsBlankConsumerId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsumerHook.Realized(" ", Audience.USER));
  }

  // Slice 484 §3.6 (2026-05-08): Promised variant reverted per Pass-8-as-merge-gate
  // compliance. Tests that exercised Promised semantics (blank-sliceId rejection;
  // Realized vs Promised sealed-type distinction) were removed alongside the variant.
  // If/when slice 485 ratifies and re-introduces Promised, the variant + its tests
  // ship together in the same commit per the Pass-8 discipline.
}
