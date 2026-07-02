/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/**
 * Pure, total mapping from an OCR attempt's baseline-quality fact to the {@link OcrSkipReason}
 * recorded when the attempt does not improve on the baseline (tempdoc 671). The functional-core
 * extraction mirrors {@code io.justsearch.indexerworker.loop.AdmissionPolicy} (the
 * {@code admission-policy} logic seam) for the same reason: a wrong or reused arm here silently
 * conflates two different causes behind one label.
 *
 * <p>The LAW: {@code baselineQuality > 0} (i.e. adequate pre-existing readable text, using the
 * same {@link io.justsearch.indexerworker.text.TextQualityAnalyzer} threshold semantics used
 * everywhere else in this package) maps to {@link OcrSkipReason#TEXTUAL} — "the existing text was
 * already adequate, OCR didn't help." Every other case maps to {@link OcrSkipReason#NO_TEXT_FOUND}
 * — "OCR was genuinely attempted against a document with no baseline text either, and found
 * nothing." These are semantically distinct outcomes (a routing decision vs. an extraction
 * failure) and must never collapse onto the same code.
 */
final class OcrOutcomeClassifier {

  private OcrOutcomeClassifier() {}

  static OcrSkipReason classifyNoImprovement(double baselineQuality) {
    return baselineQuality > 0.0d ? OcrSkipReason.TEXTUAL : OcrSkipReason.NO_TEXT_FOUND;
  }
}
