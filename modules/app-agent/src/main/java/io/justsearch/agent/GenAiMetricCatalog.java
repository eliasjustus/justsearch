/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.GenAiTags.GenAiOperationTags;
import io.justsearch.agent.GenAiTags.GenAiTokenUsageTags;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 2d catalog for {@code gen_ai.*} metrics following OTel semantic conventions.
 * Operation-name and token-type tag values are open {@code String}s; the View applies
 * {@code cardinalityLimit(16)} as a defensive guard.
 */
public final class GenAiMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "gen_ai";

  public static final String OPERATION_DURATION = "gen_ai.client.operation.duration";
  public static final String TOKEN_USAGE = "gen_ai.client.token.usage";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.histogram(OPERATION_DURATION)
              .unit(Unit.MILLISECONDS)
              .tagKeys(GenAiTags.OPERATION_KEYS)
              .buckets(List.of(1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L, 500L, 1_000L, 2_000L))
              .cardinalityLimit(16)
              .build(),
          MetricDefinition.histogram(TOKEN_USAGE)
              .unit(Unit.NONE)
              .tagKeys(GenAiTags.TOKEN_KEYS)
              .buckets(List.of(1L, 8L, 32L, 128L, 512L, 2_048L, 8_192L, 32_768L))
              .cardinalityLimit(16)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "GenAiMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  public final HistogramMetric<GenAiOperationTags> operationDuration;
  public final HistogramMetric<GenAiTokenUsageTags> tokenUsage;

  public GenAiMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.operationDuration = registry.buildHistogram(OPERATION_DURATION);
    this.tokenUsage = registry.buildHistogram(TOKEN_USAGE);
  }

  /** Cached no-op singleton. F6 fix. */
  private static final GenAiMetricCatalog NOOP =
      new GenAiMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static GenAiMetricCatalog noop() {
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
