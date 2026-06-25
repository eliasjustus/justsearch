/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

/**
 * Reason a startup attempt is being made. Tagged on {@code inference.startup.attempt_total} and
 * {@code inference.startup.duration_ms}. Bounded set; today only {@code COLD_START} is fired
 * from production code, but the type leaves room for {@code WARM_RESTART} / {@code RECOVERY}
 * once those flows acquire distinct semantics.
 */
public enum StartupReason {
  COLD_START("cold_start"),
  WARM_RESTART("warm_restart"),
  RECOVERY("recovery"),
  CONFIG_APPLY("config_apply");

  private final String wireValue;

  StartupReason(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
