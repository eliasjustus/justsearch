/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Bounded enum of health-side failure codes — values that classify periodic-probe failures and
 * post-start health degradations. Each value carries a stable snake_case {@link #wireValue()}
 * for metric tags, structured logs, and status-record {@code error} fields.
 */
public enum HealthCode {
  HEALTH_TIMEOUT("health_timeout"),
  HEALTH_INTERRUPTED("health_interrupted"),
  CONNECTION_REFUSED("connection_refused"),
  PROCESS_DIED("process_died"),
  UNKNOWN("unknown");

  private final String wireValue;

  HealthCode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
