package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link RagMetricCatalog}. */
final class RagMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private RagMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(RagMetricCatalog.DEFINITIONS);
    catalog = new RagMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.retrievalTotal);
    catalog.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.RAG));
    assertEquals(
        1L,
        registry.counterValue(
            RagMetricCatalog.RETRIEVAL_TOTAL, RagRetrievalTags.of(RagRetrievalMode.RAG)));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = RagMetricCatalog.noop();
    assertDoesNotThrow(
        () -> noop.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.ERROR)));
  }
}
