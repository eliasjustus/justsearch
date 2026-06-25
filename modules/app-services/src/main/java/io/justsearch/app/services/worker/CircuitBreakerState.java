/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Sealed-style enum for circuit-breaker states. Wire format byte-stable to lowercase strings
 * emitted pre-refactor.
 */
public enum CircuitBreakerState {
  CLOSED("CLOSED"),
  OPEN("OPEN"),
  HALF_OPEN("HALF_OPEN");

  private final String wire;

  CircuitBreakerState(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }

  /** Parses a wire string back to a state. Defaults to CLOSED on unknown input. */
  public static CircuitBreakerState fromWire(String wire) {
    if (wire == null || wire.isBlank()) return CLOSED;
    for (CircuitBreakerState s : values()) {
      if (s.wire.equals(wire)) return s;
    }
    return CLOSED;
  }
}
