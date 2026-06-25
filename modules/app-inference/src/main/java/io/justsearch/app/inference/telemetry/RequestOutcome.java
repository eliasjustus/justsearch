/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

/**
 * Bounded enum of request outcomes tagged on {@code inference.request.completed_total}.
 *
 * <ul>
 *   <li>{@code OK}: work block returned normally.
 *   <li>{@code ERROR}: work block threw a non-{@code InterruptedException} throwable.
 *   <li>{@code CANCELLED}: thread was interrupted (direct or in cause chain).
 *   <li>{@code TIMEOUT}: vision-mode lock acquisition exceeded its deadline.
 * </ul>
 */
public enum RequestOutcome {
  OK("ok"),
  ERROR("error"),
  CANCELLED("cancelled"),
  TIMEOUT("timeout");

  private final String wireValue;

  RequestOutcome(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
