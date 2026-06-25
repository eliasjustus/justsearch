/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 2b catalog for {@code extraction.*} metrics emitted by
 * {@link TimeboxedContentExtractor}. Single-metric catalog: {@code extraction.timeout_total}.
 *
 * <p>The pre-refactor emission carried a constant {@code component=content_extractor} tag.
 * F2 restored it (was incorrectly dropped during initial Phase 2b — wire-format byte-stability
 * is the plan's default).
 */
public final class ExtractionMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "extraction";

  public static final String TIMEOUT_TOTAL = "extraction.timeout_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(TIMEOUT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(ExtractionTimeoutTags.KEYS)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "ExtractionMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final CounterMetric<ExtractionTimeoutTags> timeoutTotal;

  public ExtractionMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.timeoutTotal = registry.buildCounter(TIMEOUT_TOTAL);
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
