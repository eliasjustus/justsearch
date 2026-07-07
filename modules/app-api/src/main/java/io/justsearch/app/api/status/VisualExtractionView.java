/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/** OCR/VDU capability snapshot surfaced by the Worker for visual document extraction. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisualExtractionView(
    boolean ocrEnabled,
    boolean ocrEngineAvailable,
    String ocrEngine,
    String ocrBlockedReason,
    long visualTextNeededCount,
    long visualEnrichmentNeededCount,
    String vduBlockedReason,
    // Tempdoc 672 follow-up: is a VDU offline-processing batch actively running right now?
    // visualTextNeededCount already reports how much work remains; this distinguishes "pending,
    // idle" from "pending, actively being processed" for a live progress indicator.
    boolean vduProcessing) {
  public static VisualExtractionView empty() {
    return new VisualExtractionView(false, false, null, null, 0L, 0L, null, false);
  }
}
