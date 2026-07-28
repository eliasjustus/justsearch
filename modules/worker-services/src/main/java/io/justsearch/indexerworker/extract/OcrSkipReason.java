/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Stable reason values for {@code ocr.skipped_total}. */
public enum OcrSkipReason {
  DISABLED("disabled"),
  TEXTUAL("textual"),
  // Tempdoc 671: distinct from TEXTUAL — OCR was genuinely attempted against a document with no
  // baseline text either, and found nothing. TEXTUAL means "existing text was already adequate";
  // this means "extraction failed," not a routing decision. Collapsing the two onto one code is
  // the bug this value fixes (see OcrOutcomeClassifier).
  NO_TEXT_FOUND("no_text_found"),
  SIZE("size"),
  // Tempdoc 790: the per-document fallback budget (tier count or wall-clock) was already spent
  // before this tier could start. Distinct from TIMEOUT (the tier ran and overran) and from
  // DISABLED (the tier is switched off) — the tier was permitted and affordable in principle, it
  // just did not fit this document's remaining budget.
  BUDGET("budget"),
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
