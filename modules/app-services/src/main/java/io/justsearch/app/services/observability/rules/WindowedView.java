/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import java.util.Objects;

/**
 * Materialized window of samples for a single metric over a time range.
 *
 * <p>Per tempdoc 430 §A.4: exposes {@code avg / min / max / rate / count} as CEL-callable
 * member functions. Empty windows return sentinel values per the documented contract:
 * {@code count() == 0}, other accessors throw {@link MissingMetricException} (treated as
 * predicate-false by the rule evaluator).
 */
public final class WindowedView {

  private final String metricName;
  private final long[] timestamps;
  private final double[] values;

  public WindowedView(String metricName, long[] timestamps, double[] values) {
    this.metricName = Objects.requireNonNull(metricName, "metricName");
    this.timestamps = Objects.requireNonNull(timestamps, "timestamps").clone();
    this.values = Objects.requireNonNull(values, "values").clone();
    if (this.timestamps.length != this.values.length) {
      throw new IllegalArgumentException(
          "timestamps length "
              + this.timestamps.length
              + " != values length "
              + this.values.length);
    }
  }

  /** Returns an empty view (used when the underlying RRD query returns no rows). */
  static WindowedView empty(String metricName) {
    return new WindowedView(metricName, new long[0], new double[0]);
  }

  /** Number of samples in the window. */
  public long count() {
    return values.length;
  }

  /**
   * Mean of samples in the window.
   *
   * @throws MissingMetricException if the window contains no samples
   */
  public double avg() {
    requireNonEmpty();
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  /**
   * Minimum sample value in the window.
   *
   * @throws MissingMetricException if the window contains no samples
   */
  public double min() {
    requireNonEmpty();
    double min = Double.POSITIVE_INFINITY;
    for (double v : values) {
      if (v < min) {
        min = v;
      }
    }
    return min;
  }

  /**
   * Maximum sample value in the window.
   *
   * @throws MissingMetricException if the window contains no samples
   */
  public double max() {
    requireNonEmpty();
    double max = Double.NEGATIVE_INFINITY;
    for (double v : values) {
      if (v > max) {
        max = v;
      }
    }
    return max;
  }

  /**
   * Per-second rate of change between the first and last sample in the window. Returns 0 when
   * the window has fewer than 2 samples.
   */
  public double rate() {
    if (values.length < 2) {
      return 0.0;
    }
    long elapsedSeconds = timestamps[timestamps.length - 1] - timestamps[0];
    if (elapsedSeconds <= 0) {
      return 0.0;
    }
    double delta = values[values.length - 1] - values[0];
    return delta / elapsedSeconds;
  }

  private void requireNonEmpty() {
    if (values.length == 0) {
      throw new MissingMetricException(
          "WindowedView for metric '" + metricName + "' is empty (no samples in window)");
    }
  }
}
