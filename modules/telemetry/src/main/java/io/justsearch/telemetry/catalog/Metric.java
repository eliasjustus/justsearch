/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/**
 * Sealed root of the typed metric instrument hierarchy. Each subtype wraps an OTel primitive
 * ({@code LongCounter}, {@code LongHistogram}, observable callback) and carries its tag schema as
 * a generic parameter for compile-time enforcement at every emit callsite.
 *
 * <p>Catalogs construct metric instances at module bootstrap (once the {@code SdkMeterProvider}
 * is built); production code holds typed references and emits via {@code metric.record(...)}.
 */
public sealed interface Metric
    permits CounterMetric, HistogramMetric, GaugeMetric, ObservableCounterMetric {

  /** Returns the immutable definition that backs this instrument. */
  MetricDefinition definition();

  /** Convenience: the metric's fully-qualified name. */
  default String name() {
    return definition().name();
  }
}
