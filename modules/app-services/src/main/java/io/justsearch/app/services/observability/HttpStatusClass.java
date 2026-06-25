/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

/**
 * Sealed-style enum for HTTP status class tag values. Wire format is byte-stable to the
 * pre-refactor strings ({@code "1xx"} … {@code "5xx"}, {@code "unknown"}).
 *
 * <p>Tempdoc 417 Phase 2c (relocated from {@code modules/ui} in F1).
 */
public enum HttpStatusClass {
  S1XX("1xx"),
  S2XX("2xx"),
  S3XX("3xx"),
  S4XX("4xx"),
  S5XX("5xx"),
  UNKNOWN("unknown");

  private final String wire;

  HttpStatusClass(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }

  /** Returns the {@link HttpStatusClass} for an integer HTTP status code. */
  public static HttpStatusClass forStatus(int status) {
    if (status >= 100 && status < 200) return S1XX;
    if (status >= 200 && status < 300) return S2XX;
    if (status >= 300 && status < 400) return S3XX;
    if (status >= 400 && status < 500) return S4XX;
    if (status >= 500 && status < 1000) return S5XX;
    return UNKNOWN;
  }
}
