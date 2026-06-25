package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricDefinition} record + Builder. Tempdoc 417 Phase 3a covers the
 * reintroduced {@code archivedTo} / {@code surfacedAt} declarations.
 */
final class MetricDefinitionTest {

  @Test
  void counterBuilderHappyPath() {
    var def = MetricDefinition.counter("scope.test.total").unit(Unit.COUNT).build();
    assertEquals("scope.test.total", def.name());
    assertEquals(InstrumentKind.COUNTER, def.kind());
    assertEquals(Unit.COUNT, def.unit());
    assertNull(def.rrdArchive());
    assertNull(def.statusEndpoint());
    assertNull(def.statusFieldName());
  }

  @Test
  void archivedToIsRetained() {
    var def =
        MetricDefinition.gauge("scope.test.queue_depth")
            .unit(Unit.COUNT)
            .archivedTo(RrdArchive.STANDARD)
            .build();
    assertEquals(RrdArchive.STANDARD, def.rrdArchive());
  }

  @Test
  void surfacedAtIsRetainedAndValidated() {
    var def =
        MetricDefinition.gauge("scope.test.q")
            .unit(Unit.COUNT)
            .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "writerQueueDepth")
            .build();
    assertEquals(StatusEndpoint.CORE_INDEX_VIEW, def.statusEndpoint());
    assertEquals("writerQueueDepth", def.statusFieldName());
  }

  @Test
  void surfacedAtRejectsBlankFieldName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDefinition.gauge("scope.test.q")
                .unit(Unit.COUNT)
                .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "")
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDefinition.gauge("scope.test.q")
                .unit(Unit.COUNT)
                .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "  ")
                .build());
  }

  @Test
  void canonicalConstructorRejectsFieldWithoutEndpoint() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricDefinition(
                "scope.test.q",
                InstrumentKind.GAUGE,
                Unit.COUNT,
                java.util.Set.of(),
                null,
                Exemplars.TRACE_BASED,
                null,
                null,
                null,
                "writerQueueDepth"));
  }

  @Test
  void cardinalityLimitMustBePositive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricDefinition.counter("scope.test.total")
                .unit(Unit.COUNT)
                .cardinalityLimit(0)
                .build());
  }
}
