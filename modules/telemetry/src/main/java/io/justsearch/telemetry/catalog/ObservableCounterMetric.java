/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.Objects;

/**
 * Typed asynchronous counter backed by a callback supplier. Used to bridge externally-aggregated
 * counters (e.g., {@code OperationalMetrics}'s {@code LongAdder}) into the catalog without
 * requiring an explicit emit at every increment.
 *
 * @param <T> the tag schema type associated with this metric
 */
public final class ObservableCounterMetric<T extends TagSchema> implements Metric {

  private final MetricDefinition definition;
  private final T tags;
  // Stored as Object: see GaugeMetric for rationale.
  private final Object handle;

  /**
   * Constructor — invoked by {@link MetricRegistry#buildObservableCounter} only. Public to allow
   * the registry implementation to live in a sibling package; production code should not call
   * this directly.
   */
  public ObservableCounterMetric(MetricDefinition definition, T tags, Object handle) {
    if (definition.kind() != InstrumentKind.OBSERVABLE_COUNTER) {
      throw new IllegalArgumentException(
          "ObservableCounterMetric requires kind=OBSERVABLE_COUNTER; got " + definition.kind());
    }
    this.definition = Objects.requireNonNull(definition, "definition");
    this.tags = Objects.requireNonNull(tags, "tags");
    this.handle = Objects.requireNonNull(handle, "handle");
  }

  /** Returns the immutable tag schema this observable counter was registered with. */
  public T tags() {
    return tags;
  }

  /** Releases the OTel callback registration. Idempotent / best-effort. */
  public void close() {
    if (handle instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // best-effort unregister
      }
    }
  }

  @Override
  public MetricDefinition definition() {
    return definition;
  }
}
