/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sealed-string identity for an advisory class. Open to runtime registration (plugins
 * register at startup via {@link AdvisoryClassRegistry}); closed to arbitrary strings
 * (the registry enforces known-id-only at emission time).
 *
 * <p>Per slice 494 §6 Q3=(b): sealed-string with registry, not Java enum. Phase-D
 * plugin-contributed advisory classes register at startup without touching compiled code.
 * ArchUnit invariant (§6.3) enforces 1:1 pairing with {@link AdvisoryProjector}
 * implementations.
 */
public record AdvisoryClassId(String value) {

  private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)*$");

  public AdvisoryClassId {
    Objects.requireNonNull(value, "value");
    if (!VALID_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "AdvisoryClassId must be dot-separated lowercase segments matching "
              + VALID_ID.pattern()
              + ": "
              + value);
    }
  }

  public static AdvisoryClassId of(String value) {
    return new AdvisoryClassId(value);
  }
}
