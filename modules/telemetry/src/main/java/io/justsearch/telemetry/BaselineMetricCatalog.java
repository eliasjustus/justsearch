/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Objects;

/**
 * Always-on baseline gauges that every {@code LocalTelemetry} registers automatically.
 *
 * <p>Tempdoc 417 design-property alignment follow-up (Step 3a): folds the previously
 * direct-OTel {@code jvm.uptime_ms} emission into the catalog substrate, closing the
 * "one metric outside the catalog discipline" exception documented in the alignment
 * retrospective.
 *
 * <p>Wire-format byte-stability: the metric name is the legacy {@code jvm.uptime_ms} (no
 * per-process prefix) so existing NDJSON consumers see no change. The catalog's namespace
 * is {@code jvm} (the leading segment of the metric name) which satisfies
 * {@code LocalTelemetry}'s namespace-prefix check.
 *
 * <p>This catalog is registered as the LAST entry in every {@code LocalTelemetry}'s catalog
 * list, so it always emits regardless of which other catalogs the caller registered.
 */
public final class BaselineMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "jvm";

  public static final String UPTIME_MS = "jvm.uptime_ms";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(MetricDefinition.gauge(UPTIME_MS).unit(Unit.MILLISECONDS).build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "BaselineMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  /** Wraps {@link #DEFINITIONS} as a {@link MetricCatalog} suitable for boot-time registration. */
  public static MetricCatalog catalogFor() {
    return MetricCatalog.of(NAMESPACE, DEFINITIONS);
  }

  public final GaugeMetric<EmptyTags> uptimeMs;

  /**
   * Default constructor — wires the {@link RuntimeMXBean#getUptime()} supplier. Used by
   * {@code LocalTelemetry}'s constructor to register the baseline gauge automatically.
   */
  public BaselineMetricCatalog(MetricRegistry registry) {
    this(registry, ManagementFactory.getRuntimeMXBean()::getUptime);
  }

  /**
   * Constructor with explicit supplier for tests.
   *
   * @param registry catalog-bound metric registry
   * @param uptimeSupplier returns current uptime in milliseconds; called per flush cycle
   */
  public BaselineMetricCatalog(MetricRegistry registry, java.util.function.LongSupplier uptimeSupplier) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(uptimeSupplier, "uptimeSupplier");
    this.uptimeMs =
        registry.buildGauge(UPTIME_MS, EmptyTags.INSTANCE, () -> (double) uptimeSupplier.getAsLong());
  }

  /** Cached no-op singleton for tests / bootstrap paths without {@code LocalTelemetry}. */
  private static final BaselineMetricCatalog NOOP =
      new BaselineMetricCatalog(new NoopMetricRegistry(DEFINITIONS), () -> 0L);

  /** No-op catalog for tests. */
  public static BaselineMetricCatalog noop() {
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
