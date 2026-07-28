/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Extraction fallback budget (per-document bound)")
final class ExtractionFallbackBudgetTest {

  @Test
  @DisplayName("defaults permit both fallback tiers and no more")
  void defaultsPermitTwoTiers() {
    ExtractionFallbackBudget budget = ExtractionFallbackBudget.defaults();
    assertTrue(budget.permitsTier(1), "OCR is tier 1");
    assertTrue(budget.permitsTier(2), "VDU is tier 2");
    assertFalse(budget.permitsTier(3), "there is no tier 3");
    assertFalse(budget.permitsTier(0), "tier 0 is structured extraction, not a fallback");
    assertEquals(2, ExtractionFallbackBudget.DEFAULT_MAX_FALLBACK_TIERS);
  }

  @Test
  @DisplayName("a one-tier budget stops the chain after OCR")
  void oneTierBudgetStopsBeforeVdu() {
    ExtractionFallbackBudget budget = new ExtractionFallbackBudget(1, 30_000L);
    assertTrue(budget.permitsTier(1));
    assertFalse(budget.permitsTier(2));
  }

  @Test
  @DisplayName("a zero-tier budget disables the whole chain")
  void zeroTierBudgetDisablesChain() {
    ExtractionFallbackBudget budget = new ExtractionFallbackBudget(0, 30_000L);
    assertFalse(budget.permitsTier(1));
    assertFalse(budget.permitsTier(2));
  }

  @Test
  @DisplayName("wall-clock exhaustion blocks a tier the tier-count would allow")
  void wallClockBoundIsIndependent() {
    ExtractionFallbackBudget budget = new ExtractionFallbackBudget(2, 1_000L);
    assertTrue(budget.permitsTierNow(1, 999L));
    assertFalse(budget.permitsTierNow(1, 1_000L), "boundary is inclusive: spent is spent");
    assertFalse(budget.permitsTierNow(1, 5_000L));
    assertTrue(budget.permitsTier(1), "the tier itself is still permitted — only the clock ran out");
    assertTrue(budget.exhausted(1_000L));
    assertFalse(budget.exhausted(0L));
  }

  @Test
  @DisplayName("negative bounds are rejected, not silently clamped")
  void negativeBoundsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ExtractionFallbackBudget(-1, 1_000L));
    assertThrows(IllegalArgumentException.class, () -> new ExtractionFallbackBudget(1, -1L));
  }
}
