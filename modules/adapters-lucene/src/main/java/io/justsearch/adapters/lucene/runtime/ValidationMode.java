/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/** Controls whether schema/field validation failures cause hard errors or warnings. */
public enum ValidationMode {
  FAIL,
  WARN;

  /** Parses a mode string; returns {@link #FAIL} for null, blank, or unrecognized values. */
  public static ValidationMode from(String s) {
    if (s == null || s.isBlank()) return FAIL;
    return "warn".equalsIgnoreCase(s) ? WARN : FAIL;
  }
}
