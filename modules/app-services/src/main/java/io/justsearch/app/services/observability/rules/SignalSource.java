/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.telemetry.RrdMetricStore;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for {@link Signal} instances keyed by metric name.
 *
 * <p>Per tempdoc 430 §A.4: the CEL evaluator pulls {@code signals['<name>']} via this source
 * each tick. Signals are lightweight (they hold a reference to the store + clock; queries
 * happen on accessor calls), so caching them per-name avoids per-tick allocation churn while
 * keeping the data fresh.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}.
 */
public final class SignalSource {

  private final RrdMetricStore store;
  private final Clock clock;
  private final ConcurrentMap<String, Signal> cache = new ConcurrentHashMap<>();

  public SignalSource(RrdMetricStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Returns the {@link Signal} for the given metric name. Signals for unknown / unarchived
   * metrics are still constructed (they throw {@link MissingMetricException} on access);
   * the CEL evaluator catches that and treats the tick as predicate-false (per rev 3.11 §B.X.5).
   */
  public Signal forName(String metricName) {
    Objects.requireNonNull(metricName, "metricName");
    return cache.computeIfAbsent(metricName, n -> new Signal(n, store, clock));
  }
}
