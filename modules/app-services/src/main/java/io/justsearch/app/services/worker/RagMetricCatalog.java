/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/** Tempdoc 417 Phase 2e catalog for {@code rag.*} metrics. */
public final class RagMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "rag";

  public static final String RETRIEVAL_TOTAL = "rag.retrieval_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(RETRIEVAL_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(RagRetrievalTags.KEYS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "RagMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<RagRetrievalTags> retrievalTotal;

  public RagMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.retrievalTotal = registry.buildCounter(RETRIEVAL_TOTAL);
  }

  /** Cached no-op singleton. F6 fix. */
  private static final RagMetricCatalog NOOP =
      new RagMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static RagMetricCatalog noop() {
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
