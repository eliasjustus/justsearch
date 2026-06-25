/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.inference;

import io.justsearch.app.services.inference.InferenceTags.ConfigApplyTags;
import io.justsearch.app.services.inference.InferenceTags.ConfigFailureTags;
import io.justsearch.app.services.inference.InferenceTags.HealthFailureTags;
import io.justsearch.app.services.inference.InferenceTags.RequestDurationTags;
import io.justsearch.app.services.inference.InferenceTags.RequestQueueTags;
import io.justsearch.app.services.inference.InferenceTags.StartupAttemptTags;
import io.justsearch.app.services.inference.InferenceTags.StartupDurationTags;
import io.justsearch.app.services.inference.InferenceTags.StartupFailureTags;
import io.justsearch.app.services.inference.InferenceTags.TransitionTags;
import io.justsearch.telemetry.catalog.Buckets;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.Exemplars;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 412 Phase 4 catalog for {@code inference.*} metrics. Wired by
 * {@link InferenceTelemetryAdapter}, which implements
 * {@code io.justsearch.app.inference.telemetry.InferenceTelemetryEvents} and emits via the
 * typed instrument fields exposed here. Mirrors the
 * {@link io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog} shape from
 * tempdoc 417.
 *
 * <p>Status gauges (queue.depth, queue.active_slots, queue.total_slots,
 * generation.tokens_per_second, runtime.kv_cache_usage_ratio) are intentionally absent here:
 * llama-server's Prometheus {@code /metrics} endpoint is now exposed (Phase 2 added the
 * {@code --metrics} flag), but no scraper is wired yet — these will be added when scraper
 * support lands. The current set covers all event-driven metrics which the adapter populates
 * from {@code InferenceTelemetryEvents} callbacks.
 */
