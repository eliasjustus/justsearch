/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

/**
 * Sealed-style enum for HTTP method tag values. Wire format matches the standard uppercase HTTP
 * method names ({@code "GET"}, {@code "POST"}, etc.) — byte-stable to pre-refactor strings.
 *
 * <p>Tempdoc 417 Phase 2c (relocated from {@code modules/ui} in F1).
 */
public enum HttpMethod {
  GET,
  POST,
  PUT,
  DELETE,
  PATCH,
  OPTIONS,
  HEAD;

  public String wireValue() {
    return name();
  }
}
