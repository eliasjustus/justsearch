/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Bounded enum of config-side failure codes — values that classify configuration-validation
 * failures caught before any server-lifecycle action. Each value carries a stable snake_case
 * {@link #wireValue()} for metric tags, structured logs, and status-record {@code error} fields.
 */
public enum ConfigCode {
  INVALID_CONFIG("invalid_config"),
  CONFIG_REQUIRED("config_required"),
  ALREADY_TRANSITIONING("already_transitioning"),
  EXTERNAL_SERVER_CONFLICT("external_server_conflict"),
  UNKNOWN("unknown");

  private final String wireValue;

  ConfigCode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
