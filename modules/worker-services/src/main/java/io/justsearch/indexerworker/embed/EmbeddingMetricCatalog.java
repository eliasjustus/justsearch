/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.indexerworker.embed.EmbeddingTags.InvokeFailureTags;
import io.justsearch.indexerworker.embed.EmbeddingTags.UnloadTags;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tempdoc 413 catalog for {@code embedding.runtime.*} metrics. Covers what
 * {@link EmbeddingService} and its lifecycle wrappers actually own: query-cache hit/miss/size,
 * per-call invoke failures, hot-unload events, and the chunked-embedding branch. Cold-load
 * lifecycle (assemble/acquire/fallback) is out of scope and belongs to tempdoc 414's
 * {@code ort.session.*} namespace.
 */
public final class EmbeddingMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "embedding.runtime";

  public static final String INVOKE_FAILURE_TOTAL = "embedding.runtime.invoke_failure_total";
  public static final String CACHE_HIT_TOTAL = "embedding.runtime.cache_hit_total";
  public static final String CACHE_MISS_TOTAL = "embedding.runtime.cache_miss_total";
  public static final String CACHE_SIZE = "embedding.runtime.cache_size";
  public static final String UNLOAD_TOTAL = "embedding.runtime.unload_total";
  /**
   * Histogram of chunk counts when the backend returns chunked vectors (text exceeded the
   * model context window). One sample per chunked text — fires from both the single-text
   * path ({@code embedWithChunks}) and the batch path ({@code embedDocumentBatch}'s per-result
   * loop). Bucket layout covers typical document sizes (2-128 chunks); pathologically long
   * texts tail into the overflow bucket.
   */
  public static final String CHUNK_COUNT = "embedding.runtime.chunk_count";

  /** Histogram bucket layout for {@link #CHUNK_COUNT}. */
  private static final List<Long> CHUNK_COUNT_BUCKETS =
      List.of(2L, 4L, 8L, 16L, 32L, 64L, 128L);

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(INVOKE_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(EmbeddingTags.INVOKE_FAILURE_KEYS)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.counter(CACHE_HIT_TOTAL)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.counter(CACHE_MISS_TOTAL)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.gauge(CACHE_SIZE)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.counter(UNLOAD_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(EmbeddingTags.UNLOAD_KEYS)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.histogram(CHUNK_COUNT)
              .unit(Unit.COUNT)
              .buckets(CHUNK_COUNT_BUCKETS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "EmbeddingMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<InvokeFailureTags> invokeFailureTotal;
  public final CounterMetric<EmptyTags> cacheHitTotal;
  public final CounterMetric<EmptyTags> cacheMissTotal;
  public final GaugeMetric<EmptyTags> cacheSize;
  public final CounterMetric<UnloadTags> unloadTotal;
  public final HistogramMetric<EmptyTags> chunkCount;

  /** Default supplier returning zero; used for tests / startup before EmbeddingService exists. */
  public static final Supplier<Long> EMPTY_CACHE_SIZE = () -> 0L;

  /** Test/legacy constructor — cache_size gauge registers against an EMPTY supplier. */
  public EmbeddingMetricCatalog(MetricRegistry registry) {
    this(registry, EMPTY_CACHE_SIZE);
  }

  /**
   * Constructs the catalog and registers all typed instruments — including the cache_size async
   * gauge whose callback reads from {@code cacheSizeSupplier}. The supplier is invoked at every
   * flush by the OTel SDK; supply a provider that's safe to call before {@code EmbeddingService}
   * exists (returning 0L when null is the canonical no-data signal).
   */
  public EmbeddingMetricCatalog(MetricRegistry registry, Supplier<Long> cacheSizeSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(cacheSizeSupplier, "cacheSizeSupplier");
    this.invokeFailureTotal = registry.buildCounter(INVOKE_FAILURE_TOTAL);
    this.cacheHitTotal = registry.buildCounter(CACHE_HIT_TOTAL);
    this.cacheMissTotal = registry.buildCounter(CACHE_MISS_TOTAL);
    this.cacheSize =
        registry.buildGauge(
            CACHE_SIZE, EmptyTags.INSTANCE, () -> (double) cacheSizeSupplier.get());
    this.unloadTotal = registry.buildCounter(UNLOAD_TOTAL);
    this.chunkCount = registry.buildHistogram(CHUNK_COUNT);
  }

  /** Cached no-op singleton (F6 fix in tempdoc 417). */
  private static final EmbeddingMetricCatalog NOOP =
      new EmbeddingMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static EmbeddingMetricCatalog noop() {
    return NOOP;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return DEFINITIONS;
  }
}
