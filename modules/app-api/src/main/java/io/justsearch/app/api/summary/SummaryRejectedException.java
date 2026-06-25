/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.summary;

/**
 * Exception surfaced when the worker rejects a summary request before it executes
 * (e.g., guardrail violation or queue saturation).
 */
public final class SummaryRejectedException extends RuntimeException {
  private final SummaryRejection rejection;

  public SummaryRejectedException(SummaryRejection rejection) {
    super(rejection == null ? "summary_rejected" : rejection.reasonCode());
    this.rejection = rejection;
  }

  public SummaryRejectedException(SummaryRejection rejection, Throwable cause) {
    super(rejection == null ? "summary_rejected" : rejection.reasonCode(), cause);
    this.rejection = rejection;
  }

  public SummaryRejection rejection() {
    return rejection;
  }
}
