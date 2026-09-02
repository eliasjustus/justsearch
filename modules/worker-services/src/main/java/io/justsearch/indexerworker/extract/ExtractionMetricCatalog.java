/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tempdoc 417 Phase 2b catalog for {@code extraction.*} metrics emitted by
 * {@link TimeboxedContentExtractor} and {@link PersistentExtractionSandbox}:
 * {@code extraction.timeout_total}, {@code extraction.sandbox_restart_total} and
 * {@code extraction.sandbox_spawn_total}.
 *
 * <p>The pre-refactor emission carried a constant {@code component=content_extractor} tag.
 * F2 restored it (was incorrectly dropped during initial Phase 2b — wire-format byte-stability
 * is the plan's default).
 *
 * <p>Tempdoc 885 item 14 added the two sandbox counters. Without them a child that is killed at
 * its deadline and respawned is invisible: the file's own outcome is recorded, but "how often is
 * the pool recycling, and why" has no observable at all.
 */
public final class ExtractionMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "extraction";

  public static final String TIMEOUT_TOTAL = "extraction.timeout_total";

  public static final String SANDBOX_RESTART_TOTAL = "extraction.sandbox_restart_total";

  public static final String SANDBOX_SPAWN_TOTAL = "extraction.sandbox_spawn_total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.counter(TIMEOUT_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(ExtractionTimeoutTags.KEYS)
              .build(),
          MetricDefinition.counter(SANDBOX_RESTART_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(ExtractionSandboxRestartTags.KEYS)
              .build(),
          MetricDefinition.counter(SANDBOX_SPAWN_TOTAL).unit(Unit.COUNT).tagKeys(Set.of()).build());

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

  public final CounterMetric<ExtractionSandboxRestartTags> sandboxRestartTotal;

  public final CounterMetric<EmptyTags> sandboxSpawnTotal;

  public ExtractionMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.timeoutTotal = registry.buildCounter(TIMEOUT_TOTAL);
    this.sandboxRestartTotal = registry.buildCounter(SANDBOX_RESTART_TOTAL);
    this.sandboxSpawnTotal = registry.buildCounter(SANDBOX_SPAWN_TOTAL);
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
