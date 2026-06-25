/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Time-series view of a single curated metric, backed by {@link RrdMetricStore} queries.
 *
 * <p>Per tempdoc 430 §A.4: exposes {@code latest()} for the most-recent sample and
 * {@code window(duration)} returning a {@link WindowedView} for time-aggregations
 * ({@code avg / min / max / rate / count}).
 *
 * <p>{@link #latest()} throws {@link MissingMetricException} when the metric has no
 * recorded samples (or the metric isn't archived). Per rev 3.11 §B.X.5 this is the cleanest
 * semantics: the CEL evaluator catches the exception and the rule runner treats the tick
 * as predicate-false (logging at WARN once per missing-metric per startup).
 */
public final class Signal {

  private final String metricName;
  private final RrdMetricStore store;
  private final Clock clock;

  public Signal(String metricName, RrdMetricStore store, Clock clock) {
    this.metricName = Objects.requireNonNull(metricName, "metricName");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the metric name this Signal wraps. */
  public String metricName() {
    return metricName;
  }

  /**
   * Returns the most-recent sample value.
   *
   * @throws MissingMetricException when the metric has no recorded samples in the RRD store
   *     (covers both "metric isn't archived" and "process hasn't recorded a sample yet")
   */
  public double latest() {
    long now = clock.instant().getEpochSecond();
    // Look back ~5 RRD steps (300s) — covers the post-boot window before the first archived
    // sample arrives without bloating the query into older history.
    long start = now - 300L;
    TimeSeriesResult result = store.query(metricName, start, now);
    if (result == null || result.values().length == 0) {
      // Tempdoc 600 C-2 (Design B): the message is the STABLE fact ("metric X has no samples"), NOT
      // the momentary measurement. Embedding the moving query window [start, now] here would change
      // the text every tick, defeating CelEvaluator's WARN-once dedup (keyed on this message) and
      // ConditionStore's blind-condition dedup — churning a WARN + a CONDITION_MODIFIED every 5s.
      throw new MissingMetricException("metric '" + metricName + "' has no recorded samples");
    }
    double[] values = result.values();
    return values[values.length - 1];
  }

  /**
   * Returns a windowed view over the last {@code duration} of samples.
   *
   * @param duration window size as Prometheus-style string ({@code 5m}, {@code 30s}, {@code 1h})
   * @throws IllegalArgumentException if the duration string is malformed
   */
  public WindowedView window(String duration) {
    Duration d = RuleParser.parseDuration(duration, metricName, "window");
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException("window duration must be > 0, got '" + duration + "'");
    }
    long now = clock.instant().getEpochSecond();
    long start = now - d.toSeconds();
    TimeSeriesResult result = store.query(metricName, start, now);
    if (result == null) {
      return WindowedView.empty(metricName);
    }
    return new WindowedView(metricName, result.timestamps(), result.values());
  }
}
