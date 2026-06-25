/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Stable reason values for {@code ocr.skipped_total}. */
public enum OcrSkipReason {
  DISABLED("disabled"),
  TEXTUAL("textual"),
  SIZE("size"),
  TIMEOUT("timeout"),
  ENGINE_MISSING("engine_missing"),
  LANGUAGE_MISSING("language_missing"),
  UNKNOWN("unknown");

  private final String wire;

  OcrSkipReason(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }

  public static OcrSkipReason fromBlockedReason(String reasonCode) {
    return switch (reasonCode == null ? "" : reasonCode) {
      case TikaOcrRuntime.REASON_DISABLED -> DISABLED;
      case TikaOcrRuntime.REASON_ENGINE_MISSING -> ENGINE_MISSING;
      case TikaOcrRuntime.REASON_LANGUAGE_MISSING -> LANGUAGE_MISSING;
      default -> UNKNOWN;
    };
  }
}
