/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.StatusEndpoint;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tempdoc 417 Phase 2c catalog for {@code gpu.*} gauges. {@code LocalApiServer} owns the
 * 5-second snapshot cache; the catalog gauges' suppliers call into that cache.
 *
 * <p>Relocated from {@code modules/ui} to {@code modules/app-services} in F1.
 */
public final class HeadGpuMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "gpu";

  public static final String UTILIZATION_PERCENT = "gpu.utilization.percent";
  public static final String MEMORY_UTILIZATION_PERCENT = "gpu.memory.utilization.percent";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.gauge(UTILIZATION_PERCENT)
              .unit(Unit.RATIO)
              .archivedTo(RrdArchive.STANDARD)
              // 419 C3 V1: 30-min trend exposed at gpu.recentUtilizationPercent.
              .surfacedAt(StatusEndpoint.GPU_STATUS_VIEW, "recentUtilizationPercent")
              .build(),
          MetricDefinition.gauge(MEMORY_UTILIZATION_PERCENT)
              .unit(Unit.RATIO)
              .archivedTo(RrdArchive.STANDARD)
              // 419 C3 V1: 30-min trend exposed at gpu.recentMemoryUtilizationPercent.
              .surfacedAt(StatusEndpoint.GPU_STATUS_VIEW, "recentMemoryUtilizationPercent")
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "HeadGpuMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  /** Cached no-op singleton. F6 fix. */
  private static final HeadGpuMetricCatalog NOOP =
      new HeadGpuMetricCatalog(
          new NoopMetricRegistry(DEFINITIONS), () -> Double.NaN, () -> Double.NaN);

  public final GaugeMetric<EmptyTags> utilizationPercent;
  public final GaugeMetric<EmptyTags> memoryUtilizationPercent;

  public HeadGpuMetricCatalog(
      MetricRegistry registry,
      Supplier<Double> utilizationSupplier,
      Supplier<Double> memoryUtilizationSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(utilizationSupplier, "utilizationSupplier");
    Objects.requireNonNull(memoryUtilizationSupplier, "memoryUtilizationSupplier");
    this.utilizationPercent =
        registry.buildGauge(UTILIZATION_PERCENT, EmptyTags.INSTANCE, utilizationSupplier);
    this.memoryUtilizationPercent =
        registry.buildGauge(
            MEMORY_UTILIZATION_PERCENT, EmptyTags.INSTANCE, memoryUtilizationSupplier);
  }

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static HeadGpuMetricCatalog noop() {
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
