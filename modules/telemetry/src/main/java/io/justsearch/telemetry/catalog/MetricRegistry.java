/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bound-side registry exposed by {@link io.justsearch.telemetry.LocalTelemetry} after the
 * {@code SdkMeterProvider} is built. Catalog constructors call the {@code build*} methods to
 * obtain typed instrument instances backed by OTel primitives.
 *
 * <p>The registry guarantees that the metric name passed at build time matches one of the
 * definitions handed to {@code LocalTelemetry} at construction; an unregistered name throws.
 */
public interface MetricRegistry {

  /** Builds a typed counter for a previously-registered counter definition. */
  <T extends TagSchema> CounterMetric<T> buildCounter(String name);

  /** Builds a typed histogram for a previously-registered histogram definition. */
  <T extends TagSchema> HistogramMetric<T> buildHistogram(String name);

  /**
   * Registers an asynchronous gauge backed by the supplied callback. The supplier is invoked at
   * each metric flush by the OTel SDK.
   */
  <T extends TagSchema> GaugeMetric<T> buildGauge(String name, T tags, Supplier<Double> supplier);

  /**
   * Registers an asynchronous counter backed by a {@link LongSupplier}. Use for bridging
   * externally-aggregated counters (e.g., {@code LongAdder}) without requiring per-increment
   * emit calls.
   */
  <T extends TagSchema> ObservableCounterMetric<T> buildObservableCounter(
      String name, T tags, LongSupplier supplier);
}
