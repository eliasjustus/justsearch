/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Catalog for {@code ingestion.*} metrics. Currently single-metric: tempdoc 410's
 * {@code ingestion.outcome_write_failures_total} (introduced by Slice A2; migrated from legacy
 * {@code Telemetry.Counter} to typed catalog instrument as part of the 417 → main merge).
 */
public final class IngestionOutcomeMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "ingestion";

  public static final String OUTCOME_WRITE_FAILURES_TOTAL =
      "ingestion.outcome_write_failures_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(OUTCOME_WRITE_FAILURES_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(IngestionOutcomeTags.KEYS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "IngestionOutcomeMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<IngestionOutcomeTags> outcomeWriteFailuresTotal;

  public IngestionOutcomeMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.outcomeWriteFailuresTotal = registry.buildCounter(OUTCOME_WRITE_FAILURES_TOTAL);
  }

  /** Cached no-op singleton for tests / bootstrap paths without {@code LocalTelemetry}. */
  private static final IngestionOutcomeMetricCatalog NOOP =
      new IngestionOutcomeMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  public static IngestionOutcomeMetricCatalog noop() {
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
