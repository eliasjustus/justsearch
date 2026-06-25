/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.metrics.LongCounter;
import java.util.Objects;

/**
 * Typed monotonic counter. Tag schema {@code T} is enforced at every emit callsite.
 *
 * @param <T> the tag schema type associated with this metric
 */
public final class CounterMetric<T extends TagSchema> implements Metric {

  private final MetricDefinition definition;
  private final LongCounter delegate;

  /**
   * Constructor — invoked by {@link MetricRegistry#buildCounter} only. Public to allow the
   * registry implementation to live in a sibling package; production code should not call this
   * directly.
   */
  public CounterMetric(MetricDefinition definition, LongCounter delegate) {
    if (definition.kind() != InstrumentKind.COUNTER) {
      throw new IllegalArgumentException(
          "CounterMetric requires kind=COUNTER; got " + definition.kind());
    }
    this.definition = Objects.requireNonNull(definition, "definition");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Increments the counter by 1. */
  public void increment(T tags) {
    delegate.add(1L, tags.toAttributes());
  }

  /** Adds {@code delta} to the counter. {@code delta} must be non-negative. */
  public void add(long delta, T tags) {
    if (delta < 0) {
      throw new IllegalArgumentException("delta must be non-negative; got " + delta);
    }
    delegate.add(delta, tags.toAttributes());
  }

  @Override
  public MetricDefinition definition() {
    return definition;
  }
}
