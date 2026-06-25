package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link VduMetricCatalog}. */
final class VduMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private VduMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(VduMetricCatalog.DEFINITIONS);
    catalog = new VduMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.timeoutTotal);
    assertNotNull(catalog.outcomeTotal);

    catalog.timeoutTotal.increment(VduTimeoutTags.of());
    catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.COMPLETED));
    catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.FAILED));

    assertEquals(1L, registry.counterValue(VduMetricCatalog.TIMEOUT_TOTAL, VduTimeoutTags.of()));
    assertEquals(
        1L,
        registry.counterValue(
            VduMetricCatalog.OUTCOME_TOTAL, VduOutcomeTags.of(VduOutcome.COMPLETED)));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = VduMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.timeoutTotal.increment(VduTimeoutTags.of()));
  }
}
