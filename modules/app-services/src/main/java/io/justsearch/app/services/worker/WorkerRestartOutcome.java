/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Sealed-style enum for worker-restart outcomes. Wire format byte-stable to the strings emitted
 * pre-refactor.
 */
public enum WorkerRestartOutcome {
  SUCCESS("success"),
  FAILED("failed");

  private final String wire;

  WorkerRestartOutcome(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }
}
