/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.app.services.observability.HeadApiTags.ApiErrorTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiRequestTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiStreamTags;
import io.justsearch.telemetry.catalog.CounterMetric;
import io.justsearch.telemetry.catalog.HistogramMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import io.justsearch.telemetry.catalog.Unit;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 2c catalog for {@code api.*} metrics emitted by the Head HTTP layer.
 * Replaces the inline {@code Telemetry.HistogramConfig} constants in
 * {@code KnowledgeSearchController}, {@code PreviewController}, {@code SseWriter}, and
 * {@code ApiErrorHandler}.
 *
 * <p>Bucket bounds match the pre-refactor inline configs (byte-stable wire format).
 * {@code api.error.total} keeps {@code cardinalityLimit(128)} since its values are bounded by the
 * {@link io.justsearch.app.api.ApiErrorCode} enum; {@code api.request_ms} and
 * {@code api.stream.ttft_ms} fall back to OTel's default 2000 (F7 — the previous tight limit
 * could silently drop legitimate samples).
 *
 * <p>Relocated from {@code modules/ui} to {@code modules/app-services} in F1.
 */
public final class HeadApiMetricCatalog implements MetricCatalog {

  public static final String NAMESPACE = "api";

  public static final String REQUEST_MS = "api.request_ms";
  public static final String STREAM_TTFT_MS = "api.stream.ttft_ms";
  public static final String ERROR_TOTAL = "api.error.total";

  public static final List<MetricDefinition> DEFINITIONS =
      List.of(
          MetricDefinition.histogram(REQUEST_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(HeadApiTags.REQUEST_KEYS)
              .buckets(List.of(10L, 20L, 50L, 100L, 200L, 400L, 800L, 1_500L, 3_000L))
              .build(),
          MetricDefinition.histogram(STREAM_TTFT_MS)
              .unit(Unit.MILLISECONDS)
              .tagKeys(HeadApiTags.STREAM_KEYS)
              .buckets(List.of(10L, 20L, 50L, 100L, 200L, 400L, 800L, 1_500L, 3_000L))
              .build(),
          MetricDefinition.counter(ERROR_TOTAL)
              .unit(Unit.COUNT)
              .tagKeys(HeadApiTags.ERROR_KEYS)
              .cardinalityLimit(128)
              .build());

  static {
    String prefix = NAMESPACE + ".";
    for (MetricDefinition def : DEFINITIONS) {
      if (!def.name().startsWith(prefix)) {
        throw new ExceptionInInitializerError(
            "HeadApiMetricCatalog metric '"
                + def.name()
                + "' does not match namespace '"
                + NAMESPACE
                + "'");
      }
    }
  }

  /** Cached no-op singleton. F6 fix. */
  private static final HeadApiMetricCatalog NOOP =
      new HeadApiMetricCatalog(new NoopMetricRegistry(DEFINITIONS));

  public final HistogramMetric<ApiRequestTags> requestMs;
  public final HistogramMetric<ApiStreamTags> streamTtftMs;
  public final CounterMetric<ApiErrorTags> errorTotal;

  public HeadApiMetricCatalog(MetricRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    this.requestMs = registry.buildHistogram(REQUEST_MS);
    this.streamTtftMs = registry.buildHistogram(STREAM_TTFT_MS);
    this.errorTotal = registry.buildCounter(ERROR_TOTAL);
  }

  /** No-op catalog for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static HeadApiMetricCatalog noop() {
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
