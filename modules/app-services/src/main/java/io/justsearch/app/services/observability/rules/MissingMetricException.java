/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

/**
 * Thrown by {@link Signal#latest()} and {@link WindowedView} accessors when the underlying
 * metric has no recorded samples (or isn't archived).
 *
 * <p>Per tempdoc 430 rev 3.11 §B.X.5: cleanest semantics — the CEL evaluator catches this
 * exception and the rule runner treats the tick as predicate-false. Eliminates the
 * stale-data flapping risk of returning {@code NaN} or last-known-stale.
 */
public final class MissingMetricException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MissingMetricException(String message) {
    super(message);
  }

  public MissingMetricException(String message, Throwable cause) {
    super(message, cause);
  }
}
