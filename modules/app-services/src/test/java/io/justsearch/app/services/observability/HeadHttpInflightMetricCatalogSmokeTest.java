package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link HeadHttpInflightMetricCatalog}. */
final class HeadHttpInflightMetricCatalogSmokeTest {

  @Test
  void constructsWithSupplier() {
    try (var registry = new TestMetricRegistry(HeadHttpInflightMetricCatalog.DEFINITIONS)) {
      var catalog = new HeadHttpInflightMetricCatalog(registry, () -> 7.0d);
      assertNotNull(catalog.inflightRequests);
      // Gauge supplier fires on flush; smoke test verifies construction wires correctly.
      registry.flush();
    }
  }

  @Test
  void noopCatalogDoesNotThrow() {
    assertDoesNotThrow(HeadHttpInflightMetricCatalog::noop);
  }

  @Test
  void inflightRequestsDeclaresStandardArchive() {
    // Tempdoc 417 critical-analysis A1: head.http.inflight_requests must be archived to RRD;
    // the original CURATED_METRICS list included it pre-Phase 3b.
    MetricDefinition def =
        HeadHttpInflightMetricCatalog.DEFINITIONS.stream()
            .filter(d -> d.name().equals(HeadHttpInflightMetricCatalog.INFLIGHT_REQUESTS))
            .findFirst()
            .orElseThrow();
    assertEquals(RrdArchive.STANDARD, def.rrdArchive());
  }
}
