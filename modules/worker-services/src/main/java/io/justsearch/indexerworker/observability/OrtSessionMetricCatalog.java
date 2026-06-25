/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.observability;

import io.justsearch.indexerworker.observability.OrtSessionTags.AssemblerFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerOutcomeTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.GpuInitFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.RecoveryTags;
import io.justsearch.telemetry.catalog.Buckets;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.Exemplars;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 414 catalog for {@code ort.session.*} metrics. Backs
 * {@link OrtSessionTelemetryAdapter}, which is the production binding of
 * {@link io.justsearch.ort.telemetry.OrtSessionTelemetryEvents}.
 *
 * <p>v2 (this revision): {@code semaphore_wait_us} (renamed from {@code _ms} for sub-millisecond
 * resolution at the no-contention end of the distribution); {@code retry_interval_ms} added so
 * {@code GpuRetryAttempted.sinceFailureMs} has a metric consumer; cause-tagged metrics use typed
 * {@link GpuInitFailureTags} / {@link RecoveryTags} / {@link AssemblerFailureTags} instead of
 * stringly-typed cause records.
 *
 * <p>Pattern reference: {@link io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog}
 * (Phase 1 of tempdoc 417). Static-init namespace-prefix check matches the same precedent.
 */
public final class OrtSessionMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "ort.session";

  // Metric names — public constants for cross-module reference.
  public static final String SEMAPHORE_WAIT_US = "ort.session.semaphore_wait_us";
  public static final String GPU_INIT_TOTAL = "ort.session.gpu_init_total";
  public static final String GPU_INIT_FAILURE_TOTAL = "ort.session.gpu_init_failure_total";
  public static final String FALLBACK_TOTAL = "ort.session.fallback_total";
  public static final String RECOVERY_TOTAL = "ort.session.recovery_total";
  public static final String RELEASE_TOTAL = "ort.session.release_total";
  public static final String RETRY_TOTAL = "ort.session.retry_total";
  public static final String RETRY_INTERVAL_MS = "ort.session.retry_interval_ms";
  public static final String ASSEMBLER_FAILURE_TOTAL = "ort.session.assembler_failure_total";

  /**
   * Static definitions. Pass to {@code LocalTelemetry}'s constructor before constructing this
   * catalog so per-metric Views are registered before the {@code SdkMeterProvider} is built.
   */
  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.histogram(SEMAPHORE_WAIT_US)
              .unit(Unit.MICROSECONDS)
              .tagKeys(OrtSessionTags.CONSUMER_KEYS)
              .buckets(Buckets.WRITE_BARRIER_HISTOGRAM)
              // Hot path: per-call exemplars would balloon volume; suppress at export time.
              .exemplars(Exemplars.OFF)
              .build(),
          MetricDefinition.counter(GPU_INIT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_OUTCOME_KEYS)
              .build(),
          MetricDefinition.counter(GPU_INIT_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_CAUSE_KEYS)
              .build(),
          MetricDefinition.counter(FALLBACK_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_KEYS)
              .build(),
          MetricDefinition.counter(RECOVERY_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_CAUSE_KEYS)
              .build(),
          MetricDefinition.counter(RELEASE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_OUTCOME_KEYS)
              .build(),
          MetricDefinition.counter(RETRY_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_KEYS)
              .build(),
          MetricDefinition.histogram(RETRY_INTERVAL_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(OrtSessionTags.CONSUMER_KEYS)
              .buckets(Buckets.TIME_HISTOGRAM)
              .exemplars(Exemplars.TRACE_BASED)
              .build(),
          MetricDefinition.counter(ASSEMBLER_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(OrtSessionTags.CONSUMER_KIND_KEYS)
              .build());

  // Static-time validation: every declared metric name starts with the catalog's namespace.
  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "OrtSessionMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  // Typed instruments — public final, populated by the constructor.
  public final HistogramMetric<ConsumerTags> semaphoreWaitUs;
  public final CounterMetric<ConsumerOutcomeTags> gpuInitTotal;
  public final CounterMetric<GpuInitFailureTags> gpuInitFailureTotal;
  public final CounterMetric<ConsumerTags> fallbackTotal;
  public final CounterMetric<RecoveryTags> recoveryTotal;
  public final CounterMetric<ConsumerOutcomeTags> releaseTotal;
  public final CounterMetric<ConsumerTags> retryTotal;
  public final HistogramMetric<ConsumerTags> retryIntervalMs;
  public final CounterMetric<AssemblerFailureTags> assemblerFailureTotal;

  public OrtSessionMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.semaphoreWaitUs = registry.buildHistogram(SEMAPHORE_WAIT_US);
    this.gpuInitTotal = registry.buildCounter(GPU_INIT_TOTAL);
    this.gpuInitFailureTotal = registry.buildCounter(GPU_INIT_FAILURE_TOTAL);
    this.fallbackTotal = registry.buildCounter(FALLBACK_TOTAL);
    this.recoveryTotal = registry.buildCounter(RECOVERY_TOTAL);
    this.releaseTotal = registry.buildCounter(RELEASE_TOTAL);
    this.retryTotal = registry.buildCounter(RETRY_TOTAL);
    this.retryIntervalMs = registry.buildHistogram(RETRY_INTERVAL_MS);
    this.assemblerFailureTotal = registry.buildCounter(ASSEMBLER_FAILURE_TOTAL);
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
