package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link IndexingPipelineMetricCatalog}. */
final class IndexingPipelineMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private IndexingPipelineMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(IndexingPipelineMetricCatalog.DEFINITIONS);
    catalog = new IndexingPipelineMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.stageMs);
    catalog.stageMs.record(123L, PipelineStageTags.of("extract", null));
    assertEquals(
        1L,
        registry.histogramCount(
            IndexingPipelineMetricCatalog.STAGE_MS, PipelineStageTags.of("extract", null)));
  }
}
