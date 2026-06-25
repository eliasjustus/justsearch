/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Sealed-style enum of VDU batch outcomes. Wire format byte-stable to the strings emitted
 * pre-refactor by {@link VduBatchProcessor}.
 */
public enum VduOutcome {
  COMPLETED("completed"),
  EMPTY("empty"),
  FAILED("failed"),
  SKIPPED("skipped");

  private final String wire;

  VduOutcome(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }
}
