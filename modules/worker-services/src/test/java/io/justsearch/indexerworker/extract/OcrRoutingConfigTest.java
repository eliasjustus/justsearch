/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 706: config-absent must never mean "no limits". Covers the null-fill behaviour of
 * {@link OcrRoutingConfig#from(ResolvedConfig.Ocr)} that closes the gap where a null
 * {@code maxPages} (or null image guards) silently disabled the corresponding limit — the root
 * cause of the eval/headless environment running unbounded OCR (no {@code ocr} section in its
 * config, tempdoc 706).
 */
final class OcrRoutingConfigTest {

  @Test
  void fromNullReturnsDefaults() {
    assertEquals(OcrRoutingConfig.defaults(), OcrRoutingConfig.from(null));
  }

  @Test
  void defaultsAreThirtySecondsFiftyPagesFourKDpi300AutoWorkers() {
    OcrRoutingConfig defaults = OcrRoutingConfig.defaults();

    assertTrue(defaults.enabled());
    assertEquals(30_000, defaults.perFileTimeoutMs());
    assertEquals(200, defaults.maxPages());
    assertEquals(4096, defaults.maxImageDimension());
    assertEquals(40_000_000, defaults.maxImagePixels());
    assertEquals(300, defaults.renderDpi());
    assertEquals(0, defaults.ocrWorkers());
    assertEquals(300, defaults.effectiveRenderDpi());
    assertEquals(PdfOcrEngine.defaultPoolSize(), defaults.effectiveOcrWorkers());
  }

  @Test
  void allNullLimitsFillToSafeDefaults() {
    ResolvedConfig.Ocr allNull =
        new ResolvedConfig.Ocr(null, null, null, null, null, null, null, null);

    OcrRoutingConfig resolved = OcrRoutingConfig.from(allNull);

    assertTrue(resolved.enabled(), "enabled defaults true when unspecified");
    assertEquals(30_000, resolved.perFileTimeoutMs());
    assertEquals(200, resolved.maxPages());
    assertEquals(4096, resolved.maxImageDimension());
    assertEquals(40_000_000, resolved.maxImagePixels());
    assertEquals(300, resolved.renderDpi());
    assertEquals(0, resolved.ocrWorkers(), "ocrWorkers absent must resolve to the auto marker (0)");
    assertEquals(300, resolved.effectiveRenderDpi());
    assertEquals(PdfOcrEngine.defaultPoolSize(), resolved.effectiveOcrWorkers());
  }

  @Test
  void nonPositiveLimitsAlsoFillToSafeDefaults() {
    ResolvedConfig.Ocr allZeroOrNegative =
        new ResolvedConfig.Ocr(true, List.of("eng"), -1, 0, -5, 0, -10, -1);

    OcrRoutingConfig resolved = OcrRoutingConfig.from(allZeroOrNegative);

    assertEquals(30_000, resolved.perFileTimeoutMs());
    assertEquals(200, resolved.maxPages());
    assertEquals(4096, resolved.maxImageDimension());
    assertEquals(40_000_000, resolved.maxImagePixels());
    assertEquals(300, resolved.renderDpi());
    assertEquals(0, resolved.ocrWorkers());
  }

  @Test
  void explicitPositiveLimitsAreHonoredAndBecomeTheEffectiveValues() {
    ResolvedConfig.Ocr configured =
        new ResolvedConfig.Ocr(true, List.of("eng"), 12_000, 7, 2048, 8_000_000, 220, 3);

    OcrRoutingConfig resolved = OcrRoutingConfig.from(configured);

    assertEquals(12_000, resolved.perFileTimeoutMs());
    assertEquals(7, resolved.maxPages());
    assertEquals(2048, resolved.maxImageDimension());
    assertEquals(8_000_000, resolved.maxImagePixels());
    assertEquals(220, resolved.renderDpi());
    assertEquals(3, resolved.ocrWorkers());
    assertEquals(220, resolved.effectiveRenderDpi());
    assertEquals(3, resolved.effectiveOcrWorkers());
  }

  @Test
  void disabledOcrIsRespectedRegardlessOfOtherFields() {
    ResolvedConfig.Ocr disabled =
        new ResolvedConfig.Ocr(false, List.of("eng"), 12_000, 7, 2048, 8_000_000, 220, 3);

    assertEquals(false, OcrRoutingConfig.from(disabled).enabled());
  }
}
