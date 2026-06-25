/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * The target phase for a startup attempt. Distinct from {@code Mode.TRANSITIONING} — this is the
 * phase the transition method intends to reach, used as a tag on
 * {@code inference.startup.attempt_total} and {@code inference.startup.duration_ms}. Value space
 * is bounded so wireValue() forms a closed set for cardinality control.
 */
public enum TargetPhase {
  ONLINE("online"),
  INDEXING("indexing");

  private final String wireValue;

  TargetPhase(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
