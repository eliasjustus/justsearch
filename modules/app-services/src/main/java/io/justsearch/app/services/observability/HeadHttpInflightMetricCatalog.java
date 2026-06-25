/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
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
 * Tempdoc 417 Phase 2c catalog for {@code head.http.inflight_requests} (a single gauge).
 *
 * <p>Gauges register their callback at construction time, so this catalog requires the supplier
 * up front rather than via a typed instrument field.
 *
 * <p>Relocated from {@code modules/ui} to {@code modules/app-services} in F1.
 */
public final class HeadHttpInflightMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "head.http";

  public static final String INFLIGHT_REQUESTS = "head.http.inflight_requests";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.gauge(INFLIGHT_REQUESTS)
              .unit(Unit.COUNT)
              .archivedTo(RrdArchive.STANDARD)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "HeadHttpInflightMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  /** Cached no-op singleton. F6 fix. */
  private static final HeadHttpInflightMetricCatalog NOOP =
      new HeadHttpInflightMetricCatalog(new NoopMetricRegistry(DEFINITIONS), () -> 0.0d);

  public final GaugeMetric<EmptyTags> inflightRequests;

  public HeadHttpInflightMetricCatalog(MetricRegistry registry, Supplier<Double> inflightSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(inflightSupplier, "inflightSupplier");
    this.inflightRequests =
        registry.buildGauge(INFLIGHT_REQUESTS, EmptyTags.INSTANCE, inflightSupplier);
  }

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static HeadHttpInflightMetricCatalog noop() {
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
