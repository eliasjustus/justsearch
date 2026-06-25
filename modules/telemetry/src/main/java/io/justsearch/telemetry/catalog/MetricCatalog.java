/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.List;
import java.util.Objects;

/**
 * A module-owned catalog of metric definitions. Implementations declare a stable namespace and a
 * list of {@link MetricDefinition} values; concrete catalog classes additionally expose typed
 * {@link Metric} instrument fields populated at construction time from a {@link MetricRegistry}.
 *
 * <p>Tempdoc 417 F2 fix: production catalogs have a single registry-arg constructor (final
 * fields), so they cannot be instantiated before {@code LocalTelemetry} exists. Use
 * {@link #of(String, List)} to create a definitions-only catalog wrapper for the
 * {@code LocalTelemetry} constructor; then construct the typed catalog instance against
 * {@code telemetry.registry()}:
 *
 * <pre>{@code
 * LocalTelemetry tel = new LocalTelemetry(dataDir, ..., List.of(
 *     MetricCatalog.of(IndexRuntimeMetricCatalog.NAMESPACE,
 *                      IndexRuntimeMetricCatalog.DEFINITIONS)));
 * IndexRuntimeMetricCatalog catalog = new IndexRuntimeMetricCatalog(tel.registry());
 * }</pre>
 *
 * <p>Every {@link MetricDefinition#name()} returned by {@link #definitions()} must start with
 * {@link #namespace()}{@code + "."}. The {@code LocalTelemetry} constructor enforces this.
 */
public interface MetricCatalog {

  /**
   * Returns the namespace prefix for all metrics in this catalog (e.g., {@code "index.runtime"}).
   * Every definition's metric name must begin with this prefix followed by a dot.
   */
  String namespace();

  /** Returns the list of metric definitions owned by this catalog. */
  List<MetricDefinition> definitions();

  /**
   * Returns a definitions-only catalog wrapper. Use this at {@code LocalTelemetry} construction
   * time to register Views from a catalog's static {@code DEFINITIONS} list, before constructing
   * the typed catalog instance against {@code telemetry.registry()}.
   */
  static MetricCatalog of(String namespace, List<MetricDefinition> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<MetricDefinition> defs = List.copyOf(definitions);
    return new MetricCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<MetricDefinition> definitions() {
        return defs;
      }
    };
  }
}
