/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A {@link MetricRegistry} backed by OTel's {@link MeterProvider#noop()} — every instrument it
 * builds is a real {@link Metric} wrapping an OTel no-op primitive. Emits are silently dropped;
 * no allocation beyond the wrapper.
 *
 * <p>Use this for bridge holders that expose a {@code .noop()} factory (e.g.,
 * {@code IpcTelemetry.noop()}, {@code AgentTelemetry.noop()}) so they can construct catalogs
 * without depending on {@link io.justsearch.telemetry.LocalTelemetry}. The catalog construction
 * path is the same whether the registry is real or no-op; only emit-side behavior differs.
 *
 * <p>Tempdoc 417 Phase 2a addition.
 */
public final class NoopMetricRegistry implements MetricRegistry {

  private final Meter meter = MeterProvider.noop().get("noop");
  private final Map<String, MetricDefinition> definitions;

  public NoopMetricRegistry(List<MetricDefinition> definitions) {
    Objects.requireNonNull(definitions, "definitions");
    this.definitions = new HashMap<>();
    for (MetricDefinition def : definitions) {
      if (this.definitions.put(def.name(), def) != null) {
        throw new IllegalArgumentException("Duplicate metric definition: " + def.name());
      }
    }
  }

  private MetricDefinition resolve(String name, InstrumentKind expected) {
    MetricDefinition def = definitions.get(name);
    if (def == null) {
      throw new IllegalArgumentException("No metric definition registered for name '" + name + "'");
    }
    if (def.kind() != expected) {
      throw new IllegalArgumentException(
          "Metric '" + name + "' has kind " + def.kind() + ", not " + expected);
    }
    return def;
  }

  @Override
  public <T extends TagSchema> CounterMetric<T> buildCounter(String name) {
    MetricDefinition def = resolve(name, InstrumentKind.COUNTER);
    return new CounterMetric<>(def, meter.counterBuilder(def.name()).build());
  }

  @Override
  public <T extends TagSchema> HistogramMetric<T> buildHistogram(String name) {
    MetricDefinition def = resolve(name, InstrumentKind.HISTOGRAM);
    return new HistogramMetric<>(def, meter.histogramBuilder(def.name()).ofLongs().build());
  }

  @Override
  public <T extends TagSchema> GaugeMetric<T> buildGauge(
      String name, T tags, Supplier<Double> supplier) {
    MetricDefinition def = resolve(name, InstrumentKind.GAUGE);
    Object handle = meter.gaugeBuilder(def.name()).buildWithCallback(measurement -> {});
    return new GaugeMetric<>(def, tags, handle);
  }

  @Override
  public <T extends TagSchema> ObservableCounterMetric<T> buildObservableCounter(
      String name, T tags, LongSupplier supplier) {
    MetricDefinition def = resolve(name, InstrumentKind.OBSERVABLE_COUNTER);
    Object handle = meter.counterBuilder(def.name()).buildWithCallback(measurement -> {});
    return new ObservableCounterMetric<>(def, tags, handle);
  }
}
