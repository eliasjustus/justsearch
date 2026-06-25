package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.embed.EmbeddingTags.InvokeFailureTags;
import io.justsearch.indexerworker.embed.EmbeddingTags.UnloadTags;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 413 (F4 precedent): smoke test for {@link EmbeddingMetricCatalog}. */
final class EmbeddingMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private EmbeddingMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(EmbeddingMetricCatalog.DEFINITIONS);
    catalog = new EmbeddingMetricCatalog(registry, () -> 7L);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void definitionListIsExpected() {
    assertEquals(6, EmbeddingMetricCatalog.DEFINITIONS.size());
  }

  @Test
  void constructsAndEmitsAllInstruments() {
    assertNotNull(catalog.invokeFailureTotal);
    assertNotNull(catalog.cacheHitTotal);
    assertNotNull(catalog.cacheMissTotal);
    assertNotNull(catalog.cacheSize);
    assertNotNull(catalog.unloadTotal);
    assertNotNull(catalog.chunkCount);

    catalog.cacheHitTotal.increment(EmptyTags.INSTANCE);
    catalog.cacheMissTotal.increment(EmptyTags.INSTANCE);
    catalog.chunkCount.record(4, EmptyTags.INSTANCE);
    catalog.invokeFailureTotal.increment(
        new InvokeFailureTags(Operation.SINGLE, InvokeFailureReason.BACKEND_EXCEPTION));
    catalog.unloadTotal.increment(new UnloadTags(UnloadReason.GPU_HANDOFF));

    assertEquals(
        1L, registry.counterValue(EmbeddingMetricCatalog.CACHE_HIT_TOTAL, EmptyTags.INSTANCE));
    assertEquals(
        1L, registry.counterValue(EmbeddingMetricCatalog.CACHE_MISS_TOTAL, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.histogramCount(EmbeddingMetricCatalog.CHUNK_COUNT, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL,
            new InvokeFailureTags(Operation.SINGLE, InvokeFailureReason.BACKEND_EXCEPTION)));
    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.UNLOAD_TOTAL, new UnloadTags(UnloadReason.GPU_HANDOFF)));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = EmbeddingMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.cacheHitTotal.increment(EmptyTags.INSTANCE));
    assertDoesNotThrow(() -> noop.unloadTotal.increment(new UnloadTags(UnloadReason.SHUTDOWN)));
    assertDoesNotThrow(() -> noop.chunkCount.record(4, EmptyTags.INSTANCE));
  }

  /**
   * Tempdoc 413 followup: pin the 5 archived metric names into {@link RrdMetricStore}'s curated
   * set so they are visible at {@code /api/debug/metrics/timeseries/available} and queryable via
   * {@code /api/debug/metrics/timeseries}. {@code chunk_count} is intentionally excluded
   * (histograms aren't archived in the current 3-tier RRD layout).
   */
  @Test
  void archivedMetricsLandInRrdCuratedSet(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("rrd-curated-test");
    Files.createDirectories(dataDir);
    MetricCatalog wrapper =
        MetricCatalog.of(
            EmbeddingMetricCatalog.NAMESPACE, EmbeddingMetricCatalog.DEFINITIONS);

    try (RrdMetricStore store = new RrdMetricStore(dataDir, List.of(wrapper))) {
      Set<String> curated = store.getCuratedMetrics();

      assertTrue(curated.contains(EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL));
      assertTrue(curated.contains(EmbeddingMetricCatalog.CACHE_HIT_TOTAL));
      assertTrue(curated.contains(EmbeddingMetricCatalog.CACHE_MISS_TOTAL));
      assertTrue(curated.contains(EmbeddingMetricCatalog.CACHE_SIZE));
      assertTrue(curated.contains(EmbeddingMetricCatalog.UNLOAD_TOTAL));

      // chunk_count is a histogram and intentionally not archived.
      assertFalse(
          curated.contains(EmbeddingMetricCatalog.CHUNK_COUNT),
          "chunk_count must NOT be in the curated set (histograms not RRD-archived)");
    }
  }
}
