package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link HeadGpuMetricCatalog}. */
final class HeadGpuMetricCatalogSmokeTest {

  @Test
  void constructsWithSuppliers() {
    try (var registry = new TestMetricRegistry(HeadGpuMetricCatalog.DEFINITIONS)) {
      var catalog = new HeadGpuMetricCatalog(registry, () -> 12.5d, () -> 34.0d);
      assertNotNull(catalog.utilizationPercent);
      assertNotNull(catalog.memoryUtilizationPercent);
      registry.flush();
    }
  }

  @Test
  void noopCatalogDoesNotThrow() {
    assertDoesNotThrow(HeadGpuMetricCatalog::noop);
  }

  @Test
  void bothGpuGaugesDeclareStandardArchive() {
    // Tempdoc 417 critical-analysis A1: gpu.utilization.percent and
    // gpu.memory.utilization.percent must be archived to RRD; both were in the original
    // CURATED_METRICS list pre-Phase 3b.
    MetricDefinition utilization =
        HeadGpuMetricCatalog.DEFINITIONS.stream()
            .filter(d -> d.name().equals(HeadGpuMetricCatalog.UTILIZATION_PERCENT))
            .findFirst()
            .orElseThrow();
    MetricDefinition memUtilization =
        HeadGpuMetricCatalog.DEFINITIONS.stream()
            .filter(d -> d.name().equals(HeadGpuMetricCatalog.MEMORY_UTILIZATION_PERCENT))
            .findFirst()
            .orElseThrow();
    assertEquals(RrdArchive.STANDARD, utilization.rrdArchive());
    assertEquals(RrdArchive.STANDARD, memUtilization.rrdArchive());
  }
}
