/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Bounded enum of transition-orchestration failure codes — values for failures during the
 * holder-swap / apply / restart / rollback orchestration around a mode transition. Catch-all
 * wrappers used by {@code switchToOnlineMode} / {@code switchToIndexingMode} /
 * {@code applyConfig} when the underlying cause is not classifiable as Startup, Health, or
 * Config.
 */
public enum TransitionCode {
  ONLINE_START_FAILED("online_start_failed"),
  INDEXING_START_FAILED("indexing_start_failed"),
  CONFIG_APPLY_FAILED("config_apply_failed"),
  INTERRUPTED("interrupted");

  private final String wireValue;

  TransitionCode(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
