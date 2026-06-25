/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/** Tempdoc 417 Phase 2e catalog for {@code vdu.*} metrics. */
public final class VduMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "vdu";

  public static final String TIMEOUT_TOTAL = "vdu.timeout_total";
  public static final String OUTCOME_TOTAL = "vdu.outcome_total";
  public static final String PASS1_DURATION_MS = "vdu.pass1.duration_ms";
  public static final String PASS2_DURATION_MS = "vdu.pass2.duration_ms";
  public static final String TOTAL_DURATION_MS = "vdu.total.duration_ms";

  /** VDU-scoped histogram bucket bounds (ms). Phase 3d. */
  private static final List<Long> VDU_DURATION_BUCKETS_MS =
      List.of(100L, 500L, 1_000L, 5_000L, 30_000L, 60_000L, 300_000L);

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(TIMEOUT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(VduTimeoutTags.KEYS)
              .build(),
          MetricDefinition.counter(OUTCOME_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(VduOutcomeTags.KEYS)
              .build(),
          MetricDefinition.histogram(PASS1_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(VduPassTags.KEYS)
              .buckets(VDU_DURATION_BUCKETS_MS)
              .build(),
          MetricDefinition.histogram(PASS2_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(VduPassTags.KEYS)
              .buckets(VDU_DURATION_BUCKETS_MS)
              .build(),
          MetricDefinition.histogram(TOTAL_DURATION_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(VduPassTags.KEYS)
              .buckets(VDU_DURATION_BUCKETS_MS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "VduMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<VduTimeoutTags> timeoutTotal;
  public final CounterMetric<VduOutcomeTags> outcomeTotal;
  public final HistogramMetric<VduPassTags> pass1DurationMs;
  public final HistogramMetric<VduPassTags> pass2DurationMs;
  public final HistogramMetric<VduPassTags> totalDurationMs;

  public VduMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.timeoutTotal = registry.buildCounter(TIMEOUT_TOTAL);
    this.outcomeTotal = registry.buildCounter(OUTCOME_TOTAL);
    this.pass1DurationMs = registry.buildHistogram(PASS1_DURATION_MS);
    this.pass2DurationMs = registry.buildHistogram(PASS2_DURATION_MS);
    this.totalDurationMs = registry.buildHistogram(TOTAL_DURATION_MS);
  }

  /** Cached no-op singleton. F6 fix. */
  private static final VduMetricCatalog NOOP =
      new VduMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static VduMetricCatalog noop() {
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
