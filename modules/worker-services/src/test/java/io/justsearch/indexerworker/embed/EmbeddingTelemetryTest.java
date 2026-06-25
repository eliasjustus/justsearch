package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.embed.EmbeddingTags.InvokeFailureTags;
import io.justsearch.indexerworker.embed.EmbeddingTags.UnloadTags;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 413: emit-side assertions for {@link EmbeddingTelemetry}. */
final class EmbeddingTelemetryTest {

  private TestMetricRegistry registry;
  private EmbeddingTelemetry telemetry;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(EmbeddingMetricCatalog.DEFINITIONS);
    telemetry = new EmbeddingTelemetry(new EmbeddingMetricCatalog(registry, () -> 0L));
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void recordsCacheHit() {
    telemetry.onCacheHit();
    telemetry.onCacheHit();
    assertEquals(
        2L, registry.counterValue(EmbeddingMetricCatalog.CACHE_HIT_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void recordsCacheMiss() {
    telemetry.onCacheMiss();
    assertEquals(
        1L, registry.counterValue(EmbeddingMetricCatalog.CACHE_MISS_TOTAL, EmptyTags.INSTANCE));
  }

  @Test
  void recordsChunkedAsHistogramSample() {
    telemetry.onChunked(8);
    telemetry.onChunked(2);
    assertEquals(
        2L, registry.histogramCount(EmbeddingMetricCatalog.CHUNK_COUNT, EmptyTags.INSTANCE));
  }

  @Test
  void chunkedWithNonPositiveCountIsIgnored() {
    telemetry.onChunked(0);
    telemetry.onChunked(-1);
    assertEquals(
        0L, registry.histogramCount(EmbeddingMetricCatalog.CHUNK_COUNT, EmptyTags.INSTANCE));
  }

  @Test
  void recordsInvokeFailureWithDistinctTagCombinations() {
    telemetry.onInvokeFailure(Operation.SINGLE, InvokeFailureReason.BACKEND_EXCEPTION);
    telemetry.onInvokeFailure(Operation.BATCH, InvokeFailureReason.BACKEND_EXCEPTION);
    telemetry.onInvokeFailure(Operation.SINGLE, InvokeFailureReason.NULL_TEXT);

    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL,
            new InvokeFailureTags(Operation.SINGLE, InvokeFailureReason.BACKEND_EXCEPTION)));
    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL,
            new InvokeFailureTags(Operation.BATCH, InvokeFailureReason.BACKEND_EXCEPTION)));
    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL,
            new InvokeFailureTags(Operation.SINGLE, InvokeFailureReason.NULL_TEXT)));
  }

  @Test
  void recordsUnload() {
    telemetry.onUnload(UnloadReason.GPU_HANDOFF);
    telemetry.onUnload(UnloadReason.SHUTDOWN);

    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.UNLOAD_TOTAL, new UnloadTags(UnloadReason.GPU_HANDOFF)));
    assertEquals(
        1L,
        registry.counterValue(
            EmbeddingMetricCatalog.UNLOAD_TOTAL, new UnloadTags(UnloadReason.SHUTDOWN)));
  }

  @Test
  void noopTelemetryDoesNothing() {
    var noop = EmbeddingTelemetry.noop();
    noop.onCacheHit();
    noop.onCacheMiss();
    noop.onChunked(4);
    noop.onInvokeFailure(Operation.SINGLE, InvokeFailureReason.BACKEND_EXCEPTION);
    noop.onUnload(UnloadReason.SHUTDOWN);
  }

  @Test
  void nullArgumentsAreSilentlyIgnored() {
    telemetry.onInvokeFailure(null, InvokeFailureReason.BACKEND_EXCEPTION);
    telemetry.onInvokeFailure(Operation.SINGLE, null);
    telemetry.onUnload(null);
  }
}
