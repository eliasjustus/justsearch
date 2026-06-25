/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.ObservableCounterMetric;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Tempdoc 417 Phase 3c catalog for {@code worker.*} operational metrics. Replaces the legacy
 * {@code KnowledgeServer.registerOtelObservableCallbacks} (which used
 * {@link io.justsearch.telemetry.Telemetry#meter(String)} — now retired) and
 * {@code KnowledgeServer.registerTelemetryGauges} (legacy {@code Telemetry.gauge(...)}).
 *
 * <p>Covers 34 metrics:
 * <ul>
 *   <li>16 observable counters from {@link OperationalMetrics} (LongAdder.sum()).
 *   <li>5 long-valued saturation gauges (last latencies, content-length count/min/max).
 *   <li>3 double-valued averages (avg latencies, avg content-length).
 *   <li>9 queue/buffer/state gauges read from a caller-supplied {@link Sources}.
 *   <li>1 counter for switch-buffer write failures.
 * </ul>
 *
 * <p>Curated metrics that previously lived in {@code RrdMetricStore.LEGACY_CURATED_METRICS}
 * declare {@link RrdArchive#STANDARD} so the RRD store auto-includes them post-Phase 3b.
 */
public final class WorkerOpsMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "worker";

  // Observable counters (LongAdder-backed)
  public static final String DOCUMENTS_INDEXED_TOTAL = "worker.documents.indexed.total";
  public static final String DOCUMENTS_FAILED_TOTAL = "worker.documents.failed.total";
  public static final String BATCHES_SUBMITTED_TOTAL = "worker.batches.submitted.total";
  public static final String BATCHES_REJECTED_TOTAL = "worker.batches.rejected.total";
  public static final String COMMITS_TOTAL = "worker.commits.total";
  public static final String SEARCHES_TOTAL = "worker.searches.total";
  public static final String SEARCHES_ZERO_RESULT_TOTAL = "worker.searches.zero_result.total";
  public static final String SEARCHES_FAILED_TOTAL = "worker.searches.failed.total";
  public static final String RAG_RETRIEVALS_TOTAL = "worker.rag.retrievals.total";
  public static final String RAG_RETRIEVALS_BM25_TOTAL = "worker.rag.retrievals.bm25.total";
  public static final String RAG_RETRIEVALS_HYBRID_TOTAL = "worker.rag.retrievals.hybrid.total";
  public static final String RAG_RETRIEVALS_FALLBACK_TOTAL = "worker.rag.retrievals.fallback.total";
  public static final String SEARCH_HYBRID_FALLBACK_TOTAL = "worker.search.hybrid_fallback.total";
  public static final String SEARCH_VECTOR_BLOCKED_TOTAL = "worker.search.vector_blocked.total";
  public static final String SEARCH_SPLADE_SKIPPED_TOTAL = "worker.search.splade_skipped.total";
  public static final String SWITCH_BUFFER_WRITE_FAILURES = "worker.switch_buffer.write_failures";

  // Latency / content-length gauges (LongAdder-derived)
  public static final String SEARCH_LAST_LATENCY_MS = "worker.search.last_latency_ms";
  public static final String INDEX_LAST_LATENCY_MS = "worker.index.last_latency_ms";
  public static final String CONTENT_LENGTH_COUNT = "worker.content_length.count";
  public static final String CONTENT_LENGTH_MIN_CHARS = "worker.content_length.min_chars";
  public static final String CONTENT_LENGTH_MAX_CHARS = "worker.content_length.max_chars";
  public static final String SEARCH_AVG_LATENCY_MS = "worker.search.avg_latency_ms";
  public static final String INDEX_AVG_LATENCY_MS = "worker.index.avg_latency_ms";
  public static final String CONTENT_LENGTH_AVG_CHARS = "worker.content_length.avg_chars";

  // Queue / buffer / state gauges (Sources-driven)
  public static final String JOB_QUEUE_DEPTH = "worker.job_queue.depth";
  public static final String JOB_QUEUE_PENDING_JOBS = "worker.job_queue.pending_jobs";
  public static final String JOB_QUEUE_PROCESSING_JOBS = "worker.job_queue.processing_jobs";
  public static final String JOB_QUEUE_PENDING_READY_JOBS = "worker.job_queue.pending_ready_jobs";
  public static final String JOB_QUEUE_PENDING_BACKOFF_JOBS = "worker.job_queue.pending_backoff_jobs";
  public static final String SWITCH_BUFFER_DEPTH = "worker.switch_buffer.depth";
  public static final String INDEXING_PAUSED = "worker.indexing.paused";
  public static final String INDEX_PENDING_EMBEDDINGS = "worker.index.pending_embeddings";
  public static final String INDEX_PENDING_VDU = "worker.index.pending_vdu";

  /**
   * Tempdoc 419 C3 V2 P2: instantaneous indexing rate (docs/sec) computed by
   * {@link io.justsearch.indexerworker.metrics.OperationalMetrics.ThroughputMonitor}'s 180s
   * rolling window. Curated for RRD archive so the head can fetch the recent trend and
   * surface it as {@code worker.core.recentDocsPerSec} on {@code /api/status}. Returns 0.0
   * during UNKNOWN windows (insufficient samples, or {@code processingJobs == 0}).
   */
  public static final String INDEX_DOCS_PER_SEC = "worker.documents.indexed.rate_per_sec";

  /**
   * Late-binding suppliers for the queue/buffer/state gauges. Allows the catalog to be
   * constructed before {@code jobQueue} / {@code appServices} are fully initialized; suppliers
   * are invoked at flush time by the OTel SDK.
   */
  public record Sources(
      LongSupplier jobQueueDepth,
      LongSupplier pendingJobs,
      LongSupplier processingJobs,
      LongSupplier pendingReadyJobs,
      LongSupplier pendingBackoffJobs,
      LongSupplier switchBufferDepth,
      LongSupplier indexingPaused, // 1 if paused, 0 otherwise
      LongSupplier pendingEmbeddings,
      LongSupplier pendingVdu) {

    /** Default suppliers all returning 0 — used by tests / startup. */
    public static final Sources EMPTY =
        new Sources(
            () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L,
            () -> 0L);
  }

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          // Observable counters (curated → archive)
          MetricDefinition.observableCounter(DOCUMENTS_INDEXED_TOTAL)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.observableCounter(DOCUMENTS_FAILED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(BATCHES_SUBMITTED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(BATCHES_REJECTED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(COMMITS_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(SEARCHES_TOTAL)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.observableCounter(SEARCHES_ZERO_RESULT_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(SEARCHES_FAILED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(RAG_RETRIEVALS_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(RAG_RETRIEVALS_BM25_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(RAG_RETRIEVALS_HYBRID_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(RAG_RETRIEVALS_FALLBACK_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(SEARCH_HYBRID_FALLBACK_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(SEARCH_VECTOR_BLOCKED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.observableCounter(SEARCH_SPLADE_SKIPPED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.counter(SWITCH_BUFFER_WRITE_FAILURES).unit(Unit.COUNT).build(),

          // Latency / content-length gauges
          MetricDefinition.gauge(SEARCH_LAST_LATENCY_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.gauge(INDEX_LAST_LATENCY_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.gauge(CONTENT_LENGTH_COUNT).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(CONTENT_LENGTH_MIN_CHARS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(CONTENT_LENGTH_MAX_CHARS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(SEARCH_AVG_LATENCY_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.gauge(INDEX_AVG_LATENCY_MS).unit(Unit.MILLISECONDS).build(),
          MetricDefinition.gauge(CONTENT_LENGTH_AVG_CHARS).unit(Unit.COUNT).build(),

          // Queue / buffer / state gauges (curated → archive)
          MetricDefinition.gauge(JOB_QUEUE_DEPTH)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              // 419 C3 V1: 30-min trend exposed at worker.core.recentJobQueueDepth.
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "recentJobQueueDepth")
              .build(),
          MetricDefinition.gauge(JOB_QUEUE_PENDING_JOBS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(JOB_QUEUE_PROCESSING_JOBS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(JOB_QUEUE_PENDING_READY_JOBS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(JOB_QUEUE_PENDING_BACKOFF_JOBS).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(SWITCH_BUFFER_DEPTH)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.gauge(INDEXING_PAUSED).unit(Unit.COUNT).build(),
          MetricDefinition.gauge(INDEX_PENDING_EMBEDDINGS)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build(),
          MetricDefinition.gauge(INDEX_PENDING_VDU).unit(Unit.COUNT).build(),
          // 419 C3 V2 P2: instantaneous indexing rate (docs/sec). Curated for RRD trend that
          // backs worker.core.recentDocsPerSec on /api/status.
          MetricDefinition.gauge(INDEX_DOCS_PER_SEC)
              .unit(Unit.RATIO)
              .archivedTo(RrdArchive.STANDARD)
              .surfacedAt(StatusEndpoint.CORE_INDEX_VIEW, "recentDocsPerSec")
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "WorkerOpsMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  /** Cached no-op singleton. F6 fix. */
  private static final WorkerOpsMetricCatalog NOOP =
      new WorkerOpsMetricCatalog(
          new NoopMetricRegistry(DEFINITIONS),
          // Use a no-op metrics instance avoiding singleton coupling: pass a no-op-friendly
          // empty instance via OperationalMetrics.getInstance(). It's a singleton with safe
          // accessors.
          OperationalMetrics.getInstance(),
          Sources.EMPTY);

  // Observable counter handles
  public final ObservableCounterMetric<EmptyTags> documentsIndexedTotal;
  public final ObservableCounterMetric<EmptyTags> documentsFailedTotal;
  public final ObservableCounterMetric<EmptyTags> batchesSubmittedTotal;
  public final ObservableCounterMetric<EmptyTags> batchesRejectedTotal;
  public final ObservableCounterMetric<EmptyTags> commitsTotal;
  public final ObservableCounterMetric<EmptyTags> searchesTotal;
  public final ObservableCounterMetric<EmptyTags> searchesZeroResultTotal;
  public final ObservableCounterMetric<EmptyTags> searchesFailedTotal;
  public final ObservableCounterMetric<EmptyTags> ragRetrievalsTotal;
  public final ObservableCounterMetric<EmptyTags> ragRetrievalsBm25Total;
  public final ObservableCounterMetric<EmptyTags> ragRetrievalsHybridTotal;
  public final ObservableCounterMetric<EmptyTags> ragRetrievalsFallbackTotal;
  public final ObservableCounterMetric<EmptyTags> searchHybridFallbackTotal;
  public final ObservableCounterMetric<EmptyTags> searchVectorBlockedTotal;
  public final ObservableCounterMetric<EmptyTags> searchSpladeSkippedTotal;
  public final CounterMetric<EmptyTags> switchBufferWriteFailures;

  // Latency / content-length gauges
  public final GaugeMetric<EmptyTags> searchLastLatencyMs;
  public final GaugeMetric<EmptyTags> indexLastLatencyMs;
  public final GaugeMetric<EmptyTags> contentLengthCount;
  public final GaugeMetric<EmptyTags> contentLengthMinChars;
  public final GaugeMetric<EmptyTags> contentLengthMaxChars;
  public final GaugeMetric<EmptyTags> searchAvgLatencyMs;
  public final GaugeMetric<EmptyTags> indexAvgLatencyMs;
  public final GaugeMetric<EmptyTags> contentLengthAvgChars;

  // Queue / buffer / state gauges
  public final GaugeMetric<EmptyTags> jobQueueDepth;
  public final GaugeMetric<EmptyTags> jobQueuePendingJobs;
  public final GaugeMetric<EmptyTags> jobQueueProcessingJobs;
  public final GaugeMetric<EmptyTags> jobQueuePendingReadyJobs;
  public final GaugeMetric<EmptyTags> jobQueuePendingBackoffJobs;
  public final GaugeMetric<EmptyTags> switchBufferDepth;
  public final GaugeMetric<EmptyTags> indexingPaused;
  public final GaugeMetric<EmptyTags> indexPendingEmbeddings;
  public final GaugeMetric<EmptyTags> indexPendingVdu;
  /** Tempdoc 419 C3 V2 P2: instantaneous indexing rate (docs/sec) curated for RRD trend. */
  public final GaugeMetric<EmptyTags> indexDocsPerSec;

  public WorkerOpsMetricCatalog(MetricRegistry registry, OperationalMetrics m, Sources sources) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(m, "operationalMetrics");
    Objects.requireNonNull(sources, "sources");

    // Observable counters (LongAdder-derived)
    this.documentsIndexedTotal =
        registry.buildObservableCounter(
            DOCUMENTS_INDEXED_TOTAL, EmptyTags.INSTANCE, m::getDocumentsIndexed);
    this.documentsFailedTotal =
        registry.buildObservableCounter(
            DOCUMENTS_FAILED_TOTAL, EmptyTags.INSTANCE, m::getDocumentsFailed);
    this.batchesSubmittedTotal =
        registry.buildObservableCounter(
            BATCHES_SUBMITTED_TOTAL, EmptyTags.INSTANCE, m::getBatchesSubmitted);
    this.batchesRejectedTotal =
        registry.buildObservableCounter(
            BATCHES_REJECTED_TOTAL, EmptyTags.INSTANCE, m::getBatchesRejected);
    this.commitsTotal =
        registry.buildObservableCounter(COMMITS_TOTAL, EmptyTags.INSTANCE, m::getCommitsTotal);
    this.searchesTotal =
        registry.buildObservableCounter(SEARCHES_TOTAL, EmptyTags.INSTANCE, m::getSearchesTotal);
    this.searchesZeroResultTotal =
        registry.buildObservableCounter(
            SEARCHES_ZERO_RESULT_TOTAL, EmptyTags.INSTANCE, m::getSearchesZeroResultCount);
    this.searchesFailedTotal =
        registry.buildObservableCounter(
            SEARCHES_FAILED_TOTAL, EmptyTags.INSTANCE, m::getSearchesFailed);
    this.ragRetrievalsTotal =
        registry.buildObservableCounter(
            RAG_RETRIEVALS_TOTAL, EmptyTags.INSTANCE, m::getRagRetrievalsTotal);
    this.ragRetrievalsBm25Total =
        registry.buildObservableCounter(
            RAG_RETRIEVALS_BM25_TOTAL, EmptyTags.INSTANCE, m::getRagRetrievalsBm25);
    this.ragRetrievalsHybridTotal =
        registry.buildObservableCounter(
            RAG_RETRIEVALS_HYBRID_TOTAL, EmptyTags.INSTANCE, m::getRagRetrievalsHybrid);
    this.ragRetrievalsFallbackTotal =
        registry.buildObservableCounter(
            RAG_RETRIEVALS_FALLBACK_TOTAL, EmptyTags.INSTANCE, m::getRagRetrievalsFallback);
    this.searchHybridFallbackTotal =
        registry.buildObservableCounter(
            SEARCH_HYBRID_FALLBACK_TOTAL, EmptyTags.INSTANCE, m::getHybridFallbackTotal);
    this.searchVectorBlockedTotal =
        registry.buildObservableCounter(
            SEARCH_VECTOR_BLOCKED_TOTAL, EmptyTags.INSTANCE, m::getVectorBlockedTotal);
    this.searchSpladeSkippedTotal =
        registry.buildObservableCounter(
            SEARCH_SPLADE_SKIPPED_TOTAL, EmptyTags.INSTANCE, m::getSpladeSkippedTotal);
    this.switchBufferWriteFailures = registry.buildCounter(SWITCH_BUFFER_WRITE_FAILURES);

    // Latency / content-length gauges
    this.searchLastLatencyMs =
        registry.buildGauge(
            SEARCH_LAST_LATENCY_MS, EmptyTags.INSTANCE, () -> (double) m.getLastSearchLatencyMs());
    this.indexLastLatencyMs =
        registry.buildGauge(
            INDEX_LAST_LATENCY_MS, EmptyTags.INSTANCE, () -> (double) m.getLastIndexLatencyMs());
    this.contentLengthCount =
        registry.buildGauge(
            CONTENT_LENGTH_COUNT, EmptyTags.INSTANCE, () -> (double) m.getContentLengthCount());
    this.contentLengthMinChars =
        registry.buildGauge(
            CONTENT_LENGTH_MIN_CHARS,
            EmptyTags.INSTANCE,
            () -> (double) m.getContentLengthMinChars());
    this.contentLengthMaxChars =
        registry.buildGauge(
            CONTENT_LENGTH_MAX_CHARS,
            EmptyTags.INSTANCE,
            () -> (double) m.getContentLengthMaxChars());
    this.searchAvgLatencyMs =
        registry.buildGauge(
            SEARCH_AVG_LATENCY_MS, EmptyTags.INSTANCE, m::getAverageSearchLatencyMs);
    this.indexAvgLatencyMs =
        registry.buildGauge(INDEX_AVG_LATENCY_MS, EmptyTags.INSTANCE, m::getAverageIndexLatencyMs);
    this.contentLengthAvgChars =
        registry.buildGauge(
            CONTENT_LENGTH_AVG_CHARS, EmptyTags.INSTANCE, m::getAverageContentLengthChars);

    // Queue / buffer / state gauges
    this.jobQueueDepth =
        registry.buildGauge(
            JOB_QUEUE_DEPTH, EmptyTags.INSTANCE, () -> (double) sources.jobQueueDepth().getAsLong());
    this.jobQueuePendingJobs =
        registry.buildGauge(
            JOB_QUEUE_PENDING_JOBS,
            EmptyTags.INSTANCE,
            () -> (double) sources.pendingJobs().getAsLong());
    this.jobQueueProcessingJobs =
        registry.buildGauge(
            JOB_QUEUE_PROCESSING_JOBS,
            EmptyTags.INSTANCE,
            () -> (double) sources.processingJobs().getAsLong());
    this.jobQueuePendingReadyJobs =
        registry.buildGauge(
            JOB_QUEUE_PENDING_READY_JOBS,
            EmptyTags.INSTANCE,
            () -> (double) sources.pendingReadyJobs().getAsLong());
    this.jobQueuePendingBackoffJobs =
        registry.buildGauge(
            JOB_QUEUE_PENDING_BACKOFF_JOBS,
            EmptyTags.INSTANCE,
            () -> (double) sources.pendingBackoffJobs().getAsLong());
    this.switchBufferDepth =
        registry.buildGauge(
            SWITCH_BUFFER_DEPTH,
            EmptyTags.INSTANCE,
            () -> (double) sources.switchBufferDepth().getAsLong());
    this.indexingPaused =
        registry.buildGauge(
            INDEXING_PAUSED,
            EmptyTags.INSTANCE,
            () -> (double) sources.indexingPaused().getAsLong());
    this.indexPendingEmbeddings =
        registry.buildGauge(
            INDEX_PENDING_EMBEDDINGS,
            EmptyTags.INSTANCE,
            () -> (double) sources.pendingEmbeddings().getAsLong());
    this.indexPendingVdu =
        registry.buildGauge(
            INDEX_PENDING_VDU,
            EmptyTags.INSTANCE,
            () -> (double) sources.pendingVdu().getAsLong());
    // 419 C3 V2 P2: docs/sec from ThroughputMonitor's 180s rolling window. The supplier
    // also calls recordSample() so the monitor accumulates samples on flush cadence even
    // when no /api/status RPC is hitting the worker — keeps the trend self-sufficient.
    this.indexDocsPerSec =
        registry.buildGauge(
            INDEX_DOCS_PER_SEC,
            EmptyTags.INSTANCE,
            () -> {
              m.throughputMonitor().recordSample(m.getDocumentsIndexed());
              return m.throughputMonitor()
                  .compute(sources.processingJobs().getAsLong())
                  .docsPerSec();
            });
  }

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static WorkerOpsMetricCatalog noop() {
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
