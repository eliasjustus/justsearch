package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link ExtractionMetricCatalog}. */
final class ExtractionMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private ExtractionMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS);
    catalog = new ExtractionMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.timeoutTotal);
    catalog.timeoutTotal.increment(ExtractionTimeoutTags.of());
    assertEquals(
        1L,
        registry.counterValue(
            ExtractionMetricCatalog.TIMEOUT_TOTAL, ExtractionTimeoutTags.of()));
  }
}
