/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 845 — the context-budget arithmetic.
 *
 * <p>{@code computeSafeInputBudgetTokens} had no tests at all, which is how two callers could pass
 * it a window that does not exist (8192, against a shipped 4096) paired with a reserve that ignored
 * the request, and get back a promise of ~5990 input tokens that the server answered with a 400.
 *
 * <p>The load-bearing property is the invariant in {@link #inputPlusReserveNeverExceedsTheWindow()}:
 * whatever the inputs, the budget this function hands out plus the reservation it was told about
 * must fit inside the window.
 */
final class TokenEstimationBudgetTest {

  /** Overhead + safety reserved inside the function (256 + 256). */
  private static final int FIXED_MARGIN = 512;

  @Test
  @DisplayName("Thorough's real shape (window 4096, maxTokens 3072) is small but positive")
  void thoroughAgainstRealWindowIsSmallButPositive() {
    int budget = TokenEstimation.computeSafeInputBudgetTokens(4096, 3072);

    // (4096 - 3072 - 512) * 0.9 = 460.8 -> 460
    assertEquals(460, budget, "Thorough gets a real, usable budget rather than an error");
    assertTrue(budget > 0, "budget must be positive so retrieval still contributes context");
    assertTrue(
        budget + 3072 + FIXED_MARGIN <= 4096,
        "input + reserve + margin must fit the window: " + budget);
  }

  /**
   * The specific defect, pinned so it cannot come back: the old constants are unrepresentable at
   * the real window. No (window, reserve) pair for a 4096-token engine can yield the ~5990 tokens
   * the hardcoded {@code (8192, 1024)} call handed out.
   */
  @Test
  @DisplayName("the old (8192, 1024) overcommit is unrepresentable against a real 4096 window")
  void oldOvercommitIsUnrepresentableAtTheRealWindow() {
    int oldValue = TokenEstimation.computeSafeInputBudgetTokens(8192, 1024);
    assertEquals(5990, oldValue, "the old call's value, kept here as the thing being ruled out");

    for (int reserve = 0; reserve <= 4096; reserve += 64) {
      int budget = TokenEstimation.computeSafeInputBudgetTokens(4096, reserve);
      assertTrue(
          budget < oldValue,
          "no reserve may reproduce the 8192-window budget at a 4096 window; reserve="
              + reserve
              + " budget="
              + budget);
      assertTrue(budget <= 4096, "budget alone may never exceed the window; reserve=" + reserve);
    }
  }

  /**
   * The regression that matters most. Previously the {@code MIN_BUDGET} floor was applied AFTER the
   * headroom subtraction, so {@code (4096, 4000)} returned 256 — promising 4256 tokens of a
   * 4096-token window. Fixing only the call sites would have left this reachable from any
   * large-maxTokens request.
   */
  @Test
  @DisplayName("input + reserve never exceeds the window, across the whole input space")
  void inputPlusReserveNeverExceedsTheWindow() {
    int[] windows = {512, 1024, 2048, 4096, 8192, 32768};
    int[] reserves = {0, 1, 256, 512, 1024, 3072, 4000, 4096, 8192, 100_000};

    for (int window : windows) {
      for (int reserve : reserves) {
        int budget = TokenEstimation.computeSafeInputBudgetTokens(window, reserve);
        assertTrue(budget >= 0, "budget is never negative: window=" + window + " out=" + reserve);

        if (reserve < window) {
          assertTrue(
              budget + reserve <= window,
              "OVERCOMMIT: window=" + window + " reserve=" + reserve + " budget=" + budget);
        } else {
          // The reservation alone already exceeds the window — the caller's ask is impossible and
          // nothing the budgeter returns can rescue it. What it must NOT do is make it worse by
          // handing out input tokens anyway, which is exactly what the old MIN_BUDGET floor did.
          assertEquals(
              0,
              budget,
              "budget must add nothing to an already-impossible reservation: window="
                  + window
                  + " reserve="
                  + reserve);
        }
      }
    }
  }

  @Test
  @DisplayName("a reservation larger than the window leaves zero input budget, not a floor")
  void noHeadroomYieldsZero() {
    assertEquals(0, TokenEstimation.computeSafeInputBudgetTokens(4096, 4000));
    assertEquals(0, TokenEstimation.computeSafeInputBudgetTokens(4096, 8192));
    assertEquals(
        0,
        TokenEstimation.computeSafeInputBudgetTokens(4096, 4096 - FIXED_MARGIN),
        "exactly-consumed headroom is zero, not the 256 floor");
  }

  @Test
  @DisplayName("the 256 floor still applies when there is genuinely room for it")
  void minBudgetFloorAppliesWhenHeadroomAllows() {
    // headroom = 4096 - 3300 - 512 = 284; 90% = 255.6 -> floor would drop below MIN_BUDGET.
    int budget = TokenEstimation.computeSafeInputBudgetTokens(4096, 3300);
    assertEquals(256, budget, "the floor lifts a sub-256 budget when the headroom can pay for it");
    assertTrue(budget + 3300 + FIXED_MARGIN <= 4096, "and it still fits the window");
  }

  @Test
  @DisplayName("smaller reserves buy proportionally more input budget")
  void smallerReserveBuysMoreBudget() {
    int quick = TokenEstimation.computeSafeInputBudgetTokens(4096, 512); // sv3 quick
    int standard = TokenEstimation.computeSafeInputBudgetTokens(4096, 1024); // engine default
    int thorough = TokenEstimation.computeSafeInputBudgetTokens(4096, 3072); // sv3 thorough

    assertTrue(quick > standard, "quick reserves least, so it may read most: " + quick);
    assertTrue(standard > thorough, "standard reads more than thorough: " + standard);
    // (4096 - 512 - 512) * 0.9 = 2764.8 -> 2764;  (4096 - 1024 - 512) * 0.9 = 2304.0 -> 2304
    assertEquals(2764, quick);
    assertEquals(2304, standard);
  }

  /**
   * Reasoning is spent INSIDE the completion reservation, not alongside it (tempdoc 835: "reasoning
   * tokens and answer tokens share one ceiling"; probe B3 recorded 1024 reasoning frames consuming
   * the whole 1024-token completion budget with zero answer tokens). Adding a reasoning budget on
   * top of maxTokens would therefore double-count. This pins the difference so a future "add the
   * reasoning budget too" change has to argue with a red test.
   */
  @Test
  @DisplayName("reserve is maxTokens alone — adding the reasoning budget would double-count")
  void reserveDoesNotDoubleCountReasoningBudget() {
    int correct = TokenEstimation.computeSafeInputBudgetTokens(4096, 3072);
    int doubleCounted = TokenEstimation.computeSafeInputBudgetTokens(4096, 3072 + 512);

    assertEquals(460, correct);
    assertTrue(
        doubleCounted < correct,
        "double-counting shrinks the budget for no reason: " + doubleCounted + " < " + correct);
  }

  @Test
  @DisplayName("a window below MIN_CONTEXT is raised to 512 rather than going negative")
  void tinyWindowIsRaisedToMinContext() {
    assertEquals(
        TokenEstimation.computeSafeInputBudgetTokens(512, 0),
        TokenEstimation.computeSafeInputBudgetTokens(64, 0),
        "windows under MIN_CONTEXT behave as MIN_CONTEXT");
  }
}
