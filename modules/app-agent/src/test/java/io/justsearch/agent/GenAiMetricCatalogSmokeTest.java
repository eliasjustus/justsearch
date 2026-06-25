package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.agent.GenAiTags.GenAiOperationTags;
import io.justsearch.agent.GenAiTags.GenAiTokenUsageTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link GenAiMetricCatalog}. */
final class GenAiMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private GenAiMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(GenAiMetricCatalog.DEFINITIONS);
    catalog = new GenAiMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.operationDuration);
    assertNotNull(catalog.tokenUsage);

    var opTags = new GenAiOperationTags("rewrite");
    catalog.operationDuration.record(123L, opTags);
    assertEquals(1L, registry.histogramCount(GenAiMetricCatalog.OPERATION_DURATION, opTags));

    var tokenTags = new GenAiTokenUsageTags("input");
    catalog.tokenUsage.record(50L, tokenTags);
    assertEquals(1L, registry.histogramCount(GenAiMetricCatalog.TOKEN_USAGE, tokenTags));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = GenAiMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.operationDuration.record(1L, new GenAiOperationTags("x")));
  }
}
