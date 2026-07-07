package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link OcrOutcomeClassifier} seam (governance/logic-seams.v1.json) — the pure,
 * total baseline-quality-to-reason mapping extracted from PolicyDrivenTikaExtractor (tempdoc
 * 671). Asserts both outcomes are reachable at their boundary and that the mapping is injective,
 * so a swapped or reused arm (silently conflating "text was fine" with "OCR found nothing") is
 * caught. Plain JUnit; no jqwik (tempdoc 555 §10).
 */
class OcrOutcomeClassifierTest {

  @Test
  @DisplayName("zero baseline quality maps to NO_TEXT_FOUND, not TEXTUAL")
  void noBaselineTextMapsToNoTextFound() {
    assertEquals(OcrSkipReason.NO_TEXT_FOUND, OcrOutcomeClassifier.classifyNoImprovement(0.0d));
  }

  @Test
  @DisplayName("any positive baseline quality maps to TEXTUAL")
  void adequateBaselineTextMapsToTextual() {
    assertEquals(OcrSkipReason.TEXTUAL, OcrOutcomeClassifier.classifyNoImprovement(0.01d));
    assertEquals(OcrSkipReason.TEXTUAL, OcrOutcomeClassifier.classifyNoImprovement(0.3d));
    assertEquals(OcrSkipReason.TEXTUAL, OcrOutcomeClassifier.classifyNoImprovement(1.0d));
  }

  @Test
  @DisplayName("the two outcomes are distinct (mapping is injective across the boundary)")
  void outcomesAreDistinct() {
    assertNotEquals(
        OcrOutcomeClassifier.classifyNoImprovement(0.0d),
        OcrOutcomeClassifier.classifyNoImprovement(0.5d),
        "zero and positive baseline quality must map to distinct reason codes");
  }
}
