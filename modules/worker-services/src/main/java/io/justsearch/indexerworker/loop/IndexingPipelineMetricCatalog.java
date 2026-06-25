/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 2b catalog for {@code pipeline.*} metrics emitted by the indexing loop.
 * Replaces the inline {@code Telemetry.histogram(name, tags, config, value)} call with a typed
 * histogram instrument carrying its bucket bounds + tag schema in declared metadata.
 *
 * <p>The {@code pipeline.stage_ms} bucket bounds match the pre-refactor inline
 * {@code Telemetry.HistogramConfig} from {@code IndexingLoop} so wire format is byte-stable.
 */
public final class IndexingPipelineMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "pipeline";

  public static final String STAGE_MS = "pipeline.stage_ms";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.histogram(STAGE_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(PipelineStageTags.KEYS)
              .buckets(List.of(10L, 20L, 50L, 100L, 200L, 400L, 800L, 1_500L, 3_000L))
              .cardinalityLimit(256)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "IndexingPipelineMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final HistogramMetric<PipelineStageTags> stageMs;

  public IndexingPipelineMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.stageMs = registry.buildHistogram(STAGE_MS);
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
