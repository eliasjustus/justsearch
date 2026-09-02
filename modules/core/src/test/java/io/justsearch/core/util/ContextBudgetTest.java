/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.util.ContextBudget.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 883 decision 3 — the window-derived budget, its fractions and its ceilings.
 *
 * <p>Every assertion here fails on the pre-883 code by construction: there was no type to assert
 * against, and the quantities it computes were five unrelated literals in five modules.
 */
class ContextBudgetTest {

  @Test
  @DisplayName("the window walk is observed -> configured -> 4096, and never 8192")
  void windowPrecedence() {
    assertEquals(32768, ContextBudget.of(32768, 8192, 1024).windowTokens());
    assertEquals(Source.OBSERVED, ContextBudget.of(32768, 8192, 1024).source());

    // No server observed yet: the CONFIGURED launch window is the next authority, verbatim.
    ContextBudget configured = ContextBudget.of(null, 2048, 1024);
    assertEquals(2048, configured.windowTokens());
    assertEquals(Source.CONFIGURED, configured.source());

    ContextBudget fallback = ContextBudget.of(null, null, 1024);
    assertEquals(4096, fallback.windowTokens());
    assertEquals(Source.FALLBACK, fallback.source());
    assertNotEquals(
        8192,
        fallback.windowTokens(),
        "8192 was the pre-845 hardcoded window and never existed; the fallback is the ladder's"
            + " SMALLEST rung, because over-committing an imagined window is the failure here");
  }

  @Test
  @DisplayName("a zero or unknown window is not treated as generous")
  void nonPositiveWindowsFallThrough() {
    assertEquals(4096, ContextBudget.of(0, 0, 1024).windowTokens());
    assertEquals(Source.FALLBACK, ContextBudget.of(0, 0, 1024).source());
    assertEquals(2048, ContextBudget.of(0, 2048, 1024).windowTokens());
  }

  @Test
  @DisplayName("at the smallest rung every fraction is BELOW its cap — the budget is what binds")
  void smallWindowIsBoundByTheFractionNotTheCap() {
    ContextBudget b = ContextBudget.of(4096, null, 1024);
    // (4096 - 1024 - 256 - 256) * 0.9 = 2304
    assertEquals(2304, b.inputBudget());
    assertEquals(2304, b.hierarchicalThreshold(), "the threshold IS the budget: no cap");
    assertEquals(1152, b.sectionTarget());
    assertEquals(576, b.externalContextCap());
    assertEquals(1152, b.readDocumentPageTokens());
    assertEquals(576, b.toolResultCap());
    assertTrue(
        b.externalContextCap() < 1000,
        "the old flat 1000-token history cap over-committed this window; the derived one does not");
  }

  @Test
  @DisplayName("at the top rung every capped fraction hits its cap, and the uncapped one scales")
  void largeWindowIsBoundByTheCaps() {
    ContextBudget b = ContextBudget.of(32768, null, 1024);
    // (32768 - 1024 - 256 - 256) * 0.9 = 28108
    assertEquals(28108, b.inputBudget());
    assertEquals(28108, b.hierarchicalThreshold(), "uncapped: it scales with the whole budget");
    assertEquals(4096, b.sectionTarget(), "capped: map-step latency");
    assertEquals(2048, b.externalContextCap(), "capped: history is low value per token");
    assertEquals(4096, b.readDocumentPageTokens(), "capped: agent-context hygiene");
    assertEquals(2048, b.toolResultCap(), "capped: one tool result must not own the prompt");
  }

  @Test
  @DisplayName("every derived quantity grows with the window, and the threshold grows without a cap")
  void quantitiesScaleWithTheWindow() {
    ContextBudget small = ContextBudget.of(4096, null, 1024);
    ContextBudget large = ContextBudget.of(32768, null, 1024);
    assertTrue(large.hierarchicalThreshold() > small.hierarchicalThreshold() * 10);
    assertTrue(large.sectionTarget() > small.sectionTarget());
    assertTrue(large.externalContextCap() > small.externalContextCap());
    assertTrue(large.readDocumentPageTokens() > small.readDocumentPageTokens());
    assertTrue(large.toolResultCap() > small.toolResultCap());
  }

  @Test
  @DisplayName("a reservation with no headroom reports 0 input, but derived caps stay usable")
  void zeroBudgetIsHonestButDerivedCapsStayPositive() {
    ContextBudget b = ContextBudget.of(4096, null, 9000);
    assertEquals(0, b.inputBudget(), "845: no room is reported as no room");
    assertTrue(b.toolResultCap() > 0, "a cut budgeted at 0 would mean 'cut everything'");
    assertTrue(b.sectionTarget() > 0);
    assertTrue(b.readDocumentPageTokens() > 0);
  }

  @Test
  @DisplayName("withDerivedReserve caps the reserve at a quarter of the window, never above it")
  void derivedReserveIsBoundedByTheWindow() {
    // 4096 and above: the preferred cap wins, so nothing changes at the shipped rungs.
    assertEquals(1024, ContextBudget.withDerivedReserve(4096, null, 1024).completionReserve());
    assertEquals(1024, ContextBudget.withDerivedReserve(32768, null, 1024).completionReserve());
    // Below it, a flat 1024 would crowd out the input; the window fraction takes over.
    assertEquals(512, ContextBudget.withDerivedReserve(2048, null, 1024).completionReserve());
    // And a caller that names no preference gets the fraction outright.
    assertEquals(8192, ContextBudget.withDerivedReserve(32768, null, 0).completionReserve());
  }

  @Test
  @DisplayName("token budgets convert to char budgets through ONE documented heuristic")
  void tokensConvertToChars() {
    assertEquals(4000, TokenEstimation.charsForTokens(1000));
    assertEquals(0, TokenEstimation.charsForTokens(-5));
    ContextBudget b = ContextBudget.of(32768, null, 1024);
    assertEquals(b.readDocumentPageTokens() * 4, b.readDocumentPageChars());
    assertEquals(b.toolResultCap() * 4, b.toolResultCapChars());
    assertEquals(b.inputBudget() * 4, b.inputBudgetChars());
    assertTrue(
        b.readDocumentPageChars() > 3000,
        "the old 3000-char page was sized 'well inside n_ctx 4096' and never grew; at 32768 the"
            + " derived page must be larger, or the window buys nothing");
  }

  @Test
  @DisplayName("the read page is always a smaller ask than the whole input budget")
  void pageNeverExceedsTheBudget() {
    for (int window : new int[] {2048, 4096, 8192, 16384, 32768}) {
      ContextBudget b = ContextBudget.of(window, null, 1024);
      assertTrue(
          b.readDocumentPageTokens() <= Math.max(1, b.inputBudget()),
          "page " + b.readDocumentPageTokens() + " > budget " + b.inputBudget() + " at " + window);
    }
  }
}
