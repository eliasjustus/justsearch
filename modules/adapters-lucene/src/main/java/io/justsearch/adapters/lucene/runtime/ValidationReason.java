/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded set of attribution values for the {@code index.runtime.validation_failure_total}
 * counter. Tempdoc 417 Phase 1: replaces free-form strings on
 * {@link IndexingCoordinator}'s validation paths with a compile-time-bounded enum.
 */
public enum ValidationReason {
  MISSING_ID_FIELD("missing_id_field"),
  MISSING_UID_FIELD("missing_uid_field"),
  VECTOR_NOT_NUMERIC("vector_not_numeric"),
  VECTOR_DIMENSION_MISMATCH("vector_dimension_mismatch"),
  UNKNOWN("unknown");

  private static final Map<String, ValidationReason> BY_WIRE;

  static {
    java.util.HashMap<String, ValidationReason> m = new java.util.HashMap<>();
    for (ValidationReason r : values()) {
      m.put(r.wireValue, r);
    }
    BY_WIRE = Map.copyOf(m);
  }

  private final String wireValue;

  ValidationReason(String wireValue) {
    this.wireValue = Objects.requireNonNull(wireValue, "wireValue");
  }

  public String wireValue() {
    return wireValue;
  }

  public static ValidationReason fromWire(String wire) {
    if (wire == null || wire.isBlank()) return UNKNOWN;
    ValidationReason r = BY_WIRE.get(wire);
    return r != null ? r : UNKNOWN;
  }
}
