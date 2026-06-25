/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Bounded enum of startup-side failure codes — values that carry a stable {@link #wireValue()}
 * snake_case form for use as metric tags, structured-log keys, and status-record {@code error}
 * fields. Tempdoc 412 Phase 1 introduced this taxonomy as the canonical wire form for
 * {@link InferenceFailure.StartupFailure}.
 */
public enum StartupCode {
  INSUFFICIENT_VRAM("insufficient_vram"),
  MISSING_DLL("missing_dll"),
  PROCESS_EXITED("process_exited"),
  PORT_ALLOCATION_FAILED("port_allocation_failed"),
  EXTERNAL_SERVER_POLICY_BLOCKED("external_server_policy_blocked"),
  UNKNOWN("unknown");

  private final String wireValue;

  StartupCode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