public final class InferenceMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "inference";

  // Metric names — public constants for cross-module reference.
  public static final String TRANSITION_TOTAL = "inference.transition.total";
  public static final String TRANSITION_DURATION_MS = "inference.transition.duration_ms";
  public static final String STARTUP_ATTEMPT_TOTAL = "inference.startup.attempt_total";
  public static final String STARTUP_DURATION_MS = "inference.startup.duration_ms";
  public static final String STARTUP_FAILURE_TOTAL = "inference.startup.failure_total";
  // Tempdoc 518 fix B: `inference.transition.failure_total` (added in Slice 2) is GONE.
  // It was an over-correction for observations.md #99 — routing by sub-record type
  // misattributed switchToOnlineMode failures of ConfigFailure kind to config.apply.failure_total.
  // The cleaner resolution: each event method picks one metric based on its OWN context;
  // the failure's wireCode flows through as a String tag on the existing metrics. Bug D
  // (TransitionFailure arriving at startup context → code=unknown synthesis) is resolved
  // because the tag is broadened to String wireCode — no synthesis needed.
  public static final String CONFIG_APPLY_TOTAL = "inference.config.apply_total";
  public static final String CONFIG_APPLY_FAILURE_TOTAL = "inference.config.apply_failure_total";
  public static final String HEALTH_FAILURE_TOTAL = "inference.health.failure_total";
  public static final String HEALTH_RECOVERED_TOTAL = "inference.health.recovered_total";
  public static final String REQUEST_QUEUE_WAIT_MS = "inference.request.queue_wait_ms";
  public static final String REQUEST_DURATION_MS = "inference.request.duration_ms";

  /**
   * Static definitions. Pass to {@code LocalTelemetry}'s constructor before constructing this
   * catalog so per-metric Views are registered before the {@code SdkMeterProvider} is built.
   */
  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(TRANSITION_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.TRANSITION_KEYS)
              .build(),
          MetricDefinition.histogram(TRANSITION_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(InferenceTags.TRANSITION_KEYS)
              .buckets(Buckets.TIME_HISTOGRAM)
              .exemplars(Exemplars.TRACE_BASED)
              .build(),
          MetricDefinition.counter(STARTUP_ATTEMPT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.STARTUP_ATTEMPT_KEYS)
              .build(),
          MetricDefinition.histogram(STARTUP_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(InferenceTags.STARTUP_DURATION_KEYS)
              .buckets(Buckets.TIME_HISTOGRAM)
              // Tempdoc 412 follow-up Bug G: declare OFF explicitly so the wire format invariant
              // (no exemplars key) is testable. Only inference.transition.duration_ms carries
              // trace exemplars.
              .exemplars(Exemplars.OFF)
              .build(),
          MetricDefinition.counter(STARTUP_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.STARTUP_FAILURE_KEYS)
              .build(),
          MetricDefinition.counter(CONFIG_APPLY_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.CONFIG_APPLY_KEYS)
              .build(),
          MetricDefinition.counter(CONFIG_APPLY_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.CONFIG_FAILURE_KEYS)
              .build(),
          MetricDefinition.counter(HEALTH_FAILURE_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(InferenceTags.HEALTH_FAILURE_KEYS)
              .build(),
          MetricDefinition.counter(HEALTH_RECOVERED_TOTAL).unit(Unit.COUNT).build(),
          MetricDefinition.histogram(REQUEST_QUEUE_WAIT_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(InferenceTags.REQUEST_QUEUE_KEYS)
              .buckets(Buckets.SLO_LATENCY_MS)
              .exemplars(Exemplars.OFF)
              .build(),
          MetricDefinition.histogram(REQUEST_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(InferenceTags.REQUEST_DURATION_KEYS)
              .buckets(Buckets.SLO_LATENCY_MS)
              .exemplars(Exemplars.OFF)
              .build());

  // Static-time validation: every declared metric name starts with the catalog's namespace.
  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "InferenceMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  // Typed instruments — public final, populated by the constructor.
  public final CounterMetric<TransitionTags> transitionTotal;
  public final HistogramMetric<TransitionTags> transitionDurationMs;
  public final CounterMetric<StartupAttemptTags> startupAttemptTotal;
  public final HistogramMetric<StartupDurationTags> startupDurationMs;
  public final CounterMetric<StartupFailureTags> startupFailureTotal;
  public final CounterMetric<ConfigApplyTags> configApplyTotal;
  public final CounterMetric<ConfigFailureTags> configApplyFailureTotal;
  public final CounterMetric<HealthFailureTags> healthFailureTotal;
  public final CounterMetric<EmptyTags> healthRecoveredTotal;
  public final HistogramMetric<RequestQueueTags> requestQueueWaitMs;
  public final HistogramMetric<RequestDurationTags> requestDurationMs;

  public InferenceMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.transitionTotal = registry.buildCounter(TRANSITION_TOTAL);
    this.transitionDurationMs = registry.buildHistogram(TRANSITION_DURATION_MS);
    this.startupAttemptTotal = registry.buildCounter(STARTUP_ATTEMPT_TOTAL);
    this.startupDurationMs = registry.buildHistogram(STARTUP_DURATION_MS);
    this.startupFailureTotal = registry.buildCounter(STARTUP_FAILURE_TOTAL);
    this.configApplyTotal = registry.buildCounter(CONFIG_APPLY_TOTAL);
    this.configApplyFailureTotal = registry.buildCounter(CONFIG_APPLY_FAILURE_TOTAL);
    this.healthFailureTotal = registry.buildCounter(HEALTH_FAILURE_TOTAL);
    this.healthRecoveredTotal = registry.buildCounter(HEALTH_RECOVERED_TOTAL);
    this.requestQueueWaitMs = registry.buildHistogram(REQUEST_QUEUE_WAIT_MS);
    this.requestDurationMs = registry.buildHistogram(REQUEST_DURATION_MS);
  }

  /**
   * No-op factory for code paths without a real {@code LocalTelemetry} (tests, AI-disabled
   * bootstrap). Mirrors {@code RagMetricCatalog.noop()}.
   */
  public static InferenceMetricCatalog noop() {
    return new InferenceMetricCatalog(new NoopMetricRegistry(DEFINITIONS));
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
