/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.metrics.LongHistogram;
import java.util.Objects;

/**
 * Typed long histogram. Tag schema {@code T} is enforced at every emit callsite.
 *
 * @param <T> the tag schema type associated with this metric
 */
public final class HistogramMetric<T extends TagSchema> implements Metric {

  private final MetricDefinition definition;
  private final LongHistogram delegate;

  /**
   * Constructor — invoked by {@link MetricRegistry#buildHistogram} only. Public to allow the
   * registry implementation to live in a sibling package; production code should not call this
   * directly.
   */
  public HistogramMetric(MetricDefinition definition, LongHistogram delegate) {
    if (definition.kind() != InstrumentKind.HISTOGRAM) {
      throw new IllegalArgumentException(
          "HistogramMetric requires kind=HISTOGRAM; got " + definition.kind());
    }
    this.definition = Objects.requireNonNull(definition, "definition");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Records {@code value} on the histogram. */
  public void record(long value, T tags) {
    delegate.record(value, tags.toAttributes());
  }

  @Override
  public MetricDefinition definition() {
    return definition;
  }
}
