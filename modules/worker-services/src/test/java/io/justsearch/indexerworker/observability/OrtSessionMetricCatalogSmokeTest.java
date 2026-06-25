package io.justsearch.indexerworker.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for {@link OrtSessionMetricCatalog}: every declared definition must produce a
 * non-null typed instrument when the catalog is constructed against a {@link TestMetricRegistry}
 * pre-loaded with {@link OrtSessionMetricCatalog#DEFINITIONS}. Pattern reference: 417 F4
 * per-catalog smoke tests.
 */
@DisplayName("OrtSessionMetricCatalog — smoke")
final class OrtSessionMetricCatalogSmokeTest {

  @Test
  @DisplayName("every definition produces a non-null instrument")
  void buildsAllInstruments() {
    try (var registry = new TestMetricRegistry(OrtSessionMetricCatalog.DEFINITIONS)) {
      OrtSessionMetricCatalog catalog = new OrtSessionMetricCatalog(registry);
      assertNotNull(catalog.semaphoreWaitUs);
      assertNotNull(catalog.gpuInitTotal);
      assertNotNull(catalog.gpuInitFailureTotal);
      assertNotNull(catalog.fallbackTotal);
      assertNotNull(catalog.recoveryTotal);
      assertNotNull(catalog.releaseTotal);
      assertNotNull(catalog.retryTotal);
      assertNotNull(catalog.retryIntervalMs);
      assertNotNull(catalog.assemblerFailureTotal);
    }
  }

  @Test
  @DisplayName("namespace and definitions list reachable via MetricCatalog interface")
  void catalogInterfaceAccessors() {
    try (var registry = new TestMetricRegistry(OrtSessionMetricCatalog.DEFINITIONS)) {
      OrtSessionMetricCatalog catalog = new OrtSessionMetricCatalog(registry);
      assertEquals("ort.session", catalog.namespace());
      assertEquals(9, catalog.definitions().size());
      for (MetricDefinition def : catalog.definitions()) {
        assertTrue(
            def.name().startsWith("ort.session."),
            "metric name must use namespace prefix: " + def.name());
      }
    }
  }
}
